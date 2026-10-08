## Environment Config Generation

The `scripts/gen-env.scala` script generates HOCON config files for all three services
(`auth`, `central`, `edge`) with freshly generated RSA-2048 key pairs and random secrets.
It requires [scala-cli](https://scala-cli.virtuslab.org/install).

```bash
scala-cli run scripts/gen-env.scala
```

The script first asks for the environment **Name** (default `local`):

- **`local`** — runs non-interactively. All remaining prompts are skipped and defaults are
  used (`localhost`-based — auth/central/edge are assumed to share one network, e.g. run
  directly via `sbt` or with `network_mode: host`). Files are written to the service dev
  directories consumed by `sbt` (see below):
    - `auth/dev/env.conf`
    - `central/dev/env.conf`
    - `edge/dev/env.conf`
- **`docker-local`** — also runs non-interactively, for the case where auth/central/edge
  each run in their own container on one Docker Compose bridge network, sitting behind
  nginx as the single published port (used by `versola bootstrap local`). Files are
  written to `.local/env/docker-local/` (see below).

  Defaults split into two kinds, and it matters which is which:
    - **Real network calls between containers** (Postgres, `central`'s and `edge`'s calls
      to `auth`'s admin API) point at the other container's Compose service name, e.g.
      `http://auth:8080`, `jdbc:postgresql://postgres:5432/...` — containers on a bridge
      network can't reach each other via `localhost`.
    - **Anything a browser has to load** (auth/edge's own public URLs, the post-login
      redirect) points at nginx's published port instead, `http://localhost:8080` — a
      browser outside the Compose network has no way to resolve `auth` or `edge` as
      hostnames, and edge's own port isn't published to the host at all.

    `edge` needs both at once for one thing: `EdgeConfig.versolaUrl` (public — the
    browser redirect, and the token `iss` check) vs `EdgeConfig.internalUrl` (real
    network call — edge's own token/userinfo exchange with `auth`), which resolves
    from the optional `EdgeConfig.versolaInternalUrl` field and falls back to
    `versolaUrl` when it's absent, so configs generated before this field existed
    keep working. Getting this backwards doesn't fail loudly; it 404s or
    connection-refuses partway through a login that otherwise looks like it's
    working — confirmed by hand while testing `versola bootstrap local`.
- **`vps`** — also runs non-interactively, for a real server deployed with
  `versola bootstrap vps` / `versola configure vps` (see [`deploy.md`](deploy.md)).
  auth/central/edge run with `network_mode: host` there, and Postgres is a native
  install on the server rather than a container this manages, so real network calls
  point at `127.0.0.1` (Postgres at `POSTGRES_HOST`) instead of a Compose service name,
  and the public URL is the actual domain (`AUTH_URL`, e.g. `https://id.versola.kz`)
  instead of a local port. The reverse proxy in front of them is not configured here:
  `versola-cli` generates and runs it. Files are written to `.local/env/vps/`.

  Every secret field is a `${VAR}` placeholder here too, same as `docker-local` —
  see "Secrets (OpenBao)" below. vps additionally placeholders Postgres's password
  and the admin bootstrap password, which `docker-local` doesn't: `docker-local`'s
  Postgres is a throwaway container this same run also creates, while vps's Postgres
  role lives on a server this script doesn't control — the password generated here
  becomes the real one only on the first `configure` against an empty OpenBao, and
  `versola-cli` then prints the SQL to give the role that password (deploy.md, 3.3).
- **any other name** — runs interactively, prompting for service URLs and Postgres
  credentials. Files are written to `.local/env/<name>/` (`auth.conf`, `central.conf`,
  `edge.conf`).

## Local Development

1. Compilation - `compile`
2. Test compilation - `Test / compile`
3. Run tests - `test`. First, you need to start postgres - `docker-compose -f services.yml up -d postgres`
4. ```bash
    cd central-ui
    npm install
    npm run build:forms   # compile auth forms into central/src/main/resources/forms
    npm run dev           # run admin dashboard on port 3000
    ```
5. Start server locally
    - `docker-compose -f services.yml up -d postgres` - Database
    - `docker-compose -f services.yml up -d jaeger` - Jaeger (optional)
    - Each service below needs `RUN_MIGRATIONS=true` against a fresh Postgres --
      without it, a service only *validates* its schema on startup rather than
      applying migrations to it (deliberate for real deployments, see
      [deploy.md's `RUN_MIGRATIONS`](deploy.md#run_migrations)), which fails immediately with no schema yet.
    - `PORT=9001 DPORT=9002 RUN_MIGRATIONS=true sbt -Denv.path=central/dev/env.conf "project central-postgres-impl; run"` - Central
    - `PORT=9003 DPORT=9004 APORT=9007 MPORT=9008 RUN_MIGRATIONS=true sbt -Denv.path=auth/dev/env.conf "project auth-postgres-impl; run"` - Auth
      (`MPORT` only does anything when `auth/dev/env.conf` carries a `mutual-tls` block -- see below)
    - `nginx -c "$(pwd)/edge/dev/internal-tls/nginx.conf"` - the TLS terminator in
      front of auth. Edge reaches auth through it (`versola-internal-url`), because
      an RFC 8705 client certificate is presented in a handshake and auth reads one
      only from a header. Start it before edge; without it every edge -> auth call
      is refused a connection.
    - `PORT=9005 DPORT=9006 RUN_MIGRATIONS=true sbt -Denv.path=edge/dev/env.conf "project edge-postgres-impl; run"` - Edge
    - `central-admin` is registered as `tls_client_auth` by edge (#353: the `default` tenant is on
      the FAPI 2.0 security profile, which admits no `client_secret` client). Its certificate
      is `edge/dev/internal-tls/central-admin.{crt,key}`, signed by the terminator's CA and
      carried in `central/dev/env.conf`'s `bootstrap.central-admin-mtls`; edge presents it to
      the nginx terminator above, which forwards it to auth in `ssl-client-cert`. So the
      terminator is not optional for the console login: without it edge cannot push
      `central-admin`'s authorization request to `/par`. `gen-env.scala` needs an OpenSSL 3
      `openssl` on `PATH` for this (macOS's LibreSSL lacks `-copy_extensions`), e.g.
      `PATH=/opt/homebrew/opt/openssl@3/bin:$PATH`.
      docker-local/vps/interactive targets get no `central-admin-mtls` and no terminator, so
      central keeps `central-admin` on its `client_secret` there and logs that it is outside the
      tenant's profile.
    - go to http://localhost:9005/login/central-admin
    - enter admin/Admin1234!
    - enter otp code 123456

## Docker

### Build Locally

The runtime images no longer bundle sbt -- `docker build` copies an already
built application from `<module>/target/universal/stage` (same as CI, see
`ci-cd.yml`'s "Stage services for release images" step), it doesn't build it.
Stage the module first, then build:

```bash
sbt "auth-postgres-impl/stage"
docker build -t versola-auth -f docker/Dockerfile.auth .
```

Run the Docker image (mount config file):
```bash
docker run -p 8080:8080 -p 8081:8081 \
  -v $(pwd)/auth/dev/env.conf:/app/config/env.conf:ro \
  versola-auth
```

To build central or edge locally:
```bash
sbt "central-postgres-impl/stage"
docker build -t versola-central -f docker/Dockerfile.central .

sbt "edge-postgres-impl/stage"
docker build -t versola-edge -f docker/Dockerfile.edge .
```

(`central` additionally expects `central-ui`'s forms already built into
`central/src/main/resources` -- see the `npm run build:forms` step above --
before staging, same as CI's `build` job.)

You can override the config path via `CONFIG_PATH` environment variable:
```bash
docker run -p 8080:8080 -p 8081:8081 \
  -v /path/to/your/env.conf:/custom/path/env.conf:ro \
  -e CONFIG_PATH=/custom/path/env.conf \
  versola-auth
```

(The Dockerfiles `EXPOSE 8080 9345`, but the diagnostics server listens on
`DPORT`, 8081 by default — publish that one.)

## Secrets (OpenBao)

`docker-local` and `vps` never write a secret's real value into
auth.conf/central.conf/edge.conf — every secret field (JWT signing key,
session/cookie secrets, Postgres password, admin bootstrap password, etc.) is a
`${VAR}` HOCON placeholder instead (see gen-env.scala's `secretField`/
`secretKeyField` for why it's a required, not optional, substitution).
`versola-cli` resolves each one against an [OpenBao](https://openbao.org/)
server before starting anything: an existing value there wins over the freshly
generated candidate, so the same secrets survive every reconfigure; a missing one
is generated and stored. The result goes into `<service>.secrets.env` next to
the configs, which Compose passes to the containers — the services themselves
never talk to OpenBao. See `versola-cli`'s `internal/openbao` and
`internal/deploy/secrets.go`.

Secrets live at `secret/versola/<target>/{auth,central,edge}` (KV v2), where
`<target>` is `local` or `vps` — versola-cli's target names, not `docker-local`.

The private key of the `utils` client (`loadgen provision`'s `provision.provisioner-private-key`)
is the one value stored apart from those three paths, at `secret/versola/<target>/utils`, because
nothing under a service's path may be a key central must not hold. A stored key wins over the one
gen-env generates each run, and central's public half is taken from it, so the pair survives every
reconfigure. `configure` leaves the resolved key in the bundle directory as `utils.private-key.jwk`
(mode 0600).

### Setup is automatic

`versola configure <target> <version>` (and `bootstrap`) starts the
`versola-openbao-<target>` container and, on its first run, **provisions it
itself** for both targets: init with a single unseal key, unseal, enable KV v2
and AppRole, write a policy limited to `secret/data/versola/<target>/*`, create
the `versola-<target>` role and store its credentials. Files it keeps in
`~/.versola/openbao/`:

- `<target>.json` — the AppRole `role-id`/`secret-id` it reads and writes
  secrets with (also printed on every `configure`);
- `<target>-admin.json` — the root token and unseal key. OpenBao comes back
  **sealed** after every container restart (no auto-unseal); `configure`
  unseals it with this key, and recreates the container if it was stopped or
  its `openbao.hcl` was removed with an old bundle. **Keep a copy of both
  values somewhere safe** — neither is recoverable if lost. On `vps`, if saving
  this file fails right after init, `configure` stops rather than printing a
  root token into a server terminal; the instance is then unusable and its
  volume has to be recreated.

The container and its data volume (`versola-openbao-file-<target>`) are named
per target, so a machine that has run both can't mix them up. Both targets bind
OpenBao to port 8200, so only one of them can run at a time on one machine —
`configure` says so if the other one is still up.

### Doing it by hand: `--setup-openbao`

For teams that want to run OpenBao themselves, `configure vps`/`bootstrap vps`
accept `--setup-openbao`: the CLI then never calls OpenBao's admin API and
expects AppRole credentials stored with `versola secrets login vps` beforehand.
Unsealing after a restart is then also yours to do.

TLS is disabled (see `openbao.hcl.template`), but `bao`'s own default is
https — every command below needs `BAO_ADDR` set explicitly, or it fails with
"server gave HTTP response to HTTPS client". `<address>` is `127.0.0.1:8200`
on `vps`.

```bash
# 1. Initialize (first time only). Save BOTH the unseal key and the root token.
docker exec -it -e BAO_ADDR=http://<address> versola-openbao-vps \
  bao operator init -key-shares=1 -key-threshold=1

# 2. Unseal -- again after every container start/recreation.
docker exec -it -e BAO_ADDR=http://<address> versola-openbao-vps \
  bao operator unseal <unseal key>

# 3-4. KV v2 and AppRole (need the root token).
docker exec -it -e BAO_ADDR=http://<address> -e BAO_TOKEN=<root token> versola-openbao-vps \
  bao secrets enable -path=secret kv-v2
docker exec -it -e BAO_ADDR=http://<address> -e BAO_TOKEN=<root token> versola-openbao-vps \
  bao auth enable approle
```

5. A policy scoped to this target's own secrets only:

   ```bash
   docker exec -i -e BAO_ADDR=http://<address> -e BAO_TOKEN=<root token> versola-openbao-vps \
     bao policy write versola-vps - <<'EOF'
   path "secret/data/versola/vps/*" {
     capabilities = ["create", "read", "update"]
   }
   EOF
   ```

   On Windows PowerShell, write it to a local file first with `-Encoding ascii`
   (the default `utf8` adds a BOM that breaks OpenBao's HCL parser with "illegal
   char" at 1:1), `docker cp` it in and `bao policy write versola-vps <path>`.

```bash
# 6. An AppRole role bound to that policy (long-lived credential for an unattended tool).
docker exec -it -e BAO_ADDR=http://<address> -e BAO_TOKEN=<root token> versola-openbao-vps \
  bao write auth/approle/role/versola-vps \
    token_policies="versola-vps" token_ttl=1h token_max_ttl=4h \
    secret_id_ttl=0 token_num_uses=0

# 7. The credentials versola-cli needs.
docker exec -it -e BAO_ADDR=http://<address> -e BAO_TOKEN=<root token> versola-openbao-vps \
  bao read auth/approle/role/versola-vps/role-id
docker exec -it -e BAO_ADDR=http://<address> -e BAO_TOKEN=<root token> versola-openbao-vps \
  bao write -f auth/approle/role/versola-vps/secret-id
```

Then store them on the machine that runs `versola configure` (the server
itself, for `vps`). `<secret-id>` is a separate masked prompt, not an argument,
so it never lands in shell history or `ps`:

```bash
versola secrets login vps http://<address> <role-id>
```

### Changing a stored secret

`bao kv patch` merges into a path; `bao kv put` **replaces the whole path**
and would wipe every other key there. Then run `versola configure <target>
<version>` again with the same flags as the deployment (for `vps`, see
[`deploy.md`](deploy.md#4-deploying-a-new-version)) and `versola up`, so the
new value reaches the services:

```bash
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao-vps \
  bao kv patch -mount=secret versola/vps/<service> KEY='<value>'
```

`POSTGRES_PASSWORD` is shared by all three services (one Postgres role) and
must be identical under `auth`, `central` and `edge`; `configure` refuses to
continue if the stored values disagree.

### Onboarding a deployment that already has secrets of its own

A fresh install needs none of this. It matters only when moving a deployment
that already runs with its own keys (from before OpenBao) under `versola-cli`:
several values gen-env.scala would generate fresh already have real, in-use
counterparts that a fresh one won't match, and each mismatch fails differently:

- **`POSTGRES_PASSWORD`** — the existing Postgres role's real password
  (wrong → connection refused at startup).
- **`JWT_PRIVATE_KEY`** — central is the source of truth for signing keys
  (`JwksRepository`; auth only caches a synced copy). Central's bootstrap
  seeding adds a key by `kid` rather than replacing, so a fresh one ends up
  *alongside* the real one, and which of the two signs new tokens depends on
  list order (`JWT.PublicKeys.active`) — it can work today and break after a
  restart. Seed the real key so only one `kid` is in play.
- **`CLIENT_SECRETS_SECRET`** — existing OAuth client secrets in central are
  encrypted with it; a fresh one can't decrypt them.
- **`EDGE_PRIVATE_KEY` / `EDGE_KEY_ID` / `EDGE_PUBLIC_JWK` / `JWKS_JSON`** —
  central already trusts the real edge's public key; a fresh edge key pair makes
  every edge→central sync call 401. `JWKS_JSON` is auth's public key wrapped as
  `{"keys":[<jwk>]}`, whose `kid` must match `JWT_PRIVATE_KEY`.

Existing values in OpenBao always win, but with automatic setup OpenBao only
exists once `configure` has run — and that same first run already stores
generated values. So the order is:

1. Run `versola configure vps …` once. It provisions OpenBao and stores
   generated values. It also prints a `CREATE ROLE`/`ALTER ROLE` for a new
   Postgres password — **don't run it**: the existing role keeps its real
   password, which goes into OpenBao in the next step. Don't run `migrate`/`up`
   yet.
2. Overwrite the generated values with the real ones (`kv patch`, root token
   from `~/.versola/openbao/vps-admin.json`):
```bash
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao-vps \
  bao kv patch -mount=secret versola/vps/auth \
    POSTGRES_PASSWORD='<real password>' JWT_PRIVATE_KEY='<real private key, base64>' \
    CLIENT_SECRETS_SECRET='<real value>'
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao-vps \
  bao kv patch -mount=secret versola/vps/central \
    POSTGRES_PASSWORD='<real password>' CLIENT_SECRETS_SECRET='<real value>' \
    EDGE_PUBLIC_JWK='<real public JWK, single-line JSON>' JWKS_JSON='{"keys":[<real auth public JWK>]}'
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao-vps \
  bao kv patch -mount=secret versola/vps/edge \
    POSTGRES_PASSWORD='<real password>' EDGE_PRIVATE_KEY='<real private key, base64>' EDGE_KEY_ID='<real kid>'
```

3. Run the same `versola configure vps …` again — it now resolves the real
   values into the bundle — then `versola migrate` and `versola up`.

With `--setup-openbao` instead, nothing is stored before you do it: provision
OpenBao by hand, write the real values with `bao kv put` (a path's very first
write — `kv patch` fails on a path that doesn't exist yet), then `configure`.
`ADMIN_BOOTSTRAP_PASSWORD` is deliberately not in this list:
nothing outside gen-env.scala owns that value, so the generated one is correct.

If containers named `versola-auth`/`versola-central`/`versola-edge` are already
running under a different Compose project, the first `configure`/`up` fails
with a container-name conflict (the vps compose file is always project
`versola-vps`). Stop and remove those containers by name first — nothing
stateful lives in them (Postgres is native, OpenBao's volume is external).

## CI/CD Pipeline

`.github/workflows/ci-cd.yml`:

1. **On every push and PR to `main`** — builds and tests (`build` job).
2. **On a published release** — builds and pushes the images to
   `ghcr.io/versolauth/`, tagged with the release tag verbatim (no `v`):
   `versola-auth`, `versola-central`, `versola-edge`, and `versola-tools`, which
   also carries this release's admin console (`central-ui` is built in the
   `docker-tools` job) and every service's migrations.
3. **Manually (`workflow_dispatch`)** with a `test_tag` input — the same image
   builds, published under that tag, for trying a build on a server without
   cutting a release. The tag can't be `latest` or an existing git tag.

Nothing here deploys: production is updated with `versola-cli` from the server
itself — see [`deploy.md`](deploy.md#4-deploying-a-new-version).

## HTTP Server

Metrics, liveness, and readiness probes are served on the diagnostics port (`DPORT`, default 8081):
- `GET /metrics`
- `GET /liveness`
- `GET /readiness`

The application API is served on the main port (`PORT`, default 8080). Services may expose a
separate internal application surface on `APORT` (default 8082); auth uses it for Account Settings.
Auth alone may also expose a third surface on `MPORT` (default 8083): RFC 8705 §5's mutual-TLS
listener, terminating TLS itself and demanding a client certificate on the handshake, rather
than reading one from a header a proxy forwarded. It is served only when `auth/dev/env.conf`
carries a `mutual-tls` block -- absent one, `MPORT` does nothing. `scripts/gen-env.scala`'s
`local` target writes that block, plus the certificate it presents and a client certificate
signed by the same CA (`auth/dev/mtls/{ca,server,client}.{crt,key}`) for e2e's own use --
see `MutualTlsListenerSpec`. The `docker-local`/`vps`/`k8s` configs do carry the block, and the bundle / chart
issue the certificate it names -- see [Certificate storage and rotation](#certificate-storage-and-rotation-440).

The ports are configured via `PORT`, `DPORT`, `APORT`, and `MPORT` environment variables.
### Native apps through edge (#420)

`edge/dev/env.conf`'s `native { ... }` block (written by the `local` target) turns on edge's
`POST /native/{start,complete,token,revoke}/{clientId}` endpoints: edge authenticates to auth
as an edge-fronted native client (`applicationType = native`, `tls_client_auth`) straight on
auth's `MPORT` listener, pinning `auth/dev/mtls/server.crt`. The client certificate edge
presents is the `edgeClientCertificate` central syncs, and must be issued by a CA in auth's
`mutual-tls.trusted-certificates` -- zio-http hands auth the leaf only. Without the block every
native endpoint answers 404.

A registration can ask central to issue that certificate (`issueEdgeClientCertificate`, #440)
instead of supplying it: central signs one from its `client-certificate-authority` and registers
the client `tls_client_auth` by its subject (`CN=<client>,OU=<tenant>,O=Versola`). The `local`
target points that CA at the terminator's (`edge/dev/internal-tls/ca.{crt,key}`), the one issuer
nginx advertises, and writes `auth/dev/mtls/trusted-clients.crt` -- the listener's own CA plus the
terminator's -- as the listener's `trusted-certificates`, so one issued certificate serves an
edge-fronted web client through nginx and a native one on `MPORT`. Without a
`client-certificate-authority`, central refuses such a registration.
### Certificate storage and rotation (#440)

Three certificate relationships exist in the mTLS work, with different rotation properties:

| | What | Shape in config | Rotation |
|---|---|---|---|
| 1 | auth's trust anchor for incoming client certificates (`mutual-tls.trusted-certificates`) | PEM path; the file may hold **several CAs** | overlap window: keep outgoing + incoming CA in the file, reissue clients, drop the outgoing CA |
| 2 | edge's pin on auth's own listener certificate (`native.trusted-certificates`, `versola-internal-trusted-certificates`) | **list** of PEM paths, each a **leaf** (a CA is refused at startup, in every certificate of every file) | overlap window: publish `[old leaf, new leaf]` on every edge, cut auth over to the new certificate, drop the old pin |
| 3 | client certificates of edge-fronted clients | the edge enrols for it (`enrollEdgeClientCertificate`, key on the edge) -- or `edgeClientCertificate` in central (PEM + PKCS#8 key) | 14-day certificates; the edge renews at a third of the lifetime left, or central renews the one it issued at 4 days left |

**(1) CA rotation.** Netty loads every certificate in the file, so no code is involved --
`ClientCaBundleRotationSpec` proves that clients issued by either CA connect while both are in
the file and that dropping one ends it. The order matters: add the incoming CA and restart auth
(it reads the file at startup); reissue every client certificate from the incoming CA and let
`cert-sync` (or a human) put it into central; only then remove the outgoing CA. Under Kubernetes
trust-manager maintains that union (`pki.trustBundle`, [k8s/README.md](k8s/README.md)).

**(2) Pin rotation.** Both fields are lists, e.g.

```hocon
native {
  trusted-certificates = ["/certs/auth-mtls-old.crt", "/certs/auth-mtls-new.crt"]
}
versola-internal-trusted-certificates = ["/certs/auth-internal.crt"]
```

and the TLS client accepts a server certificate matching *any* entry. Roll it out as: (a) put
`{old, new}` on every edge and restart them; (b) switch auth to the new certificate and restart
it; (c) once every edge has the new set, drop the old entry. Edge reads the files at startup, so
a changed file needs a restart. A single-element list is the previous behaviour; the previous
bare-string form (`trusted-certificates = "/path"`) no longer parses -- wrap it in `[...]`.

**(3a) Client certificates the edge enrols for (#463) -- preferred.** Register an edge-fronted client with
`"enrollEdgeClientCertificate": true` (and `mtlsAuth`, or none for `CN=<client>,OU=<tenant>,O=Versola`).
Central stores **neither a certificate nor a key** -- only that the client is enrolled
(`edge_certificate_enrollment`, with when and for which edge the last certificate was signed). Each edge
replica generates its own EC P-256 key (held in the process and sent nowhere; the TLS stack needs a file, so it is written to the owner-only directory `ClientCertificateFiles` removes on a graceful shutdown), and the client sync tells it
what the certificate must say (`edgeCertificateSubject`); it sends a PKCS#10 request for exactly that to
`POST /configuration/clients/edge-certificate/sign`, authenticated as the edge itself (the signed token it
syncs with -- only a registered edge may call it, and it is not on the admin API). Central checks the client
is enrolled and that the request is signed by its own key and names exactly the registered subject and
alternative names, has the CA sign it (below), checks the result carries what `mtlsAuth` expects, and
returns the chain. The edge renews when a third of the lifetime is left -- on its ordinary client sync, so
no new schedule -- keeps the current certificate if the CA is briefly unavailable, and enrols again after a
restart. Replicas hold different certificates carrying the same subject, which is what auth recognises the
client by. Mutually exclusive with `issueEdgeClientCertificate` and with supplying `edgeClientCertificate`, and only for a native app fronted by edge (`applicationType: native`): a web client's tokens are bound to its certificate (RFC 8705 §3) and replicas enrolling separately would not share it, so a web client takes `issueEdgeClientCertificate`.

**(3b) Client certificates issued by central** (`issueEdgeClientCertificate`), where central generates the key
pair and stores the certificate and key encrypted, then renews it itself. Kept for deployments that have
clients registered this way; (3a) is the one to use for new ones.

**Issuing, and the CAs both modes sign through.** For (3b), register an edge-fronted client with
`"issueEdgeClientCertificate": true` (and `mtlsAuth`) and central generates an EC P-256 key pair and
a PKCS#10 request whose subject and SAN are built from the `mtlsAuth` it registers, has a CA sign it,
validates that the result names what `mtlsAuth` expects, and stores it as `edgeClientCertificate`. It
renews every certificate it issued once less than `renew-before` remains (checked every
`check-interval`), and `POST /configuration/clients/edge-certificate/renew?clientId=` renews one now.
A certificate you supply yourself through an update stops central renewing that client's.

Where `mtlsAuth` is left out, the client is recognised by the subject `CN=<client>,OU=<tenant>,O=Versola`
(what a registration has always got from `client-certificate-authority`, above). Central normally
holds **no CA key**: the signing is delegated through `ClientCertificateIssuer`, configured by the
`client-certificates` block of `central.conf`:

```hocon
client-certificates {
  validity     = "14 days"
  renew-before = "4 days"
  # exactly one of:
  step-ca       { url = "https://step-ca:9000", root-certificate = "/app/ca/root_ca.crt",
                  provisioner = "central", provisioner-key = "/app/ca/provisioner.json" }
  cert-manager  { issuer-name = "versola-client-ca" }   # + issuer-kind, issuer-group, namespace
}
```

- **step-ca** (docker-local, vps): a one-time token from a JWK provisioner (`central`, which the
  compose file creates with a 14-day maximum and a template that keeps the request's subject and
  limits the certificate to client authentication). The provisioner key is the only thing central
  holds; a CA created before that template existed keeps the default one, which rewrites the subject
  to the bare CN -- central then refuses a longer `subject_dn` registration with that reason.
- **cert-manager** (k8s): a `CertificateRequest` created with the pod's service account, which may only
  create, get and delete those in its namespace.
- **`client-certificate-authority`** (the `local` stack, or any deployment that already has one): the one
  backend where central holds the CA's certificate *and key*. Used when no `client-certificates` block
  names a CA; it signs the request itself, leaf only, and gets the same renewal. Prefer the other two.

Which clients central issued for is recorded in `client_certificate_issuance` (serial, expiry; no key
material) -- the beginning of the audit trail #462 designs. Generating keys on edge instead of in
central is #463.

**Externally issued certificates.** `versola-tools`' `cert-sync` command (`migrate-tool`'s
`CertSyncTool`) still copies certificates something else issued (cert-manager through the chart's
`pki.clients`, or by hand) into central: it reads `$CERT_SYNC_DIR/<client-id>/tls.{crt,key}` and `PUT`s
`/configuration/clients` with only `edgeClientCertificate` whenever the content changes
(`CENTRAL_URL`, `CENTRAL_SECRET`/`CENTRAL_SECRET_FILE`/`CENTRAL_RESOURCE_SECRET`,
`CERT_SYNC_INTERVAL_SECONDS`; `0` runs one pass). Off by default. Keys must be unencrypted PKCS#8.
