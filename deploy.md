# Deployment

How Versola is deployed to production (`id.versola.kz`). For local development see
[`develop.md`](develop.md); for Kubernetes see [`k8s/README.md`](k8s/README.md) (it shares the
configuration *format* with this path and nothing else).

Production is one VM (Debian 12) and a managed PostgreSQL 18 cluster in the same private network.
Versola runs on the VM under [`versola-cli`](https://github.com/versolauth/versola-cli):
`versola configure vps`, `versola migrate`, `versola up` pull the release, generate its
configuration, resolve secrets against OpenBao and start everything. Commands assume you are logged
in on the VM as the deploy user, with `sudo`.

Two ways to put Versola on 80/443, chosen with `--proxy` (it must be given on every `configure`):

- `nginx` (default): Versola's own reverse proxy, `versola-proxy`, owns 80/443 and gets its
  certificate from Let's Encrypt.
- `external`: a web server already on the host terminates TLS and forwards to `versola-proxy` on
  `127.0.0.1:2821` — see [Behind an existing web server](#behind-an-existing-web-server).

---

## 1. Overview

| Component | How it runs | Managed by |
|---|---|---|
| PostgreSQL 18 | managed cluster, private address only, `:6432` (the provider's pooler), TLS verified against the provider's CA | the cloud provider (backups, failover) |
| `auth`, `central`, `edge` | Docker, Compose project `versola-vps`, `network_mode: host`, bound to `127.0.0.1` | `versola-cli` |
| `step-ca`, `mtls-init` | Docker, same project: internal CA for client certificates ([mutual TLS](#mutual-tls-certificates)); `mtls-init` is a one-shot | `versola-cli` |
| `versola-proxy` | Docker, `nginx:1.30-alpine`, same project | `versola-cli` (config generated) |
| OpenBao (`versola-openbao-vps`) | Docker, same project, `127.0.0.1:8200` | `versola-cli` |

```
browser ──443──▶ versola-proxy (TLS, Let's Encrypt) ──▶ auth  127.0.0.1:8080
                                                    └──▶ edge  127.0.0.1:8095 ──▶ central 127.0.0.1:8090
```

All routing — which path goes to `auth` and which to `edge`, the admin console, legacy redirects —
lives in `versola-proxy`, whose configuration `versola-cli` regenerates on every `configure`.
Nothing binds to `0.0.0.0` except `versola-proxy` (or the host's web server with `--proxy external`).

The cluster has no public address; the VM is the only way in. The VM's security group should allow
`80` and `443` from anywhere (`80` is needed for certificate issuance and renewal), `22` as you
decide, and nothing else inbound.

| Service | App (`PORT`) | Additional (`APORT`) | Diagnostics (`DPORT`) |
|---|---|---|---|
| `auth` | 8080 | 8082 (Account Settings, internal) | 8081 |
| `central` | 8090 | — | 8091 |
| `edge` | 8095 | — | 8096 |
| `versola-proxy` | 80/443 (`2821` with `--proxy external`) | — | — |
| `step-ca` / OpenBao | 9000 / 8200 | — | — |

`auth` also has a mutual-TLS listener (`MPORT`, 8083, loopback). The generated `compose.yml` is the
source of truth for ports; `EXPOSE` in the Dockerfiles is inert under `network_mode: host`.

**Startup order matters.** `auth` and `edge` block on their initial configuration sync from `central`
(up to two minutes) and are not serving meanwhile. `versola up` starts `central`, waits for its
`/readiness`, then `auth` and `edge`, and `versola-proxy` last.

### Where things live on the host

| Path / object | What it is |
|---|---|
| `~/.versola/active/state.json` | the live deployment: version, bundle, `--auth-url`, proxy mode, `--postgres-host`, `--postgres-db` |
| `~/.versola/active/bundle-*/` | one directory per `configure`: `compose.yml`, `proxy.yml`, `*.conf`, `*.secrets.env` (mode `600`), `openbao.hcl`, `central-ui/` (the admin console), `proxy/`, `postgres-ca/ca.crt` |
| `~/.versola/openbao/vps.json` | AppRole credentials `versola-cli` uses to read/write secrets |
| `~/.versola/openbao/vps-admin.json` | OpenBao root token + unseal key. **Keep a copy off the host** — neither is recoverable if lost |
| Volume `versola-openbao-file-vps` | OpenBao's data (all secrets). Survives `down` |
| Volume `versola-acme-vps` | Let's Encrypt state (`--proxy nginx`) |
| Volumes `versola-step-ca-vps`, `versola-central-ca-vps`, `versola-mtls-vps` | the internal CA's keys, central's access to it, auth's mTLS certificate. Not in OpenBao or the database |

Everything in a bundle is **generated** — do not hand-edit it. The next `configure` writes a new
bundle and removes old ones once nothing runs from them.

---

## 2. First-time setup

For a **fresh** server; routine releases are [3](#3-releases-and-recovery).

**Prerequisites**

- Docker and the Compose plugin (Debian: Docker CE from Docker's apt repository — `docker.io` has no
  Compose plugin; add the deploy user to the `docker` group). `versola doctor --target vps` checks
  them, plus memory and disk.
- The managed PostgreSQL 18 cluster and the CA file that signed its certificate.
- A DNS record for the `--auth-url` host pointing at the host's public IP **before `versola up`**
  (Let's Encrypt validates over port 80). Behind Cloudflare it must be *DNS only* while the
  certificate is issued.
- `versola-cli`: `curl -fsSL https://raw.githubusercontent.com/versolauth/versola-cli/main/install.sh | sh`,
  then `export PATH="$HOME/.local/bin:$PATH"`. `versola upgrade` updates it later.
- Published images in `ghcr.io/versolauth/` (`versola-tools`, `versola-auth`, `versola-central`,
  `versola-edge`); they are public, no `docker login` needed.
- A client of the server's major version for `psql`/`pg_dump`: Debian 12's is 15 and `pg_dump`
  refuses a version 18 server, so install `postgresql-client-18` from `apt.postgresql.org`.

### Configure

```bash
versola doctor --target vps
versola configure vps <version> \
  --auth-url https://id.versola.kz \
  --postgres-host c-<cluster-id>.rw.mdb.yandexcloud.kz:6432 \
  --postgres-db auth \
  --postgres-ca ~/yandex-root.crt
```

- `<version>` is the release tag **without** a leading `v` (`0.7.1`, not `v0.7.1`): image tags are the
  git tag verbatim.
- `--auth-url` must be exactly the origin browsers use — scheme and host, no path, no default port;
  the CLI rejects anything else (a mismatch would break passkeys and redirects).
- `--postgres-host` is `host:port`; use the cluster's *read-write* name (`c-<id>.rw…`), which follows
  a failover. `--postgres-db` defaults to `auth`. `--postgres-ca` is the CA for a database the JVM
  does not trust (the managed cluster's is `https://storage.yandexcloud.kz/cloud-certs/CA.pem`).
  These three are remembered for later runs (`versola-cli` 0.2.10+, `versola-tools` 0.7.1+).
- `--acme-staging` rehearses certificate issuance without counting against Let's Encrypt's rate
  limits (the certificate is untrusted). `--proxy external` — see below.

`configure` pulls `versola-tools:<version>`, generates the bundle, starts OpenBao and, on the first
run, provisions it (init, unseal, KV v2, AppRole, a policy limited to `secret/data/versola/vps/*`),
saving the root token and unseal key to `~/.versola/openbao/vps-admin.json`. It then resolves every
secret: an existing OpenBao value wins, a missing one is generated and stored. Nothing of Versola is
started yet. To set OpenBao up yourself, pass `--setup-openbao` and follow
[`develop.md`](develop.md#secrets-openbao).

### Database, role and schemas

All three services share **one** database (`auth` by default), isolated by schema; the generated
configs pin each to its own (`?currentSchema=central`, …). Schemas of other applications in the same
database are never touched. **Do not merge schemas:** Flyway keeps `flyway_schema_history` inside the
schema it points at, and two services sharing one abort with `FlywayValidateException`
([6.1](#61-flywayvalidateexception)).

On the first `configure` against an empty OpenBao the Postgres password is generated then, so the
role gets it only afterwards. `configure` prints the statements:

```
CREATE ROLE "versola_app" WITH LOGIN PASSWORD '…';   -- if the role doesn't exist yet
ALTER ROLE "versola_app" WITH PASSWORD '…';          -- if it already exists
```

On the managed cluster the role and database come from the cloud (user `versola_app` owning `auth`;
there is no superuser). Set the password as `versola_app` itself or with
`yc managed-postgresql user update versola_app --cluster-name <name> --password …` (the pooler applies
it a few seconds late), then create the schemas. These `PG*` variables are what every `psql`/`pg_dump`
in this document assumes:

```bash
export PGHOST=c-<cluster-id>.rw.mdb.yandexcloud.kz PGPORT=6432 PGDATABASE=auth PGUSER=versola_app \
  PGSSLMODE=verify-full PGSSLROOTCERT=~/yandex-root.crt
export PGPASSWORD=$(grep '^POSTGRES_PASSWORD=' ~/.versola/active/bundle-*/auth.secrets.env | cut -d= -f2-)
for s in auth central edge; do psql -c "CREATE SCHEMA IF NOT EXISTS $s AUTHORIZATION versola_app"; done
```

The password is printed only on that first run; it also stays in the bundle's `auth.secrets.env`.

<a id="postgres-tls"></a>
**Postgres TLS.** Every connection (the pool and the `LISTEN` connection) uses `sslmode=verify-full`
unless the JDBC URL sets its own: the certificate must chain to a trusted CA and match the host. On
vps you do not write this — `--postgres-ca` copies the CA into the bundle, mounts it read-only into
the services and the `migrate` container and sets `postgres.ssl-root-cert`. A URL that does not
verify (`disable|allow|prefer|require`) logs a warning and, when `env = "prod"`, stops the service
from starting; loopback hosts are exempt.

### Migrate, start, verify

```bash
versola migrate    # applies central's, auth's and edge's migrations from one throwaway container
versola up         # central → auth, edge → versola-proxy, then checks a request reaches auth
```

Services never migrate on their own startup ([`RUN_MIGRATIONS`](#run_migrations)). `versola up` ends
by printing where the admin's bootstrap password is ([First admin login](#first-admin-login)).
`versola bootstrap vps <version> --auth-url … --postgres-host …` runs configure, migrate and up in
one go; on a first run it stops before `migrate` so you can run the SQL above, then continue with
`versola migrate` and `versola up`.

```bash
curl -s -o /dev/null -w "%{http_code} %{ssl_verify_result}\n" https://id.versola.kz/.well-known/openid-configuration   # 200 0
curl -s -o /dev/null -w "%{http_code}\n" https://id.versola.kz/central/admin/                                          # 200
curl -s -o /dev/null -w "%{http_code}\n" https://id.versola.kz/central/permissions/me                                  # 401
```

### Behind an existing web server

With `--proxy external` `versola-proxy` listens on `127.0.0.1:2821` and the host's web server
terminates TLS (for the same checks as above, curl `http://127.0.0.1:2821/…` with
`-H Host:id.versola.kz -H X-Forwarded-Proto:https`). For nginx the server block is TLS plus one forward:

```nginx
server {
    server_name id.versola.kz;
    client_max_body_size 8m;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    location / {
        proxy_pass http://127.0.0.1:2821;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";
    }

    listen 443 ssl http2;
    # ssl_certificate / ssl_certificate_key managed by certbot
}
```

- `Host` is passed through but not trusted: issuer, redirect URLs and the DPoP `htu` come from
  `--auth-url`.
- `X-Forwarded-For` is trusted by `versola-proxy` only from `127.0.0.1`; `auth` rate-limits by the
  restored client address.
- HSTS is set here because `versola-proxy` overwrites `X-Forwarded-Proto` with plain http, so the
  services cannot tell a request came in over https. Drop `includeSubDomains` if any host under the
  domain is not https-only.
- `client_max_body_size 8m` must match `versola-proxy` (themes, JWKS and resources are POSTed
  through the console), or the console hits `413`.

---

## 3. Releases and recovery

### Deploying a new version

Images are built and pushed **only** by the `release: published` event; merging to `main` publishes
nothing.

1. **Cut a release.** GitHub → Releases → new tag (no `v`), target `main` → *Generate release notes*
   → *Publish*.
2. **Wait for the images:** `docker-tools`, `docker-auth`, `docker-central`, `docker-edge` green.
3. **Check the release does not *edit* an applied migration** — only adding is safe. On a clone with
   both tags: `git diff --name-status <old>..<new> -- '*/postgres/migrations'` must show only `A`
   lines (or check *Files changed* at `…/versola/compare/<old>...<new>`). An edited migration fails
   Flyway's checksum validation and leaves only [recreating that schema](#recreating-the-database).
4. **On the host**, update the CLI and take a dump right before `migrate` (the cluster has its own
   backups, but this one matches the schema you are about to change; migrations cannot be rolled
   back, the dump is the rollback):
   ```bash
   versola upgrade
   (umask 077; pg_dump > ~/auth-backup-before-<version>-$(date +%Y%m%d-%H%M%S).sql)
   ```
5. **Configure, migrate, start** with the same flags as the first time (`--auth-url`, and
   `--proxy external` if used, are always required; the Postgres flags are remembered):
   ```bash
   versola configure vps <version> --auth-url https://id.versola.kz
   versola migrate
   versola up
   ```
   `configure` and `migrate` do not touch the running services; the outage is only `up` (pulling
   images, recreating containers — a couple of minutes), so migrations must be compatible with the
   previous version, which keeps serving while they run.
6. **Verify** with the checks in [2](#migrate-start-verify) and `versola status`.

#### Tag naming

The image tag is the git tag verbatim, with no `v` prefix (`0.7.1`), and that is the `<version>` `versola configure` takes.

### Rollback

If the release only *added* migrations and the previous version runs on the newer schema, going back
is `versola configure vps <previous> …` + `versola up` (install the matching CLI if needed). If the
migrations must be undone, restore the database **before** starting the previous version — never run
it against a schema only the new release understands:

```bash
versola down                                               # nothing may write while restoring
psql -c "DROP SCHEMA auth CASCADE; DROP SCHEMA central CASCADE; DROP SCHEMA edge CASCADE;"
psql -c "CREATE SCHEMA auth AUTHORIZATION versola_app; CREATE SCHEMA central AUTHORIZATION versola_app; CREATE SCHEMA edge AUTHORIZATION versola_app;"
psql < ~/auth-backup-before-<version>-<timestamp>.sql
versola configure vps <previous> --auth-url https://id.versola.kz
versola migrate                                            # no-op: the dump has the previous schema
versola up
```

(The managed service has no superuser and a database cannot be dropped over its own connection, so
the three schemas are recreated; other applications' schemas stay.) Anything written between the
dump and the rollback is lost.

### Recreating the database

Flyway runs with `cleanDisabled(true)`, so the application cannot drop anything. **Stop the stack
first, then dump, then drop** — a dump taken while services run misses what they write before they
stop.

```bash
versola down                                               # also stops OpenBao; `up` does not restart it, the next `configure` does
(umask 077; pg_dump > ~/auth-backup-$(date +%Y%m%d-%H%M%S).sql)
```

Then drop and recreate the three schemas as in the rollback (or the database itself through the cloud
console), `versola migrate` and `versola up`. `central` re-runs its bootstrap and reseeds clients,
roles, forms and the admin user; anything configured by hand is gone. For a single schema:
`pg_dump -Fc -n <schema>`, `psql -c "DROP SCHEMA <schema> CASCADE; CREATE SCHEMA <schema> AUTHORIZATION versola_app;"`,
`versola migrate`, `versola up`.

### Schema facts worth knowing

- **Login state is `UNLOGGED`** (`auth_conversations`, `authorization_codes`,
  `pushed_authorization_requests`, `dpop_proofs`). A clean Postgres restart keeps the rows; a crash
  empties them, a standby never receives them, a restore brings them back empty. Only in-flight state
  is lost (users mid-login start over, unused codes are rejected and the client retries); sessions,
  tokens, consents and users are unaffected. Flipping a table's logging mode takes an
  `ACCESS EXCLUSIVE` lock and rewrites it: run such a migration at low traffic.
- **Configuration snapshots.** `auth` and `edge` keep the last configuration sync from `central`
  (signed with a key the node already holds) and start from it when `central` is unreachable or
  failing, retrying in the background and reporting `config_snapshot_age_seconds`. The tables fill on
  the first successful sync, and rows stop verifying after `central.secret-key` or edge's
  `private-key` is rotated, until the next sync.

The Helm chart (`k8s/versola`) does the same and migrates with a Job on every install and upgrade
(`migrations.*`); see [`k8s/README.md` §5](k8s/README.md#5-applying-migrations).

`versola migrate` (and that Job) waits at most 5 seconds for any lock (`MIGRATE_LOCK_TIMEOUT_MS`,
`0` waits forever) and retries the service's migration 3 times (`MIGRATE_LOCK_RETRIES`) before it
fails, so a statement that has to wait for a long transaction of the version still serving does not
queue the logins behind it. Postgres not yet accepting connections is retried too
(`MIGRATE_CONNECT_RETRIES`, 3 times, 5 seconds apart). All three are environment variables of the
`migrate` container. Only `postgres.url`, `user`, `password` and `ssl-root-cert` of each config have to
resolve; other unset placeholders no longer stop it.

---

## 4. Operations

```bash
versola status                                   # containers of the live deployment and their health
docker logs -f --tail=100 versola-central        # also versola-auth, versola-edge, versola-proxy
docker restart versola-auth                      # restart in place
docker stats --no-stream                         # memory use
docker logs --tail=200 versola-auth 2>&1 | jq -r '"\(.timestamp) \(.level) \(.message)"'   # logs are JSON lines
```

`versola-proxy`'s access log is plain nginx format; its first field is the real client address.

### Secrets and configuration

Secrets live in OpenBao under `secret/versola/vps/{auth,central,edge}` (KV v2). The generated `.conf`
files hold only `${VAR}` placeholders; `configure` resolves them into `<service>.secrets.env` in the
bundle (mode `600`), which Compose passes to the containers. Services never talk to OpenBao at
runtime; it only has to be up for `configure`. To change a secret, patch it (`kv put` would replace
the whole path), then `versola configure vps <current version> …` with the usual flags and
`versola up`:

```bash
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao-vps \
  bao kv patch -mount=secret versola/vps/auth SOME_KEY='<value>'
```

The Postgres password is shared by all three paths and must be identical in each — `configure`
refuses to continue otherwise. Non-secret configuration is generated by `versola-tools`; there is no
supported way yet to change individual settings (OTP provider, SMTP, …): an edit to a bundle file
survives `docker restart` but not the next `configure`. If you must edit one, keep it UTF-8 — HOCON
cannot read UTF-16 and the service crash-loops with a parse error at its next restart.

### Admin console

`central-ui` is a static SPA shipped in the release's `versola-tools` image; `configure` copies it
into the bundle and `versola-proxy` serves it at `/central/admin/`, so deploying a release deploys
its console. It shares the `id.versola.kz` origin with its edge API (no CORS handling), and the
`EDGE_SESSION` cookie is scoped `Path=/central`, so every URL the console touches lives under that
prefix; the proxy strips it before forwarding:

| Path on `id.versola.kz` | Routed to |
|---|---|
| `/central/admin/` | static `central-ui/` from the bundle (`Cache-Control: no-cache`) |
| `/central/permissions/me` | `edge`, rewritten to `/permissions/me` |
| `/central/…` (else) | `edge`, rewritten to `/resources/central/…` (admin API) |
| `/login/`, `/complete`, `/logout/…`, `/resources/`, `/permissions/` | `edge` |
| `/admin`, `/admin/…`, `/central`, `/central/` | `301` → `/central/admin/…` |
| everything else | `auth` (the OIDC endpoints) |

The login preset `central-admin` is seeded from `central.conf` (`bootstrap.presets`:
`redirect-uri = <auth-url>/complete`, `post-login-redirect-uri = <auth-url>/central/admin/`,
`cookie-path = /central`) once, on `central`'s first bootstrap against an empty database — changing
the seed later has no effect; change the preset in the console or by SQL. The client's
`redirect-uris` must contain only `https://id.versola.kz/complete` in prod. If you change the cookie
path, clear cookies once: an `EDGE_SESSION` at the old path is a different cookie that logout at the
new path does not clear.

#### First admin login

`AuthBootstrapService` creates the `admin` user with the `oauth-admin` role and a **temporary**
password, `ADMIN_BOOTSTRAP_PASSWORD`, generated once and kept in OpenBao. `versola up` prints which
bundle file holds it:

```bash
grep ADMIN_BOOTSTRAP_PASSWORD <bundle printed by versola up>/auth.secrets.env
```

It is valid **24 hours** and forces a set-password step. If it expired, `docker restart
versola-auth` makes bootstrap set a fresh one. More admin users: Console → **Users** → **Create
User**, **Edit Roles**, **Reset Password** — in prod the temporary password can only be delivered by
email or SMS, and neither is configured yet ([5](#5-known-gaps)).

### Mutual TLS certificates

`auth`'s mutual-TLS listener (`MPORT`, RFC 8705) is on by default, with its certificate machinery;
three certificates are involved, each with its own rotation, described in
[`develop.md`](develop.md#certificate-storage-and-rotation-440). The generated compose file runs:

- `step-ca` — the internal "Versola Client CA" (state in `versola-step-ca-*`), with a `central` JWK
  provisioner (14-day maximum lifetime) that `central` uses to issue and renew edge-fronted clients'
  certificates. To get one, register the client with `issueEdgeClientCertificate: true` and any
  `mtlsAuth`. Its first start prints the generated password of an unused `versola` provisioner once
  to `docker logs versola-step-ca`; keep that log private or remove the provisioner.
- `mtls-init` — one-shot: signs `auth`'s listener certificate into `versola-mtls-*`. A restart leaves
  the files alone (re-issuing would change the leaf `edge` pins). `central`, `auth` and `edge` start
  after it; `auth.conf`'s `mutual-tls` and `edge.conf`'s `native` blocks already point at the volume.

All of it stays on loopback (`127.0.0.1:8083`). The listener certificate is valid two years and is
not renewed automatically: rotating it needs the two-pin procedure in `develop.md`. A step-ca volume
from before its client template existed keeps issuing certificates with the subject rewritten to the
bare CN, which `central` refuses when longer; recreate the `versola-step-ca-*` volume to lift it.

### Overload protection

When a service is asked for more than it can do, it should refuse the surplus quickly rather than queue it
until everything is slow. Two guards do that, both in `off` / `shadow` / `enforce` mode and both in
**`shadow` by default**: `shadow` counts what `enforce` would refuse and refuses nothing, so a deployment
behaves as before until someone turns enforcement on.

- **Inbound admission** (edge): a bound on requests in flight, with priorities. A new login is refused
  first, then work already begun (code exchange, logout), then proxied resource calls; health, metrics,
  config sync and back-channel logout are never refused. A refusal is `503` with `Retry-After`. An
  adaptive limit can replace the fixed bound: it shrinks when call times rise or the executor stalls and
  grows back while calls are fast, inside operator-set bounds.
- **Outbound guard** (every service, per peer): a limit on calls in flight, a bounded wait and a timeout
  (`503` / `504`), a circuit breaker and a retry budget. Central is never limited by it, so a service can
  still wait for its configuration at start-up; nothing is enforced until the service has started.

Settings start from the environment (`HTTP_ADMISSION_MODE`, `_MAX_IN_FLIGHT`, `_CONTINUATION_SHARE`,
`_START_SHARE`, `_RETRY_AFTER`; `HTTP_CLIENT_GUARD_MODE`, `_MAX_CONCURRENT`, `_MAX_QUEUE`, `_QUEUE_TIMEOUT`,
`_REQUEST_TIMEOUT`). An **edge** also takes an *overload policy* from central, per edge, which is a patch
over those values: set it with `PUT /configuration/edges/overload?edgeId=…` (the body is described in
`central/open-api/central.yaml`; `{}` removes it). It reaches a running edge within the configuration
refresh interval, and the edge keeps the last policy it received while central is unreachable. The
console has no screen for it yet. Migration `V1025` adds a nullable column and needs no action.

Roll out in `shadow`: watch `http_admission_refused_total{mode="shadow"}` and
`http_client_guard_rejected_total{mode="shadow"}` under real load (they are what `enforce` would have
refused; they should be near zero while the service has capacity), then set `enforce`. Other metrics:
`http_admission_in_flight`, `http_admission_limit`, `http_executor_lag_seconds`,
`http_client_guard_in_flight`, `_waiting`, `_breaker_state`.

### Configuration reference

| Variable | Default | Meaning |
|---|---|---|
| `PORT` / `APORT` / `DPORT` | `8080` / `8082` / `8081` | app / additional internal / diagnostics (`/metrics`, `/liveness`, `/readiness`) port |
| `BIND_HOST` | — | address to bind; the vps compose file sets `127.0.0.1` |
| `CONFIG_PATH` | `/app/config/env.conf` | the mounted HOCON config |
| `RUN_MIGRATIONS` | `false` | see below |
| `VERSOLA_CRYPTO_PROVIDER` | `accp-required` in the auth and edge images | `accp` (native if it works, else JDK), `accp-required` (refuse to start without it), `jdk`; jar in `VERSOLA_ACCP_JAR` |
| `JAVA_OPTS` | fixed budget in the images | overriding replaces the whole string: the images set `-Xms512m -Xmx512m`, `MaxDirectMemorySize` 64m (96m for edge), `MaxMetaspaceSize` 128m, `ReservedCodeCacheSize` 64m, `-Xss512k`, a heap dump and rotating GC log in `/diagnostics` |

Everything else — secrets, keys, database credentials, bootstrap data — is in the HOCON file and
OpenBao, not in environment variables.

<a id="run_migrations"></a>
**`RUN_MIGRATIONS`**: unset → migrations do not run; the service *validates* the schema and fails to
start if migrations are missing (applying them is the separate `versola migrate` step). `true` /
`false` (lowercase) → as specified. Anything else → the service fails to start, deliberately. The
generated `compose.yml` sets `"false"` on all three services.

**Behind a connection pooler.** `central` and `edge` keep a connection parked on `LISTEN` (that is
how config changes and token revocations reach every replica). A pooler in transaction mode cannot
carry it, so when `postgres.url` points at one, set `postgres.notifications-url` to a **direct**
Postgres URL in the same block. Each service checks at startup that it receives its own
notification and refuses to start, naming this setting, if it cannot.

---

## 5. Known gaps

- **No `mem_limit`** in the generated compose file (versolauth/versola#613: until a replica test
  shows how limits interact with the heap settings). With `network_mode: host`, each of three JVMs
  sizes its heap from the *host's* memory (`MaxRAMPercentage=75`); `doctor`/`configure` check
  available memory first, which lowers the risk but doesn't remove it.
- **No supported way to change configuration on `vps`** (OTP provider, SMTP, …), so **SMTP and SMS are
  not configured in prod**: password resets and additional admin accounts can't deliver a temporary
  password.
- **Single instance per service**: fixed `container_name`s and ports, so every `versola up` of a new
  version is a short outage. Replicas and rolling upgrades are planned in `versola-cli`.
- **The VM's security group is the network's default** (all inbound allowed); narrow it to the rules
  in [1](#1-overview).
- **Single zone, and the VM is a single point of failure.** `kz1-a` is the region's only zone; the
  VM's Docker volumes (OpenBao's data, the CA's keys) are backed up by nothing but your own copies of
  `vps-admin.json` and the dumps above.
- `configure` prints the OpenBao AppRole `role-id` and `secret-id` on every run, so they reach
  terminal scrollback; OpenBao listens on `127.0.0.1` only.

---

## 6. Troubleshooting

### 6.1 `FlywayValidateException`



```
Validate failed: Migrations have failed validation
Detected applied migration not resolved locally: ...
```

Two services share one schema and therefore one `flyway_schema_history`. Check the JDBC URL's
`?currentSchema=` in the service's log. A **checksum mismatch** (`Migration checksum mismatch for
migration version …`) means a release edited a migration production had already applied; there is no
clean fix except [recreating that schema](#recreating-the-database) — check for it before migrating.

`versola migrate --dry-run` currently reports *pending* migrations as a validation failure too
(`Detected resolved migration not applied to database`); that is a known bug in the dry run, not a
problem with the database, and plain `versola migrate` applies them fine.

### 6.2 `central` crashes with `FileNotFoundException: forms/common.css`

The login forms under `central/src/main/resources/forms/` are *generated* by `central-ui`
(`npm run build:forms`) and gitignored; an image built without that step fails the moment bootstrap
seeds them, only on an empty database. CI's `docker-central` job builds them first — if you see this
again, that step has been lost. The same step writes `util/src/main/resources/versola/forms/common.css`;
without it `auth` and `central` fail with `Classpath resource versola/forms/common.css is missing`.

### 6.3 Container dies with no error in its own log

`sudo dmesg | tail -20 | grep -i 'killed process'`. If the kernel OOM-killed it, the JVM never got to
log anything (a JVM `OutOfMemoryError`, by contrast, is logged). See the `mem_limit` gap in
[5](#5-known-gaps).

### 6.4 502 or 504 from `id.versola.kz`

Work inwards:

```bash
docker ps --format '{{.Names}}  {{.Status}}' | grep versola
curl -s -o /dev/null -w "%{http_code}\n" https://id.versola.kz/.well-known/openid-configuration
curl -sf http://127.0.0.1:8091/readiness && echo central ok
curl -sf http://127.0.0.1:8081/readiness && echo auth ok
curl -sf http://127.0.0.1:8096/readiness && echo edge ok
```

Connection refused on 443 (`--proxy external`: nothing on `2821`) → `versola-proxy` is down
(`docker logs versola-proxy`; `versola up` starts it). 502 → the service behind it is down; check
`central` first. A bare `curl -sI https://id.versola.kz/` returning **404** is expected.

### 6.5 `auth`/`edge` never become ready

They wait up to two minutes for `central` (`/liveness` 200, `/readiness` 503 meanwhile); a failed
startup exits so `restart: unless-stopped` retries it. If one stays unready, confirm `central` is
ready, then `docker restart versola-auth` (or `-edge`). Don't restart all three by hand at once —
`versola up` orders them.

### 6.6 Tag not found on `docker pull` / `configure`

Image tags have no leading `v`. `docker buildx imagetools inspect ghcr.io/versolauth/versola-tools:<tag>`
shows what exists.

### 6.7 A user event is stuck in the outbox dead letter table

`central` propagates user changes to `auth` through `central.user_outbox`; after
`user-outbox.max-attempts` (5 in prod) failures — e.g. `auth` was down — the event moves to
`central.user_outbox_dead` and stops retrying. Once `auth` is ready, requeue it:

```sql
SELECT id, user_id, event_type, attempts, failed_at, error FROM central.user_outbox_dead;

INSERT INTO central.user_outbox (id, user_id, event_type, payload, attempts, next_attempt_at)
SELECT id, user_id, event_type, payload, 0, NOW() FROM central.user_outbox_dead WHERE id = '<event-id>';
DELETE FROM central.user_outbox_dead WHERE id = '<event-id>';
```

### 6.8 OpenBao is sealed or won't start

OpenBao comes back **sealed** after every restart and mounts `openbao.hcl` from a bundle directory;
if that bundle was removed it can't start. Neither affects running services (their secrets are in
`*.secrets.env`); both only block the next `configure`, which recreates the container and unseals it
from `vps-admin.json`. Without that file (or with `--setup-openbao`):
`docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 versola-openbao-vps bao operator unseal`.

### 6.9 "container name already in use"

A container with a fixed name exists under another Compose project — `configure` hits it for
`versola-openbao-vps`, `up` for `versola-auth`, `-central` or `-edge` — typically left by a
deployment not made by `versola-cli`. Check `docker compose ls` and `docker ps -a`, remove those
containers by name (nothing stateful lives in them) and run `configure` again.

### 6.10 The console shows 404 right after a deploy

The first open of `/login/central-admin` right after `versola up` has sometimes returned 404 while a
reload works. Under investigation (readiness gating); reload once.

### 6.11 `PKIX path building failed`

```
Failed to initialize pool: SSL error: (certificate_unknown) PKIX path building failed: …
```

The database's certificate is signed by a CA the JVM does not trust and the services verify it. Give
`configure` the CA with `--postgres-ca <file>`, then `versola up`; do not edit the bundle. Check with
`ls ~/.versola/active/bundle-*/postgres-ca/` (`ca.crt`) and
`grep ssl-root-cert ~/.versola/active/bundle-*/*.conf` (three hits).

### 6.12 `versola up` fails getting the certificate

Let's Encrypt must reach the host on port 80 through the `--auth-url` name. Check in order: DNS points
at this host (`dig +short id.versola.kz`); behind Cloudflare the record is *DNS only* (a proxied one
answers with Cloudflare's addresses); the security group lets `80` and `443` in; nothing else listens
on them. Repeated failures hit Let's Encrypt's rate limits: rehearse with `--acme-staging`.

### 6.13 `password authentication failed for user "versola_app"`

Right after a password change, the managed pooler applies it a few seconds late: retry once. If it
persists, the value in OpenBao and the database differ — set the database's from `POSTGRES_PASSWORD`
in the bundle's `auth.secrets.env`.
