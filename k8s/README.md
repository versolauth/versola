# Deploying Versola to Kubernetes

Two charts live here:

| Chart | What it deploys |
|---|---|
| [`versola/`](versola) | `auth`, `central`, `edge` and the admin console — the system itself |
| [`loadgen/`](loadgen) | the load emulator: coordinator, driver fleet and `mockapi` — never the system under test |

> **Scope.** This is not how the shared production host runs today. That is a Docker Compose
> deployment on a single VPS driven by `versola-cli`, documented in [`../deploy.md`](../deploy.md),
> and the two paths share the configuration *format* and nothing else — no `versola-cli`, no
> compose file, no `versola-proxy`, no OpenBao-backed `*.secrets.env`. Where `deploy.md` is a runbook for one specific machine, this is
> an installation guide for a cluster you bring yourself.

---

## 1. What these charts deliberately do not bundle

Straight from `versola/values.yaml`, and the same principle holds in `loadgen/values.yaml`:

> No bundled Postgres. […] No bundled secret backend. This chart consumes secret values/refs
> (native Secret, External Secrets Operator, Vault, …), it never generates or mandates one.
> […] No bundled Prometheus or Grafana.

All three are **external prerequisites**, not things that appear when you `helm install`. "We
already have Postgres" is not the same as "the chart will find it" — each has to exist, and be
reachable from the cluster, before the first install.

---

## 2. Prerequisites

- **A cluster on Kubernetes 1.28 or newer.** The loadgen driver reads its shard index from the
  `apps.kubernetes.io/pod-index` label the StatefulSet controller stamps on each pod; the
  `PodIndexLabel` gate is on by default from 1.28 and the field is GA since 1.32. The `versola`
  chart alone has no such floor.
- **PostgreSQL reachable from the cluster.** One instance per service is the campaign topology
  (see [`loadgen-runbook`](https://github.com/versolauth/loadgen-runbook), `docs/03-postgres-topology.md`);
  one instance with three schemas is enough for anything smaller. Either way the services
  validate their schema at boot and refuse to start if migrations have not been applied — see
  [§5](#5-applying-migrations).
- **An ingress controller, and an IngressClass the chart can name.** See [§7](#7-ingress).
- **[scala-cli](https://scala-cli.virtuslab.org/install) on your workstation**, to generate
  configuration. Not needed in the cluster.
- **Helm.** Helm 4 applies manifests server-side, which matters in one place — see
  [§5](#5-applying-migrations).
- **Published images** in `ghcr.io/versolauth/`: `versola-auth`, `versola-central`,
  `versola-edge` for the system; `versola-loadgen`, `versola-mockapi` for the
  emulator; `versola-tools` for migrations, and for the console, which is a stock nginx serving the
  static build `versola-tools` carries (an init container copies it out). All are public, so no `imagePullSecrets` are needed.
  Both charts default their image tag to their own `appVersion`, and CI guards that this matches
  a tag that was actually published.

---

## 3. Generating configuration

Each service reads one HOCON file, mounted at `/app/config/env.conf`, in which every secret is a
`${VAR}` placeholder resolved from the process environment. `scripts/gen-env.scala` generates
that file, the placeholders, and the values behind them as a single coherent set — coherent
matters, because several values must agree across services (`CENTRAL_SECRET_KEY` and
`CLIENT_SECRETS_SECRET` are shared between auth and central, and central holds the public half
of edge's key).

Run it and answer `k8s` at the first prompt:

```bash
scala-cli run scripts/gen-env.scala
```

Unlike `docker-local`/`vps`, this mode stays **interactive** — it asks for each value in turn,
one prompt at a time, rather than deriving everything from a couple of environment variables.
That is deliberate: a k8s deployment is a manual, occasional bootstrap (there is no pipeline
driving it, unlike `vps`'s `Deploy` workflow), and the campaign topology genuinely needs a
human's input at several of these prompts — three independent Postgres instances, not one host
split by `?currentSchema=`, and auth's internal address, which under an Ingress is never the
same as its public one. Read every default the tool shows before accepting it; the ones that
matter most:

| Prompt | Don't accept the default as-is | Answer instead |
|---|---|---|
| Auth internal URL | defaults to the public URL you just typed | central's cluster-DNS address, e.g. `http://versola-auth:8080` |
| Postgres URL (×3, one per service) | defaults to `localhost` | each service's real JDBC URL. Verified TLS (`sslmode=verify-full`) is the default and prod refuses to start without it; mount the database's CA bundle with the service's `extraVolumes`/`extraVolumeMounts` and set `postgres.ssl-root-cert` to its path (see [deploy.md](../deploy.md#postgres-tls)) |
| Postgres password (×3) | a placeholder default (`1234`) | the password that database actually has — this tool never talks to a database, so a made-up value here just disagrees with it silently later |

Every prompt above also accepts a `--flag=value` on the command line instead — give it and the
prompt is skipped entirely, still reading any prompt you didn't give a flag for. Supply all of
them and the whole run is non-interactive, which is what scripting this bootstrap (rather than
typing it by hand) needs:

```bash
scala-cli run scripts/gen-env.scala -- \
  --target=k8s \
  --auth-url=https://auth.example.com \
  --auth-internal-url=http://versola-auth:8080 \
  --auth-additional-url=http://versola-auth:8082 \
  --central-url=http://versola-central:8090 \
  --edge-url=https://edge.example.com \
  --auth-postgres-url='jdbc:postgresql://pg:5432/auth?currentSchema=auth' \
  --auth-postgres-user=versola_app --auth-postgres-password='...' \
  --central-postgres-url='jdbc:postgresql://pg:5432/auth?currentSchema=central' \
  --central-postgres-user=versola_app --central-postgres-password='...' \
  --edge-postgres-url='jdbc:postgresql://pg:5432/auth?currentSchema=edge' \
  --edge-postgres-user=versola_app --edge-postgres-password='...' \
  --admin-login=admin --admin-password='...' \
  --central-redirect-uris=https://admin.example.com/complete \
  --otp=false --smtp=false
```

`--otp`/`--smtp` (and their `--otp-*`/`--smtp-*` sub-flags, e.g. `--otp-url`, `--smtp-host`) work
the same way — see `scripts/gen-env.scala`'s own `cliArgs` comment for the full flag-to-prompt
mapping. A bare `--flag` with no `=value` is treated as `true`, so `--otp` alone answers that
prompt's yes/no the same as `--otp=true`.

Output lands in `.local/env/k8s/`:

| File | Contents |
|---|---|
| `auth.conf`, `central.conf`, `edge.conf` | configuration with `${VAR}` placeholders |
| `auth.generated-secrets.env`, … | the values behind those placeholders, as `KEY=value` |

Two of central's generated values need a matching home outside these files entirely.

`bootstrap.utility-client` is the client `loadgen provision` authenticates as to reach central's
admin API (see [§8](#8-the-load-emulator)). It is seeded as RFC 7523 `private_key_jwt` with
DPoP-bound tokens, which the default tenant's FAPI 2.0 profile admits (versolauth/versola#424).
`gen-env` generates a P-256 pair: the public half is `UTILITY_CLIENT_PUBLIC_JWK` in
`central.generated-secrets.env`, and the private half is written to
`utils.private-key.jwk` (mode 0600), which is *not* a `*.generated-secrets.env` file
and so is never loaded into central. It is the private key of the `utils` client, for whatever
authenticates as it: today `loadgen`, which takes its contents as `provision.provisioner-private-key`,
for example from a Secret of its own:

```bash
kubectl create secret generic versola-utils-key --from-file=private-key.jwk=.local/env/k8s/utils.private-key.jwk
```

Central seeds `utils` once, so a later `gen-env` run would produce a pair whose public half central
never sees (versolauth/versola#380). To rerun `gen-env` against a deployment that already has its key, hand it back through the
environment of that one command (a bare assignment on its own line would not reach `scala-cli`):

```bash
UTILS_PRIVATE_KEY_JWK="$(kubectl get secret versola-utils-key -o jsonpath='{.data.private-key\.jwk}' | base64 -d)" \
  scala-cli run scripts/gen-env.scala
```

`gen-env` then writes that pair's public half into `central.generated-secrets.env` and the same
private key into `utils.private-key.jwk`, instead of generating a new one. (`versola-cli` does the equivalent for
`docker-local` and `vps` out of OpenBao, at `secret/versola/<target>/utils`.)

**Upgrading a deployment that already seeded `utils` with a `client_secret`:** a client's
authentication method is fixed when it is created, so central refuses to start (naming `utils`,
both methods and the setting) on a `central.conf` that now carries `public-key-jwk`. Either keep
the previous `central.conf` for `bootstrap.utility-client`, or delete the `utils` client so the
next boot registers it again with the new key, and give `loadgen` the new private key.

`bootstrap.resource-secret` has no counterpart to match — central is the only reader — but it
still has to be present at central's *first* boot: central seeds each of the two exactly once,
the first time it finds neither configured, and a value added to the config later has no effect
on one already seeded (versolauth/versola#380).

### `vps` mode

`vps` is the other placeholder-emitting mode, kept non-interactive for the `Deploy` workflow's
sake and driven entirely by `AUTH_URL`/`POSTGRES_HOST`/`ENV_NAME` (see `deploy.md`). It assumes
one VPS behind everything — one Postgres host shared by all three services via
`?currentSchema=`, auth's internal address equal to its public one — which is correct for that
one machine and wrong for a cluster. Use `k8s` instead unless you're specifically scripting a
non-interactive run against values a cluster doesn't have (a CI job that only checks the chart
and the generator still agree, for instance — see `.github/scripts/check-chart-secret-vars.sh`).

---

## 4. The two kinds of Secret

The chart consumes two, and they are not interchangeable:

| Values key | Holds | Delivered as |
|---|---|---|
| `services.<name>.config.existingSecret` | one key, `env.conf` — **one Secret per service** | a mounted file |
| `secrets.existingSecret` | the values its `${VAR}` placeholders reference — **one Secret shared by all three** | individual `secretKeyRef` entries |

The second is shared rather than per-service on purpose: `gen-env.scala` produces one coherent
set, and splitting it would invent a boundary the source of truth does not have. Nothing gains
broader access — each Deployment pulls only the keys it needs.

**Key names are not the variable names.** `versola.secretKeyFor` kebab-cases each variable, then
prefixes it with the service name unless the result already starts with that prefix; the two
genuinely shared values stay bare. So:

| Variable | Service | Key |
|---|---|---|
| `ACCESS_TOKENS_SECRET` | auth | `auth-access-tokens-secret` |
| `AUTH_CODES_SECRET` | auth | `auth-codes-secret` — not `auth-auth-codes-secret` |
| `EDGE_PUBLIC_JWK` | central | `central-edge-public-jwk` — central holds edge's public half |
| `CENTRAL_SECRET_KEY` | auth *and* central | `central-secret-key` — one key, one value |
| `POSTGRES_PASSWORD` | each | `auth-postgres-password`, `central-postgres-password`, `edge-postgres-password` |

Twenty-nine keys in total. `versola.secretEnv` fails the template if `secrets.existingSecret` is
unset, and a missing key surfaces as an unresolved HOCON substitution at startup, so both
mistakes fail loudly rather than silently.

**Upgrading an existing Secret:** edge now reads its native-app blob key from `EDGE_NATIVE_BLOB_KEY`
instead of a literal in `edge.conf`, so the shared Secret needs one more key, `edge-native-blob-key`
(32 random bytes, URL-safe base64 without padding — it is in the new `edge.generated-secrets.env`).
Add it before upgrading the chart, or edge's pods will not start. Until then the old edge keeps the
literal from the `edge.conf` it was generated with; a new one regenerates it, so a native login
in flight across the switch has to be restarted.

Build both kinds from the generated files without the values passing through a terminal:

```bash
kubectl create secret generic auth-config -n versola \
  --from-file=env.conf=.local/env/k8s/auth.conf \
  --dry-run=client -o yaml | kubectl apply -f -
```

The shared Secret is the same idea, with one `--from-literal` per key — or generate it from the
three `*.generated-secrets.env` files, mapping variable names to key names by the rule above.

---

## 5. Applying migrations

The services **validate** their schema at boot and fail to start when migrations are missing.
They do not apply them: `RUN_MIGRATIONS` is `"false"` in the Deployment template. The chart's
migration Job does (`migrations.*` in `values.yaml`): on every `helm install` and `helm upgrade` it
runs `versola-tools migrate` once, before the new pods start. If it fails, the release fails and the
Deployments are left as they were.

Setting `RUN_MIGRATIONS` through `services.<name>.extraEnv` does not work: that renders after the
hardcoded entry, so the container gets two `env` entries of the same name, and Helm 4's server-side
apply rejects `duplicate entries for key [name="RUN_MIGRATIONS"]`.

### What the Job reads

Nothing the Deployments do not already read, and less of it:

- **One container per enabled service** (`migrate-auth`, `migrate-central`, `migrate-edge`; a
  disabled service is not migrated), running side by side. The three schemas are independent.
  The Job fails if any container does.
- Each container mounts **only its own service's** `config.existingSecret` as `<service>.conf`, and
  gets **only that service's `POSTGRES_PASSWORD`** from `secrets.existingSecret`. The three configs
  all say `${POSTGRES_PASSWORD}` with a different value each, which is why there is no single
  container. The tool reads `postgres.url`, `user`, `password` and `ssl-root-cert` and tolerates every
  other unresolved placeholder, so token, signing and client secrets never reach the Job.
- A service's `extraVolumes` / `extraVolumeMounts` are mounted in its container too, so a
  `postgres.ssl-root-cert` path resolves exactly as it does in the Deployment.

Both Secrets must exist **before** `helm install`. A hook runs ahead of every other resource of the
release, so a Secret created by the same release, or by an operator that has not synced it yet,
is not there for it and the Job waits until `activeDeadlineSeconds`. The connection must reach
Postgres itself, not a transaction-pooling PgBouncer in front of it.

### Why a rolling update is safe

The order is *migrate, then roll the pods*, and it holds up for two reasons that live in the code:

- The old pods keep serving on the migrated schema. Migrations are written to be compatible with the
  previous release (`.claude/rules/database-migrations.md`: add, never drop or rename in the same
  release), and a service ignores a schema that is *ahead* of its build.
- If the Job did not run at all (the tool does not support Helm hooks, someone deleted it), the new
  pods stop at boot with the name of the missing migration instead of serving errors from a table that
  is not there.

`helm rollback` does not undo a migration, and does not run one. The previous version starts on the
newer schema for the same reason the old pods above do. A migration that cannot be survived by the
previous release has to be said so in its release notes; restoring the pre-upgrade `pg_dump` is the
only way back from it.

### Settings

| Value | Default | |
|---|---|---|
| `migrations.enabled` | `true` | Turn off only if something else migrates (below). |
| `migrations.hook` | `true` | A `pre-install,pre-upgrade` Helm hook. `false`: an ordinary Job. |
| `migrations.activeDeadlineSeconds` | `270` | Keep it **below** the `--timeout` of `helm upgrade` (5m unless set), or Helm gives up while the Job keeps running. Raise both for a long migration. |
| `migrations.lockTimeoutMs` | `5000` | A statement waiting longer for a lock gives up and the service's migration is retried, instead of queueing every query behind it while the old pods serve. `0` waits forever. |
| `migrations.lockRetries` / `connectRetries` | `3` / `3` | |
| `migrations.dryRun` | `false` | Reports the pending migrations and applies nothing. |
| `migrations.backoffLimit` | `1` | Job retries after a pod dies for its own reason, such as a node drain. |
| `migrations.ttlSecondsAfterFinished` | `86400` | How long a finished Job keeps its logs. |

A failed hook Job stays for its logs (`kubectl logs job/<release>-versola-migrate --all-containers`);
the next attempt replaces it. A succeeded one is removed.

### Without Helm hooks (Argo CD, Flux, plain manifests)

Set `migrations.hook=false`. The chart then renders an ordinary Job whose name carries a hash of what
it runs (image, arguments, environment, mounts), because a Job's pod template cannot be changed in
place. Ordering becomes the tool's job; put its annotations in `migrations.annotations`.

- **Argo CD:** `argocd.argoproj.io/hook: PreSync` and
  `argocd.argoproj.io/hook-delete-policy: BeforeHookCreation`. Argo CD ignores every `helm.sh/hook`
  as soon as one `argocd.argoproj.io/hook` exists, runs PreSync before the other resources of the
  Application, and cannot tell an install from an upgrade, so the Secrets must live outside this
  Application (or in an earlier sync wave) and the Job runs on every sync, which a migration that has
  nothing left to apply tolerates. Without a hook the pods start first and crash-loop until the Job
  has run.
- **Flux:** the HelmRelease runs the Helm hooks itself, so the default works; set
  `spec.upgrade.timeout` above `migrations.activeDeadlineSeconds`.
- **Plain manifests:** `helm template` and apply the Job first, wait for it, then the rest.

### By hand

With `migrations.enabled=false`, or for a one-off (`--dry-run`, a single service), run the same Job
yourself. It needs the same two things per service: the env.conf mounted as `<service>.conf` under
`/opt/versola-tools/config`, and `POSTGRES_PASSWORD` in the environment.

```bash
helm template versola k8s/versola -f values.yaml --set migrations.dryRun=true \
  -s templates/migrate-job.yaml | kubectl apply -f -
```

It creates each schema if absent, then migrates each service independently. `--dry-run` reports
what would be applied, including against a database with no history at all, without touching
anything.

---

## 6. Installing the charts

```bash
helm upgrade --install versola k8s/versola --namespace versola --create-namespace \
  --set services.auth.config.existingSecret=auth-config \
  --set services.central.config.existingSecret=central-config \
  --set services.edge.config.existingSecret=edge-config \
  --set secrets.existingSecret=versola-secrets \
  --set ingress.enabled=true \
  --set ingress.className=nginx \
  --set 'ingress.hosts[0].host=id.example.com' \
  --set 'ingress.hosts[0].routes={oidc,login,api}'
```

On a first install **auth and edge come up before central is answering**, and wait for it. Both
sync their configuration from central while building their own dependencies, and nothing orders
the three — a Helm install schedules them at once. A service in that state is `Running` and
`0/1` Ready, logging `Still waiting for the source of cache ...`
([#378](https://github.com/versolauth/versola/issues/378)).

How that ends depends on the outbound pool (`HTTP_CLIENT_POOL_KIND`, below):

- With `dynamic` (min 0) the service becomes ready by itself once central binds its port, and
  `restartCount` stays 0.
- With the default `fixed` pool it does **not**. The first request to central opens all the pool's
  connections, and when central refuses them the pool keeps one failed connection per slot (128 by
  default). Each retry, every 5 seconds, consumes one of them, so the wait cannot recover inside
  its budget even though central is already listening. The service exits with code 1 after the two
  minutes and relies on the restart policy (`restartPolicy: Always` in Kubernetes,
  `restart: unless-stopped` in the compose templates); the restarted process builds a fresh pool
  and starts normally. Expect one restart on a cold install.

The wait is two minutes, and only for a central that cannot be *reached*. A central that answers
and rejects the request — wrong URL, wrong sync key — still fails startup within seconds rather
than spending the whole budget on something waiting cannot fix. Once the service has synced at
least once, a restart that finds central unreachable after the wait starts from the configuration
snapshot kept in its own database and becomes Ready on it, retrying central in the background
(`config_snapshot_age_seconds`, see deploy.md, #566); it does not restart.

### Availability: spread, disruption budgets, priority, autoscaling

Four things keep a node drain, a zone loss or a traffic peak from taking the login path with it
([#211](https://github.com/versolauth/versola/issues/211)). Every default below is a starting point, not
a guarantee — the right numbers depend on your traffic profile — and every one is overridable per
service under `services.<name>` (or `global` for the spread).

| | Default | What it does |
|---|---|---|
| Topology spread | on, **soft** (`ScheduleAnyway`) across hostname and zone | Replicas of one component are spread over nodes and zones. Soft, because a hard constraint leaves pods Pending on a cluster with fewer nodes or zones than replicas. A constraint with no `labelSelector` gets the component's own, so `auth` replicas spread among `auth` replicas. A component's own list **replaces** `global`'s; `[]` turns spreading off. |
| PodDisruptionBudget | on, `maxUnavailable: 1` | Rendered only for a service running more than one replica (`replicaCount`, or `autoscaling.minReplicas` under an HPA): with one, any budget either protects nothing or makes a drain hang. Set `minAvailable` instead (and `maxUnavailable: null`), not both. |
| PriorityClass | off | `priorityClasses.create: true` creates `versola-customer-facing` (auth, edge) and `versola-control-plane` (central), so node pressure evicts the admin plane first. Off because a PriorityClass is cluster-scoped and installing the chart should not need rights over cluster objects; `services.<name>.priorityClassName` names an existing class either way. |
| HorizontalPodAutoscaler | off | `services.<name>.autoscaling.enabled`. The Deployment then stops stating `replicas` (it would reset the autoscaler on every upgrade). |

**Do not scale these services on memory, and be careful with CPU.** The JVMs run a fixed heap
(`-Xms512m -Xmx512m`), so memory use is flat in load and says nothing about demand. CPU is a weak signal
too: RSA signing and Argon2id hashing are the CPU-bound hot paths and both run behind semaphores, so
under load CPU saturates at what those admit instead of rising with demand, and a utilisation target can
sit below its threshold while requests queue. The default metric is CPU utilisation of the request
(`targetCPUUtilizationPercentage: 70`, which needs `resources.requests.cpu`); `autoscaling.metrics`
replaces it with any autoscaling/v2 metric list — a Pods metric from `/metrics` through an adapter, for
instance — and `autoscaling.behavior` slows scale-in. `central` is the admin plane and is better left at a
fixed replica count.

Check what the chart renders with `.github/scripts/check-chart-availability.sh`.

### Outbound connections: `HTTP_CLIENT_POOL_*`

auth, central and edge share one outbound HTTP client (calls to each other, and edge's calls to the resource backends it proxies). Its pool is per **target**: zio-http 3.11.6 keeps one pool per (scheme, host, port, proxy, SSL and timeout settings), so every backend gets its own pool of the configured size, not one pool shared by all. Set the variables through `services.<name>.extraEnv`; the resolved pool is logged once at startup (`outbound HTTP client pool: ...`), so an A/B run can check what a pod runs with. An invalid value falls back to its default.

| Variable | Meaning | Default |
|---|---|---|
| `HTTP_CLIENT_POOL_KIND` | `fixed` or `dynamic` | `fixed` |
| `HTTP_CLIENT_POOL_SIZE` | connections per target; for `dynamic` the maximum | `128` (zio-http's own default is 10) |
| `HTTP_CLIENT_POOL_MIN` | `dynamic` only: connections kept open when idle (not above the maximum) | `0` |
| `HTTP_CLIENT_POOL_TTL` | `dynamic` only: how long the pool must be unused before it shrinks to the minimum, a duration such as `30s` or `2m` | `30s` |

An HTTP/1.1 connection serves one request at a time, so the pool bounds a pod's throughput towards one backend at `size / backend latency`: with 10 connections and a 13 ms backend, ~780 requests/s however idle the CPU, and past that the requests queue (seen in the step-load test as a ~1 s p99 on one edge pod). Size it for `peak requests/s per pod x backend p99 latency`, with headroom. Neither kind fails when it is exhausted: the request waits for a free connection with no timeout of its own, so a pool that is too small shows as latency, never as errors. `http_client_active_requests{peer}` is the number of outbound requests in flight, queued ones included: it climbs first where a pool is too small.

What the kinds do (read from zio-http 3.11.6 and zio 2.1.26):

- `fixed`: exactly `size` connections per target, all opened when the target is first used (the first request waits for them) and kept for good. The default, unchanged from before this variable existed.
- `dynamic`: between `min` and `size` connections per target. `min` are opened on first use, more on demand up to `size`. The pool shrinks back to `min` only after it has seen no checkout or return for `TTL`; this is a pool-wide idle time, not a per-connection one, so under steady traffic the extra connections stay open. A connection that closed while pooled is discarded on checkout and another is tried.
- zio-http also has per-host sizes (`FixedPerHost`, `DynamicPerHost`); they are not exposed here.

Which to pick (provisional: this is reasoning from how pools behave, to be replaced by the A/B on the cluster, which is the point of the switch):

- Edge to many resource backends, long-lived keep-alive (reverse-proxy shape): `fixed 128` opens 128 connections to every backend it touches, idle or not. With many backends that is a lot of sockets, and memory in the pod and on the backends, and a rarely used backend holds them indefinitely. `dynamic` with a low `MIN` and a `SIZE` at the ceiling you would have used for `fixed` pays only for the backends in use. It costs a connection set-up when a burst arrives.
- Service-to-service (edge to auth, auth to central): few targets, steady traffic, usually in-cluster and often plain HTTP. `fixed` keeps warm connections ready for a burst, with no handshake on the request path; with so few targets the idle-socket cost is small. A TLS handshake (or mTLS through a mesh) makes a connection expensive to open, which favours a high `MIN` or `fixed` here.
- Stale idle connections: a backend, load balancer or mesh sidecar that closes idle connections after its own timeout leaves the client with dead sockets. zio-http only discovers this on checkout, and retries on another. A `fixed` pool under low traffic holds idle connections the longest, so it is more exposed. `dynamic` only helps once the whole pool has been idle for `TTL`, so keep `TTL` below the shortest idle timeout on the path.
- Burst cost: `dynamic` with `MIN=0` turns a burst into a burst of connects (TCP, plus TLS where used), visible as a latency step at the start of the burst; raise `MIN` to the steady-state concurrency to avoid it.
- Memory: each connection holds a socket and Netty channel buffers (and TLS state), small per connection but multiplied by targets x pods; compare pod memory and the backends' connection counts between the two kinds.

---

## 7. Ingress

The chart exposes route *groups*, not individual paths. A host lists the groups it serves, and
nothing is exposed that is not listed — there is no catch-all group.

| Group | Backend | Paths |
|---|---|---|
| `oidc` | auth | `/authorize` `/token` `/par` `/introspect` `/revoke` `/userinfo` `/logout` `/.well-known/` `/challenge` |
| `login` | edge | `/login/` `/complete` `/logout/` |
| `api` | edge | `/resources/` `/permissions/` |
| `native` | edge | `/native/` — what a mobile app calls under FAPI 2.0 (#420); list it on any host with native clients |
| `console` | console | `console.basePath` |
| `users` | auth | `/users`, `/users/` — normally reached over cluster DNS instead |
| `service` | auth | `/service/` — non-prod config-sync tooling, `404` when `env` is `prod` |
| `settings` | auth | `/settings` on auth's *additional* port |

`/logout` is the one overlap: auth owns it exactly, edge owns the `/logout/` prefix.

**`ingress.className` is required.** The template fails rather than fall back to the cluster's
default IngressClass — a cluster whose only controller is a stock `ingress-nginx` install has no
default, because that chart does not mark its own class as default. Left to the fallback, the
Ingress would render, look entirely correct, and be adopted by nobody: `ADDRESS` stays empty and
requests get a bare `404` from the controller, which reads as a routing bug rather than an
unclaimed resource. Annotating the class as default *afterwards* does not fix an Ingress already
created without one either — the default is applied at admission, so the object has to be
recreated or patched with an explicit `spec.ingressClassName`. Name the class up front instead.

There is no route group for central, but nothing needs one: `loadgen provision` reaches central
through `auth` (a `client_credentials` token, the `oidc` group's `/token`) and then `edge`'s
generic resource proxy (the `api` group's `/resources/`), both already exposed. central's admin
API is otherwise reached over cluster DNS, by callers that are already inside the cluster.

---

## 8. The load emulator

`loadgen/` deploys the instrument, never the system under test. Its configuration follows the
same two-Secret shape, with two differences:

- **Each role needs its own config Secret.** `role` lives in the file, not the environment, so
  one Secret cannot serve both the coordinator and the drivers:
  `driver.config.existingSecret` and `coordinator.config.existingSecret` are both required, and
  the template fails rather than rendering a pod that would crash-loop on a missing config.
- **`envName` is a chart value**, injected as `ENV_NAME`, and the config is expected to say
  `env = ${ENV_NAME}`. An empty `envName` fails the template.

`driver.replicaCount` is also `SHARD_COUNT`, and it must equal the `seed.shard-count` the
population was seeded with. Changing it after seeding leaves the new shards' rows unowned.

### Keeping the emulator and the system apart

A campaign's numbers are only as good as the isolation of whatever produces them: a driver
sharing a node with auth adds its own CPU contention to the latencies it reports. Both charts take
`nodeSelector`, `tolerations` and `affinity`, chart-wide under `global` and per component, so the
two can share one cluster on separate node groups:

```yaml
# loadgen values
global:
  nodeSelector: { yandex.cloud/node-group-id: <loadgen node group> }

# versola values
global:
  nodeSelector: { yandex.cloud/node-group-id: <system node group> }
```

- **A component's own value replaces the global one**; it is not merged with it. An empty one
  inherits. So a component cannot opt out of a global value by leaving its own empty — give it a
  different value instead.
- **The coordinator keeps its standby spread.** It defaults to a preferred `podAntiAffinity` that
  keeps its standby off the active replica's node, and that default survives a `nodeAffinity` you
  add to pin the chart. An explicit `podAntiAffinity` of your own replaces it.
- **A selector keeps a chart's pods on its group; it does not keep other workloads off it.** For a
  dedicated loadgen group, taint the group and give loadgen the matching toleration, so nothing
  else schedules there.
- **The three fields live under `global` or a component, never at the top level.** `--set
  nodeSelector.workload=loadgen` sets a path no template reads; Helm would install it happily and
  place nothing. Both charts refuse to render instead, naming the path they do read.

A pod whose selector matches no node stays `Pending`, with `didn't match Pod's node
affinity/selector` in its events — it does not quietly schedule somewhere else. Check placement
with `kubectl get pods -o wide` before a run, not after.

### Reaching the system under test, and being reached by it

Traffic goes both ways, which is easy to miss:

- drivers call auth and edge — through the ingress, or over cluster DNS if co-located;
- **edge calls `mockapi`**, because it proxies `/resources/` to whatever URI `loadgen provision`
  registered in central.

In one cluster both directions work over cluster DNS. If the emulator and the system under test
are in different clusters, `mockapi` needs to be reachable *from* the system's cluster, not only
the other way round.

The `mockapi` Service names each resource separately, and an nginx sidecar makes each one look
like its own origin: `proxy_pass http://127.0.0.1:8100/resources/core/` means a `GET /accounts`
on the core port arrives at mockapi as `GET /resources/core/accounts`. The URI registered in
central is therefore a **bare origin with no path**. Writing `http://host:8110/resources/core`
doubles the prefix, and every resource call returns `404` in a way that looks like edge is
broken rather than like a configuration mistake.

---

## 9. Certificates for mutual TLS

On by default (`pki.enabled: true`), so **cert-manager and trust-manager have to be installed before this chart** —
their `ClusterIssuer`/`Certificate`/`Bundle` CRDs are rendered. It wires up [cert-manager](https://cert-manager.io/)
for the three certificate relationships described in
[`../develop.md`](../develop.md#certificate-storage-and-rotation-440). The chart consumes
cert-manager (and, optionally, trust-manager); it installs neither. Opt out with
`pki.enabled: false`.

Out of the box you get: auth's mutual-TLS listener on `8083` (container and Service port), the
Versola Client CA (a `ClusterIssuer`, its Secret in cert-manager's namespace so trust-manager can
read it), auth's listener certificate, a trust-manager `Bundle` that auth mounts as its trust
anchors, Reloader annotations on auth and edge, and a service account for central that may create
cert-manager `CertificateRequest`s in the release namespace — nothing else. Central uses it to
issue and renew edge-fronted clients' certificates itself: register the client with
`issueEdgeClientCertificate: true` and an `mtlsAuth`, and it asks the issuer named by
`CLIENT_CERT_ISSUER_NAME` (set by the chart; `scripts/gen-env.scala`'s `k8s` target writes the
`client-certificates` block that reads it) for a 14-day certificate, renewed at 4 days left.
`scripts/gen-env.scala` also writes the `mutual-tls` and `native` blocks with the paths below.

`pki.clients` and `pki.certSync` are for certificates issued *outside* central (a cert-manager
`Certificate` per client, copied into central by `cert-sync`), and are off by default.

```yaml
# Everything below except `clients` is already the default.
services:
  auth:
    mutualTlsPort: 8083      # container + Service port, MPORT env
pki:
  enabled: true
  trustBundle:
    enabled: true
    sources: []              # additional CAs only, e.g. the incoming one during a rotation
  reloader: true             # Stakater Reloader restarts auth/edge on renewal
```

What that renders: a self-signed root and a CA `ClusterIssuer` (the "Versola Client CA" -- or point
`pki.issuerRef` at an issuer you already run), a leaf `Certificate` for auth's listener
(`<release>-versola-auth-mtls`, PKCS#8), the trust-manager `Bundle`, and central's service account,
`Role` and `RoleBinding`.

The chart generates no `env.conf`, so point yours at the files it mounts:

| Service | Mounted at | Use as |
|---|---|---|
| `auth` | `/app/mtls/server/tls.crt`, `tls.key` | `mutual-tls.certificate`, `.private-key` |
| `auth` | `/app/mtls/trust/ca.crt` | `mutual-tls.trusted-certificates` (the trust-manager bundle if enabled, else the issuing CA) |
| `edge` | `/app/mtls/auth-pin/tls.crt` | `native.trusted-certificates = ["/app/mtls/auth-pin/tls.crt"]` (a leaf pin; edge gets only the certificate, never the key) |
| `central` | `/app/central-mtls/server/tls.crt`, `tls.key` | `mutual-tls.certificate`, `.private-key` (central's listener for the edge surface) |
| `central` | `/app/central-mtls/trust/ca.crt` | `mutual-tls.trusted-certificates` (the issuing CA) |
| `edge` | `/app/central-mtls/client/tls.crt`, `tls.key` | `central.mutual-tls.certificate`, `.private-key` |
| `edge` | `/app/central-mtls/server-pin/tls.crt` | `central.mutual-tls.trusted-certificates = ["/app/central-mtls/server-pin/tls.crt"]` (a leaf pin on central's listener certificate) |

Both auth and edge read these once at startup, so a renewal needs a restart: set
`pki.reloader` with Stakater Reloader, or `kubectl rollout restart` after cert-manager renews. A
renewal restarts auth and edge together, so for zero-downtime rotation of auth's listener
certificate use the two-pin procedure (publish the next leaf on every edge before auth switches)
instead of relying on automatic renewal. **CA rotation** with `trustBundle`: add the incoming CA's
Secret (in trust-manager's trust namespace) to `sources` next to the bootstrapped one, reissue the
clients, then remove the outgoing source.

**The edge surface on central (#554).** Central serves what an edge reads (`/edge/*`) on its own
mutual-TLS listener, `services.central.mutualTlsPort` (`8093`, MPORT), and nowhere else; edge's
`central.url` is `https://<release>-versola-central:8093`. Every call carries two proofs: the
handshake's certificate chains to the issuing CA, and carries the URI name
`urn:versola:edge:<id>` -- `pki.edgeCentral.edgeId`, which must equal the `id` in edge's
`env.conf` -- for the same edge the signed token names. cert-manager issues both certificates
(`pki.centralServer`, `pki.edgeCentral`) and renews them; nothing is registered in central when it
does, so a renewal needs only the restart Reloader gives edge and central. The chart's Service is
a ClusterIP. To let edges outside the cluster in, publish the port with a `LoadBalancer` or a
TLS-**passthrough** route (ingress-nginx `ssl-passthrough`, a Gateway `TLSRoute`) -- never a
terminating one, which would swallow the certificate -- add the public name to
`pki.centralServer.dnsNames`, and restrict the source addresses where you can. With `pki.issuerRef`
left empty the issuing CA also signs edge-fronted clients' certificates; central refuses to issue
one carrying an edge's URI name, but a CA used for nothing else is the cleaner setup
(`pki.issuerRef`).

A **web** client that authenticates with a certificate (`tls_client_auth`, what a FAPI 2.0 tenant admits) needs
the same listener, and edge uses it by itself: with `versola-internal-url` plaintext (the chart's `http://versola-auth:8080`)
and the `native { }` block present, edge sends that client's `/par`, `/token` and the `/userinfo` of its
certificate-bound tokens to `native.auth-mutual-tls-url`, pinned to `native.trusted-certificates`. Every other call
(a secret or key client, a key-bound token's `/userinfo`) stays on `versola-internal-url`. With a TLS terminator in front of
auth instead (`versola-internal-url` is `https`), nothing moves. With neither, the login is refused with
`CredentialNeedsTls` (versolauth/versola#551).

Registering a native client that central should issue for needs no certificate in the request — see
above. A client whose certificate you supply yourself (`edgeClientCertificate`) is left alone: central
renews only the certificates it issued.

---

## 10. Known rough edges

All of these have open issues; none of them has a fix in the chart yet.

| | |
|---|---|
| [#380](https://github.com/versolauth/versola/issues/380) | central's admin API secret cannot be obtained or rotated once bootstrap has generated it — set `bootstrap.resource-secret` **before** the first start |

Observability is external by design. The Grafana dashboard is checked in under `dashboards/grafana/` at the repository root, outside both charts, and is not installed by either. Nothing here deploys Prometheus, and neither chart creates
`ServiceMonitor` or `PodMonitor` objects — both `loadgen` and `versola` carry `prometheus.io/scrape`
annotations on every pod (`versola`'s via `metrics.prometheusAnnotations`, default `true`), so how
scraping happens is still the cluster's business, but a `kubernetes_sd_configs`/annotation-based
scraper (vmagent, vanilla Prometheus) can now find every replica of every component without any
extra manifest here.

**Don't point a scrape target at this chart's Service DNS names instead of the annotations.**
`versola-auth`/`-central`/`-edge` are ClusterIP, one Service per component fronting every
replica — that name resolves to the Service's single, stable ClusterIP, not to a random pod; the
randomness is one layer below DNS, in kube-proxy's iptables/IPVS rules, which DNAT each new
connection to that ClusterIP to one of the backing pods, chosen essentially at random. So at
`replicaCount > 1` the instance label such a target produces (the ClusterIP:port itself) stays
fixed while the pod actually behind it varies per connection, `rate()`/`increase()` over the
result undercounts and jitters, and the series never carries a `pod` label. This is exactly what
happened in the target cluster's `vmagent` (a `job_name: versola-sut` static target on the Service
DNS, first caught as a several-times
distortion during the 1M-run loadgen campaign) — see
[`k8s/monitoring/vmagent-values.yaml`](monitoring/vmagent-values.yaml) for that cluster's actual
fix, checked in there because it previously existed only as a live Helm release nobody had a copy
of.

The dashboard and how to load it are described in [`dashboards/grafana/README.md`](../dashboards/grafana/README.md). It selects series by `namespace` and the `app_kubernetes_io_component` label, which the vmagent job above copies from the pod label onto every series.
