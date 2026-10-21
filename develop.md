# Development

How to run, test and build Versola locally. For production see [`deploy.md`](deploy.md); for
Kubernetes see [`k8s/README.md`](k8s/README.md).

1. [Running from source](#running-from-source)
2. [Testing and formatting](#testing-and-formatting)
3. [Environment config generation](#environment-config-generation) — `scripts/gen-env.scala`
4. [Docker images](#docker-images)
5. [Services, ports and certificates](#services-ports-and-certificates) — probes, native apps, mTLS
6. [Secrets (OpenBao)](#secrets-openbao)
7. [CI/CD pipeline](#cicd-pipeline)

---

## Running from source

Requires a JDK, [sbt](https://www.scala-sbt.org/), Docker,
[scala-cli](https://scala-cli.virtuslab.org/install), nginx, Node.js/npm and an OpenSSL 3 `openssl`
on `PATH` (macOS's LibreSSL lacks `-copy_extensions`; e.g. `PATH=/opt/homebrew/opt/openssl@3/bin:$PATH`).

1. **Generate the dev config.** Answer `local` at the Name prompt
   ([details](#environment-config-generation)); it writes `auth/dev/env.conf`, `central/dev/env.conf`,
   `edge/dev/env.conf`, and the certificates under `edge/dev/internal-tls/` and `auth/dev/mtls/`:
   ```bash
   scala-cli run scripts/gen-env.scala
   ```
2. **Start Postgres** (and optionally Jaeger):
   ```bash
   docker-compose -f services.yml up -d postgres
   docker-compose -f services.yml up -d jaeger
   ```
3. **Build the login forms** that `central`, `auth` and `util` need (generated, gitignored):
   ```bash
   cd central-ui
   npm install
   npm run build:forms   # forms -> central/src/main/resources/forms, common.css -> util/src/main/resources/versola/forms
   npm run dev           # optional: the admin dashboard on port 3000
   ```
4. **Start the services**, each in its own terminal. They need `RUN_MIGRATIONS=true` against a fresh
   Postgres: without it a service only *validates* its schema on startup (deliberate for real
   deployments, see [`RUN_MIGRATIONS`](deploy.md#run_migrations)) and fails at once.
   ```bash
   PORT=9001 DPORT=9002 MPORT=9011 RUN_MIGRATIONS=true sbt -Denv.path=central/dev/env.conf "project central-postgres-impl; run"
   PORT=9003 DPORT=9004 APORT=9007 MPORT=9008 RUN_MIGRATIONS=true sbt -Denv.path=auth/dev/env.conf "project auth-postgres-impl; run"
   nginx -c "$(pwd)/edge/dev/internal-tls/nginx.conf"      # TLS terminator in front of auth; start it before edge
   PORT=9005 DPORT=9006 RUN_MIGRATIONS=true sbt -Denv.path=edge/dev/env.conf "project edge-postgres-impl; run"
   ```
   `MPORT` does anything only when the service's `env.conf` has a `mutual-tls` block (the `local`
   target writes one for auth and one for central). Central's is required: it is the only listener
   the edge surface is served on, and edge, which syncs from it, refuses to start without
   `central.mutual-tls`.
5. **Log in** at <http://localhost:9005/login/central-admin> with `admin` / `Admin1234!`, OTP `123456`.

**Why the nginx terminator is not optional.** `central-admin` is registered as `tls_client_auth` by
edge (#353: the `default` tenant is on the FAPI 2.0 security profile, which admits no `client_secret`
client). Its certificate, `edge/dev/internal-tls/central-admin.{crt,key}`, is signed by the
terminator's CA and carried in `central/dev/env.conf`'s `bootstrap.central-admin-mtls`. Edge reaches
auth through the terminator (`versola-internal-url`) because an RFC 8705 client certificate is
presented in a TLS handshake and auth reads one only from a header; the terminator forwards it in
`ssl-client-cert`. Without it every edge → auth call is refused, and edge cannot push
`central-admin`'s request to `/par`.

The other targets differ. `docker-local` and `vps` need neither the terminator nor a certificate in
the config: central issues `central-admin`'s certificate itself through step-ca and renews it, and edge
presents it to auth's own mutual-TLS listener (`native` in `edge.conf`); the proxy in front of auth
clears `ssl-client-cert`, so a certificate cannot be asserted from outside. Redeploying over a
deployment from before this keeps `central-admin` on its `client_secret` and central then refuses to
boot ("A client's authentication method is fixed when it is created"): delete the `central-admin`
client or start from empty volumes. The interactive and k8s targets get no `central-admin-mtls`, so
`central-admin` stays on its `client_secret` there and central logs that it is outside the tenant's profile.

---

## Testing and formatting

```bash
sbt compile                      # all services
sbt Test/compile                 # also compiles e2e
sbt test                         # unit tests; needs Postgres and the built forms, see below
sbt e2e/test                     # end-to-end; needs the services staged as in CI's "Stage services for e2e tests" step
sbt tools/test                   # scripts/gen-env.scala
cd central-ui && npm run test:unit && npm run test:ui   # Vitest, Playwright
```

**Postgres for `sbt test`:** `docker-compose -f services.yml up -d --build postgres`. It serves TLS (and
still accepts plaintext), so every spec, `PostgresTlsConnectionSpec` included, runs against it; the CA it
generates is written to `target/postgres-tls/ca.crt` (the container runs as the `postgres` user, so on
Linux run `mkdir -p target/postgres-tls && chmod a+w target/postgres-tls` first). To run next to another
stack or worktree, publish it elsewhere with `POSTGRES_PORT=5433` and run the tests with
`POSTGRES_HOST=localhost:5433`; `POSTGRES_TLS_HOST` and `POSTGRES_TLS_CA` override where the TLS spec
connects and which CA it trusts.

### Formatting

Scala code is formatted with scalafmt per `.scalafmt.conf` (CLI: `scalafmt`; it reads the pinned version
from the config). Before a push run `scalafmt --mode diff --diff-branch origin/main` and commit the
result; `scalafmt --test` checks without writing.

The project-wide reformat commit is listed in `.git-blame-ignore-revs`, so it does not hide the real
author of each line in `git blame`. GitHub honours that file on its own; for local `git blame` (and IDE
annotate) run once per clone:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

---

## Environment config generation

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
      redirect) points at the proxy's published port instead, `http://localhost:2821` — a
      browser outside the Compose network has no way to resolve `auth` or `edge` as
      hostnames, and edge's own port isn't published to the host at all.

    `edge` needs both at once for one thing: `EdgeConfig.versolaUrl` (public — the
    browser redirect, and the token `iss` check) vs `EdgeConfig.internalUrl` (real
    network call — edge's own token/userinfo exchange with `auth`), which resolves
    from the optional `EdgeConfig.versolaInternalUrl` field and falls back to
    `versolaUrl` when it's absent, so configs generated before this field existed
    keep working. Getting this backwards doesn't fail loudly; it 404s or
    connection-refuses partway through a login that otherwise looks like it's
    working.
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
  `versola-cli` then prints the SQL to give the role that password (deploy.md, "Database, role and schemas").
- **any other name** — runs interactively, prompting for service URLs and Postgres
  credentials. Files are written to `.local/env/<name>/` (`auth.conf`, `central.conf`,
  `edge.conf`).

---

## Docker images

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

(`central` additionally expects the forms already built (`npm run build:forms`, see [Running from source](#running-from-source)) before staging, same as CI's `build` job.)

You can override the config path via `CONFIG_PATH` environment variable:
```bash
docker run -p 8080:8080 -p 8081:8081 \
  -v /path/to/your/env.conf:/custom/path/env.conf:ro \
  -e CONFIG_PATH=/custom/path/env.conf \
  versola-auth
```

(The Dockerfiles `EXPOSE 8080 9345`, but the diagnostics server listens on
`DPORT`, 8081 by default — publish that one.)

---

## Services, ports and certificates

### HTTP Server

Metrics, liveness, and readiness probes are served on the diagnostics port (`DPORT`, default 8081):
- `GET /metrics`
- `GET /liveness`
- `GET /readiness`

The application API is served on the main port (`PORT`, default 8080). Services may expose a
separate internal application surface on `APORT` (default 8082); auth uses it for Account Settings.
Auth and central may also expose a third surface on `MPORT` (default 8083); `MBIND_HOST` sets where it
binds, `BIND_HOST` unless set, since it is the one listener a deployment may need to reach from outside
the host or cluster. Central's is the edge surface, described under "The edge surface" below. Auth's is RFC 8705 §5's mutual-TLS
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

### The edge surface on central (#554)

Central serves what an edge reads (`GET /edge/sync/*`) and the certificate signing an edge enrols
through (`POST /edge/clients/edge-certificate/sign`) on one listener only: its `MPORT` mutual-TLS
listener, configured by `mutual-tls { certificate, private-key, trusted-certificates }`. Every other
central route, and auth's `/configuration/*` reads, stay on the main listener, which refuses an edge
token.

The listener is meant to be reachable from wherever an edge runs, so it is not left to network
placement. Two things are required of every call: the TLS handshake presents a certificate that
chains to `trusted-certificates`, and that certificate carries the URI name
`urn:versola:edge:<edge id>` for the same edge the signed token names (`authorizeEdge`). The first
keeps strangers off the port; the second means a certificate issued to one edge cannot be used with
another's token, and a stolen token is useless without a certificate. `trusted-certificates` must
therefore vouch for **edge certificates only** -- a dedicated CA or issuer, not the one that signs
auth's clients' certificates, and not a public CA.

On the edge, `central { url = "https://...", mutual-tls { certificate, private-key,
trusted-certificates = [...] } }`: the certificate it presents and a **pin** on central's own leaf
(a list, for the overlap-window rotation under (2) below; a CA is refused at startup). Edge refuses to
start without it. The certificate files are read at startup, so renewing them -- cert-manager
rewrites the mounted Secret, step-ca's renewal rewrites the file -- takes effect on the next
restart; issue edge certificates with a lifetime comfortably longer than the interval between
restarts you can guarantee. Nothing is registered in central when an edge certificate is renewed:
the URI name is the only link, which is why it is a name and not a pinned fingerprint.

`local` writes `central/dev/mtls/{ca,server,edge-client}.{crt,key}` (a CA of its own, central's
leaf, and `edge-default`'s certificate) for both services and e2e's `EdgeSyncSurfaceSpec`.
`MPORT=9011` there. docker-local: `mtls-init` issues `central-server.*` and `edge-client.*`
into the shared `/app/mtls` volume; k8s: the chart's `pki` block (cert-manager).

vps (the compose bundle that `versola-cli` deploys) splits the roles across three intermediates of one
step-ca root, so the rule above is enforced by the handshake and not only by the name: `central`
trusts `/app/mtls/edge-ca.crt`, which holds the **edge** intermediate and nothing else, so a client
certificate (from the **client** intermediate) does not complete the handshake, and `auth` trusts
`/app/mtls/auth-trust.crt` (client + edge; auth accepts no edge certificate for anything yet, so
the name check on that side comes with the step that does). The listener
leaves come from the **server** intermediate (365 days), the edge identity of the local edge from the edge
intermediate (365 days; `versola configure vps --cert-lifetime-days N`, 0 = unlimited). **`central` signs edge identities itself** (`/edge/enroll`,
`/edge/certificate/renew`) with the edge intermediate, so that its listener, which trusts only that
intermediate, accepts them: `mtls-init` puts `edge-ca.crt` and `edge-ca.key` (unencrypted PKCS#8, 0600)
into `versola-central-ca-*` -- the volume only central mounts, `/app/ca`, never the shared
`/app/mtls` -- and `central.conf` gets `edge-certificate-authority { certificate, private-key }`. That
is acceptable because central already decides which edges exist (registering one and enrolling it are
both its admin API). Clients' certificates are still signed by the client intermediate through
step-ca's `central` provisioner (`client-certificates`). `central`'s `MPORT` is bound to loopback there (`MBIND_HOST`);
central's `APORT` (8094, not auth's 8082) is the enrollment listener (`POST /edge/enroll` only), also loopback. A remote edge or
an external client reaches them only through public names that are opt-in
(`versola configure vps --mtls-domain`, `--central-mtls-domain`, `--central-domain`), which
`versola-cli`'s proxy forwards; the first two are added to the leaf's SAN by `mtls-init` through
`MTLS_DOMAIN` / `CENTRAL_MTLS_DOMAIN` of `versola-tools`. The local edge keeps using
`https://127.0.0.1:8083` and `:8093`, never the public names.

### Certificate storage and rotation (#440)

Three certificate relationships exist in the mTLS work, with different rotation properties:

| | What | Shape in config | Rotation |
|---|---|---|---|
| 1 | auth's trust anchor for incoming client certificates (`mutual-tls.trusted-certificates`) | PEM path; the file may hold **several CAs** | overlap window: keep outgoing + incoming CA in the file, reissue clients, drop the outgoing CA |
| 2 | edge's pin on auth's own listener certificate (`native.trusted-certificates`, `versola-internal-trusted-certificates`) | **list** of PEM paths, each a **leaf** (a CA is refused at startup, in every certificate of every file) | overlap window: publish `[old leaf, new leaf]` on every edge, cut auth over to the new certificate, drop the old pin |
| 2b | edge's own certificate on central's `MPORT` listener, and central's trust anchor for it (`mutual-tls.trusted-certificates`) | PEM paths; the edge's certificate must carry `urn:versola:edge:<id>`; edge pins central's leaf as in (2) | the deployment re-issues the edge certificate from the edge CA (vps: `mtls-init`, 365 days by default, `MTLS_INIT_PHASE=identity`) and restarts the edge -- no registration in central; a CA change follows (1) |
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

In the vps bundle the overlap is built in. Edge's pins are always the pairs
`/app/mtls/server.crt` + `server.next.crt` (auth) and `central-server.crt` + `central-server.next.crt`
(central); `<name>.next.crt` is a copy of the current leaf until a replacement is staged. `mtls-init`
(`docker/versola-tools/mtls-init.sh`, whose header is the contract) does the steps of (a)-(c)
as phases of `MTLS_INIT_PHASE`, run with
`docker compose run --rm -e MTLS_INIT_PHASE=<phase> mtls-init` and driven by `versola certs renew`
and `rollout`:

1. `prepare`: for each server leaf that is due, write the replacement as `<name>.next.crt` and
   `<name>.next.key`; the current pair is untouched. Printing `mtls-init: staged <name>` and
   `mtls-init: changed`. Repeating it keeps what is staged, so a renewal cut off here goes on to 3.
2. Restart `edge`: it now pins old and new.
3. `commit`: the staged pair becomes the current pair (`mtls-init: committed <name>`); `<name>.next.crt`
   stays as a copy of it. With nothing staged it prints nothing.
4. Restart the service whose leaf changed (`auth` or `central`).

`all` (the default, what `up` runs) skips the choreography and replaces what is due in place, with
the short gap described in [deploy.md](deploy.md#mutual-tls-certificates); `identity` renews only
`edge-client`, which nobody pins; `report` changes nothing and prints one line per certificate,
`mtls-init: cert <file> notBefore=<UTC> notAfter=<UTC> subject=<CN or URN>`, for `doctor` and `status`.
`docker/versola-tools/test/mtls-init.test.sh` runs every phase against a throwaway step-ca in Docker.

**(3a) Client certificates the edge enrols for (#463) -- preferred.** Register an edge-fronted client with
`"enrollEdgeClientCertificate": true` (and `mtlsAuth`, or none for `CN=<client>,OU=<tenant>,O=Versola`).
Central stores **neither a certificate nor a key** -- only that the client is enrolled
(`edge_certificate_enrollment`, with when and for which edge the last certificate was signed). Each edge
replica generates its own EC P-256 key (held in the process and sent nowhere; the TLS stack needs a file, so it is written to the owner-only directory `ClientCertificateFiles` removes on a graceful shutdown), and the client sync tells it
what the certificate must say (`edgeCertificateSubject`); it sends a PKCS#10 request for exactly that to
`POST /edge/clients/edge-certificate/sign`, authenticated as the edge itself (the signed token it
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

---

## Secrets (OpenBao)

1. [How it works](#how-it-works)
2. [Automatic setup](#automatic-setup)
3. [Changing a stored secret](#changing-a-stored-secret)
4. [Moving a deployment that already has secrets](#moving-a-deployment-that-already-has-secrets)
5. [Running OpenBao yourself](#running-openbao-yourself---setup-openbao)
6. [The secret schema (adding a secret)](#the-secret-schema-adding-a-secret)

### How it works

For `docker-local` and `vps`, `auth.conf`, `central.conf` and `edge.conf` never contain a secret's
value. Every secret field (JWT signing key, session and cookie secrets, Postgres password, admin
bootstrap password, …) is a required `${VAR}` HOCON placeholder (`secretField`/`secretKeyField` in
`gen-env.scala` say why it is required and not optional). `versola-cli` resolves each one against an
[OpenBao](https://openbao.org/) server before it starts anything:

- an existing value in OpenBao **wins** over the freshly generated candidate, so secrets survive every
  reconfigure;
- a missing one is generated and stored, except secrets the schema marks
  `generate-on-first-install-only`, which are an error on an upgrade
  ([schema](#the-secret-schema-adding-a-secret));
- the result is written to `<service>.secrets.env` beside the configs, which Compose passes to the
  containers. The services never talk to OpenBao.

The code is `internal/openbao` and `internal/deploy/secrets.go` in `versola-cli`.

Secrets live at `secret/versola/<target>/{auth,central,edge}` (KV v2), where `<target>` is `local` or
`vps` — `versola-cli`'s names, not `docker-local`. One value is stored apart, at
`secret/versola/<target>/utils`: the private key of the `utils` client (`loadgen provision`'s
`provision.provisioner-private-key`), because nothing under a service's path may be a key that
`central` must not hold. A stored key wins over the one `gen-env` generates on each run and central's
public half is derived from it, so the pair survives reconfigures. `configure` leaves the resolved key in
the bundle directory as `utils.private-key.jwk` (mode `0600`).

### Automatic setup

`versola configure <target> <version>` (and `bootstrap`) starts the `versola-openbao-<target>`
container and, on its first run, **provisions it itself**: init with a single unseal key, unseal,
enable KV v2 and AppRole, write a policy limited to `secret/data/versola/<target>/*`, create the
`versola-<target>` role and store its credentials. It keeps two files in `~/.versola/openbao/`:

| File | Holds |
|---|---|
| `<target>.json` | the AppRole `role-id`/`secret-id` it reads and writes secrets with (also printed on every `configure`) |
| `<target>-admin.json` | the root token and unseal key |

OpenBao comes back **sealed** after every container restart (there is no auto-unseal); `configure`
unseals it with the key in `<target>-admin.json`, and recreates the container if it was stopped or its
`openbao.hcl` went away with an old bundle. **Keep a copy of both files off the machine** — neither value
is recoverable if lost. On `vps`, if saving `-admin.json` fails right after init, `configure` stops
instead of printing a root token into a server terminal; that instance is then unusable and its volume
has to be recreated.

The container and its data volume (`versola-openbao-file-<target>`) are named per target. Both targets
bind port 8200, so only one can run at a time on a machine; `configure` says so if the other is still up.

### Changing a stored secret

Use `bao kv patch`, which merges into a path; `bao kv put` **replaces the whole path** and wipes every
other key there. Then run `versola configure <target> <version>` again with the deployment's usual flags
(for `vps`, see [`deploy.md`](deploy.md#deploying-a-new-version)) and `versola up`, so the services get
the new value:

```bash
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao-vps \
  bao kv patch -mount=secret versola/vps/<service> KEY='<value>'
```

`POSTGRES_PASSWORD` is one password shared by `auth`, `central` and `edge` and must be identical under
all three: `configure` refuses to continue if the stored values disagree, and `gen-env` stops on `vps`
when the three `--*-postgres-password` flags differ. (`k8s` is the target with a password per service.)

### Moving a deployment that already has secrets

A fresh install needs none of this. It matters when a deployment that already runs with its own keys
(from before OpenBao) is moved under `versola-cli`: several values `gen-env` would generate fresh have
real, in-use counterparts, and each mismatch fails differently.

| Secret | What depends on it, and the failure with a fresh value |
|---|---|
| `POSTGRES_PASSWORD` | the existing Postgres role's real password; wrong → connection refused at startup |
| `JWT_PRIVATE_KEY` | `central` is the source of truth for signing keys (`JwksRepository`; auth caches a synced copy). Bootstrap seeding adds a key by `kid` instead of replacing, so a fresh one sits *beside* the real one, publishing a key nobody holds; auth matches its private key to a JWKS entry (an RSA key by modulus, an EC key by proof of possession: a challenge signed with the key must verify under the entry's public key) and the entry needs a usable `alg`, which is also the algorithm tokens are signed under. `gen-env` generates an EC P-256 key (`jwt-es256-<date>`, ES256) and publishes only its public half; a pre-existing RSA key is still accepted. Seed the real key so only one `kid` is in play |
| `CLIENT_SECRETS_SECRET` | encrypts existing OAuth client secrets in `central`; a fresh one cannot decrypt them |
| `PASSWORDS_SECRET` | the key of every stored password hash (`PasswordService`); a fresh one fails every user's login |
| `REFRESH_TOKENS_SECRET` | the MAC key of every stored refresh token; a fresh one turns each, including on-device `offline_access` tokens, into `invalid_grant` |
| `EDGE_TOKEN_ENC_KEY` | the AES key (exactly 32 bytes, base64url) of the refresh tokens in edge's session table; with a fresh one every session fails to decrypt in `refreshSession`, a server error rather than a re-login |
| `CENTRAL_RESOURCE_SECRET` | `central` keeps its copy from first boot (create-only, AES-encrypted); `cert-sync` and operator tools present the store's value, so a fresh one is a 401 for them. Edge gets it by sync. A rotation through the admin API changes central's copy, not the store's |
| `EDGE_PRIVATE_KEY`, `EDGE_KEY_ID`, `EDGE_PUBLIC_JWK`, `JWKS_JSON` | `central` already trusts the real edge's public key (its `kid` must equal `EDGE_KEY_ID`, and it must be RSA); a fresh pair makes every edge → central sync 401. `JWKS_JSON` is `{"keys":[<jwk>,…]}` with the public key of `JWT_PRIVATE_KEY` (the same pair: same modulus for RSA, same point for EC) and an `alg`; the edge key is the one that must stay RSA, `JWT_PRIVATE_KEY` need not be |
| `UTILITY_CLIENT_PUBLIC_JWK`, `UTILS_PRIVATE_KEY_JWK` | `central` re-applies the utility client's public key on every boot, so a fresh pair silently replaces the key loadgen and `versola-cli` authenticate with. Reuse the existing pair: the public half goes into central's path below; the private half is not in OpenBao at all, it stays wherever loadgen or the operator keeps it (the `utils.private-key.jwk` a fresh `configure` writes is a new, unrelated key — ignore it). `gen-env`'s reuse path takes an EC P-256 private JWK; `k8s/README.md` covers a client seeded with a `client_secret` |

`ADMIN_BOOTSTRAP_PASSWORD` is deliberately absent: nothing outside `gen-env` owns it, so the generated
value is correct.

Existing values in OpenBao always win, but with automatic setup OpenBao exists only once `configure` has
run, and that first run already stores generated values. So the order is:

1. Run `versola configure vps …` once. It provisions OpenBao and stores generated values. It also prints
   a `CREATE ROLE`/`ALTER ROLE` for a new Postgres password — **don't run it**: the existing role keeps
   its real password, which goes into OpenBao in the next step. Don't run `migrate` or `up` yet.
2. Overwrite the generated values with the real ones (root token from `~/.versola/openbao/vps-admin.json`):
   ```bash
   docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao-vps \
     bao kv patch -mount=secret versola/vps/auth \
       POSTGRES_PASSWORD='<real password>' JWT_PRIVATE_KEY='<real private key, base64>' \
       CLIENT_SECRETS_SECRET='<real value>' PASSWORDS_SECRET='<real value>' \
       REFRESH_TOKENS_SECRET='<real value>'
   docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao-vps \
     bao kv patch -mount=secret versola/vps/central \
       POSTGRES_PASSWORD='<real password>' CLIENT_SECRETS_SECRET='<real value>' \
       EDGE_PUBLIC_JWK='<real public JWK, single-line JSON>' JWKS_JSON='{"keys":[<real auth public JWK>]}' \
       CENTRAL_RESOURCE_SECRET='<real value>' \
       UTILITY_CLIENT_PUBLIC_JWK='<public half of the existing utils key, single-line JSON>'
   docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao-vps \
     bao kv patch -mount=secret versola/vps/edge \
       POSTGRES_PASSWORD='<real password>' EDGE_PRIVATE_KEY='<real private key, base64>' EDGE_KEY_ID='<real kid>' \
       EDGE_TOKEN_ENC_KEY='<real 32-byte base64url value>'
   ```
3. Run the same `versola configure vps …` again — it now resolves the real values into the bundle — then
   `versola migrate` and `versola up`.

With `--setup-openbao` nothing is stored before you do it: provision OpenBao by hand, write the real
values with `bao kv put` (a path's very first write — `kv patch` fails on a path that does not exist yet),
then `configure`.

If containers named `versola-auth`, `versola-central` or `versola-edge` already run under a different
Compose project (the vps compose file is always project `versola-vps`), the first `configure`/`up` fails
with a container-name conflict. Stop and remove those containers by name first; nothing stateful lives in
them (OpenBao's volume is external).

### Running OpenBao yourself (`--setup-openbao`)

`configure vps` and `bootstrap vps` accept `--setup-openbao`: the CLI then never calls OpenBao's admin
API and expects AppRole credentials stored beforehand with `versola secrets login vps`. Unsealing after
a restart is then yours too.

TLS is disabled (see `openbao.hcl.template`) but `bao`'s own default is https, so `BAO_ADDR` must be set
explicitly or every command fails with "server gave HTTP response to HTTPS client". With a small helper
(address `127.0.0.1:8200` on `vps`; `BAO_TOKEN` is passed through when set):

```bash
bao() { docker exec -i -e BAO_ADDR=http://127.0.0.1:8200 ${BAO_TOKEN:+-e BAO_TOKEN} versola-openbao-vps bao "$@"; }

# 1. Initialize (first time only). Save BOTH the unseal key and the root token.
bao operator init -key-shares=1 -key-threshold=1
# 2. Unseal -- again after every container start or recreation.
bao operator unseal <unseal key>

# 3-4. KV v2 and AppRole (need the root token).
export BAO_TOKEN=<root token>
bao secrets enable -path=secret kv-v2
bao auth enable approle

# 5. A policy scoped to this target's own secrets only.
bao policy write versola-vps - <<'EOF'
path "secret/data/versola/vps/*" {
  capabilities = ["create", "read", "update"]
}
EOF

# 6. An AppRole role bound to that policy (long-lived credential for an unattended tool).
bao write auth/approle/role/versola-vps \
  token_policies="versola-vps" token_ttl=1h token_max_ttl=4h secret_id_ttl=0 token_num_uses=0

# 7. The credentials versola-cli needs.
bao read auth/approle/role/versola-vps/role-id
bao write -f auth/approle/role/versola-vps/secret-id
```

On Windows PowerShell, write the policy to a local file first with `-Encoding ascii` (the default
`utf8` adds a BOM that breaks OpenBao's HCL parser with "illegal char" at 1:1), `docker cp` it in and run
`bao policy write versola-vps <path>`.

Then store the credentials on the machine that runs `versola configure` (the server itself, for `vps`).
`<secret-id>` is a separate masked prompt, not an argument, so it never lands in shell history or `ps`:

```bash
versola secrets login vps http://127.0.0.1:8200 <role-id>
```

### The secret schema (adding a secret)

`scripts/gen-env.scala` declares every secret in one list, `SecretSchema.specs`, and writes it beside the
configs as `secrets.schema.json` (one per target; no values, so it needs no special permissions).
`versola-cli` uses it to find the secrets a new version needs that the store does not have yet.

Each entry has:

| Field | Meaning |
|---|---|
| `name`, `services` | what the secret is called and which services receive it. A value shared by several services is listed once; the same name in several entries is *separate* values (`k8s` has a `POSTGRES_PASSWORD` entry per service), so key a secret by `(name, service)`, never by name alone. `utils` is the holder of the `utils` client's private key, stored under `secret/versola/<target>/utils` |
| `type`, `size` | the shape, a promise about every value the store may hold, made only where the services enforce it. `base64url`: URL-safe base64 of exactly `size` bytes, as the `Secret.Bytes16`/`Bytes32` decoders require (written without padding, accepted with `=` padding — validate the decoded length, not the characters). `rsa-private-key`: PKCS#8 in standard base64, any modulus size. `signing-private-key`: PKCS#8 in standard base64, RSA (any modulus size) or EC (what `gen-env` generates), for `JWT_PRIVATE_KEY`. `opaque`: no shape or size, for values an operator can set (`POSTGRES_PASSWORD`, `ADMIN_BOOTSTRAP_PASSWORD`) and values decoded as a plain `Secret` of any length (`ACCOUNT_RESOURCE_SECRET`, `CENTRAL_RESOURCE_SECRET`, `EDGE_INTERNAL_SECRET`) |
| `group` | secrets that only work as a set (e.g. a signing key and the public JWKS carrying it); one `onMissing` for all members: take all or none |
| `onMissing` | what to do when the store lacks it. `generate`; `generate-on-first-install-only`, for a value something already depends on, so missing it on an upgrade is an error — either something outside the store holds it (`POSTGRES_PASSWORD`; the `utils` key pair, whose private half loadgen and `versola-cli` hold) or it protects stored data (`PASSWORDS_SECRET`, `REFRESH_TOKENS_SECRET`, `CLIENT_SECRETS_SECRET`, `CENTRAL_RESOURCE_SECRET`, `EDGE_TOKEN_ENC_KEY`, the JWT and edge key pairs); or `external`, supplied by the operator |
| `file` | where a value is written when it is not in a `*.generated-secrets.env` |
| `since` | the schema revision in which the entry appeared (everything the schema started with is 1) |

On `vps` `POSTGRES_PASSWORD` is one value for all three services; the users and URLs are separate flags
and are not checked.

**Revision.** The schema has its own revision, `SecretSchema.Revision` (`"revision"` at the top of
`secrets.schema.json`; not `schemaVersion`, which is the version of the file's *format*). It is how
`versola-cli` tells, on an upgrade, a secret that is new in this version (generate it) from one that was
lost from the store (for `generate-on-first-install-only`, stop). A reader treats a file without
`revision`/`since` as revision 1 / since 1. The revision is one number per file, shared by all targets, and
the consumer records the revision it last applied. Members of a group share one `since`; removing or
renaming a secret is not expressed by `since`.

**Adding a secret takes five edits that must agree:**

1. a `SecretSpec` in `SecretSchema.specs`;
2. raise `Revision` by one and give the new secret `since = Revision`. If you forget (for a
   `generate-on-first-install-only` secret), it looks like one that always existed and every upgrade
   refuses to continue on it as "lost" — a safe failure, never a silently regenerated value, but it
   fails every upgrade until fixed;
3. a `secretField`/`secretKeyField` placeholder in the service's config;
4. an entry in that service's `writeGeneratedSecrets` list;
5. for `k8s`, `versola.requiredSecretVars` in `k8s/versola/templates/_helpers.tpl`.

`gen-env` stops when the keys it writes differ from the schema; `sbt tools/test` covers the schema itself
(including that every `since` is within `1..Revision`) and `.github/scripts/check-secret-schema.sh` runs
the generator for `docker-local`, `vps` and `k8s` and compares the schema with what it wrote.

---

## CI/CD pipeline

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
itself — see [`deploy.md`](deploy.md#deploying-a-new-version).
