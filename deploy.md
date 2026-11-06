# Deployment

How Versola is deployed to production (`id.versola.io`). For local development see
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
| `step-ca`, `mtls-init` | Docker, same project: internal CA (one root, three intermediates) for client, edge and listener certificates ([mutual TLS](#mutual-tls-certificates)); `mtls-init` is a one-shot | `versola-cli` |
| `versola-proxy` | Docker, `nginx:1.30-alpine`, same project | `versola-cli` (config generated) |
| OpenBao (`versola-openbao-vps`) | Docker, same project, `127.0.0.1:8200` | `versola-cli` |

```
browser ──443──▶ versola-proxy (TLS, Let's Encrypt) ──▶ auth  127.0.0.1:8080
                                                    └──▶ edge  127.0.0.1:8095 ──▶ versola-proxy 127.0.0.1:8090 ──▶ central 127.0.0.1:28090
```

All routing — which path goes to `auth` and which to `edge`, the admin console, legacy redirects —
lives in `versola-proxy`, whose configuration `versola-cli` regenerates on every `configure`.
Nothing binds to `0.0.0.0` except `versola-proxy` (or the host's web server with `--proxy external`).

The cluster has no public address; the VM is the only way in. The VM's security group should allow
`80` and `443` from anywhere (`80` is needed for certificate issuance and renewal), `22` as you
decide, and nothing else inbound.

| Service | App (`PORT`) | Additional (`APORT`) | Diagnostics (`DPORT`) |
|---|---|---|---|
| `auth` | 8080 | 9082 (Account Settings, internal) | 8081 |
| `central` | 28090 (8090 is `versola-proxy`'s front) | — | 28091 |
| `edge` | 8095 | — | 8096 |
| `versola-proxy` | 80/443 (`2821` with `--proxy external`) | — | — |
| `step-ca` / OpenBao | 9000 / 8200 | — | — |

`auth` also has a mutual-TLS listener (`MPORT`, 9083, loopback; `versola-proxy` fronts it on 8083, and fronts `APORT` on 8082). `central` has one too (`MPORT`, 8093, loopback), the only place edges reach it, and an enrollment listener (central's `APORT`, 8094 -- not auth's `APORT`, 9082 -- loopback, `POST /edge/enroll` only). Nothing of this is public unless you ask for it with `versola configure vps --mtls-domain`, `--central-mtls-domain` or `--central-domain` -- see [Mutual TLS certificates](#mutual-tls-certificates). The generated `compose.yml` is the
source of truth for ports; `EXPOSE` in the Dockerfiles is inert under `network_mode: host`.

On `vps` the reverse proxy also fronts `central`: it listens on `127.0.0.1:8090`, the address `auth`,
`edge` and the console call, and `central` itself listens 20000 higher, on `28090` (`PORT`) and
`28091` (`DPORT`), so that `versola rollout` can move it through a temporary second replica
(`28190`/`28191`) without an outage. Probe `central`'s `/readiness` on `28091`, not `8091`.

**Startup order matters.** `auth` and `edge` block on their initial configuration sync from `central`
(up to two minutes) and are not serving meanwhile. `versola up` starts `central`, waits for its
`/readiness`, then the `versola-proxy` front of `central` (`auth` and `edge` call `central` through it),
then `auth` and `edge`, and waits for the whole proxy last.

### Where things live on the host

| Path / object | What it is |
|---|---|
| `~/.versola/active/state.json` | the live deployment: version, bundle, `--auth-url`, proxy mode, `--postgres-host`, `--postgres-db` |
| `~/.versola/active/proxy-vps/` | the proxy's generated configuration, one `gen-NNNNNN` directory per change plus `current`; each is checked with `nginx -t` before the reload |
| `~/.versola/active/bundle-*/` | one directory per `configure`: `compose.yml`, `proxy.yml`, `*.conf`, `*.secrets.env` (mode `600`), `overrides.yml` (the ports moved behind the proxy; written by `up`), `replicas.yml` (while a service has more than one replica), `openbao.hcl`, `central-ui/` (the admin console), `proxy/`, `postgres-ca/ca.crt`, `secrets.schema.json` |
| `~/.versola/active/postgres-ca.crt` | the copy of `--postgres-ca` that later runs reuse |
| `~/.versola/openbao/vps-postgres-role.sql` | the SQL that creates or updates the database role (mode `600`); you run it with `sudo -u postgres psql` |
| `~/.versola/openbao/vps.json` | AppRole credentials `versola-cli` uses to read/write secrets |
| `~/.versola/openbao/vps-admin.json` | OpenBao root token + unseal key. **Keep a copy off the host** — neither is recoverable if lost |
| Volume `versola-openbao-file-vps` | OpenBao's data (all secrets). Survives `down` |
| Volume `versola-postgres-data-vps` | the bundled Postgres' data ([bundled mode](#bundled-postgres) only). Declared `external`, so `compose down --volumes` and uninstall leave it |
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
- PostgreSQL 18: either the managed cluster and the CA file that signed its certificate, or, in
  [bundled mode](#bundled-postgres), nothing: the bundle runs the database in a container.
- A DNS record for the `--auth-url` host pointing at the host's public IP **before `versola up`**
  (Let's Encrypt validates over port 80). Behind Cloudflare it must be *DNS only* while the
  certificate is issued.
- **Optional DNS names for remote edges and external mutual-TLS clients**, all pointing at the same
  host and all off by default. Without them every mutual-TLS port stays on loopback and an edge
  runs on the same host (`https://127.0.0.1:8083` and `:8093`). Each one is a flag of
  `versola configure vps` and is routed by the proxy `versola-cli` writes (its own repository):

  | Name (example) | Flag | What it reaches | TLS |
  |---|---|---|---|
  | `id-mtls.<domain>` | `--mtls-domain` | `auth`'s mutual-TLS listener, `127.0.0.1:8083` (remote edges, external RFC 8705 clients) | forwarded by SNI **without terminating**; the handshake and the client certificate reach `auth` |
  | `central-mtls.<domain>` | `--central-mtls-domain` | `central`'s mutual-TLS listener, `127.0.0.1:8093` (the edge surface, #554, for edges on another host) | forwarded by SNI without terminating |
  | `central.<domain>` | `--central-domain` | `central`'s enrollment listener, `127.0.0.1:8094`, `POST /edge/enroll` only | terminated by the proxy with a public certificate |

  The two mutual-TLS names are added to the SAN of the listener's certificate (`mtls-init`), and
  `id-mtls.<domain>` becomes `mutual-tls.external-url` in `auth.conf`; an edge **on the same host
  keeps the loopback addresses** whatever is configured. A remote edge authenticates the listener
  by pinning its leaf certificate, so no public certificate is needed for those two names. Set a
  name on a deployment that exists already and the next `mtls-init` run re-issues the leaf that
  lacks it (see [Mutual TLS certificates](#mutual-tls-certificates)).
- `versola-cli`: `curl -fsSL https://raw.githubusercontent.com/versolauth/versola-cli/main/install.sh | sh`,
  then `export PATH="$HOME/.local/bin:$PATH"`. `versola upgrade` updates it later.
- Published images in `ghcr.io/versolauth/` (`versola-tools`, `versola-auth`, `versola-central`,
  `versola-edge`); they are public, no `docker login` needed. Test builds pushed to another registry
  are deployed with `versola-cli`'s `--registry <prefix>` (`configure`, `bootstrap`, `secrets plan`): it hands the
  prefix to `versola-tools` as `IMAGE_REGISTRY`, which writes it in front of `versola-central`,
  `-auth`, `-edge` and `-tools` in the compose file (default `ghcr.io/versolauth`). The tag stays the
  tools image's own `VERSION`, so build it with `--build-arg VERSION=<the tag you push it under>`; the
  CLI refuses a compose file whose images are not under the prefix at that tag. `docker login` for a
  private registry is the operator's.
- A client of the server's major version for `psql`/`pg_dump`: Debian 12's is 15 and `pg_dump`
  refuses a version 18 server, so install `postgresql-client-18` from `apt.postgresql.org`.

### Configure

```bash
versola doctor --target vps
versola configure vps <version> \
  --auth-url https://id.versola.io \
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
- Shared-host variant: `--postgres-host 127.0.0.1:5432 --proxy external`, no `--postgres-ca`. Only
  exactly `127.0.0.1`, `localhost` or `[::1]` skips TLS verification; any other name is verified even
  if it resolves to this machine (the host's own name usually maps to `127.0.1.1`), so it must be in
  the certificate's subject alternative names, and a private CA needs `--postgres-ca`.
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
role gets it only afterwards. `configure` never prints the password: it writes the SQL that gives the
role that password to `~/.versola/openbao/vps-postgres-role.sql` (readable only by you; it creates the
role if it doesn't exist, and sets the password and allows login if it does) and prints the command
to run it:

```bash
sudo -u postgres psql < ~/.versola/openbao/vps-postgres-role.sql
```

On the managed cluster the role and database come from the cloud (user `versola_app` owning `auth`;
there is no superuser). Set the password as `versola_app` itself or with
`yc managed-postgresql user update versola_app --cluster-name <name> --password …` (the pooler applies
it a few seconds late); the password is `POSTGRES_PASSWORD` in the bundle's `auth.secrets.env`, the CLI
does not print it. Then create the schemas. These `PG*` variables are what every `psql`/`pg_dump`
in this document assumes:

```bash
export PGHOST=c-<cluster-id>.rw.mdb.yandexcloud.kz PGPORT=6432 PGDATABASE=auth PGUSER=versola_app \
  PGSSLMODE=verify-full PGSSLROOTCERT=~/yandex-root.crt
export PGPASSWORD=$(grep '^POSTGRES_PASSWORD=' ~/.versola/active/bundle-*/auth.secrets.env | cut -d= -f2-)
for s in auth central edge; do psql -c "CREATE SCHEMA IF NOT EXISTS $s AUTHORIZATION versola_app"; done
```

The file is written only on that first run; the password itself is kept in OpenBao and in the bundle's
`auth.secrets.env`.

<a id="bundled-postgres"></a>
**Bundled Postgres.** With `POSTGRES_MODE=bundled` (a `versola-tools` input; the default `external` is
everything above, unchanged) the compose file also runs the database, so none of the SQL above is run by
hand. The `postgres` service is the pinned `postgres:<major>.<minor>` image (the PostgreSQL release the
project is verified against; a release can move the tag to a newer minor, never to a newer major: the data
directory is `/var/lib/postgresql/data`, with no major in its path, so a newer major finds the old files and
refuses to start, "database files are incompatible with server", until they are dumped and restored), on the host network and
listening on `127.0.0.1:5432` only, never on a public interface. Its healthcheck gates `migrate` and the
three services. The first start with an empty data directory creates the role (the user in the configs),
a plain role and not a superuser, the database (`--postgres-db`, `auth` by default) owned by it and the
three schemas owned by it; a later start finds the data directory and initialises nothing. The cluster's
superuser is `postgres`, with no password: it logs in over the container's socket only (`docker exec`),
never over TCP, and connections to `127.0.0.1:5432` need the role's password (`scram-sha-256`), so no other
process on the VM gets in without it. The password reaches the container through
`postgres.secrets.env` (`POSTGRES_PASSWORD` only), written by `versola-cli` from the same value it stores
in OpenBao. `env_file` makes it part of the container's configuration, so `docker inspect` shows it to
anyone who can use Docker on the VM; it is in no image layer, log line or generator output. The services
connect with `sslmode=disable`, which is what the loopback rule above writes.

The data lives in the external Docker volume `versola-postgres-data-vps` on the VM, created by
`versola-cli` before the first start, so it survives container recreation, `compose down --volumes`,
uninstall and a redeploy. Nothing but a backup protects it: the volume is not in OpenBao and not in a
managed cluster's snapshots, so take `pg_dump` backups ([3](#3-releases-and-recovery)) and keep a copy off
the host. A dump goes through the container, since there is no native server to `sudo -u postgres`
into: `(umask 077; docker exec versola-postgres-vps pg_dump -U versola_app -Fc auth > ~/versola-backup-$(date +%Y%m%d-%H%M%S).dump)`
(`auth` is `--postgres-db`; `-U` is the role in the configs). Like the external role, the password is
read once, at initialisation: changing it later in OpenBao does not change the role's.

One thing to know: the container uses the host network, so port 5432 on the VM must be free: a native
Postgres already listening there stops the container from starting.

Sizing: `max_connections` is 150 and `shared_buffers` 256 MB, for a server with 4 GB of RAM; the services'
pools (auth 10, central 15, edge 10 per replica; central and edge add one `LISTEN` connection each) and a
rollout's stand-ins fit in it.

<a id="postgres-tls"></a>
**Postgres TLS.** Every connection (the pool and the `LISTEN` connection) uses `sslmode=verify-full`
unless the JDBC URL sets its own: the certificate must chain to a trusted CA and match the host. On
vps you do not write this — `--postgres-ca` copies the CA into the bundle, mounts it read-only into
the services and the `migrate` container and sets `postgres.ssl-root-cert`. A URL that does not
verify (`disable|allow|prefer|require`) logs a warning and, when `env = "prod"`, stops the service
from starting; loopback hosts are exempt.

<a id="postgres-own-ca"></a>
**Postgres with your own CA.** If your Postgres is self-hosted and no public CA signed its
certificate, make a CA and a server certificate yourself. Keep `ca.key` off the database host (it can
sign a certificate for any name); only `ca.crt` goes to Versola.

```bash
# 1. The CA, valid 10 years
openssl genrsa -out ca.key 4096
openssl req -x509 -new -key ca.key -sha256 -days 3650 -subj "/CN=Postgres CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" -out ca.crt

# 2. The server's key and a request. The SAN is what is checked: it must list every name and IP
#    address that the CLI and the services connect to (the host in --postgres-host).
openssl genrsa -out server.key 2048
openssl req -new -key server.key -subj "/CN=db.example.com" \
  -addext "subjectAltName=DNS:db.example.com,IP:10.0.0.5" -out server.csr

# 3. Sign it with the CA
printf 'subjectAltName=DNS:db.example.com,IP:10.0.0.5\nbasicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' > server.ext
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 -sha256 \
  -extfile server.ext -out server.crt
openssl verify -CAfile ca.crt server.crt     # server.crt: OK
```

On the database host, put `server.crt` and `server.key` in a directory readable by `postgres` (the key
must be owned by `postgres` with mode `0600`, or Postgres refuses to start), and set in
`postgresql.conf`:

```
ssl = on
ssl_cert_file = '<dir>/server.crt'
ssl_key_file = '<dir>/server.key'
listen_addresses = 'localhost,10.0.0.5'
```

In `pg_hba.conf`, a `hostssl` line for the role and no plain `host` line, so a connection without TLS
is refused (the address is the client's, as Postgres sees it):

```
hostssl  all  versola_app  <address of the Versola host>/32  scram-sha-256
```

Restart Postgres, copy `ca.crt` to the machine that runs Versola, and check from there:

```bash
openssl s_client -starttls postgres -connect db.example.com:5432 -CAfile ca.crt \
  -verify_hostname db.example.com -verify_return_error </dev/null 2>&1 | grep Verification   # Verification: OK
psql "host=db.example.com user=versola_app dbname=auth sslmode=verify-full sslrootcert=ca.crt" \
  -c "select ssl from pg_stat_ssl where pid = pg_backend_pid()"                               # t
```

Then `versola configure vps <version> … --postgres-host db.example.com:5432 --postgres-ca ~/ca.crt`.
Use the name that is in the SAN: another name fails verification even when it resolves to the same
machine, and only the literal `127.0.0.1`, `localhost` and `[::1]` are exempt. `ca.crt` must be the root
certificate (a file with only an intermediate CA does not verify). If you use an intermediate CA, append
it to `server.crt` after the server's certificate, so that the server sends the whole chain. Renew `server.crt` with the same CA
before it expires; `--postgres-ca` does not change.

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
curl -s -o /dev/null -w "%{http_code} %{ssl_verify_result}\n" https://id.versola.io/.well-known/openid-configuration   # 200 0
curl -s -o /dev/null -w "%{http_code}\n" https://id.versola.io/central/admin/                                          # 200
curl -s -o /dev/null -w "%{http_code}\n" https://id.versola.io/central/permissions/me                                  # 401
```

### Behind an existing web server

With `--proxy external` `versola-proxy` listens on `127.0.0.1:2821` and the host's web server
terminates TLS (for the same checks as above, curl `http://127.0.0.1:2821/…` with
`-H Host:id.versola.io -H X-Forwarded-Proto:https`). For nginx the server block is TLS plus one forward:

```nginx
server {
    server_name id.versola.io;
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
   both tags: `git diff --name-status <old>..<new> -- '*/postgres/migrations/*'` must show only `A`
   lines (or check *Files changed* at `…/versola/compare/<old>...<new>`). Keep the trailing `/*`:
   migrations live deeper (for example `auth/implementations/postgres/migrations/V0015__….sql`), and
   without it the pathspec matches nothing, so the command prints nothing, which looks like "all
   safe". An edited migration fails Flyway's checksum validation and leaves only
   [recreating that schema](#recreating-the-database).
   `A` only means nothing was edited, not that the new migrations are compatible with the previous
   version, which keeps serving while they run (a dropped or renamed column breaks it). Read what was
   added: `git diff <old>..<new> -- '*/postgres/migrations/*'`.
4. **On the host**, update the CLI and take a dump right before `migrate` (the cluster has its own
   backups, but this one matches the schema you are about to change; migrations cannot be rolled
   back, the dump is the rollback):
   ```bash
   versola upgrade
   (umask 077; pg_dump > ~/auth-backup-before-<version>-$(date +%Y%m%d-%H%M%S).sql)
   ```
   Shared-host variant: `sudo -u postgres pg_dump auth > …`.
5. **Check the secrets** the new version needs against OpenBao (read-only; names and decisions,
   never values):
   ```bash
   versola secrets plan vps <version>
   ```
   Secrets new in the release are generated by `configure`; keys stored data depends on (JWT and
   edge key pairs, `PASSWORDS_SECRET`, `REFRESH_TOKENS_SECRET`, `CLIENT_SECRETS_SECRET`, …) are
   never generated again — if one is missing, `configure` stops with nothing written and the plan
   names it (`lost`). Restore it from your OpenBao backup rather than overriding with
   `--generate-missing`. Details: [`develop.md`](develop.md), "How the CLI applies the schema".
6. **Configure, migrate, start** with the same flags as the first time (`--auth-url`, and
   `--proxy external` if used, are always required; the Postgres flags are remembered):
   ```bash
   versola configure vps <version> --auth-url https://id.versola.io
   versola migrate
   versola up
   ```
   `configure` and `migrate` do not touch the running services; the outage is only `up` (pulling
   images, recreating containers — a couple of minutes), so migrations must be compatible with the
   previous version, which keeps serving while they run. If `auth` or `edge` runs more than one
   replica, `configure` refuses: use `versola rollout` (below) or remove the extra replicas first.
7. **Verify** with the checks in [2](#migrate-start-verify) and `versola status`.

#### Tag naming

The image tag is the git tag verbatim, with no `v` prefix (`0.7.1`), and that is the `<version>` `versola configure` takes.

### Without stopping: `versola rollout`

On `vps`, when the release only *adds* migrations and the running version keeps working on the new
schema, `versola rollout` replaces the running version while it serves. Releases before 1.0 do not
promise that compatibility, so read the release notes and say you did with `--assume-compatible`:

```bash
versola rollout <version> --dry-run          # the plan, the secrets plan, the pending migrations; changes nothing
versola rollout <version> --assume-compatible
```

Take the dump of step 4 first: the migrations are applied before anything is replaced and cannot be
rolled back. Then `central` moves through a temporary second replica, and `auth` and `edge` are
replaced one replica at a time behind a temporary extra one, so no service has fewer replicas serving
than before. If the run is cut off (an error, Ctrl-C, a dropped SSH session), the same command
continues where it stopped.

Before a temporary replica starts, `rollout` checks that the server has the memory for one more JVM
(what a running replica uses now, never less than 900 MiB, plus 256 MiB: at least about 1.13 GiB free)
and that Postgres has the connections for its pool plus 3 spare; `versola replica add` makes the same
Postgres check. If either is short it refuses with the numbers before anything changes (the checks run
again before each temporary replica): free the memory or raise `max_connections`, or use `--no-surge`,
which replaces replicas with no extra one, so one fewer serves meanwhile (an `auth` or `edge` with a
single replica then also needs `--allow-downtime`).

`central` is replaced in place, and the admin console and self-registration are unavailable until it is
ready, only with `--no-surge`, or on the first rollout of a deployment made before the proxy fronted
`central`; `auth` and `edge` keep serving meanwhile. Connections to `auth`'s mutual-TLS port (8083) that
the old proxy workers hold are closed 30 seconds after each proxy reload: idle ones are reopened by
`edge`, a request in flight at that moment fails. The
[`versola-cli` README](https://github.com/versolauth/versola-cli#rollout-version--rollout---abort-vps)
has the details.

### Rollback

While a `versola rollout` is unfinished, `versola rollout --abort` goes back to the version it came
from (add `--no-surge` if the rollout needed it); the migrations and secrets stay. Replicas whose
version did not change are not recreated, unless the previous bundle is gone: then the previous
version is configured anew and every replica is recreated. For a release that predates the secrets
schema, that path needs `--allow-legacy-secrets`. After a rollout has finished there is nothing to abort. Otherwise,
if the release only *added* migrations and the previous version runs on the newer schema, going back
is `versola configure vps <previous> …` + `versola up` (install the matching CLI if needed). If the
migrations must be undone, restore the database **before** starting the previous version — never run
it against a schema only the new release understands:

```bash
versola down                                               # nothing may write while restoring
psql -c "DROP SCHEMA auth CASCADE; DROP SCHEMA central CASCADE; DROP SCHEMA edge CASCADE;"
psql -c "CREATE SCHEMA auth AUTHORIZATION versola_app; CREATE SCHEMA central AUTHORIZATION versola_app; CREATE SCHEMA edge AUTHORIZATION versola_app;"
psql < ~/auth-backup-before-<version>-<timestamp>.sql
versola configure vps <previous> --auth-url https://id.versola.io
versola migrate                                            # no-op: the dump has the previous schema
versola up
````

(The managed service has no superuser and a database cannot be dropped over its own connection, so
the three schemas are recreated; other applications' schemas stay.) Anything written between the
dump and the rollback is lost. With extra replicas of `auth` or `edge`, remove them first
(`versola replica remove auth|edge <n>`): `configure` refuses while a service has more than one. An
unfinished `versola rollout` is undone with `versola rollout --abort` instead; secrets the new
version added stay in OpenBao either way.

#### Behaviour change: `/native/start` has a default limit per address when `client-ip = "forwarded"`

`/native/start/{clientId}` takes no client credential, and every call that gets past the proof pushes a request to `auth` and records the
proof. Until now nothing limited it unless an operator had installed an address policy. Now, on an edge with `client-ip = "forwarded"`
(see [Rate limits](#rate-limits)), each address gets **60 calls a minute with bursts of 20**, per pod (IPv4 per address, IPv6 per `/64`),
and a call above that is answered `429` with `Retry-After` in whole seconds and no body, before the proof is read and before `auth` is called.

- **It stays out of the way** when `client-ip` is `socket` (behind a proxy every caller would have the proxy's address and the limit would
  count the world as one caller, so there it does nothing, as before), when the header is missing or not an address, and as soon as the edge
  has any policy at the address checkpoint, in any mode: such a policy already counts `/native/start` with the other entry points (R1), and
  that is where to set a different number or to look at it in `shadow` first.
- **Metrics.** `edge_native_start_rate_limit_decisions_total{policy="native-start-default",result}` and
  `edge_native_start_rate_limit_tracked_keys`.
- **Who can notice.** An app that starts more than 20 sign-ins at once from one address (a load test, a shared carrier address during a
  campaign) and gets `429`. A real sign-in is one call.
- **No setting, no migration.** The numbers are not configurable on their own: an address policy replaces them.

#### Behaviour change: edge binds a login to the browser that began it (login CSRF on `/complete`)

`/login/{presetId}` now sets an `EDGE_LOGIN_<state>` cookie (`Secure`, `HttpOnly`, `SameSite=Lax`, `Path` =
the path of the preset's `redirect-uri`, `Max-Age` 10 minutes) and `/complete` finishes a login only for a
request that brings it back; any other gets the `400` an unknown `state` gets. Before, whoever held a valid
`/complete?code&state` link was signed in as the account that produced it. Edge stores only a SHA-256 hash of
the secret, in `pending_logins.browser_binding_hash` (migration `V2008`, nullable).

- **Migration.** Additive, so the previous release keeps serving on the new schema and a rollback works.
- **Rolling out.** A login begun before the upgrade has no hash and no cookie; it is accepted without the
  cookie until it expires (10 minutes). There is no setting for this. Edges of both releases behind one
  balancer are safe: rows written by the old one have no hash.
- **What to check.** The cookie is host-only and is sent to the path of `redirect-uri`, so `/login` and the
  redirect URI must be on the same host, as in the table above. A proxy or CDN that strips `Set-Cookie`
  from the `/login` redirect, or a client that does not keep cookies between `/login` and `/complete`
  (a script, a load generator without a cookie jar), now gets `400` on `/complete`.
- The native flow (`/native/*`) never used the pending login and is unaffected.

#### Breaking change (2026-10-25): the access log is set per edge in central, not by environment

`HTTP_ACCESS_LOG`, `HTTP_ACCESS_LOG_SLOW_MS` and `HTTP_ACCESS_LOG_SAMPLE_RATE` are no longer read by any service; a value left
in a compose file or a Helm `env` block is ignored. See [Access log](#access-log).

- **auth and central** always log every request, and have no setting for it.
- **The edge** starts at `off` (failures and slow requests only; 500 ms, 0.05 when sampled) and is configured per edge in
  central: the admin console (an edge's page, "Access log") or `PATCH /configuration/edges/policies?edgeId=...` with an `accessLog` section. It reaches
  the edge on its next configuration sync.
- An edge that had `HTTP_ACCESS_LOG=full` or `sampled` returns to `off` until a policy is set for it. Set one after
  upgrading if you want the lines back.
- Schema: migration `V1028` adds the nullable `edges.access_log_policy` column; additive, the previous release runs on it.
  `LOG_LEVEL` is unchanged.

#### Behaviour change: `auth` keeps its own list of revoked access tokens (migration `V0032`)

`auth` stores no access tokens, but it now stores what was revoked, in the new `auth_revocations` table, and refuses
a revoked access token at `/userinfo` and `/introspect` (#729). Before, a revoked token was reported to the client's
`back_channel_logout_uri` and nowhere else: `/introspect` answered `active: true` until `exp` and `/userinfo` kept
serving it, and a client without that URI heard nothing at all. A row is written in the same transaction as what
causes it: a client's `/revoke` (of an access token: `jti`; of a refresh token: the whole refresh-token family), an
authorization code or refresh token replayed (the family), a logout, an administrator ending a session or forgetting
a device, the user ending one of their sessions (the session), a user deleted or signed out everywhere (the user,
bounded at that instant, so a login afterwards works). A row lives for one access token lifetime of the longest-lived
client, then the cleanup manager removes it (`auth_revocations`, hourly, in the generated `cleanup` block). Password change,
role change and `DELETE /users/refresh-tokens` do not write one yet.

- **Migration.** Additive (one new table, an index pair, a trigger), so the previous release keeps serving on the new
  schema and a rollback works; the old release ignores the table. Everything it creates at database level is
  prefixed `auth_` (table, indexes, trigger, function and the `auth_revocation` notification channel), because `edge` has
  a `revocations` table and a `revocation` channel of its own and a setup that runs the services in one schema (the
  generated dev configs, the e2e stack) would otherwise collide. Nothing to backfill: tokens revoked before the upgrade
  are not in it and stay valid until `exp`.
- **Behaviour you will see.** `/introspect` returns `{"active": false}` and `/userinfo` returns `401 invalid_token` for
  a revoked token. A revocation reaches each replica by `LISTEN`/`NOTIFY`, normally within a second; the replica
  reconciles with the table every `revocation.reload-interval` as a backstop. The `revocation` block of the config is
  optional: `reload-interval` (600 seconds), `purge-interval` (60 seconds), `overlap` (30 seconds), `batch-size` (50000).
  Metrics: `revocation_cache_entries`, `revocation_cache_staleness_seconds`, `revocation_cache_reload_failures_total`.
- **Startup.** A replica loads the whole list before it serves and does not start if it cannot, and it now holds a
  connection parked on `LISTEN` like `central` and `edge` do: behind a pooler in transaction mode `auth` needs
  `postgres.notifications-url` (see [Behind a connection pooler](#run_migrations)). `auth` refuses to start, naming that
  setting, when it cannot receive its own notification.
- **Back channel.** The signed `versola:event:access-token-revocation` event still goes to `back_channel_logout_uri`,
  now through the retrying outbox instead of a single attempt. Delivery stays best effort; the table is the record.
- **`/revoke` changes.** The check that a token belongs to the calling client is now an exact comparison of client ids.
  Revoking a refresh token now ends its whole family, not only the presented row.
- Components that verify a JWT on its signature alone (a resource server without `edge`, without introspection and
  without the event) still accept a revoked token until `exp`.

#### Behaviour change: edge syncs through `/edge/sync/*`, on central's mutual-TLS port

Central serves edges on a surface of their own (#554): `GET /edge/sync/{clients,resources,roles,
permissions,jwks,dpop,auth-request-presets,server-metadata}` and `POST
/edge/clients/edge-certificate/sign`, each answering only for the tenants bound to the calling
edge, **on central's mutual-TLS listener (`MPORT`) and nowhere else**. Every route outside `/edge/*`
-- `/configuration/**` including the `*/sync` routes auth reads, `/users/registrations` -- refuses
an edge token with `403`. The routes edge used before
(`/configuration/{resources,roles,permissions,auth-request-presets}/sync`,
`/configuration/edges/dpop/sync`, `/configuration/clients/edge-certificate/sign`) are gone, and
there is no compatibility period.

An edge call is accepted only when the TLS handshake presented a certificate that chains to
`central`'s `mutual-tls.trusted-certificates` **and** carries the URI name `urn:versola:edge:<id>`
of the edge the signed token names. The listener is meant to be reachable from outside the host or
cluster, so open its port deliberately (see [Mutual TLS certificates](#mutual-tls-certificates)).

- **Upgrade `central` and `edge` together, and regenerate their configuration**
  (`versola configure`, then `versola up`). `central.conf` gains a `mutual-tls` block, `edge.conf`'s
  `central` block gains `mutual-tls` and its `url` points at central's `MPORT` over `https`. An edge
  without `central.mutual-tls` refuses to start. An older edge against this central gets
  `403`/`404`, or no connection, and keeps serving the configuration it last loaded; a newer edge
  against an older central cannot connect.
- The bundle's `mtls-init` issues the new certificates when it next runs; the Helm chart's `pki`
  block does (`pki.centralServer`, `pki.edgeCentral`), with `services.central.mutualTlsPort: 8093`.
- `MBIND_HOST` (new, defaults to `BIND_HOST`) sets where the mutual-TLS listeners bind. The vps bundle
  binds central's to `127.0.0.1` (see below); it used to bind `0.0.0.0` in earlier builds of this branch.
- Central refuses to issue a client certificate whose `san_uri` is in the `urn:versola:edge:`
  namespace, so a client cannot be given a certificate that passes for an edge.
- No schema change, no data migration.

#### Breaking change: edge reaches `/userinfo` with its own certificate, not an edge assertion; edge's mutual-TLS listener

Two linked changes make an API behind edge's `/resources` callable with an access token bound to the
*caller's* client certificate (RFC 8705 §3, `cnf.x5t#S256`), the way a bank would call an operator's API
with a FAPI 2.0 client (`tls_client_auth`, PAR, certificate-bound tokens).

1. **`/userinfo` over mutual TLS.** An edge no longer sends `Versola-Edge-Assertion`, and `auth` no longer
   reads it. When an edge-served endpoint needs `/userinfo` for a key-bound (DPoP) token, or for a token
   bound to a caller's certificate, the edge calls auth's mutual-TLS listener with **its own** certificate
   (the one it presents to central: issued by central, `urn:versola:edge:<id>`). `auth` serves the call
   without the DPoP proof and without the holder's certificate **only if all of these hold**: the
   certificate was signed by one of the certificates in the new `auth` setting
   `mutual-tls.edge-trusted-certificates` (certificate authorities, valid now; checked at startup) and names
   exactly one edge; **central still lists that edge** (auth syncs `GET /configuration/edges/registry`, ids
   and assigned tenants, with the rest of its configuration); and the edge is **assigned to the tenant of
   the token's client**. Otherwise the call is judged as any client's. A token bound to *both* a key and a
   certificate is refused anyway, a certificate in a forwarded header is never taken for an edge's, and
   signature, revocation and the `openid` scope are checked as always. Without `edge-trusted-certificates`
   no certificate is an edge's and `/userinfo` behaves as before. Removed: `EdgeAssertion`,
   `EdgeAssertionService`, the signing keys in central's registry (it now serves ids and tenants only), the
   `dpop_edge_assertion_*` metrics (and their dashboard panels), and the assertion-sized floor on
   `dpop_proofs` eviction.
2. **A mutual-TLS listener on `edge`** (`mutual-tls { certificate, private-key, trusted-certificates,
   external-url }` in `edge.conf`, shaped like auth's; `MPORT`, default 8083, `MBIND_HOST`). It serves
   `/resources/{resourceId}/{path}` and nothing else, requires a client certificate in the handshake, and
   honours a `Bearer` token with `cnf.x5t#S256` only when the SHA-256 thumbprint of that certificate equals
   it. The plain listener is unchanged: such a token is still `401 certificate_bound_token_outside_session`
   there. Opt-in: with no `mutual-tls` block edge serves no second port.

What breaks, and how to migrate:

- **`auth` and `edge` upgrade together.** A new edge sends no assertion, so an old `auth` refuses its
  `/userinfo` calls for key-bound tokens; a new `auth` ignores an old edge's assertion.
- **A key-bound token on an endpoint with `fetchUserInfo` needs `mutual-tls.edge-trusted-certificates`**
  in `auth.conf` and the edge's certificate in the handshake of `native.auth-mutual-tls-url`. `versola
  configure vps` writes `edge-trusted-certificates = "/app/mtls/edge-ca.crt"` (and the Helm chart already
  mounts `/app/mtls/edge-trust/ca.crt` for it); `docker-local` names its single CA, which central never uses
  to sign a client a name in the edge namespace. Add the line to an existing `auth.conf` that predates it.
  An edge with no `native` block cannot make this call (the certificate is only ever sent to auth's own
  listener, never to `versola-internal-url`) and answers `500` for such a token.
- `dpop_edge_assertion_*` metrics are gone, and `GET /configuration/edges/registry` no longer carries keys (`auth` reads ids and tenants from it).

Security implication: **the edge's certificate private key becomes the most sensitive key of the
deployment.** Whoever holds it can call `/userinfo` as that edge, for any token of the tenants the edge is
assigned to, with no proof of possession: that is the blast radius of a stolen edge certificate. What limits
it: the edge CA must be dedicated (never the CA that issues clients' certificates: a client whose issuer
can sign `urn:versola:edge:<id>` could otherwise impersonate an edge, which is why `auth` checks the issuer
and not only the name; `docker-local` has a single CA and relies on central refusing to issue a client such
a name, `gen-env` says so), edge certificates are short-lived (vps: 30 days, renewed by the edge), and
**deleting an edge in central (or unassigning its tenants) cuts it off at `auth` within one configuration
refresh**, not at expiry. Rotate the edge CA like any other, and make auth's mutual-TLS listener reachable
only from the network the edges run in.

In plain words: an edge certificate lets its holder call `/userinfo` **without proof of possession, for any
token of the tenants assigned to that edge**. A certificate's own validity cannot be shortened after
issuance; deleting the edge in central cuts it off at `auth` only **after the next configuration refresh**
(`configuration-cache-refresh-interval` in `auth.conf`, one minute in `docker-local`, five elsewhere), and if
central is unreachable `auth` keeps the last registry it had (with none loaded, no certificate is an edge's:
it fails closed). **The edge CA must be dedicated.** If `edge-trusted-certificates` names the CA that also issues
client certificates (`docker-local`, or a `client-certificates` backend sharing a CA), the only thing keeping a
client from presenting an edge name is central refusing `urn:versola:edge:` names in client certificates
(`ClientCertificateServiceSpec`); `auth` logs a warning at startup when the listener trusts nothing else, and
`gen-env` warns for `docker-local`.

Edge's own mutual-TLS listener is opt-in and its certificates are yours to provide for now (`versola
configure` does not issue them): a server certificate for the public name callers use, and
`trusted-certificates` naming the CA(s) that issue your callers' client certificates.

#### Behaviour change (vps bundle): three intermediates, pin pairs, loopback `MPORT`

The vps bundle's mutual-TLS certificates are split by role (#554 follow-up). Breaking, and
`versola configure` plus `versola up` migrate it:

- `step-ca` gets two more intermediates under the same root, **server** and **edge**, next to the
  existing **client** one (the CA step-ca signs clients with; client issuance does not change).
  `auth`'s and `central`'s listener certificates now come from the server intermediate (365 days, was
  two years) and `edge-client` from the edge intermediate (365 days, was two years). The leaves of the
  single-intermediate layout do not verify against their new intermediate, so `mtls-init` re-issues
  all of them on the first run (and says why in its output); the pins on `edge` follow because it
  restarts with them.
- `auth.conf`: `mutual-tls.trusted-certificates` is `/app/mtls/auth-trust.crt` (client and edge
  intermediates, never the root) and `mutual-tls.edge-trusted-certificates` is `/app/mtls/edge-ca.crt` (the edge intermediate alone: a
  certificate it signed, naming an edge, is what `/userinfo` takes as an edge's own call). `central.conf`:
  `mutual-tls.trusted-certificates` is `/app/mtls/edge-ca.crt` (edge identities only; a client
  certificate no longer completes the handshake). `edge.conf`: `native.trusted-certificates` and
  `central.mutual-tls.trusted-certificates` are now two-element lists, `server.crt` +
  `server.next.crt` and `central-server.crt` + `central-server.next.crt`.
- `central`'s `MPORT` is bound to `127.0.0.1` (`MBIND_HOST`); it was open on all interfaces. A
  remote edge needs `--central-mtls-domain` (above) and the CLI's proxy. central's `APORT=8094` (not auth's 8082), the
  enrollment listener, is new and also loopback.
- `ca.crt` (root plus client intermediate) is still written, for anything that pinned it; nothing
  generated reads it any more.
- `central` signs edge identities with the **edge intermediate**, whose key `mtls-init` copies (unencrypted
  PKCS#8, mode 0600) into the central-only volume `versola-central-ca-*` as `edge-ca.key` (`edge-ca.crt`
  beside it); `central.conf` gets `edge-certificate-authority`. Central already is the authority on which
  edges exist, so holding that key adds no new party. Clients' certificates are still signed by the client
  intermediate through step-ca.
- **Existing deployments:** upgrade `versola-tools`, run `versola configure`, then `versola up` (or
  `versola rollout`). Roll `central`, `auth` and `edge` together: an old `edge.conf` against the new
  leaves, or the other way round, fails the pin check.

### `RUN_MIGRATIONS`

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
- **Revocations** (`auth_revocations`, `V0032`). The list of access tokens `auth` refuses before their `exp`: one row per revoked
  token, refresh-token family, session or user, with an expiry of one access token lifetime. It is small by construction, the
  last few minutes of revocations rather than a history. `revoked_at` is stamped with the database's clock, and the pair
  `(revoked_at, revoked_key)` is the cursor every replica resumes its reconciliation from. Rows are collected by the cleanup
  manager once `expires_at` has passed.
- **Configuration snapshots.** `auth` and `edge` keep the last configuration sync from `central`
  (signed with a key the node already holds) and start from it when `central` is unreachable or
  failing, retrying in the background and reporting `config_snapshot_age_seconds`. The tables fill on
  the first successful sync, and rows stop verifying after `central.secret-key` or edge's
  `private-key` is rotated, until the next sync.
- **Token signing key.** A deployment generated by `gen-env` signs access tokens, ID tokens and the
  other tenant-signed JWTs with ES256: `JWT_PRIVATE_KEY` is an EC P-256 key and `JWKS_JSON` publishes
  only its public half. A deployment that already holds an RSA `JWT_PRIVATE_KEY` keeps signing with it
  (PS256 or whatever its JWKS entry's `alg` says); nothing migrates by itself. To move it to ES256,
  generate an ES256 key in the console (Keys, or `POST /configuration/jwks/generate?alg=ES256`) and
  select it as the tenant's signing key (tenant security settings, "Signing key"). Every verifier
  learns the new key before anything signs with it; keep the old key published until tokens issued
  under it have expired.

- **Central's audit log.** `central_audit_event` (V1034) holds what administrators did through central's API (`admin.<resource>.<operation>`,
  a look at a user, a query of the audit report, every response that hands a secret back) and what bootstrap created
  (`system.bootstrap`). It is partitioned by day like the antifraud event log, and the service keeps the partitions: it creates
  14 days ahead and drops a day once the whole day is older than `audit.retention`. Dropping a partition is the retention; nothing is
  vacuumed. A row whose day had no partition lands in `central_audit_event_default`; those rows are **never moved** into the day's partition
  (an insert-only logical replication of the table would see them twice), a day that has rows in the default is not attached and
  central reports it (`audit_events_in_default`), and the rows leave the default when they age out of the retention. A trigger
  refuses `UPDATE`, `DELETE` and `TRUNCATE` unless the transaction sets `versola.audit_maintenance` to `on`, which only
  central's own prune of the default partition does. Creating and dropping partitions needs the role central connects as to own the
  table (or be a member of the owner); otherwise central logs that the partitions are not maintained and `audit_partition_owner_mismatch`
  is set. Long-term keeping is the operator's logical replication of `central_audit_event` (a publication with `publish_via_partition_root = true`
  and `publish = 'insert'`, an unpartitioned table on the subscriber; the publisher needs PostgreSQL 13 or later).
  The `audit` block of central's `env.conf` is optional, every key has a default, and a deployment that sets none records admin actions
  for 7 days:

  ```hocon
  audit {
    retention     = "7 days"      # 1 to 14 days, any other value stops the start; about 37 GB a day per 1 000 events a second (see below)
    overflow      = "drop"        # drop | block: what a full writer queue does to an event (drop does not wait, block waits up to block-timeout for room)
    block-timeout = "2 seconds"
    events { admin-user-viewed = true }   # whether a look at a user (GET /users) is an event: the one read that is many
  }
  ```

  **Disk.** `audit.retention` is from 1 to 14 days, and central refuses to start with another value. A row is about 430 bytes on disk,
  indexes included, so at 1 000 events a second the log takes about 37 GB a day: about 260 GB for 7 days and about 520 GB for 14 days;
  at 100 events a second a tenth of that. Events a second equal requests a second only if every request records an event, and
  `audit.events.admin-user-viewed` changes that. The per-user report asks auth, which keeps its log as long, for the same window. This is a
  design estimate, to be replaced with the figure measured under load.

  A call that reaches central without the query parameters edge adds (`actorId`, `actorTenantId`, `actorClientId`, `actorSessionId`) is
  not refused: it is recorded as made by a `service` with no id, and counted in `audit_actor_missing_total{reason}` (an alert is in
  `dashboards/alerts/versola-audit-central.rules.yaml`). Edge adds them to every admin route since this release, and central's
  bootstrap brings the registrations of the existing `central` resource up to date at start (it adds those inject rules to endpoints
  an earlier release registered, removes the header rules an earlier form of this feature registered, and leaves what an operator added
  alone); until edge has synced them, admin actions are recorded without an actor. Edge's sync is the refresh interval (5 minutes by
  default).

  The `ip` of an `admin.*` row is empty: the address of the administrator's browser is not passed on to central yet, so an empty `ip`
  there means "not recorded", not an unknown source. The log is the hot window of 7 days; keeping it as the record of what was done
  needs the replication described above.

  `audit:read` is not tenant-scoped, like `users:read`: a holder reads the report of any user.

  Where an action is made in auth (sessions, refresh tokens, passkeys, passwords, claims, limits, the antifraud settings), central records
  its outcome and auth records its effect; the two rows carry the same trace id. The per-user report shows both as they are. A report
  served without auth's part is counted in `audit_report_partial_total{reason}`. A page holds at most `limit` events of the two logs together
  (100 by default), and its `nextCursor` is the key of its last event; the changes administrators made, in the summary of the first page, are
  bounded at 100 and `truncated: true` says when they were more.

- **Antifraud configuration.** Each antifraud key has exactly one home. What is tuned during an attack (the mode,
  thresholds, weights, limits, the SMS budget's limits, the proof-of-work difficulty, shadow rules) is stored settings,
  changed through central's admin API with no restart (below). Everything else is the `antifraud` block of `auth`'s
  `env.conf`, and a restart applies a change. **Every key of the block is required**, as for the other services, except
  `lists.max-broad-ttl` (it has a default, so a config written before it existed keeps working) and the `asn` and `geo`
  blocks with each of their keys (a config without them keeps loading, the signal being off). `scripts/gen-env.scala`
  writes the block, and a config without a required key makes `auth` refuse to start, naming it. The chart takes the
  config from the Secret you give it, so there is nothing to set in the chart's values. The keys, with what the
  generator writes:

  ```hocon
  antifraud {
    retention        = "14 days"    # the event log's partitions; bounds how long personal data is kept
    stats-interval   = "1 minute"   # how often af_dest_stats is rolled up; a whole number of minutes (auth refuses to start otherwise)
    stats-retention  = "30 days"
    sms-budget { prefix-length = 4 }  # digits of a number that make a range (changing it starts the counts over)
    decision {
      enabled        = false        # the rule engine; off, nothing is evaluated
      timeout        = "20 milliseconds"
      failure-action = "allow"      # allow, challenge, fake or block: what a request gets when the evaluation fails, is too slow,
                                    # or the stored settings have not been read yet
      fake-send-delay = "400 milliseconds"  # how long a fake send waits until a real send has been timed
    }
    velocity { enabled = false, flush-interval = "10 seconds", load-interval = "20 seconds" }
    tuning   { refresh-interval = "5 seconds" }      # how often a change of the stored settings is looked for
    lists    { enabled = false, refresh-interval = "5 seconds", audit-retention = "365 days",
               max-broad-ttl = "30 days" }  # the longest a network, mail-domain or prefix entry may live (optional)
    detectors { enabled = false, interval = "1 minute", retention = "90 days" }
    challenge { enabled = true, ttl = "2 minutes", max-attempts = 5, pause = "20 seconds" }
    asn      { file = "", url = "", refresh-interval = "24 hours" }   # optional block; empty file is off; also an optional hosting-keywords list
    geo      { file = "", url = "", refresh-interval = "7 days" }     # optional block; empty file is off
  }
  ```

  Upgrade: `decision.fake-send-delay` and `challenge.pause` are the newest keys, so an `env.conf` generated before them has
  to be given the lines (or be regenerated) before `auth` of this version starts. `decision.failure-action` no longer
  accepts `delay`. Sizes that are constants of the service and not keys: the event queue and batch, the velocity counters'
  `max-keys` (20000 per kind), `stripes`, `share-from`, `max-flush-rows` and `max-load-keys`, the SMS budget's
  `max-ranges` (10000), the lists' hit flush (30 seconds), weights and traffic share, and the detectors' pace and
  thresholds. What each switch does, with the rules and the rollout from `shadow` to `enforce`, is on the Antifraud pages
  of the documentation (`antifraud/overview`, `antifraud/settings`, `antifraud/rollout`).

  **The module fails open.** A failed evaluation (a rule that throws, `decision.timeout` passed, the stored settings
  not read yet) gets `decision.failure-action`, recorded under the rule `antifraud_error` or `antifraud_tuning_unavailable`, a failed refresh of the settings or the lists keeps the last ones read,
  a velocity table that cannot be reached leaves per-instance counts, and a full event queue drops events; nothing of it
  delays a sign-in. Every switch is off by default (the exception is `challenge.enabled`, which acts only once
  `decision.enabled` is on and `mode` is `enforce`), and `mode` is `shadow` by default, so turning `decision.enabled` on
  changes nothing for the traffic until someone sets `mode` to `enforce`.

  **Stored settings (`af_settings`, migration `V0024`; `V0027` adds `version`).** One row for the whole deployment, with the
  keys `mode` (`shadow` or `enforce`), `challengeAt`, `blockAt`, `pow`, `thresholds` (with `trustedDiscount`), `smsLimits`,
  `ruleWeights` and `shadowRules`. Every key has a default and a stored row that lacks one is read with it, so a release
  that adds a key never makes the row of the version before it unreadable. The migration creates the table empty and
  `auth` writes the first row, everything off, at start if there is none (a row an operator saved is never
  overwritten). They are changed through central's `GET`/`PUT`/`PATCH`/`DELETE /antifraud/settings` (see
  `central/open-api/central.yaml`; `GET` needs `antifraud:read`, the others `antifraud:operate`). `PATCH` is a typed
  settings patch (`AntifraudSettingsPatch` in that file, the members of the settings and no others): it changes what it
  names, and `null` puts a setting back to its default (everything off) and removes a `byPrefix` or `ruleWeights` entry.
  **An unknown key is refused**: a `PUT` or `PATCH` with a key the settings do not have (a misspelt `blokAt`) is `400`, and a
  stored row with one fails to read, so an instance keeps the tuning it has (or, starting, decides as `failure-action`
  says) until the row is corrected; the three keys that builds before the removal of the Delay step wrote (`delayAt`,
  `delayMillis`, `fakeSendDelayMillis`) are still read and ignored. `PUT` and `PATCH` may name the version they were
  read at, in `If-Match`, and are answered `412` if the row has moved on. Every instance
  holds the row in memory and rereads it when `af_settings_version` changes, checked every
  `antifraud.tuning.refresh-interval`, and in full once a minute; a change made by hand in SQL has to bump `version` and
  `af_settings_version` too. An instance that starts with the database unreadable decides every request as
  `decision.failure-action` says until a read succeeds. The request and response shapes, the validation and the rule
  names are on the Antifraud settings page of the documentation (`antifraud/settings`).

- **Antifraud migrations, in `auth`'s migration set** (there is no `V0023`). None is undoable except by a dump, like every
  migration, and the additions to existing tables are compatible with the previous release, which does not name them.

  | migration | adds | lock and rewrite |
  |---|---|---|
  | `V0018` | `af_event`, the event log: the parent table partitioned by day, with two indexes (`af_event_subject_idx`, `af_event_dest_idx`) | creates the parent only; `auth` creates the partitions (below) |
  | `V0019` | `af_dest_stats`, the log rolled up per tenant, range of numbers and minute, and a BRIN index `af_event_at_idx` on `af_event (at)` | new table and indexes; the BRIN index is built on `af_event` and its partitions |
  | `V0020` | `af_list_entry` (fillfactor 80), `af_list_audit` and `af_list_version` (one row, version 0) | new tables only |
  | `V0021` | `af_velocity`, the velocity counters: `UNLOGGED`, fillfactor 70, vacuumed after 2% of it has died | new table only |
  | `V0022` | `af_proposal`, and `af_dest_stats.fake_verifies INTEGER NOT NULL DEFAULT 0` (the previous release's rollup does not name it) | new table; a column with a constant default |
  | `V0024` | `af_settings` (one row, JSONB) and `af_settings_version` | new tables only, created empty |
  | `V0025` | `af_event.would_action TEXT`, and `auth_conversations.challenge_failed` and `challenge_solved`, `INTEGER NOT NULL DEFAULT 0` (the default is there because the table has rows and the previous release inserts conversations without naming them) | nullable column; two columns with a constant default |
  | `V0026` | drops `af_event_subject_idx` and `af_event_dest_idx` (and with them the same index of every partition: nothing read them, and the writer inserts into two B-trees fewer per event); adds `af_rollup_state` (one row), `af_list_audit.previous_expires_at`, `previous_reason`, `previous_source`, `previous_shadow` (nullable) and `af_counter_reset` | `DROP INDEX` takes a brief exclusive lock on each partition, under `lock_timeout = 5s`: if it fails, run the migration again, outside a long query on `af_event` |
  | `V0027` | `af_settings.version BIGINT NOT NULL DEFAULT 1` | constant default |
  | `V0028` | `af_event.asn INTEGER` and `asn_class TEXT`, nullable, no default, no index | a catalog change on the partitioned table, no rewrite; the `ALTER` runs under `lock_timeout = 5s`, and if it fails the migration is run again |
  | `V0029` | `af_event.country TEXT`, nullable, no default, no index | as `V0028` |
  | `V0030` | `af_event_default`, the default partition of `af_event`: an event of a day that has no partition goes there instead of being refused | `CREATE TABLE ... PARTITION OF af_event DEFAULT` takes `ACCESS EXCLUSIVE` on `af_event` once, for an instant, under `lock_timeout = 5s`: if it fails, run the migration again, outside a long query on the table |

  Upgrade: before this version an administrator who changed the settings or reset them with `antifraud:manage` now needs
  `antifraud:operate` (below).

- **What runs where.** Everything that decides runs in `auth`: the rule engine, the velocity counters, the lists, the SMS
  budget, the challenge, the detectors and the event log. `central` serves the admin API (settings, lists, counter reset,
  rule names, status) and keeps no antifraud state, passing the calls to `auth`'s internal `/antifraud/...` routes. `edge`
  takes no part in a decision. Auth keeps in memory, per instance: the settings and the lists (reread every
  `tuning.refresh-interval` and `lists.refresh-interval`; a request never queries the database), the velocity counters
  (at most 20000 keys of each of five kinds, see the velocity bullet below),
  the SMS budget's counts over the last hour (at most 10000 ranges), the list hit counters, the event queue, and the
  ASN and country databases (some 30 MB for the ASN one). A restart loses what was queued and unwritten of the events, the
  budget's counts and up to the flush interval of the other counts. Postgres holds the tables of the migrations above; the
  velocity table and the conversations are `UNLOGGED`, so a crash of the database empties them and a standby never
  receives them. Background jobs, in every instance, with an advisory lock where one instance at a time must run them:

  - Events are queued and written in batches by one writer per instance, each statement under a 30-second statement timeout.
    A full queue or a failed write drops events and counts them, never delaying a sign-in; under an attack the log is a
    lower estimate.
  - The partition upkeep of `af_event`: today and seven days ahead are created at start and every six hours, and partitions
    older than `antifraud.retention` are dropped, in separate phases, both retried after a minute if they fail. The
    rows of the day the limit falls in that are older than it are deleted every 5 minutes, in batches of 5000 under their
    own statement timeout, at most 200 batches a run. Since `V0030` a day is made as a table of its own and attached
    (`ATTACH PARTITION`: `SHARE UPDATE EXCLUSIVE` on `af_event`, which stops neither reads nor inserts, and `ACCESS EXCLUSIVE`
    on `af_event_default` only, waited for up to 5 seconds), and an old day is detached (`DETACH PARTITION`: `ACCESS
    EXCLUSIVE` on `af_event`, tried with a `lock_timeout` of 500 ms, so inserts queue behind it for that long at most) and then
    dropped on its own. `DETACH ... CONCURRENTLY` is not used, PostgreSQL refuses it with a default partition; the minimum
    PostgreSQL version is 12. Before a day's partition is attached its rows are moved out of `af_event_default` (where an
    event of a day without a partition is written, instead of being dropped as before `V0030`), with a warning naming the
    count; the default partition is never dropped and the retention prune deletes from it too.
  - The rollup of `af_event` into `af_dest_stats` every `stats-interval`, one instance at a time (a marker row in
    `af_rollup_state` under the advisory lock), recomputing the last 2 minutes (or twice the interval); rows older than
    `antifraud.stats-retention` are removed.
  - The velocity counters: each instance writes its changed minutes every `flush-interval` and reads the others'
    every `load-interval` (its first load reads the whole day, starting at a random moment within half a `load-interval`
    so that a deploy does not hit the database from every instance at once), and one instance deletes the rows older than
    a day and an hour every ten minutes, 10000 rows per statement.
  - The lists: the whole table is reread every `lists.refresh-interval`; every 30 seconds the hit counters are written
    and the expired entries are removed in batches of 1000, with the audit records older than `lists.audit-retention`.
    Starting waits up to 30 seconds for the first load of the lists, then starts without them and retries every second.
  - The detectors, every `detectors.interval`, one instance per detector; the proposals expire after 12 hours and are
    removed after `detectors.retention`.
  - Every statement of the lists, settings and counter-reset repositories runs under a 10-second statement timeout, so a
    refresh that hangs fails and is counted.
  - The counter reset (`af_counter_reset`): every instance reads new rows every `tuning.refresh-interval` and applies them
    to its memory.

  The Antifraud pages of the documentation describe what each of them is for (`antifraud/velocity`, `antifraud/lists`,
  `antifraud/detectors`, `antifraud/challenge`).

- **Secrets and personal data.** Phone numbers and addresses in the event log, the velocity counters and the `phone_hash` list
  entries are a keyed hash made with `security.antifraud-subject-secret` (`ANTIFRAUD_SUBJECT_SECRET`, generated like the
  other secrets that only sign or authenticate; an IP address is hashed as its network, with a key derived from the secret
  under a label of its own), and the same secret, or the sessions secret without it, signs the challenge tokens. A config
  written before the secret existed has no such key and falls back on `security.sessions-secret`, so an upgrade needs no
  change, but rotating the sessions secret then changes the hashes too. `versola configure` generates the secret for a
  deployment that wants its own; the Helm chart reads it from the release's Secret as an optional key, so a release whose
  Secret predates it keeps starting (an `env.conf` regenerated to reference `${ANTIFRAUD_SUBJECT_SECRET}` needs the key in
  the Secret too: add the key together with the new config). **Rotating it** breaks the match between events before and
  after it, starts the velocity counts over (the old rows expire within a day) and stops every `phone_hash` list entry
  from matching until it is re-created; nothing else. **The hash is pseudonymous, not anonymous**, and the user agent is stored
  whole, so the rows of `af_event` are personal data: deleting a user does not delete their events, they leave with
  `antifraud.retention`, which must not exceed the retention you promise for such data. `af_event` is not under `cleanup`
  (it has no `expires_at`). A phone key is not scoped to a tenant. The address is the request's `auth.ip`, which is the
  client's only when the proxies in front set the header as expected. A partition can be created or dropped only by the
  role that owns the parent: that is the role `versola migrate` and the Helm migration Job use, which read the same
  `postgres.user` as the service, so it is `auth`'s own role. A deployment that migrates as a different role must run
  `ALTER TABLE af_event OWNER TO <auth's role>` once after `migrate`. The service checks this at start and before every
  upkeep run: when its role neither owns the table nor is a member of the owner, it logs an ERROR naming the owner, the role
  and that statement, sets the gauge `antifraud_partition_owner_mismatch` to 1 and does not attempt the DDL (the partitions
  that exist, `af_event_default` included, stay owned by whoever created them). Antifraud and the secrets are also
  described in `antifraud/overview` and `operations/secrets` of the documentation.

- **Who may change what.** Three permissions on the `central` resource guard the routes: `antifraud:read` (the reads: the
  settings, the rule catalogue, the status, a tenant's lists and their audit), `antifraud:manage` (a tenant's lists) and
  `antifraud:operate` (the operator's routes: `PUT`/`PATCH`/`DELETE /antifraud/settings`, `POST /antifraud/counters/reset`
  and the twin routes `/antifraud/global/lists/...`). Every start registers them on the `central` resource and gives them
  to `oauth-admin` (all permissions), `security` (`read` and `manage`), and the read-only roles (`antifraud:read`, except
  `viewer`, which sees no PII). `antifraud:operate` is seeded into the default tenant's administrator role (`oauth-admin`)
  only. **Do not grant `antifraud:operate` to a role of any other tenant:** it carries the deployment-wide routes, and a
  role is all that decides which tenant's admin may use them. A tenant's admin (`antifraud:manage`) acts through
  `/antifraud/lists/...` in the scope of the tenant of its access token: edge adds it (`callerTenantId`, from the token's
  `tenant_id`, overwriting what a client sent) and central sets the scope from the route, so nothing in a body can widen
  it. Edge also tells central who the admin is (an inject rule for `userId` on the calls that change something, a body
  field from the verified token; a removal is a `DELETE` with a JSON body naming the `id`), and a request to such an endpoint
  whose body is not JSON (`application/json`, or `application/merge-patch+json`) is refused by edge with `415` rather than
  forwarded. A change that reaches central with the resource secret but no `userId` is `400`; auth records that user id as
  who made each change (`created_by`, the audit). The lists are **off by default** (`antifraud.lists.enabled`); the
  console has no screen for them yet. Upgrade: an administrator who changed the settings or reset them with
  `antifraud:manage` now needs `antifraud:operate`. Rules of the lists (precedence, expiry, preview, the audit) are on
  the Antifraud lists page of the documentation (`antifraud/lists`).

- **SMS budget, velocity counters, detectors and the challenge: what to know when deploying.** The SMS budget
  (`antifraud.sms-budget.prefix-length`, the tuned `smsLimits`) and the velocity counters (`antifraud.velocity.enabled`, the
  tuned `thresholds`) hold their counts in memory per instance (an estimate, not a measurement: a key seen for the first time
  costs about 0.4 to 0.8 KB, so a full table of 100000 keys, the five kinds of 20000, is about 50 to 80 MB, a key busy all
  day up to about 90 to 130 KB, and the other instances' counts are held too, about the same again for each of them), so a limit holds to within the number of instances
  and starts again from zero after a restart; both limits are off by default (`0`). **A closed range refuses every
  code to it, the legitimate users' included**: size `sentPerHour` above the real peak of the busiest range before it is
  turned on, see the Velocity counters and SMS budget page of the documentation (`antifraud/velocity`). Changing the
  prefix length mixes lengths in `af_dest_stats`, so clear the table first. The detectors (`antifraud.detectors.enabled`,
  off by default) only propose list entries; there is no way yet to approve a proposal. The challenge needs no table and no new
  page: its state is the conversation's (`auth_conversations.challenge_failed` and `challenge_solved`, `V0025`), its token is
  signed with the key of `security.antifraud-subject-secret`, and `challenge.pause` travels in the conversation with no
  migration. Only the proof of work is built. The `pow` defaults (`minDifficulty` 14, `maxDifficulty` 18, `scoreStep` 15, in zero bits
  of a hash) come from a measurement in headless Chromium with 4 workers (2.7 M hashes/s per thread): 14 bits 13 ms, 16 bits
  33 ms, 18 bits 77 ms mean (p90 165 ms), 20 bits 219 ms (p90 460 ms); a mid-range phone takes 3 to 5 times that, which is
  why the default maximum is 18 bits. For the work a difficulty asks, see the Challenge and Fake page of the
  documentation (`antifraud/challenge`).

- **ASN signal (`af_event.asn` and `asn_class`, migration `V0028`).** Off by default; with an empty `antifraud.asn.file` every
  lookup counts as `unavailable` and no rule or `asn` list entry acts. The lookup is made by `auth` in memory, from a database
  it holds, so no request calls anything outside the process. The source is the free `ip2asn-combined.tsv.gz` of
  [iptoasn.com](https://iptoasn.com), a public-domain dedication (PDDL; check the terms of whatever mirror you use), gzipped or
  plain, IPv4 and IPv6 together; a file in the same tab-separated format from another source works. Put the file at
  `antifraud.asn.file` (or let `antifraud.asn.url` fetch it) and restart: it is read before the service serves, in about half a
  second, and held in some 30 MB. A file that is missing or is not a database is a warning in the log and the same as off.
  With a `url` (`https` preferred; a redirect is not followed) the file is downloaded at start if there is none and then every
  `antifraud.asn.refresh-interval` (24 hours): into a temporary file next to `file`, **so that directory must be writable by
  `auth`**, read as a database and moved over `file` in one atomic step, and on any failure the previous database stays in use.
  `url` is held as a secret in memory and only its host is logged. Without a `url` the file is looked at every interval and
  read again when its modification time has changed. Each instance downloads for itself: point many instances at a shared
  mirror. A download is one `send-http` line at `INFO`. An AS is `hosting` when its description contains one of
  `antifraud.asn.hosting-keywords`, as a whole word, case-insensitively; the default list is `hosting`, `cloud`, `datacenter`,
  `data center`, `data-center`, `vps`, `dedicated`, `colocation`, `amazon`, `aws`, `google cloud`, `google-cloud`,
  `microsoft`, `azure`, `digitalocean`, `ovh`, `hetzner`, `linode`, `vultr`, `choopa`, `alibaba`, `oracle`, `contabo`,
  `leaseweb`, `m247`, `scaleway`, `online s.a.s`, `ionos`, `godaddy`, `rackspace`, `tencent` and `huawei cloud`, and setting
  the key replaces it (an empty list classifies nothing as hosting). The IP address is not stored; the AS number and its class
  are in `af_event` and on the `receive-http` line under `antifraud`. The sign-in forms and the OTP request go to `auth`
  directly, not through `edge`, and `edge` does not use the signal. See `antifraud/asn` of the documentation for the rules.

- **Country signal (`af_event.country`, migration `V0029`).** Off by default, from a database `auth` holds in memory like the
  ASN one: the block `antifraud.geo { file, url, refresh-interval = "7 days" }` is optional, an empty `file` is off, and
  refresh, failure handling and the writable directory next to `file` are as for the ASN database. `url` is held as a secret in
  memory and only its host is logged (the same holds for `antifraud.asn.url`); it may hold `{yyyy}` and `{mm}`, the year and
  the month (UTC) of the download, and a month with no file yet (a 404) is retried once with the month before. The source is
  DB-IP IP to Country Lite, **CC BY 4.0, attribution required**: one file, gzip or plain, CSV with the first address, the last
  address and the ISO 3166-1 alpha-2 code of each range; a range with `ZZ` (or no code) is not held. **Attribution:** DB-IP
  asks for a visible credit, "IP Geolocation by DB-IP" with a link to https://db-ip.com, on the site or product that uses the
  data (the sign-in form footer, say, or the deployment's own site); a paid DB-IP licence lifts it. The signal is the
  database's answer and nothing else (the country an AS is registered in is not used), it is in `af_event.country` and on
  the `receive-http` line, and it is read by the `country` list entries and by two rules, `country_mismatch` and
  `country_outside_regions` (rule names that `ruleWeights` and `shadowRules` accept, with `asn_hosting`). What they fire on
  is on the Country signal page of the documentation (`antifraud/geo`).

- **Antifraud metrics** (all in `auth`; none has a tenant or a value as a label). Decisions: `antifraud_decisions_total{action,would_action}`,
  `antifraud_rules_fired_total{rule,shadow}`, `antifraud_evaluation_failed_total{reason}` (`timeout`, `error`,
  `tuning_unavailable`), `antifraud_challenges_total{kind,outcome}`. Event log: `antifraud_events_enqueued_total`,
  `antifraud_events_written_total`, `antifraud_events_dropped_total{reason}` (`queue_full`, `write_failed`, `throttled_shed`),
  `antifraud_event_queue_size`, `antifraud_partition_days_ahead` (7 when healthy; the default partition is not counted),
  `antifraud_partition_maintenance_failed_total`, `antifraud_partition_owner_mismatch` (1 when the role cannot keep the
  partitions), `antifraud_events_moved_from_default_total` (a day ran without a partition; its events are safe),
  `antifraud_stats_rollup_failed_total`. Settings and lists: `antifraud_tuning_refresh_failed_total`,
  `antifraud_tuning_refreshed_timestamp_seconds`, `antifraud_list_refresh_failed_total`,
  `antifraud_lists_refreshed_timestamp_seconds`, `antifraud_list_hit_flush_failed_total`,
  `antifraud_list_hits_total{list_type,value_type,shadow}`. Velocity and the SMS budget: `antifraud_velocity_flushed_total`,
  `antifraud_velocity_keys`, `antifraud_velocity_failed_total{what}` (`flush`, `load`, `prune`),
  `antifraud_velocity_evicted_total`, `antifraud_velocity_untracked_total`, `antifraud_sms_budget_refused_total{reason,mode}`
  (`cap`, `conversion`; `shadow`, `enforce`), `antifraud_sms_budget_evicted_total`, `antifraud_sms_budget_full_total`,
  `antifraud_counters_reset_total{scope}`, `antifraud_counters_reset_failed_total{what}` (`poll`, `delete`). Detectors:
  `antifraud_proposals_created_total{kind}`, `antifraud_detector_failed_total{kind}`,
  `antifraud_proposal_housekeeping_failed_total`. The signals: `antifraud_asn_lookups_total{result}` and
  `antifraud_geo_lookups_total{result}` (`hit`, `miss`, `unavailable`), `antifraud_asn_database_loaded_timestamp_seconds`,
  `antifraud_asn_database_ranges`, `antifraud_asn_database_refresh_failed_total`,
  `antifraud_geo_database_loaded_timestamp_seconds`, `antifraud_geo_database_ranges` and
  `antifraud_geo_database_refresh_failed_total`. The Grafana board `versola-antifraud` is `dashboards/grafana/versola-antifraud.json` and the
  Prometheus alert rules (also read by vmalert) are `dashboards/alerts/versola-antifraud.rules.yaml`, loaded as
  `dashboards/alerts/README.md` says; both use metrics that exist only from the release that added them. What to alert on
  is in the Antifraud rollout playbook (`antifraud/rollout`).

- **Audit log (`auth_audit_event`, migration `V0031`; #469).** `auth` records every authentication, token, consent,
  account, antifraud administration and refused certificate enrolment event (the kinds are the closed vocabulary `AuthAuditKinds` of `auth`; the per-user report
  reads them through `central`) into `auth_audit_event`, partitioned by day like `af_event` and maintained by the service
  the same way (today and 14 days ahead, at start-up and every hour; a day older than `audit.retention` is dropped,
  which is the retention: nothing is deleted row by row except from the default partition). Its keys are the `audit`
  block of `auth`'s `env.conf`, **every key required**, written by `scripts/gen-env.scala` (a config written before
  the block existed makes `auth` refuse to start naming the missing key, as for `antifraud`):

  ```hocon
  audit {
    retention     = "7 days"      # the hot window, 1 to 14 days, any other value stops the start; the long-term copy is the replication below
    overflow      = "drop"        # drop: an event that finds no room in the writer's queue is counted in audit_events_dropped_total
                                  #   and the request goes on;
                                  # block: the event waits up to block-timeout for room first, then it is counted as dropped
                                  #   (the request goes on in both cases)
    block-timeout = "2 seconds"
    events { token-refresh = false, authorize-success = true, par-success = true }   # the high-volume kinds; refusals, reuse detection and failures are always recorded
  }
  ```

  **What the trigger covers.** `auth_audit_event_immutable` is a row trigger on the table and every partition, and a
  `BEFORE TRUNCATE` statement trigger on the parent table. It refuses an `UPDATE` or `DELETE` of a row of any partition and a
  `TRUNCATE` of `auth_audit_event` itself unless the transaction has set `versola.audit_maintenance = 'on'`. A `TRUNCATE` of a
  single partition is not covered (a statement trigger of a partitioned table is not cloned to its partitions). The
  service's own prune of the default partition sets the mark; an operator who must delete (a legal erasure) sets it the
  same way, `SET LOCAL`, in the transaction. Any session can set it, and the table's owner can drop the trigger or a
  partition. Dropping a partition is DDL and is not refused. A row of a day that had no partition goes to `auth_audit_event_default`
  and stays there (it is never moved into the day's partition, unlike the antifraud log, because the replication
  below is insert-only and a move is a delete and an insert): the gauge `audit_events_in_default` counts such days,
  the day gets no partition while they are there, and they leave with the retention. The owner of the table must be
  the service's role, as for `af_event` (`audit_partition_owner_mismatch`).

  **Disk.** A row is about 430 bytes on disk, indexes included, so at 1 000 events a second `auth_audit_event` takes about 37 GB a day:
  about 260 GB for the 7 days of the default and about 520 GB for the 14 days that are the most `audit.retention` takes; at 100 events a
  second a tenth of that. Events a second equal requests a second only if every request writes an event: `audit.events.token-refresh`
  (off by default), `authorize-success` and `par-success` are what changes it. This is a design estimate, to be replaced with the figure
  measured under load.

  **Facts to plan for.** The hot window is 7 days (`audit.retention`) unless you change it. A replication slot that is
  not read keeps the WAL it has not sent on the primary's disk until the slot is read or dropped; the alert
  `AuditReplicationSlotRetainsWal` watches the WAL each slot holds back (there is no metric of whether the subscription
  exists). `auth_audit_event.at` is the clock of the node that records the event, so the nodes need synchronised clocks (NTP
  or the cloud's time service), and the per-user report compares its window with the clock of the node that serves it.
  `auth` reads the report of one user for at most 4 requests at once; another waits up to 2 seconds for its turn and is
  answered `503` with `Retry-After: 1` and the code `audit_report_busy`.

  **What is recorded, and how often.** Refusals reachable without signing in (`client.auth_failed`, `token.refused`, a
  failed `authorize.request`, a refused `par.request`) are counted in memory per (kind, client, address, reason) and written
  as one row a minute with `details.count`; the table of keys holds at most 10 000, past which new keys are counted
  together in one more row. The `token.issued` of a refresh-token grant is off by default (`events.token-refresh = false`); `token.refused` and
  `token.reuse_detected` are always recorded. `PUT /users` records `account.updated` (actor `service`, `details.changed`
  names `email`, `phone` or `login` and never a value, only when one changed); `DELETE /users` ends the user's sessions with
  `session.ended` and the cause `user_deleted`.

  **Long-term storage is your logical replication** of `auth_audit_event` to another database, which the hot window's
  drops never reach. PostgreSQL 13 or later on both sides (`publish_via_partition_root` is 13+; the minimum of this
  schema otherwise stays 12). On the source:

  ```sql
  CREATE PUBLICATION versola_audit FOR TABLE auth_audit_event WITH (publish = 'insert', publish_via_partition_root = true);
  ```

  On the subscriber, an **unpartitioned** table of the same name and columns (the same `CREATE TABLE` without `PARTITION BY`
  and with the primary key `(id, at)`, no trigger needed: nothing but the subscription writes it), then
  `CREATE SUBSCRIPTION ... PUBLICATION versola_audit`. `publish = 'insert'` means a drop of a day on the source
  (`DETACH` and `DROP` of the partition, which is not replicated) leaves the subscriber's rows in place, and the
  prune of the default partition is a `DELETE` that is not published either. The subscriber's retention and access
  control are the operator's.

  **Metrics and alerts** (`source` label `auth`; `central` writes its own table, `central_audit_event`, with the same metric names):
  `audit_events_total{source,kind,outcome}`, `audit_events_dropped_total`, `audit_events_blocked_total` (requests
  that waited for room), `audit_events_failed_total` (best-effort inserts that failed), `audit_queue_size`,
  `audit_partitions_ahead`, `audit_events_in_default`, `audit_partition_owner_mismatch`, `audit_events_unavailable_total`
  (`central`'s requests refused with 503 `audit_unavailable`; `auth` does not refuse a request for it) and `audit_events_rejected_total`
  (events the database refused one by one; a text with U+0000 and an address that is none are cleaned before the insert, so this
  stays at zero) and `audit_events_failed_total`. The alert rules are `dashboards/alerts/versola-audit.rules.yaml`
  (`AuditEventsDropped`, `AuditEventsRejected`, `AuditEventsFailed`, `AuditEventsUnavailable`, `AuditQueueHigh`, `AuditEventsBlocked`,
  `AuditPartitionsRunningOut`, `AuditEventsInDefault`, `AuditPartitionOwnerMismatch`, `AuditReplicationSlotRetainsWal`), loaded like the antifraud ones. **The rows are personal data** (an address, a user agent, a user id): the hot window and the replica's
  retention must not exceed what you promise for such data; deleting a user does not delete their events.

  The per-user report shows the country of an address (a `country` on an event and on an entry of `ips`, and a `countries` list in the summary) only when `antifraud.geo` is configured; it is looked up when the report is read and is not stored. With no database loaded the field is absent. The database's attribution requirement is described with `antifraud.geo`.

  Central reads auth's rows through two internal endpoints (`GET /users/audit-events` and `GET /users/audit-summary`, the user the query parameter `id` as for the other `/users` routes,
  authenticated with the service token like `/users/*`; not public, not in the OpenAPI specs), and tells auth which
  administrator acts with the optional `actorId` of the call it makes for one (a query parameter of a `GET` or `DELETE`, a field of the
  body of a `POST`, `PUT` or `PATCH`; the antifraud calls carry the administrator as `userId`); a call without it is recorded
  as the `central` service's.

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

Every secret is declared in the release's `secrets.schema.json` (see [`develop.md`](develop.md),
"How the CLI applies the schema"), and `configure` follows it. A secret new in a release is
generated on the upgrade to it. A key that stored data depends on (the JWT and edge key pairs,
`PASSWORDS_SECRET`, `REFRESH_TOKENS_SECRET`, `CLIENT_SECRETS_SECRET`, `CENTRAL_RESOURCE_SECRET`,
`EDGE_TOKEN_ENC_KEY`, the Postgres password) is generated only on the first install, and `configure`
stops if one goes missing later. The `utils` key pair is handled apart (`secret/versola/vps/utils`):
a missing private key is replaced by a new one, and `configure` only warns if central's public half
no longer matches. **Changing one of those keys makes the data it protects unreadable** (password
hashes, refresh tokens, client secrets, sessions); the others can simply be rotated.
`versola secrets plan vps <version>` shows what `configure` would keep, create, or stop on.

### Admin console

`central-ui` is a static SPA shipped in the release's `versola-tools` image; `configure` copies it
into the bundle and `versola-proxy` serves it at `/central/admin/`, so deploying a release deploys
its console. It shares the `id.versola.io` origin with its edge API (no CORS handling), and the
`EDGE_SESSION` cookie is scoped `Path=/central`, so every URL the console touches lives under that
prefix; the proxy strips it before forwarding:

| Path on `id.versola.io` | Routed to |
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
`redirect-uris` must contain only `https://id.versola.io/complete` in prod. If you change the cookie
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

#### The Security page asks for a fresh sign-in

On a database where `central`'s bootstrap **creates** the `auth` resource (the one that proxies the
Security page of Account Settings), removing a passkey, starting to add one and signing another session
out require the built-in `sensitive` ACR with a sign-in no older than 300 seconds. `sensitive` is the
strongest factor the user has: a passkey if they have one, else their password, else a one-time code, so
a session that only passed a one-time code cannot change who can sign in to an account that has a passkey.
Edge answers such a call with `401`, the RFC 9470 challenge and, for a browser session, a `Location` header
with the login that asks for what is missing; the page goes there, asking to be sent back to itself (`return_to`,
a path of the preset's own origin; edge keeps it in `pending_logins.return_to`, migration `V2007`). Back on the
page the action the user had already confirmed is carried out at once (within ten minutes; adding a passkey
leaves the name in its field if the browser wants a click of its own), and a sign-in that ends in an error (for
example `unmet_authentication_requirements`) is reported on the page. Reading the page, renaming a
passkey and finishing an enrollment ask for nothing more.

A deployment that already has the `auth` resource is **not** changed: bootstrap keeps the step-up an
operator has set (none, until now), so an upgrade alters nothing for it. To switch it on, update the
resource in the console (**Resources** → `auth` → endpoints → **Step-up**) or with `PUT /configuration/resources`
(`central/open-api/central.yaml`), and give the three endpoints
`stepUpCondition: "true"`, `stepUpAcr: "sensitive"` and `maxAge: 300`:

| Method | Path |
|---|---|
| `DELETE` | `/settings/sessions` |
| `DELETE` | `/settings/passkeys` |
| `POST` | `/settings/passkeys/register/start` |

The console's resource editor lists `sensitive` as a built-in choice next to the tenant's own ACRs, so the
same change can be made there. A user whose flow can ask for none of their factors (a passkey the flow cannot ask for,
or no password and no one-time-code destination) is refused with `unmet_authentication_requirements`
rather than let through.

### Mutual TLS certificates

`auth`'s mutual-TLS listener (`MPORT`, RFC 8705) is on by default, with its certificate machinery;
the certificate relationships are described in
[`develop.md`](develop.md#certificate-storage-and-rotation-440). The generated compose file runs:

- `step-ca` — the internal CA (state in `versola-step-ca-*`): one root and **three intermediates**, so
  each listener trusts exactly the roles it should (a trust anchor that is the root would accept all
  of them). **client** ("Versola Client CA", created by the image) is what `step-ca` signs with and
  what edge-fronted clients' certificates come from, through a `central` JWK provisioner (14-day
  maximum lifetime); to get one, register the client with `issueEdgeClientCertificate: true` and any
  `mtlsAuth`. **server** signs the listener certificates of `auth` and `central`, **edge** signs the
  identity of an edge. The two extra intermediates are created once, when missing, and only ever used
  offline by `mtls-init`. Its first start prints the generated password of an unused `versola`
  provisioner once to `docker logs versola-step-ca`; keep that log private or remove the provisioner.
- `mtls-init` — one-shot (`mtls-init.sh` in the bundle; its header is the contract). It writes into
  `versola-mtls-*`:

  | File | What | Signed by | Lifetime |
  |---|---|---|---|
  | `server.{crt,key}` | `auth`'s listener | server | 365 days |
  | `central-server.{crt,key}` | `central`'s listener | server | 365 days |
  | `edge-client.{crt,key}` | identity of the edge on this host (`urn:versola:edge:edge-default`) | edge | 365 days |
  | `server.next.crt`, `central-server.next.crt` | pin copies, see below | | |
  | `client-ca.crt`, `edge-ca.crt`, `server-ca.crt` | one intermediate each | | |
  | `auth-trust.crt` | client + edge intermediates: what `auth`'s handshake accepts | | |
  | `ca.crt` | root + client intermediate, as before the split | | |

  A leaf is replaced only when it is missing, a third of its life or less is left, it does not verify
  against its intermediate (certificates of the single-CA layout) or it lacks a public name that is
  now configured. `central`, `auth` and `edge` start after it.

`auth`'s and `central`'s listeners stay on loopback (`127.0.0.1:8083`, `:8093`); an edge on the same
host needs nothing opened. For remote edges and external mutual-TLS clients, configure
`--mtls-domain` / `--central-mtls-domain` (see [First-time setup](#2-first-time-setup)): the names go
into the leaf's SAN, and the proxy forwards them to the loopback ports. A call to `central`'s
listener is answered only when the handshake presented a certificate from the **edge** intermediate
and the signed token names the same edge as the certificate's `urn:versola:edge:<id>` name; `auth`
additionally requires an `urn:versola:edge:*` name to chain to the edge intermediate, so a client
certificate cannot pass for an edge.

**Edge identity certificates.** An edge's identity certificate lives 30 days (vps) and the edge renews it
itself when a third of its life is left, so an outage of about twenty days is survived. The chain of renewals
ends a year after the enrollment: central refuses to renew after that, and the operator enrols the edge
again with a new token (`versola edge add <id> --existing`; a token lasts an hour unless it is asked to last
longer, up to 24 hours, with `validityHours`). A revoked edge is cut off at once by deleting it or rotating
its signing key (central needs certificate and token); a list of revoked serials will come with the step
where auth accepts an edge certificate. Central signs these certificates with the edge intermediate (above);
the certificate `mtls-init` writes for the edge on this host is a different thing: it is a file renewed by `versola certs renew`, and lives 365 days (see below).

**Lifetime of the files `mtls-init` writes.** The server leaves and the identity of the edge on the same host live
365 days by default; renewal is at a third of the life left, so with the timer running it happens months before
expiry. `MTLS_CERT_DAYS` (versola-tools; `versola configure vps --cert-lifetime-days N`) sets another number of days,
and `0` means unlimited: a notAfter 100 years away, so the files never need renewing. Unlimited gives up the expiry as
a limit on what a leaked key can do, and the renewal timer is then not installed (with a finite lifetime it is
required). The intermediates and the root keep their own lifetimes; a leaf of another lifetime than the configured one
is replaced at the next `mtls-init` run. The edges central issues renew in-process and are not affected.

**Renewal and the pin overlap.** `edge` pins the two server leaves, and reads certificates at startup.
In 0.8.0 the pins of an edge on another host are updated by the operator: central does not publish them.
The overlap above (current + next pin) is what `versola certs renew` prepares, and the CLI's bootstrap bundle
carries both.
`versola certs renew` (run it on a schedule: a daily cron or systemd timer; it does nothing until a leaf
has a third of its life or less left) drives `mtls-init` through `MTLS_INIT_PHASE`:

| Phase | Effect | Output the CLI reads |
|---|---|---|
| `all` (default) | issue what is missing or due, in place; `<name>.next.crt` is a copy of the current certificate | `mtls-init: issued <name>`, `mtls-init: changed` |
| `prepare` | stage each due server leaf as `<name>.next.crt` + `<name>.next.key`, current pair untouched; repeatable | `mtls-init: staged <name>`, `mtls-init: changed` |
| `commit` | move each staged pair into place; nothing staged prints nothing | `mtls-init: committed <name>`, `mtls-init: changed` |
| `identity` | issue or renew `edge-client` only | `mtls-init: issued edge-client`, `mtls-init: changed` |
| `report` | change nothing | one `mtls-init: cert <file> notBefore=... notAfter=... subject=...` line per certificate |

The order for a server leaf is `prepare`, restart `edge` (it now pins the old and the staged
certificate), `commit`, restart `auth` or `central`; no handshake fails at any point. `edge-client` is
not pinned by anyone and is renewed with `identity`. Without the schedule nothing renews: a leaf
expires 365 days (by default, see below) after it was issued. A step-ca volume from before its client template
existed keeps issuing certificates with the subject rewritten to the bare CN, which `central` refuses
when longer; recreate the `versola-step-ca-*` volume to lift it.

### Client certificates through EST (RFC 7030)

A client that authenticates with `tls_client_auth` (a bank calling a FAPI 2.0 API, say) can obtain and
renew its certificate by itself with standard EST, with a key it generated. It is off until enabled, and
additive: no existing client, route or key changes (central gets one migration, `V1027`, three new tables).

1. **Enable it in `auth`.** `est { enabled = true }` in `auth.conf` (`configure` writes it for `local`;
   on `vps`, `docker-local` and Kubernetes set `EST_ENABLED=true` in `versola-tools`' environment). It needs
   the `mutual-tls` block, because renewal is served on that listener only. `est.ca-certificates` is **required**
   (auth does not start without it): a PEM file with the **client CA alone**, which `cacerts` hands out. It is
   deliberately not defaulted to `mutual-tls.trusted-certificates`, which is every anchor a client chain is validated
   against and may hold the edge CA as well (the `vps` bundle and the chart do), and a bank must not be told to trust
   that. On the generated targets: `EST_ENABLED=true` and, except on `local` and Kubernetes (where the chart mounts the client CA alone at `/app/mtls/client-trust/ca.crt`: from the trust-manager bundle `<release>-client-only-ca`, or `pki.est.caSecret` without trust-manager; never the root, and the chart fails rendering with `pki.est.enabled` and no source), `EST_CA_CERTIFICATES=<path in auth's container>`.
   **A client needs two base URLs**: enrolment on the issuer origin and re-enrolment on the mutual-TLS origin (a stock EST client
   is configured, and run, once for each; `simplereenroll` on the issuer origin is `404`). Deliberate narrowing of RFC 7030: a
   renewal always uses a **new key** (clients whose HSM cannot rotate keys cannot use it), and a request with
   `challengePassword` (tls-unique channel binding, §3.5) is refused. A token bound to a certificate (RFC 8705 §3) is
   accepted only over that certificate, so a client keeps presenting the old certificate with its old tokens until they
   expire, and uses the new one for new tokens; the renew window (a third of the life by default) is the overlap to count on.
   The routes are `/.well-known/est/{cacerts,csrattrs,simpleenroll}` on the public name of `auth`, and
   `/.well-known/est/{cacerts,csrattrs,simplereenroll}` on the public name of its mutual-TLS listener (see
   [Remote and partner edges](#remote-and-partner-edges-and-the-public-domains)); the spec is
   `auth/open-api/est.yaml`.
2. **Central needs a CA to issue from**: the `client-certificates` block (step-ca or cert-manager), as for
   edge-fronted clients. `client-certificates.validity` (14 days by default) is how long a certificate lives;
   the client renews before it ends; the certificate is revoked by expiry, and what can be revoked at once is the right to renew (below). `auth` holds no CA credentials and calls central
   (`POST /est/enroll`, `POST /est/renew`, auth's token only) to have a certificate issued; central's own
   listeners are not exposed to the client. **Auth is in the same trust zone as the mutual-TLS terminator, not outside
   it**: it is what tells central which certificate a client presented and which token it sent, and central cannot
   check either (a certificate is public, and no key is shown to it). Whoever controls auth, or its internal token to
   central, can therefore renew the certificate of any EST client that has a current one, and enrol with a token they
   observe. What central narrows: only a certificate issued through EST, of the client's current generation, renews;
   only in the last third of its life (`client-identity.renew-window`); a replaced certificate that renews again
   closes the chain; the chain ends at `max-age` whatever is asked; and the certificate carries the registered
   subject, never one the caller names.
3. **Register the client** with `estEnrollment: true` (web and service clients; `authMethod` `tls_client_auth`;
   the tenant must set `mtlsCertificateHeader`, as for any `tls_client_auth` client). It holds no certificate yet.
   The certificate's subject is the client's `mtlsAuth`, or `CN=<client>,OU=<tenant>,O=Versola` where it names none;
   it is never taken from a request, and stays the same across renewals.
4. **Create a one-time token** with `POST /configuration/clients/est/enrollment-tokens?clientId=<id>` (24 hours
   by default, `validityHours` up to 168). It is shown once together with the `subject` the request must say.
   The client trades it at `simpleenroll` (HTTP Basic, the client id as user name, or Bearer) with a PKCS#10 for
   that subject. It renews at `simplereenroll` on the mutual-TLS listener with the certificate it has, a new key
   each time; an expired certificate does not renew, enrol the client again with a new token.
5. **Chains.** Renewals are a chain that begins at the enrolment. `client-identity { max-age = 365d,
   auto-extend = false, renew-window = 0.3333 }` in `central.conf`: at `max-age` central refuses renewals (`403`) until
   an operator calls `POST /configuration/clients/est/extend?clientId=<id>`, so every client is re-approved yearly;
   `auto-extend = true` makes central extend by itself. A certificate renews only in the last `renew-window` of its life
   (earlier is `403`, with the date it opens). The end of the chain is soft: a certificate issued the day before it
   ends is valid for its whole `validity`, so the client can run up to that long past `Versola-Chain-Ends-At`. The
   client is told the date in every answer, and `GET /configuration/clients` shows `est.pending`,
   `est.certificateExpiresAt` and `est.chainEndsAt`.
   **Interop notes (libest `estclient`, Cisco's reference client, run against a local stack).** `cacerts`, `csrattrs` (204) and
   `simpleenroll` with a Basic token work, with these caveats: (a) libest compares response header names case-sensitively
   and looks for `WWW-Authenticate`, while auth (zio-http) writes header names in lower case, so it stops with "No
   WWW-Authenticate header found" unless a proxy in front restores the case; (b) its command-line client builds a
   CSR with a common name only, so the client has to be registered with `mtlsAuth` of `subject_dn` `CN=<name>` for its
   request to say the registered subject; (c) its `-r` re-enrolment always signs with the key of the presented
   certificate and has no way to rekey, which this profile refuses (400, "a renewal needs a new key"), so the stock
   command-line client cannot renew; a client built on the libest API can. Other clients were not tried.
6. **If a key is out.** What is revoked is the right to **renew**, not the certificate, which keeps authenticating until
   it expires (set another `mtlsAuth` subject on the client, or delete it, to cut that too). Every certificate belongs
   to a generation of the chain; a new enrolment (a new token) starts one, so earlier certificates stop renewing, and
   so does `POST /configuration/clients/est/revoke?clientId=<id>`, which also withdraws an open token (a new token
   replaces the earlier unused ones). A certificate that a renewal replaced and that comes to renew again after 3
   minutes means the key is in two hands: central closes the chain (`403`, `est_chain_fork_detected`, outcome
   `chain_fork`) and the operator enrols the client again. `simpleenroll` is limited to 30 attempts a minute per
   client id and replica (`429`).

Metrics: `est_requests_total{operation,outcome}` in `auth` and `est_certificate_requests_total{operation,outcome}`
in `central` (`outcome`: `issued`, `unauthorized`, `bad_request`, `forbidden`, `chain_fork`, `rate_limited` (auth only), `error`).

### Remote and partner edges, and the public domains

> **Design for the next release.** This section describes what that release contains, so a deployment
> can be planned. Nothing here is released yet and nothing is certified. The user-facing version, with
> the same facts, is "Network, domains and certificates" and "Partner edge" in the documentation
> (versolauth/versola-website, in the pull request versolauth/versola-website#144).

Ports, to avoid mixing them up: `central` has `PORT` (internal), `MPORT` (the edges' mutual TLS) and `APORT` (public; there it serves the enrollment route `POST /edge/enroll` behind a TLS terminator, and it binds only where `APORT` is set). `auth` has its own `APORT`, a different service and a different port.

The default deployment is one host: every edge is local and reaches `central` and `auth` on `127.0.0.1`
(`8093`, `8083`). Two things add public names: an edge in another network (**remote first-party
edge**) and clients that authenticate with a certificate. A **partner edge** is a different thing: it
is run by a partner and is, for `auth`, an ordinary OAuth client.

**Which names to create, and when**

| Name | Create it when | TLS ends at | Certificate |
|---|---|---|---|
| `id.<domain>` | always | the proxy | Let's Encrypt |
| `id-mtls.<domain>` | a remote edge reaches auth, or an external client uses `tls_client_auth` | auth (the proxy forwards TCP by SNI) | internal CA leaf, name in its SAN |
| `central-mtls.<domain>` | a remote edge reads central's configuration | central (forwarded the same way) | internal CA leaf, name in its SAN |
| `central.<domain>` | a remote first-party edge enrolls | the proxy | Let's Encrypt; serves `POST /edge/enroll` only |

A local edge never uses these names: it keeps using the loopback addresses. The mutual-TLS names stay
off until you pass them to `configure`; with no flag, the listeners stay on loopback and no `stream`
server is generated. Never terminate TLS on the two `-mtls` names (the client certificate is lost).
Protect the public ports: `limit_conn` per source at the `stream` layer with its own log, source
addresses restricted to the hosts that run an edge, attempt limits on `/edge/enroll`, and an alert on
certificate expiry.

**Enrolling a remote first-party edge**

1. Create the edge group and the edge: `POST /configuration/edge-groups` (`{"id","kind"}`) and
   `POST /configuration/edges` (optional `groupId`, default `default`). `kind` and an edge's group
   never change.
2. `POST /configuration/edges/enrollment-tokens?edgeId=<id>` returns `{token, expiresAt}`. It is shown
   once and works once; it lives 1 hour, or up to 24 with the `validityHours` query parameter.
3. On the remote host give the edge the token file, `central.enrollment { url, token-file }` (the
   `central.<domain>` URL) and a persistent `identity { directory }`. The edge makes its key locally,
   sends a CSR with exactly `urn:versola:edge:<id>` and `urn:versola:edge-group:<group>` to
   `POST /edge/enroll`, and renews with `POST /edge/certificate/renew` at a third of its lifetime left,
   with a new key each time. The certificate lives 30 days; an outage of `central` of about twenty days is survived. Renewals form a chain from the enrollment; `central`'s `edge-identity { max-age = 365d, auto-extend = true }` extends it itself at `max-age`, so no edge stops for want of a new enrollment. `auto-extend = false` applies only to partners' edges: `central` then stops at `max-age` until `POST /configuration/edges/extend?edgeId=<id>` or a new enrollment (token up to 24 hours). The edge is told the end date in every answer and warns daily from 30 days before; `GET /configuration/edges` shows `identityChainEndsAt`. First-party edges are always extended. Without the volume it must enroll again on every restart.
4. Set `central.url` to `https://central-mtls.<domain>` and `native.auth-mutual-tls-url` to
   `https://id-mtls.<domain>`, and pin the server leaves (documentation page "Edge configuration"). In this release the operator updates a remote edge's pins (0.8.0 is planned to publish them from `central`).

**Partner edge.** The operator decides, in `central`: an edge group has a `kind` of `first_party` or
`partner`, set at creation and never changed by the edge. The `mode` key in an edge's own configuration
is only a declaration: at start the edge compares it with the group in its credentials and refuses to
start on a mismatch. A partner group never reaches `/edge/sync/*`, so a partner edge cannot read
`central`'s configuration; a client of a partner group needs standard client authentication (`private_key_jwt` or
`tls_client_auth`). Planned, not in this release: `auth` accepting an edge's certificate for the clients of its group. Onboarding: create a `partner` group and its edge, register the partner's clients
bound to that group, publish `id.<domain>` (and `id-mtls.<domain>` for `tls_client_auth`). A partner
never gets `central-mtls.<domain>`.

**Behaviour change and breaking items** (read before upgrading; `central`, `auth` and every `edge`
move together and `configure` is re-run)

- The public name for central's mutual-TLS port is `central-mtls.<domain>`, not `central.<domain>`;
  `central.<domain>` now means enrollment over ordinary TLS. A deployment that already routed
  `central.<domain>` to `8093` must rename it.
- `central` signs edge certificates with its own edge CA (`edge-certificate-authority` on `vps`, an
  edge `Issuer` through `edge-certificates` on Kubernetes): it is already the one authority on which
  edges exist, and registering and enrolling an edge are both its admin API. Clients' certificates
  stay with the client CA.
- The edge identity is 30 days (`vps`) and renewed by the edge itself, not by `versola certs renew`;
  it needs a persistent `identity.directory`. Revocation: deleting the edge, or rotating its signing
  key (`POST /configuration/edges/rotate-key`), cuts it off at once, because `central` needs both the
  certificate and the edge's signed token. A list of revoked serials comes later.
- **Not in this release:** `auth` accepting an edge's certificate for a client served by an edge
  (such clients still need their own certificate or `issueEdgeClientCertificate`), and `central`
  publishing pins to remote edges. (`mutual-tls.edge-trusted-certificates` on `auth`, which makes
  `/userinfo` take an edge's certificate as the proof that the edge verified a token's holder, comes with
  "edge reaches `/userinfo` with its own certificate" below.)
- The migration creates the group `default` (`first_party`) and puts every existing edge in it, so an
  upgrade changes nothing for existing edges.
- CAs are split by role (server, edge, client). Whether the server role is its own intermediate is not
  decided.

### Request bodies state every field (breaking for API clients)

`central`'s admin API no longer fills in values a caller leaves out. A field that has no meaning when missing used to
decode to a default (`theme` to `"default"`, a missing boolean to `false`, a missing set to empty); it is now required, and a
body that omits it is refused with `400`. A field typed as optional (`null` allowed) is still optional. The reason: a
field left out by mistake used to become a security-relevant value (an unthrottled category, DPoP off, an empty scope patch)
without anyone having chosen it.

| Endpoint | Fields that must now be sent |
|---|---|
| `POST /configuration/clients/web` | `theme`, `otpTemplateId`, `frontChannelLogoutSessionRequired`, `dpopBoundAccessTokens`, `dpopSigningAlgs`, `certificateBoundAccessTokens`, `requireSignedRequestObject`, `requirePushedAuthorizationRequests`, `estEnrollment` (on top of the ones already required) |
| `POST /configuration/clients/native` | `theme`, `otpTemplateId`, `frontChannelLogoutSessionRequired`, `dpopSigningAlgs` |
| `POST /configuration/clients/service` | `permissions`, `dpopBoundAccessTokens`, `dpopSigningAlgs`, `certificateBoundAccessTokens`, `estEnrollment` |
| `PUT /configuration/clients/web`, `/native` | `redirectUris` and `scope` patches (`{"add":[],"remove":[]}` for no change) |
| `PUT /configuration/clients/service` | `scope` and `permissions` patches |
| `consentFlow` in any client body | `requiredScopes` (`[]`) and `offlineAccessOptional` (`false`) next to `allowPartial` |
| `PATCH /configuration/edges/policies`, section `rateLimits` | `replicas`; and for every policy `by`, `scope`, `burst`, `cost`, `mode` |
| `POST /configuration/resources` | `audienceRateLimits` (`[]` for none) |
| `rateLimit` of a resource endpoint, and of an audience client | `burst`, `by`, `mode` next to `limits` |

**Migrating a client of the API.** Send the fields above; the values to send for "as before" are the old defaults:
`theme` and `otpTemplateId` `"default"`, the booleans `false`, `dpopSigningAlgs` and `permissions` `[]`, an unchanged patch
`{"add":[],"remove":[]}`, a rate limit's `burst` `1`, `cost` `1`, `by` `[]` (policy) or `"none"` (declared limit), `scope`
`"edge-wide"`, `mode` `"shadow"`, `replicas` `1`. The console, `versola-cli`, the load generator, `scripts/est-smoke.sh` and
`openid-conformance/run.py` send them; a script of your own that posts a minimal body will start getting `400` and the reason is in
`central`'s log. Read the new `required` lists in `central/open-api/central.yaml`.

**Stored data.** Migration `V1032` adds `requiredScopes` and `offlineAccessOptional` to every stored `consent_flow`
that lacks them, so no stored client is unreadable. `auth` and `edge` read configuration from `central` in the same shape:
upgrade `central` first, then `auth` and `edge` (the configuration snapshot an `auth` or `edge` keeps for a start while `central` is unreachable is the raw answer
of the older release and is decoded like a live one, so a snapshot that lacks a field cannot be used; start `central` first so the services
refresh it).

### Overload protection

When a service is asked for more than it can do, it should refuse the surplus quickly rather than queue it
until everything is slow. Two guards do that, both in `off` / `shadow` / `enforce` mode and both in
**`shadow` by default**: `shadow` counts what `enforce` would refuse and refuses nothing, so a deployment
behaves as before until someone turns enforcement on.

- **Inbound admission** (edge): a bound on requests in flight, with priorities. A new login is refused
  first, then work already begun (code exchange, logout), then proxied resource calls; health, metrics,
  config sync and back-channel logout are never refused. The bound is not a number someone guessed: it is
  **learned** from the call times and the executor lag, shrinking when calls slow down or the executor
  stalls and growing back while they are fast, inside `minLimit`..`maxLimit`. It is learned whenever the
  mode is not `off`; there is no separate switch. `maxInFlight` is where it starts.
- **Outbound guard** (every service, per peer): a limit on calls in flight, a bounded wait and a timeout
  (`503` / `504`), a circuit breaker and a retry budget. Central is never limited by it, so a service can
  still wait for its configuration at start-up; nothing is enforced until the service has started.

**Modes.** `off`: nothing is counted, nothing is refused. `shadow`: the limit is learned and
`http_admission_refused_total{mode="shadow"}` counts what `enforce` would have refused; nothing is refused.
`enforce`: a request above the learned limit for its priority is answered `503` with `Retry-After`.
In a load test of a two-node cluster at 1.3x and 1.7x of its knee (4.3k steps/s), with no policy goodput fell to
1.7-2.1k steps/s with a p99 of 10 s and recovered slowly; `enforce` with a fixed `maxInFlight` of 512 held 4.0-4.5k with a p99 of 1.3 s;
`enforce` with the learned limit (16 to 1536) held 4.6-4.7k with a p99 near 120 ms and recovered at once.

**Defaults** (what an edge uses for a field nobody set):

| Inbound field | Default | Environment base |
|---|---|---|
| `mode` | `shadow` | `HTTP_ADMISSION_MODE` |
| `maxInFlight` (start of the learned limit) | 256 | `HTTP_ADMISSION_MAX_IN_FLIGHT` |
| `continuationShare` | 0.85 | `HTTP_ADMISSION_CONTINUATION_SHARE` |
| `startShare` | 0.65 | `HTTP_ADMISSION_START_SHARE` |
| `retryAfterSeconds` | 1 | `HTTP_ADMISSION_RETRY_AFTER` |
| `adaptive.minLimit` / `maxLimit` | 16 / 1024 | policy only |
| `adaptive.tolerance` / `smoothing` / `lagTargetMillis` | 1.5 / 0.2 / 50 | policy only |

| Outbound field | Default | Environment base |
|---|---|---|
| `mode` | `shadow` | `HTTP_CLIENT_GUARD_MODE` |
| `maxConcurrent` | the connection pool size (128, the maximum of the default `dynamic` pool; `HTTP_CLIENT_POOL_*` in k8s/README.md) | `HTTP_CLIENT_GUARD_MAX_CONCURRENT` |
| `maxQueue` / `queueTimeoutMillis` / `requestTimeoutMillis` | 256 / 1000 / 10000 | `HTTP_CLIENT_GUARD_MAX_QUEUE`, `_QUEUE_TIMEOUT`, `_REQUEST_TIMEOUT` |
| `breaker` | off; failureRatio 0.5, minCalls 20, window 10 s, open 5 s, probeCalls 3 | policy only |
| `retryBudget` | ratio 0.1, minPerSecond 5 | policy only |

An **edge** also takes an *overload policy* from central, per edge, which is a patch over those values: a
field the policy leaves out keeps what the edge started with (the environment value when set, the default
otherwise). Set it in the admin console (an edge's page, section "Overload protection") or with
`PUT /configuration/edges/overload?edgeId=...` (the body is described in `central/open-api/central.yaml`;
`{}` removes it and the edge returns to its own deployment values). The change reaches a running edge on its
next configuration sync, within the refresh interval (5 minutes by default), and the edge keeps the last
policy it received while central is unreachable. A policy that fails validation is rejected with `400` by
central and ignored by an edge. Unknown fields are ignored; a leftover `inbound.adaptive.enabled` has no effect.
Migration `V1025` adds a nullable column and needs no action. `maxInFlight` below `adaptive.minLimit` (or above
`maxLimit`) starts the limit at the nearest bound.

**Clients.** A refusal is `503` with `Retry-After: <retryAfterSeconds>`. A client should wait at least that
long, add jitter and retry a bounded number of times; an immediate retry loop makes the overload worse.

**Metrics.** `http_admission_refused_total{tier,mode}` (in `shadow`: what `enforce` would refuse),
`http_admission_in_flight` and `_in_flight_peak` (the highest value since the previous sample),
`http_admission_limit` (the learned limit in force), `http_admission_adaptive_limit_ratio` (the learned limit
over `maxLimit`; near 1 means the edge has room, near `minLimit / maxLimit` means it is squeezed),
`http_admission_rtt_seconds{tier}`, `http_executor_lag_seconds`, and for the outbound guard
`http_client_guard_rejected_total{mode}`, `http_client_guard_in_flight`, `_waiting`, `_breaker_state`.

**Rolling out.**

1. Leave `shadow` (the default) for at least a day of real traffic, including a peak.
2. Look at `http_admission_refused_total{mode="shadow"}`: near zero while the service has capacity means the
   limit is not in the way. Many shadow refusals during a peak mean `enforce` would shed that traffic: raise
   `adaptive.maxLimit` or add capacity first.
3. Set the inbound `mode` to `enforce` on one edge, watch refusals, goodput and latency for a peak, then the rest.
4. Do the outbound guard the same way, one step later; turn the breaker on last.

**Tuning.**

| Symptom | Change |
|---|---|
| Refusals in `enforce` while the pod is idle (CPU low) | raise `adaptive.minLimit`, or `maxLimit` if `http_admission_limit` sits at it |
| Latency climbs before the edge refuses anything | lower `adaptive.tolerance` (nearer 1) or `lagTargetMillis` |
| The limit swings up and down | lower `adaptive.smoothing` |
| New logins refused while proxied calls are fine | this is the intended order; raise `startShare` only if logins matter more than calls |
| Clients retry in a tight loop after a refusal | raise `retryAfterSeconds` |
| Calls to a slow backend pile up | lower outbound `maxConcurrent`, and set `requestTimeoutMillis` just above the backend's p99 |

### Rate limits

The edge limits how much each client, user, session or key may send through it. An operator sets the policies per edge in
central; a policy in `shadow` only counts what it would refuse, a policy in `enforce` refuses. An edge with no policy runs with
the limiter disabled, and a proxied request pays one volatile read. The design is in #744 (and #310 for the original ask).

**Start from the recommended set.** `GET /configuration/edges/rate-limits/recommended` (and "Add recommended policies" in the
console, on the edge page under *Rate limits*) gives five generous policies, all in `shadow`: `per-client` (120,000 a minute,
bursts of 5,000), `per-user` (300 a minute and 5,000 an hour, bursts of 60), `per-user-endpoint` (120 a minute per user and
endpoint, bursts of 30), `per-address` (600 a minute, bursts of 100; see R1) and `per-certificate` (60,000 a minute, bursts of
2,000; mutual-TLS listener). They are meant to catch a runaway client, user, address or certificate, not a busy one. Add them, read
`edge_rate_limit_decisions_total{result="would_limit"}` for a week, adjust the numbers to what well-behaved callers really do,
then switch one policy at a time to `enforce`. Switching it back to `shadow` or `off` is the off switch.

- **Policies per edge, in central: one endpoint for all the edge's policies.** `GET` and `PATCH /configuration/edges/policies?edgeId=`
  (the overload, access-log and rate limit policies are its three sections, `overload`, `accessLog` and `rateLimits`):
  - `PATCH` changes the sections the body names and leaves the others as they are. `null` removes a section, an object replaces it **as a
    whole** (a section is never merged field by field); an empty section is the same as `null`. A key that is not a section is refused
    (`400`), so a misspelled section is an error and not a silent no-op. `400` and `409` come with **no body** (a body would be a
    contract callers parse; the reason of a `400` is in central's log as `invalid_policy`, and `dryRun` answers whether a body is valid); `404`
    for an unknown edge. The answer is the new state with the new `revisions`.
  - **Conditional change, without headers:** `expectedRevisions` in the body names, per section, the revision the client last read
    (`GET` returns `revisions` and `updatedAt`; the edge listing returns `policyRevisions`). If any named section has moved on, nothing is
    written and the answer is `409` with no body: read the state again. A section that is not named has no precondition, so a client
    that only patches its own section needs none. A change that changes nothing does not move the revision. The console sends the revision
    it read and, on `409`, reads the edges again and says so; on `400` (the console validates, so it should not happen) it says central did not accept the values.
  - **`dryRun=true`** checks the body with the rules the edge applies (and the revisions) and answers the state the patch would make,
    writing nothing: for CI and automation.
  - Stored in `edges.rate_limit_policies` (migration `V1029`), `edges.overload_policy`, `edges.access_log_policy` and the revisions in
    `edges.policy_meta` (`V1030`), all nullable JSONB and additive: the previous release's inserts do not name them and a rollback works on
    the new schema. The edge listing shows `rateLimitPolicies`, `overloadPolicy`, `accessLogPolicy` and `policyRevisions`; the edge gets a
    section in its own sync (`GET /edge/sync/rate-limits`, `/overload`, `/access-log`; the calling edge's own row only). The sections are
    independent. The edge picks a change up on its
  next configuration sync (the refresh interval, 5 minutes by default), keeps the last set while central is unreachable, and
  treats `404` from an older central as "none set". A set that fails validation on the edge is not applied and the previous
  one stays.
- **Two checkpoints on the proxy path**, both fed by the verified token and the matched route, never by what the caller
  sent: R2, after the JWT is verified and before the revocation check and the DPoP proof is recorded (client, subject,
  session `sid`, key `jkt`); R3, after the endpoint and the caller's permissions are resolved and before the `/userinfo`
  call and the upstream (resource and registered endpoint template, global and per subject). A request refused by
  permissions is counted at R2 and not at R3. A refresh is not counted separately: the proxied call is. A call refused at R3
  has already had its DPoP proof recorded (the proof is checked before the route is known, and reordering would let a caller
  without a valid proof learn whether it is permitted); a call refused at R2 has not, and neither reaches `/userinfo` or the
  resource.
- **R1, before the token is read, keyed on what the connection shows** (policy `by: ["ip"]` or `["certificate"]`, scope
  `edge-wide`, no other key):
  - *Plain listener, the address.* Counts **failed and unauthenticated attempts only**: `/login`, `/complete` and the other entry
    points count every call (nobody is signed in yet); a proxied call counts only when it is refused as unauthenticated (a
    missing, invalid or expired token, a bad DPoP proof; not a nonce challenge, a step-up or a redirect to sign in again).
    **Successful calls are never limited by address**, so a mobile carrier's shared address does not refuse its honest users.
    An address whose bucket is empty is refused with `429` before anything else is done. IPv4 per address, IPv6 per `/64`.
  - *Mutual-TLS listener, the certificate.* Keyed by the verified client certificate's SHA-256 thumbprint; every call counts (a
    certificate is an identity); no address is needed, and none is reliably known behind an L4 proxy.
  - *Which address:* the edge's own config file, `client-ip = "socket"` (default: the peer of the connection) or `"forwarded"` (the
    header named by `client-ip-header`, default `X-Real-IP`). Set `forwarded` only when the edge is reachable **only** through the
    proxy that sets the header (compose network, chart network policy): anything else could write the header and choose its own
    bucket. nginx (`versola-proxy`) and ingress-nginx already resolve the client chain and set `X-Real-IP`. A header that is
    missing or not an IP literal falls back to the peer. **Inside a Kubernetes cluster** a call through a Service (or an internal load balancer)
    from a pod on another node reaches the backend with the caller's *node* address as its source (measured on the test cluster; only a
    caller on the same node with `externalTrafficPolicy: Local` kept its own address). So behind kube-proxy, pods on one node share one
    address, and `socket` cannot tell them apart: put a proxy that sets the header in front (ingress-nginx does) and use `forwarded`. The
    address a real internet client has through a cloud load balancer was not measured (the cloud's address quota blocked creating one). Behind a proxy with `socket`, every caller is the proxy: the address limit
    would count the whole world as one caller. Check `edge_client_address_total{source}` (`socket`, `forwarded`,
    `forwarded_missing`) before enforcing an address policy; the edge logs a warning at start when `forwarded` is set. Whether a
    cloud L4 load balancer delivers the client address to the pods (and an `externalTrafficPolicy` that keeps it) depends on the
    platform and is to be verified on the first real load balancer.
- **A refusal is `429 Too Many Requests`** with `Retry-After` in whole seconds, rounded up (the wait of the policy that makes the
  caller wait longest) and **no body**: a body would be a contract; everything a caller can act on is in the headers, and the policy is named only by `RateLimit-Policy`, never the key. A caller
  passing every policy is charged on all of them; one refused by any is charged on none. A refusal is counted
  (`result="limited"`) and its `receive-http` line follows the success rules (written when slow or sampled, not for every
  refusal), so a flood cannot become a flood of log lines. A `429` also carries `RateLimit-Policy: "<name>";q=<quota>;w=<window seconds>` (the
  policy's tightest rate) and `RateLimit: "<name>";r=0;t=<wait>`, as in draft-ietf-httpapi-ratelimit-headers, which is still a draft and
  has changed between versions: so they are sent on a refusal only, never on a successful response, and never name a key.
- **The operator's view of refusals is the log, once a minute per policy.** Each edge and auth logs one line per policy that
  refused something in the last minute (`... rate limit 'per-client' in the last 60 s: refused 12, would refuse (shadow) 0, ...`):
  `WARN` when the policy enforced, `INFO` when it only counted in shadow, nothing when it did neither. It names no key, so it holds
  nothing about a caller, and it is bounded by the number of policies however hard the edge is hit. A single refused request is not
  logged by itself (a flood of them would be a flood of lines); its `429` names the policy in `RateLimit-Policy`, and a request
  log line, when the access log is `full`, carries the error `rate_limited` with the same policy name.
- **Rate limits and overload protection never share a counter**: a rate limit refuses a specific party with `429`; overload
  protection refuses whoever is there, by priority, with `503`.
- **The limit is per pod, divided by `replicas`.** Every pod counts on its own (no shared counter, no network hop on the request
  path). Set `replicas` in the policy set (`{"replicas": 3, "policies": [...]}`, "Edge pods" in the console) to the number of pods
  of the edge and each pod enforces `limit / replicas` of every window and `burst / replicas` (rounded up, never below `cost`), so the
  edge as a whole allows about the limit written. It is as exact as the balancer is even; a limit meant to be exact across pods
  (a billed daily quota) would need leased tokens from a shared store, which is a separate piece of work. Left at 1, the limit
  applies to each pod and N pods allow N times it. Change it when the number of pods changes.
  **Counting pods automatically:** set `replicas-from = "dns:<name>"` in the edge config to count the pods from DNS instead. The
  chart ships a headless Service for this (`<fullname>-edge-headless`, ready pods only) and puts its name in the environment variable
  `VERSOLA_EDGE_HEADLESS_SERVICE` of the edge pods, so the edge's `env.conf` can say `replicas-from = "dns:"${VERSOLA_EDGE_HEADLESS_SERVICE}` and nothing is
  typed by hand; the edge resolves it every 10 seconds, keeps
  the last good count if a lookup fails, and uses the policy's `replicas` until the first answer. The count in force and where it came
  from are the gauge `edge_rate_limit_replicas{source="dns|policy"}`. Nothing detects Kubernetes at runtime: without the key the
  policy's `replicas` is used.
- **Memory is bounded**: at most 100,000 tracked keys per policy; past that, new keys share one overflow bucket (counted in
  `edge_rate_limit_overflow_hits_total`), so a flood of distinct keys cannot grow memory.

**Metrics** (a key is never a label; the policy name is a closed set, at most 64 names of `[a-z][a-z0-9_-]*`):
`edge_rate_limit_decisions_total{policy,result}` with `result` in `allowed`, `limited` and `would_limit`;
`edge_rate_limit_tracked_keys{policy}`; `edge_rate_limit_overflow_hits_total{policy}`. With no policy none of them is written.

**Limits declared by the owner of an API** (levels 2 and 3 of #744 section 4). The resource owner sets limits where the API is
declared, so a change travels with it and the owner of a client sets nothing about how much of an API it may take:

- *A resource endpoint*: `rateLimit` in the endpoint of `POST` / `PUT /configuration/resources` (`createEndpoints` replaces an
  endpoint by id, so send the whole endpoint), read back by `GET /configuration/resources`. Applied at R3 for that endpoint, one
  bucket per key and registered endpoint template. Stored inside the endpoint's JSON in `resources.endpoints`: no migration, and an
  endpoint stored before this has none.
- *A client of the resource's audience*: `audienceRateLimits` on the resource, a list of `{"clientId": ..., "rateLimit": ...}`, in
  `POST` / `PUT /configuration/resources` (on `PUT` it replaces the whole list; absent leaves it; removing a client from the
  audience drops its limit), read back by `GET /configuration/resources`. Applied at R3 to that client's calls to this resource,
  whichever endpoint; one entry per client, only for a client in the audience. Stored in `resources.audience_rate_limits` (JSONB,
  migration `V1030`: `NOT NULL DEFAULT '[]'`, because the previous release's inserts do not name it and an existing row has none).
- *The shape*: `{"limits": [{"limit": 600, "windowSeconds": 60}], "burst": 50, "by": "subject", "mode": "shadow"}`. `by` is `subject`
  (one bucket per user), `client` (per client; endpoints only) or `none` (one bucket for the endpoint, or for that client on the
  resource); `burst` defaults to 1, `by` to `none`, `mode` to `shadow`. The rules are those of a policy (`400` names the first
  problem; `InvalidRateLimit` for an endpoint, `InvalidAudienceRateLimit` for an audience client), and `cost` is always 1.
- *They add to the edge's policies, they do not replace them*: a request must pass every limit that applies to it, so the
  effective limit is the smallest. Declared limits are divided by `replicas` like the edge's own. A bootstrap start keeps a
  `rateLimit` an operator put on one of the account endpoints.
- *Metrics*: the policy is named `resource-endpoint` or `resource-client` (reserved: an edge policy cannot take either name), never the
  endpoint, resource or client, so the label set stays closed; the key counts of every declaration add up under that name. A `429` names
  the same policy. Up to 10,000 tracked keys per declaration (the edge's own policies keep 100,000), so many declarations cannot
  grow memory.
- *Order of work and rollback*: an edge that predates this ignores the fields, and a central that predates them sends none, so either
  can be upgraded first. The previous release keeps serving on the new schema, and `audience_rate_limits` can stay when rolling back.

Not there yet: partner edges tightening any of these locally.

### Limits on auth's channel that is not mutual TLS

The edge's rate limits stand in front of the resources. `auth` reached without the edge, that is not over mutual TLS, is exposed
directly, so it has limits of its own (#745), from the `limits` block of auth's config file (every key optional; the block may be
absent):

| Limit | Applies to | Counts | Default |
|---|---|---|---|
| failed client authentication | `POST /token`, `/par`, `/introspect`, `/revoke` | an address's **failures only** (`401`, or `invalid_client`) | `enforce`, 20 a minute, burst 10 |
| entry | `/authorize` | every call by address | `shadow`, 600 a minute, burst 100 |

An address over its failed-authentication limit is refused with `429`, `Retry-After` (whole seconds), the `RateLimit` fields and no body
before its next request is read, however good the credential in it. A client that authenticates is never limited, so a shared
address is not refused for another's failures. Guessing a client's secret at `/token` was the classic unthrottled path; the
secret's own entropy protects it, and this makes the guesses slow as well. The mutual-TLS listener is not limited this way (a
certificate is not a guessable secret).

```hocon
limits {
  failed-mode = "enforce"     # off | shadow | enforce
  failed-per-minute = 20
  failed-burst = 10
  entry-mode = "shadow"
  entry-per-minute = 600
  entry-burst = 100
  client-ip = "socket"        # socket | forwarded
  ip-header = "X-Real-IP"
}
```

`client-ip` is read as the edge's is (see *Rate limits*, R1): `socket` is the peer of the connection, `forwarded` the last value of
`ip-header`, to be used **only** when auth is reachable solely through the proxy that sets it. Behind a proxy with `socket` every
caller is the proxy, so the limit would count the world as one address. A value outside its range stops the start. State is per
pod (N pods allow N times a limit) and bounded (a flood of addresses shares an overflow bucket).
`gen-env.scala` writes `failed-mode = "shadow"` into the **local** config, because the e2e suite fails client authentication from one
address on purpose; a generated deployment has no block, so the defaults apply. A load campaign whose drivers share addresses
should set `failed-mode` and `entry-mode` to `shadow` for the run.

Metrics: `auth_rate_limit_decisions_total{policy,result}` (`allowed`, `limited`, `would_limit`; policies
`failed-client-auth-per-address` and `authorize-per-address`), `auth_rate_limit_tracked_keys{policy}`,
`auth_rate_limit_overflow_hits_total{policy}`.

### Access log

Every service writes one `receive-http` line per inbound request and one `send-http` line per outbound call. They are
the largest part of the log volume, and rendering them cost about 17% of an edge's CPU and 12% of auth's in a JFR
profile under load, almost all of it for successful requests.

- **auth and central have no access-log setting.** They always log every request (`full`), and there is no environment
  variable or console field to change that: their lines are few, and they are where an investigation of a login or an
  admin change ends up. A successful outbound call is `DEBUG` below 500 ms, as described further down.
- **The edge** is the only service whose access log is set, and it is set **in central, per edge**: in the admin console
  (an edge's page, section "Access log") or with `PATCH /configuration/edges/policies?edgeId=...` and an `accessLog` section (the body is described
  in `central/open-api/central.yaml`; `null` removes the policy). There is no environment variable for it, and no value in
  the edge's own configuration. A fresh edge, or one that has never reached central, uses the built-in defaults:
  `mode` `off`, `slowMillis` 500, `successSampleRate` 0.05. A policy field that is absent means that default.
- `mode` `full`: every request.
- `mode` `sampled`: a request is always logged when its status is 400 or above, when an error was recorded against it,
  or when it took `slowMillis` or longer. Other requests are logged for `successSampleRate` (one in 20 by default) of
  traces. The choice is a hash of the trace id, so every hop of a trace (edge, auth, central) keeps or drops its lines
  together, and a sampled trace is complete. A request that has no trace id is sampled at random.
- `mode` `off` (the edge's default, because its successful request lines are most of its log and the `http_server_*`
  metrics have them): only requests that failed (400 and above, or an error) and slow ones.
- The policy reaches a running edge on its next configuration sync (at most the refresh interval, 5 minutes by default).
  The edge keeps the last policy it received while central is unreachable. Central refuses an invalid policy with `400`;
  an edge ignores one it cannot validate (a central newer or older than the edge) and keeps what it had. Removing the
  policy returns the edge to the defaults above. It is independent of the overload policy, which is stored and served
  the same way but keeps an environment base because the outbound guard has to protect the process before the first sync.
- `HTTP_ACCESS_LOG`, `HTTP_ACCESS_LOG_SLOW_MS` and `HTTP_ACCESS_LOG_SAMPLE_RATE` are no longer read by any service; a value
  left in a compose file or a Helm `env` block is ignored, and an edge that relied on one is back to `off` until a policy
  is set for it. `LOG_LEVEL` is separate and stays an environment setting.
- A request that is not logged is built nowhere: no header or cookie collection, no record, no JSON.

`http_server_requests_total` and `http_server_request_duration_seconds` count every request in every mode, so rates,
error ratios and latency do not change; only a search for one successful request in the log can come up empty.

`send-http` lines for a successful call to one of the deployment's own services (central, auth, and edge's proxied
upstreams) are `DEBUG` when the call was faster than the slow threshold (`slowMillis`, 500 ms unless an edge's policy sets it), so they are not written at the default
`LOG_LEVEL`; a slow call and a 4xx are `INFO`, a 5xx or a transport failure `ERROR`, as before. Raise `LOG_LEVEL` to
`debug` to see all of them again. A call to any other peer (an OTP, SMS or e-mail provider, any third-party integration)
is external: its success is `INFO` whatever the access-log mode is, because that line is the record that a message was
handed over. A peer nobody classified is external. `http_client_requests_total` is unchanged.

### Configuration reference

| Variable | Default | Meaning |
|---|---|---|
| `PORT` / `APORT` / `DPORT` | `8080` / `8082` / `8081` | app / additional internal / diagnostics (`/metrics`, `/liveness`, `/readiness`) port |
| `BIND_HOST` | — | address to bind; the vps compose file sets `127.0.0.1` |
| `CONFIG_PATH` | `/app/config/env.conf` | the mounted HOCON config |
| `RUN_MIGRATIONS` | `false` | see below |
| `VERSOLA_CRYPTO_PROVIDER` | `accp-required` in the auth and edge images | `accp` (native if it works, else JDK), `accp-required` (refuse to start without it), `jdk`; jar in `VERSOLA_ACCP_JAR` |
| `LOG_LEVEL` | `info` | lowest level written: `trace`, `debug`, `info`, `warn`, `error`; successful `send-http` lines to our own services are `debug` |
| `JAVA_OPTS` | fixed budget in the images | overriding replaces the whole string: the images set `-Xms512m -Xmx512m`, `MaxDirectMemorySize` 64m (96m for edge), `MaxMetaspaceSize` 128m, `ReservedCodeCacheSize` 64m, `-Xss512k`, a heap dump and rotating GC log in `/diagnostics` |

The access log has no variables: auth and central always log every request, and an edge's is set in central (see [Access log](#access-log)).

Everything else — secrets, keys, database credentials, bootstrap data — is in the HOCON file and
OpenBao, not in environment variables.

**`env`** in the HOCON file names the environment. `prod`, and any name ending in it (`eu-prod`, what
`versola configure vps` writes from `ENV_NAME`, default `prod`), is production. **Any other name is a test
environment**: one-time codes are fixed and not delivered, an admin's password reset can show the password,
`/service/` is served and Postgres TLS need not verify. Nothing warns about the name, so a deployment that
serves users is named for what it is, never `qa`, `k8s` or a misspelling.

<a id="run_migrations"></a>
**`RUN_MIGRATIONS`**: unset → migrations do not run; the service *validates* the schema and fails to
start if migrations are missing (applying them is the separate `versola migrate` step). `true` /
`false` (lowercase) → as specified. Anything else → the service fails to start, deliberately. The
generated `compose.yml` sets `"false"` on all three services.

**Behind a connection pooler.** `central`, `edge` and, since `V0032`, `auth` keep a connection parked on `LISTEN` (that is
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
- **No supported way to change the rest of `auth.conf` on `vps`**, so its `smtp` and `otp-provider`
  blocks are not there. The delivery channels are the exception (versolauth/versola#832): the SMTP
  server and the SMS provider are set in the admin console, on **Delivery** (`security:manage`), kept in
  central (`/configuration/delivery-settings`, migration `V1033`, one new table, additive, no action) and
  picked up by auth at its next configuration refresh (5 minutes on `vps`) without a restart. Until
  one is saved, a `vps` deployment has no channel: password resets and additional admin accounts can't
  deliver a temporary password. Each channel is decided on its own, so a block that is not saved in
  central keeps using the matching block of `auth.conf`, if there is one. Upgrade in any order: an auth that
  is older does not read the record, and an auth that is newer than its central treats the missing
  endpoint as nothing saved.

  **Behaviour changes to know before upgrading** (the timeouts apply to every deployment, also one that sends
  through `auth.conf` only):
  - SMTP connections now give up after 10 seconds without an answer (connect, read and write), and the
    call to an SMS provider after 10 seconds in all. Neither had a limit; a server that was slower than
    that and worked will now fail.
  - A `PUT /configuration/delivery-settings` with an `otpProvider.url` that the HTTP client cannot parse is refused with `400`.
  - A block saved in central that `auth` cannot read (a password that does not decrypt under the shared secret, a sender
    or an address that is not valid) fails the sync. A running `auth` keeps what it has and logs a warning; an `auth` that
    starts while such a block is saved fails to start, also on a deployment that sends through `auth.conf` only. Correct or
    remove the record in the console (or `DELETE /configuration/delivery-settings`) and start it again.
  - An SMTP block with `startTls` on now **requires** the upgrade and verifies the server's certificate against
    `host`: a server that does not offer STARTTLS, or whose certificate is not for its name or not signed by a CA
    the JVM trusts, is refused and nothing is sent in clear. Before, the session quietly went on without TLS.
  - Every HTTPS call the services make themselves (the SMS provider, a relying party's back-channel logout URI, the
    ASN/geo range databases) now trusts the JVM's trust store, as any HTTPS client does; before, any certificate was
    accepted. The CAs the image ships cover a public certificate. A private CA goes into the trust store:
    mount it and pass `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/path/truststore.p12 -Djavax.net.ssl.trustStorePassword=…`
    to the service, or bake it into the image with `keytool -importcert -cacerts`. A refused peer shows as
    `PKIX path building failed` in the `send-http` line. Edge's calls to central pin a certificate and are not
    affected; its calls to auth over `versola-internal-url` use the `versola-internal-trusted-certificates` pin
    when it is set (as `gen-env` writes for a terminator) and the trust store otherwise, so an `https` internal
    URL with a private certificate and no pin needs either the pin or the trust store entry.
- **`central` has one replica.** `auth` and `edge` can run several (`versola replica add`), and
  `versola rollout` moves all three without an outage when the release is compatible (see
  [Without stopping](#without-stopping-versola-rollout)); an incompatible release still means the short
  outage of `versola up`.
- **The VM's security group is the network's default** (all inbound allowed); narrow it to the rules
  in [1](#1-overview).
- **Single zone, and the VM is a single point of failure.** `kz1-a` is the region's only zone; the
  VM's Docker volumes (OpenBao's data, the CA's keys) are backed up by nothing but your own copies of
  `vps-admin.json` and the dumps above.

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

### 6.4 502 or 504 from `id.versola.io`

Work inwards:

```bash
docker ps --format '{{.Names}}  {{.Status}}' | grep versola
curl -s -o /dev/null -w "%{http_code}\n" https://id.versola.io/.well-known/openid-configuration
curl -sf http://127.0.0.1:28091/readiness && echo central ok
curl -sf http://127.0.0.1:8081/readiness && echo auth ok
curl -sf http://127.0.0.1:8096/readiness && echo edge ok
```

Connection refused on 443 (`--proxy external`: nothing on `2821`) → `versola-proxy` is down
(`docker logs versola-proxy`; `versola up` starts it). 502 → the service behind it is down; check
`central` first. A bare `curl -sI https://id.versola.io/` returning **404** is expected.

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
`*.secrets.env`). Before it logs in, `versola configure`, `rollout` and `secrets plan|test|login` unseal
OpenBao themselves with the key in `vps-admin.json`, and `configure` also recreates a container whose
bundle was removed. Without that file (or with `--setup-openbao`):
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
`configure` the CA with `--postgres-ca <file>`, then `versola up`; do not edit the bundle (to make a CA for a self-hosted Postgres, see
[Postgres with your own CA](#postgres-own-ca)). Check with
`ls ~/.versola/active/bundle-*/postgres-ca/` (`ca.crt`) and
`grep ssl-root-cert ~/.versola/active/bundle-*/*.conf` (three hits).

### 6.12 `versola up` fails getting the certificate

Let's Encrypt must reach the host on port 80 through the `--auth-url` name. Check in order: DNS points
at this host (`dig +short id.versola.io`); behind Cloudflare the record is *DNS only* (a proxied one
answers with Cloudflare's addresses); the security group lets `80` and `443` in; nothing else listens
on them. Repeated failures hit Let's Encrypt's rate limits: rehearse with `--acme-staging`.

### 6.13 `password authentication failed for user "versola_app"`

Right after a password change, the managed pooler applies it a few seconds late: retry once. If it
persists, the value in OpenBao and the database differ — set the database's from `POSTGRES_PASSWORD`
in the bundle's `auth.secrets.env`.
