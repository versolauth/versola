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
| `step-ca`, `mtls-init` | Docker, same project: internal CA (one root, three intermediates) for client, edge and listener certificates ([mutual TLS](#mutual-tls-certificates)); `mtls-init` is a one-shot | `versola-cli` |
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

`auth` also has a mutual-TLS listener (`MPORT`, 8083, loopback). `central` has one too (`MPORT`, 8093, loopback), the only place edges reach it, and an enrollment listener (central's `APORT`, 8094 -- not auth's `APORT`, 8082 -- loopback, `POST /edge/enroll` only). Nothing of this is public unless you ask for it with `versola configure vps --mtls-domain`, `--central-mtls-domain` or `--central-domain` -- see [Mutual TLS certificates](#mutual-tls-certificates). The generated `compose.yml` is the
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

- **The antifraud event log (`af_event`, migration `V0018`)** is partitioned by day, and `auth` itself creates
  the partitions (today and three days ahead, at start-up and every six hours) and drops the ones older than
  `antifraud.retention` (14 days). The migration creates the parent only, and a partition can be created or dropped
  only by the role that owns the parent: that is the role `versola migrate` and the Helm migration Job use, which
  read the same `postgres.user` as the service, so it is `auth`'s own role. A deployment that migrates as a
  different role must run `ALTER TABLE af_event OWNER TO <auth's role>` once after `migrate`, or the service cannot
  keep the partitions and every write fails. The service takes at most 5 seconds for the lock the change needs
  (a long query on `af_event` makes it give up, not queue the writes behind it) and tries again after a minute.
  **Alert on `antifraud_partition_maintenance_failed_total` increasing**: a failed run is only a warning in the log,
  and once the days ahead are used up the events are dropped (`antifraud_events_dropped_total{reason="write_failed"}`).
  Events are queued in memory and written in batches: a full queue or a failed write drops events and counts them,
  never delays a sign-in. A restart loses what was queued and unwritten. Under an attack the log is a lower
  estimate: requests that were turned away (`throttled`, `locked`) give way first once the queue is half full
  (`reason="throttled_shed"`), and what is still dropped is counted, so read statistics together with that counter.
  Phone numbers and addresses are stored as a keyed hash made with `security.antifraud-subject-secret`
  (`ANTIFRAUD_SUBJECT_SECRET`, generated like the other secrets that only sign or authenticate): a new one breaks
  the match between events before and after it, nothing else. A config written before the secret existed has no
  such key and falls back on `security.sessions-secret`, so an upgrade needs no change, but rotating the sessions
  secret then changes the hashes too. `versola configure` generates the secret for a deployment that wants its own;
  the Helm chart reads it from the release's Secret as an optional key, so a release whose Secret predates it keeps
  starting (an `env.conf` regenerated to reference `${ANTIFRAUD_SUBJECT_SECRET}` needs the key in the Secret too: add the
  key together with the new config). **The hash is pseudonymous, not anonymous:** the space of phone numbers is small enough to be tried
  against it by anyone who holds both the table and the key, and with the user agent the rows are personal data.
  Deleting a user does not delete their events; they leave with `antifraud.retention`, which must not exceed the
  retention you promise for such data. Rows are not under `cleanup` (the table has no `expires_at`). Its keys are the
  optional `antifraud` block of `auth`'s config.

- **Antifraud lists (`af_list_entry` and `af_list_audit`, migration `V0020`)** hold values the antifraud rules
  recognise at once (a destination hash, a number prefix, an IP or network, an ASN, a device, a user-agent or JA4
  hash, a mail domain): `deny` blocks, `fake` answers as if the step succeeded, `watch` only adds to the score, `allow`
  outweighs score-based rules but never a `deny` of the same or a narrower value. The migration only adds tables. They
  are **off by default** (`antifraud.lists.enabled`, `false`): nothing is read and no rule is contributed until it is
  turned on. Once on, each instance reads the whole table every `antifraud.lists.refresh-interval` (5 seconds), so a
  change made through another instance shows within that time, and a refresh that fails keeps the previous lists
  (`antifraud_list_refresh_failed_total`; fail-open, so alert on it). Hit counters are kept in memory and written every
  `antifraud.lists.hit-flush-interval` (30 seconds), expired entries are removed then, in batches of 1000, and audit records older than `antifraud.lists.audit-retention` (365 days) too; a restart loses at most that
  interval of counts. `antifraud.lists.watch-weight` (20) and `allow-weight` (1000) are the score a `watch` adds and
  an `allow` takes off. The lists are changed through central's admin API (`/antifraud/lists`, `/antifraud/lists/preview`,
  `/antifraud/lists/audit`, see `central/open-api/central.yaml`), which names the caller and passes the request to `auth`'s internal
  `/antifraud/lists`; the console has no screen for them yet. Entries added by SQL skip the audit and the version bump (an
  instance still picks them up within a minute). The endpoints are new central resource endpoints with two permissions,
  `antifraud:read` and `antifraud:manage`: every start registers them on the `central` resource and gives them to `oauth-admin`
  (all permissions), `security` (both), and the read-only roles (`antifraud:read`, except `viewer`, which sees no PII), and nothing
  else changes for a deployment that is upgraded. Edge tells central who the admin is (inject rules `adminSubject` and `adminTenant`,
  query parameters of a `GET` and body fields of any other call, as for the account endpoints, from the verified token and overwriting what a client sent; so a removal is a `DELETE` with a JSON body naming the `id`): an admin whose token is of the `default` tenant (the one that carries
  central's roles) is an **operator** and may change global entries, other tenants' entries, and read the audit; an admin of any other tenant
  (central grants these permissions in the default tenant only; this is how a tenant's own admin would be limited) is limited to that tenant's entries. A call to central with the resource secret but
  no such parameters is `403`, so the audit always names a person. A `phone_hash` entry is made from a phone number (`phone`); the
  number is hashed in `auth` with `security.antifraud-subject-secret` and only the hash is stored.
  Safety rules the service enforces: `allow` only for an exact subject (phone hash, device, address, /32 or /128);
  entries that name many clients (anything but an exact subject, networks wider than /24 or /48) and automated ones
  (`source` other than `manual`) must expire; an enforcing entry that would have matched more than
  `antifraud.lists.max-traffic-share` (5%, 0 disables) of the last 24 hours of events (judged over at least
  `share-min-events`, 100) is refused, so add it as a `shadow` entry first (matched and counted, enforces nothing, shows as
  `shadow_list:<type>` in the fired rules). Only an operator may change global entries. **`fake` is for confirmed fraud only:**
  a real person on a faked number or device is told a code was sent and gets none, so have a support path for "why am
  I not getting a code". Hits are counted in `antifraud_list_hits_total{list_type,value_type}`. Starting waits up to
  `antifraud.lists.startup-timeout` (30 s) for the first successful load so that lists are enforced from the first
  request; if it fails it starts without them, logs it and retries every second. **Rotating
  `security.antifraud-subject-secret` silently stops every `phone_hash` entry from matching**: re-create them.

- **SMS budget and range statistics (`af_dest_stats`, migration `V0019`).** The service rolls `af_event` up every
  minute into `af_dest_stats` (per tenant, range of numbers — the first `antifraud.stats-prefix-length` digits, 4 by
  default, country code included — and minute: codes asked for, sent, refused, verified), keeps 30 days of it
  (`antifraud.stats-retention`) and reads it for nothing itself yet: it is what an operator or a report asks "which
  ranges send but do not convert" of. Changing the prefix length mixes lengths in the table; clear it first. Only one
  instance does a rollup at a time; a failed one is logged and counted (`antifraud_stats_rollup_failed_total`) and the
  next run recomputes the last `stats-lookback` (2 minutes). A BRIN index on `af_event (at)` keeps that read to the
  blocks of those minutes.
  Separately, `antifraud.sms-budget` closes a range (tenant plus the first `prefix-length` digits) for the next hour:
  `sent-per-hour` (that many codes may be sent to it, no more) and `min-volume` with `min-conversion` (a range with at
  least that many codes sent of which fewer than that share were verified). Both are **off by default** (`0`).
  **Think before turning one on:** a range is an operator's number block, possibly millions of numbers, and a closed
  range refuses *every* code to it, the legitimate users' included, for up to an hour. The refusal has to be the same
  for a number that is a user and one that is not (otherwise it would say which are), so existing users cannot be
  exempted. Where self-registration is open for a number, anyone can ask for codes to its range and leave them
  unverified, which closes it. For that reason the conversion breaker (`min-volume`) is never applied to a number
  whose region allows registration (`phoneRegions[].registration`); `sent-per-hour` is, so keep it well above the
  real peak. Where registration is closed, an unknown number gets no real code, is not counted, and so cannot close a
  range. The other side of that: a code for a user is counted and the stand-in for an unknown number is not, so
  someone who owns numbers in a range can bring its count to the limit and see by the next refusal whether a
  candidate number is a user. That oracle exists only where registration is closed, is slow (the range is closed for
  an hour by each hit, the asker's own numbers included) and costs the asker real codes; where enumeration of numbers
  matters more than the pumping it protects against, leave `sent-per-hour` off. A refusal ends the sign-in in "access denied", the event is `throttled`
  and `antifraud_sms_budget_refused_total` counts it. The counts are kept in memory per instance, over the last
  hour, so the limit holds to within the number of instances and starts again from zero after a restart. Only a code
  that is really sent is counted, in the same step as the check, and given back if it is then not sent. At most
  `max-ranges` (10000) ranges are tracked; beyond that new ones are let through untracked
  (`antifraud_sms_budget_full_total` counts them), so **the limit stops protecting a tenant whose attacker has filled
  the table of ranges**; alert on that counter. Enforcement of `phoneRegions` is unchanged: a number outside the
  tenant's regions is refused before the conversation takes it, whatever the budget says.

- **Velocity counters (`af_velocity`, migration `V0021`)** count requests per phone (the keyed hash), network of the IP
  address, device, ASN and range of numbers over the last minute, 10 minutes, hour and day, for the antifraud rules. They
  count *requests*, not distinct values: how many different phones one device, address or ASN asked for, or how many
  ranges one address tried, needs the device identity of P1-1 and is the next thing to build on them. They are **off
  by default** (`antifraud.velocity.enabled`); nothing is counted, written or read while they are, and no rule fires.
  The keys of the optional `antifraud.velocity` block of `auth`'s config: `max-keys` (20000 per kind), `stripes` (8),
  `flush-interval` (10 s), `load-interval` (20 s), `share-from` (3), `max-flush-rows` (20000, a strict cap, the busiest
  keys first), `max-load-keys` (20000 per query, the busiest first) and
  `thresholds.<phone|ip|device|asn|prefix>.<per-minute|per-ten-minutes|per-hour|per-day>` with `weight` (defaults: phone 50,
  device 60, prefix 30, ip 20, asn 20) and `thresholds.trusted-discount` (40): the most requests a key may have in the
  window, 0 for no limit; a rule fires above it and adds its `weight` to the score the `antifraud` thresholds turn into a
  decision. **Keep `ip` and `asn` weights low:** in carrier-grade NAT networks (common in KZ and the CIS) thousands of
  subscribers share one IPv4 address; a device key outweighs them, and ASN classes (mobile, hosting) can scale the limits
  once P1-3 provides them. A request of a known user (`device.known` or `session.recent_success` `true` in the rule
  context, filled by later tasks) that fired a velocity rule gets `trusted-discount` taken off its score.
  *Counting what is attacker-chosen.* Memory is bounded by `max-keys` per kind (five kinds). Estimated, not measured on a
  JVM: a key seen for the first time costs about 0.4 to 0.8 KB (its text, the map entry, and one 8-byte bucket per
  minute in each of up to `stripes` stripes), so a full table of 100,000 keys is about 50 to 80 MB; every minute a key is
  active adds 8 bytes in each stripe that recorded it, up to 1440 minutes, so a key busy all day and recorded from every
  stripe holds up to about 90 to 130 KB (8 stripes). The other instances' counts are held too, per instance and key,
  about the same again for each other instance at most. A load or a flush briefly holds a list of the rows moved
  (tens of MB at the most extreme). Lower `max-keys` or `stripes` to spend less.
  full table does not stop counting: a new key takes the place of the oldest key seen only once
  (`antifraud_velocity_evicted_total`; a steady rate is a flood of throwaway keys), and only when every key held
  has been seen again is the new key not counted, so its rules do not fire (`antifraud_velocity_untracked_total`).
  **Alert on `antifraud_velocity_untracked_total` increasing; any value above 0 is itself a sign of an attack or of a
  `max-keys` too small for the traffic.**
  *Accuracy.* Counts live in memory per instance; each instance writes its changed minutes to `af_velocity` every
  `flush-interval` and reads the others' every `load-interval`: the last few minutes each time, and one hour of the older
  day with them in turn (24 loads cover it, so a flush that was late is found within about 8 minutes); the first load
  reads the whole day, in 24 queries of an hour each, starting at a random moment within half a `load-interval` so that a deploy does not hit the database from every instance at once, so a count is the instance's own exactly plus the others' as of the last load: **stale by up to
  `flush-interval + load-interval`, accurate to within `(instances - 1) * (the key's requests per second at one
  instance) * (flush-interval + load-interval)`**, and by up to `share-from - 1` further requests per instance for a key
  too quiet to be shared. A window of n minutes is the current minute and the n-1 before it, so the **1-minute window is
  the current clock minute: a burst across a minute boundary is split, and up to about twice the limit can pass**. A
  restart keeps the counts the instance had written (they are read back as the others'); what it had not written yet
  is lost. A database that is down leaves per-instance counts and never slows a request: failures count in
  `antifraud_velocity_failed_total{what="flush"|"load"|"prune"}` and are logged once per outage.
  *The table.* `af_velocity` is `UNLOGGED` (rewritten every few seconds, worth a day at most): a crash of the database
  empties it, and the counts are per instance until it fills again. It is not on a replica. One instance at a time
  (an advisory lock) deletes the rows older than a day and an hour, every ten minutes, 10000 rows per statement with a
  rest between. Every flush updates rows (a dead tuple each): the table is created with a fillfactor of 70 and is
  vacuumed after 2% of it has died.
  *Personal data.* The key of a phone number is the keyed hash of `af_event`, **not scoped to a tenant**: one number
  is one key across tenants, so abuse of one tenant weighs on the next and a person's number is correlated across
  tenants; that is a privacy decision of the operator's, and scoping it per tenant means keying the hash per tenant.
  An IP address is stored as a keyed hash of its network (`/32` of an IPv4 address, `/64` of an IPv6 one; IPv4-mapped
  addresses are IPv4), made with a key derived from `security.antifraud-subject-secret` under a label of its own, so
  the table holds no address. **Rotating `security.antifraud-subject-secret` changes every phone and address key and so
  starts the counts over** (the old rows expire within a day). The address is the request's `auth.ip`: it is the client's only when the proxies in front set the header as expected.

- **Antifraud detectors and proposals (`af_proposal`, migration `V0022`).** Every `antifraud.detectors.interval` (1
  minute) each instance runs two detectors over the last minutes of `af_dest_stats` and **proposes** list entries; it
  never creates one. **Off by default** (`antifraud.detectors.enabled`, `false`), and each detector has its own
  `enabled`. The block is read whole: set every key of it (and of `prefix-anomaly` and `fake-verify`), or none.
  Defaults: `interval` 1 minute, `settle` 2 minutes (the newest minutes of the rollup are not read), `statement-timeout`
  10 s, `max-proposals` 20 per detector and run, `candidates` 200 (ranges looked at closely, busiest first), `open-for`
  12 hours (an undecided proposal then expires), `cooldown` 24 hours (a value approved or rejected is not proposed
  again for that long), `ttl` 24 hours (what an entry made from a proposal lives), `retention` 90 days, `batch-size`
  1000.
  `prefix-anomaly` proposes a range (tenant plus the first `stats-prefix-length` digits) for `fake` when in the last
  `window` (15 minutes) it was asked for at least `min-requests` (50) codes, `spike-factor` (5) times what the
  `baseline` (24 hours before the window) gives for a window of that length (at least `baseline-floor`, 5), at least
  `min-sent` (20) were sent and its conversion (verified over sent) is at most `max-conversion` (0.2) and, if the
  baseline sent `min-baseline-sent` (50), at most `conversion-drop` (0.5) times the baseline's. `fake-verify` proposes
  a range for `deny` when in its `window` (30 minutes) at least `min-fake-verifies` (5) codes were entered after a fake
  send and at most `max-verified` (0) real ones were verified. A range with no tenant is never proposed. Nothing
  proposes a value that is already open, was decided on lately, or is covered by an unexpired list entry of any type
  (a shorter prefix covers a longer one). Sequential numbers are not detected: the log keeps a number as a keyed hash
  and its first six digits, which cannot tell consecutive numbers from others.
  One instance runs a detector at a time (an advisory lock; the others skip it), each statement under
  `antifraud.detectors.statement-timeout` and on the service's own pool. `V0022` also adds `af_dest_stats.fake_verifies`
  with `DEFAULT 0` (the previous release's rollup does not name it); it counts `fake_verify` events. Proposals are
  counted in `antifraud_proposals_created_total{kind}`; **alert on** `antifraud_detector_failed_total{kind}` and
  `antifraud_proposal_housekeeping_failed_total` increasing: a failing detector is only a warning in the log. There
  is no way yet to approve a proposal: that, and creating the entry (`AntifraudProposal.toNewListEntry`), is a
  follow-up.

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

### Access log

Every service writes one `receive-http` line per inbound request and one `send-http` line per outbound call. They are
the largest part of the log volume, and rendering them cost about 17% of an edge's CPU and 12% of auth's in a JFR
profile under load, almost all of it for successful requests.

- `HTTP_ACCESS_LOG=full` (default): every request, as before.
- `HTTP_ACCESS_LOG=sampled`: a request is always logged when its status is 400 or above, when an error was recorded
  against it, or when it took `HTTP_ACCESS_LOG_SLOW_MS` (500) or longer. Other requests are logged for
  `HTTP_ACCESS_LOG_SAMPLE_RATE` (0.05, one in 20) of traces. The choice is a hash of the trace id, so every hop of a trace
  (edge, auth, central) keeps or drops its lines together, and a sampled trace is complete. A request that has no
  trace id is sampled at random.
- `HTTP_ACCESS_LOG=off`: only requests that failed (400 and above, or an error) and slow ones.
- auth ignores `HTTP_ACCESS_LOG*` and always logs every request (`accessLogConfigurable = false`); edge and central follow the setting.
- A request that is not logged is built nowhere: no header or cookie collection, no record, no JSON.

`http_server_requests_total` and `http_server_request_duration_seconds` count every request in every mode, so rates,
error ratios and latency do not change; only a search for one successful request in the log can come up empty.

`send-http` lines for a successful call to one of the deployment's own services (central, auth, and edge's proxied
upstreams) are `DEBUG` when the call was faster than `HTTP_ACCESS_LOG_SLOW_MS`, so they are not written at the default
`LOG_LEVEL`; a slow call and a 4xx are `INFO`, a 5xx or a transport failure `ERROR`, as before. Raise `LOG_LEVEL` to
`debug` to see all of them again. A call to any other peer (an OTP, SMS or e-mail provider, any third-party integration)
is external: its success is `INFO` whatever `HTTP_ACCESS_LOG` says, because that line is the record that a message was
handed over. A peer nobody classified is external. `http_client_requests_total` is unchanged.

Invalid values fall back to the defaults.

### Configuration reference

| Variable | Default | Meaning |
|---|---|---|
| `PORT` / `APORT` / `DPORT` | `8080` / `8082` / `8081` | app / additional internal / diagnostics (`/metrics`, `/liveness`, `/readiness`) port |
| `BIND_HOST` | — | address to bind; the vps compose file sets `127.0.0.1` |
| `CONFIG_PATH` | `/app/config/env.conf` | the mounted HOCON config |
| `RUN_MIGRATIONS` | `false` | see below |
| `VERSOLA_CRYPTO_PROVIDER` | `accp-required` in the auth and edge images | `accp` (native if it works, else JDK), `accp-required` (refuse to start without it), `jdk`; jar in `VERSOLA_ACCP_JAR` |
| `HTTP_ACCESS_LOG` | `full` | `receive-http` lines: `full` (every request), `sampled`, `off`; see [Access log](#access-log) |
| `HTTP_ACCESS_LOG_SLOW_MS` | `500` | `sampled`: a request at least this slow is always logged |
| `HTTP_ACCESS_LOG_SAMPLE_RATE` | `0.05` | `sampled`: share of traces whose successful requests are logged (0.05 = 1 in 20) |
| `LOG_LEVEL` | `info` | lowest level written: `trace`, `debug`, `info`, `warn`, `error`; successful `send-http` lines to our own services are `debug` |
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
