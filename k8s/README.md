# Deploying Versola to Kubernetes

Two charts live here:

| Chart | What it deploys |
|---|---|
| [`versola/`](versola) | `auth`, `central`, `edge` and the admin console — the system itself |
| [`loadgen/`](loadgen) | the load emulator: coordinator, driver fleet and `mockapi` — never the system under test |

> **Scope.** This is not how the shared production host runs today. That is a Docker Compose
> deployment on a single VPS, documented in [`../deploy.md`](../deploy.md), and the two paths
> share the configuration *format* and nothing else — no compose file, no `env-config`
> repository, no host nginx. Where `deploy.md` is a runbook for one specific machine, this is
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
  `versola-edge`, `versola-gateway` for the system; `versola-loadgen`, `versola-mockapi` for the
  emulator; `versola-tools` for migrations. All are public, so no `imagePullSecrets` are needed.
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
| Postgres URL (×3, one per service) | defaults to `localhost` | each service's real JDBC URL — append `?ssl=true&sslmode=require` if the database needs it |
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

Two of central's generated values need a matching home outside these files entirely:
`bootstrap.utility-client`'s secret must equal whatever configures `loadgen`'s own
`provision.provisioner-secret` (`loadgen provision` authenticates as this client to reach
central's admin API — see [§8](#8-the-load-emulator)), the same kind of out-of-band match a
Postgres password is. `bootstrap.resource-secret` has no such counterpart to match — central is
the only reader — but it still has to be present at central's *first* boot: central seeds each
of the two exactly once, the first time it finds neither configured, and a value added to the
config later has no effect on one already seeded (versolauth/versola#380).

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

Twenty-six keys in total. `versola.secretEnv` fails the template if `secrets.existingSecret` is
unset, and a missing key surfaces as an unresolved HOCON substitution at startup, so both
mistakes fail loudly rather than silently.

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
They do not apply them: `RUN_MIGRATIONS` is hardcoded to `"false"` in the Deployment template,
and the migration Job that will replace it is
[#209](https://github.com/versolauth/versola/issues/209).

Nor can you override it through `services.<name>.extraEnv`. That renders *after* the hardcoded
entry, so the container ends up with two `env` entries of the same name, and Helm 4's
server-side apply rejects that outright:

```
.spec.template.spec.containers[name="auth"].env: duplicate entries for key [name="RUN_MIGRATIONS"]
```

Apply migrations with `versola-tools` instead, as a one-shot Job. It reads the *same*
`auth.conf`/`central.conf`/`edge.conf` the services will start from, so what it applies cannot
drift from what they expect to find:

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: versola-migrate
  namespace: versola
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: migrate
          image: ghcr.io/versolauth/versola-tools:<appVersion>
          args: ["migrate"]            # add "--dry-run" to report without applying
          envFrom:
            - secretRef:
                name: versola-migrate-env
          volumeMounts:
            - name: config
              mountPath: /opt/versola-tools/config
              readOnly: true
      volumes:
        - name: config
          secret:
            secretName: versola-migrate-config
```

Two Secrets of its own, because it needs the configs under their own names and the values keyed
by *variable* name rather than by the chart's key naming:

- `versola-migrate-config` — keys `auth.conf`, `central.conf`, `edge.conf`.
- `versola-migrate-env` — the union of the three `*.generated-secrets.env` files. The tool calls
  `ConfigFactory.parseFile(...).resolve()`, which resolves the whole document, so *every*
  placeholder in all three files must be satisfied — not only the Postgres block it reads.

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
`0/1` Ready, logging `Still waiting for the source of cache ...`, and becomes ready by itself
once central binds its port; `restartCount` stays 0 throughout, so it remains usable as a health
signal after a rollout ([#378](https://github.com/versolauth/versola/issues/378)).

The wait is two minutes, and only for a central that cannot be *reached*. A central that answers
and rejects the request — wrong URL, wrong sync key — still fails startup within seconds rather
than spending the whole budget on something waiting cannot fix.

---

## 7. Ingress

The chart exposes route *groups*, not individual paths. A host lists the groups it serves, and
nothing is exposed that is not listed — there is no catch-all group.

| Group | Backend | Paths |
|---|---|---|
| `oidc` | auth | `/authorize` `/token` `/par` `/introspect` `/revoke` `/userinfo` `/logout` `/.well-known/` `/challenge` |
| `login` | edge | `/login/` `/complete` `/logout/` |
| `api` | edge | `/resources/` `/permissions/` |
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

## 9. Known rough edges

All of these have open issues; none of them has a fix in the chart yet.

| | |
|---|---|
| [#380](https://github.com/versolauth/versola/issues/380) | central's admin API secret cannot be obtained or rotated once bootstrap has generated it — set `bootstrap.resource-secret` **before** the first start |
| [#209](https://github.com/versolauth/versola/issues/209) | no migration Job — see [§5](#5-applying-migrations) |

Observability is external by design. The dashboards in `loadgen/dashboards/` are checked in but
not installed: `dashboards.configMap.enabled` is off by default, and turning it on only helps if
Grafana's sidecar watches that label. Nothing here deploys Prometheus, and neither chart creates
`ServiceMonitor` or `PodMonitor` objects — `loadgen` carries `prometheus.io/scrape` annotations,
`versola` carries none, so how scraping happens is the cluster's business.
