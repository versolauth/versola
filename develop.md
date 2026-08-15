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
- **`vps`** — also runs non-interactively, for the one real VPS this project deploys
  to (used by `versola bootstrap vps`). auth/central/edge run with
  `network_mode: host` there (Postgres and nginx are native installs on the VPS,
  not containers this manages), so real network calls point at `127.0.0.1` instead
  of a Compose service name, and the public URL is the actual domain
  (`https://id.versola.kz`) instead of a local port. Files are written to
  `.local/env/vps/`.

  Every secret field is a `${?VAR}` placeholder here too, same as `docker-local` —
  see "Secrets (OpenBao)" below. vps additionally placeholders Postgres's password
  and the admin bootstrap password, which `docker-local` doesn't: `docker-local`'s
  Postgres is a throwaway container this same run also creates, but vps's Postgres
  role already exists outside this script's control — see that section's note on
  seeding it before the first deploy.
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
    - `PORT=9001 DPORT=9002 sbt -Denv.path=central/dev/env.conf "project central-postgres-impl; run"` - Central
    - `PORT=9003 DPORT=9004 sbt -Denv.path=auth/dev/env.conf "project auth-postgres-impl; run"` - Auth
    - `PORT=9005 DPORT=9006 sbt -Denv.path=edge/dev/env.conf "project edge-postgres-impl; run"` - Edge
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
docker run -p 8080:8080 -p 9345:9345 \
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
docker run -p 8080:8080 -p 9345:9345 \
  -v /path/to/your/env.conf:/custom/path/env.conf:ro \
  -e CONFIG_PATH=/custom/path/env.conf \
  versola-auth
```

This will test if the package is public or requires authentication.

## Secrets (OpenBao)

`docker-local` and `vps` never write a secret's real value into
auth.conf/central.conf/edge.conf — every secret field (JWT signing key,
session/cookie secrets, Postgres password, admin bootstrap password, etc.) is a
`${?VAR}` HOCON placeholder instead (see gen-env.scala's `secretField`/
`secretKeyField`). `versola-cli` resolves each one against an
[OpenBao](https://openbao.org/) server before starting anything: an existing
value there wins over regenerating one, so the same secrets survive being
reconfigured. See `versola-cli`'s `internal/openbao` and
`internal/deploy/secrets.go`.

OpenBao itself needs a one-time setup before any of this works: enabling the
auth method and secrets engine this CLI expects, and creating the AppRole
credentials it authenticates with. Nothing automates this on purpose — it's a
one-off administrative action, not something `configure` should ever do
silently. Do it once per target.

### One-time setup

`versola configure <target> <version>` starts the `versola-openbao` container
automatically (the rest of that run will keep failing until the steps below
are done, but that's expected — run it once first just to get the container
up). The steps below are identical for `docker-local` and `vps`; only the
address differs — `localhost:8200` (published to the host by
`compose.fragment.yml.template`) for `docker-local`, `127.0.0.1:8200` for
`vps` (via `network_mode: host`, running these directly on the VPS itself).

TLS is disabled (see `openbao.hcl.template`), but `bao`'s own default is
https — every command below needs `BAO_ADDR` set explicitly, or it fails
with "server gave HTTP response to HTTPS client".

```bash
# 1. Initialize (first time only). -key-shares=1 -key-threshold=1: a single
#    operator, not Shamir's multi-party scheme — this is an internal deploy
#    tool, not a system that needs to survive one key-holder disappearing.
#    Save BOTH the unseal key and the root token this prints; neither is
#    recoverable if lost.
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 versola-openbao \
  bao operator init -key-shares=1 -key-threshold=1

# 2. Unseal. Needed again after every fresh container start/recreation —
#    seal state does NOT persist on the storage volume, even though the
#    data itself does. There's no auto-unseal configured, so this is a
#    standing manual step, not just a first-run thing.
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 versola-openbao \
  bao operator unseal <unseal key from step 1>

# Steps 3-7 need the root token from step 1 as well:
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao \
  bao secrets enable -path=secret kv-v2        # 3. KV v2 -- `server` mode doesn't
                                                #    enable this by default (unlike -dev)
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao \
  bao auth enable approle                      # 4. AppRole auth method
```

5. A policy scoped to this target's own secrets only — `versola-cli` never needs to
   read or write another target's, and there's no reason for its credentials to be
   able to. On Linux/macOS this can be piped in directly:

   ```bash
   docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao \
     bao policy write versola-<target> - <<'EOF'
   path "secret/data/versola/<target>/*" {
     capabilities = ["create", "read", "update"]
   }
   EOF
   ```

   On Windows PowerShell, write it to a local file first — with `-Encoding ascii`,
   not the default `utf8`, which adds a BOM that breaks OpenBao's HCL parser with
   "illegal char" at 1:1 — then `docker cp` it in and `bao policy write
   versola-<target> /path/inside/container.hcl`.

```bash
# 6. An AppRole role bound to that policy. secret_id_ttl=0/token_num_uses=0:
#    no expiry -- this is a long-lived credential for an unattended deploy
#    tool, not a human's short-lived session.
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao \
  bao write auth/approle/role/versola-<target> \
    token_policies="versola-<target>" \
    token_ttl=1h token_max_ttl=4h \
    secret_id_ttl=0 token_num_uses=0

# 7. Get the credentials versola-cli needs.
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao \
  bao read auth/approle/role/versola-<target>/role-id
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao \
  bao write -f auth/approle/role/versola-<target>/secret-id
```

Then, on the machine that will run `versola configure <target> ...` (the VPS
itself, for `vps` — see the note below), store them:

```bash
versola secrets login <target> http://127.0.0.1:8200 <role-id> <secret-id>
```

(`http://localhost:8200` for `docker-local`.)

### vps-specific: seeding the real Postgres password

`docker-local`'s Postgres is a throwaway container this same `configure` run
also creates, so there's nothing for a freshly generated password to
disagree with. `vps`'s Postgres role (`versola_app`) already exists on the
VPS with its own real password this script has no way to know — left alone,
gen-env.scala's placeholder pipeline will generate and store a WRONG one the
first time it runs against an empty OpenBao, and every service will then
fail to start with a plain Postgres authentication error.

Before the very first `versola configure vps <version>`, seed the real
password by hand into all three of vps's per-service paths — auth, central,
and edge each read the `POSTGRES_PASSWORD` key independently (see
`resolveServiceSecrets` in `versola-cli`), so it has to be written to all
three, not just one:

```bash
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao \
  bao kv put -mount=secret versola/vps/auth    POSTGRES_PASSWORD=<real password>
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao \
  bao kv put -mount=secret versola/vps/central POSTGRES_PASSWORD=<real password>
docker exec -it -e BAO_ADDR=http://127.0.0.1:8200 -e BAO_TOKEN=<root token> versola-openbao \
  bao kv put -mount=secret versola/vps/edge    POSTGRES_PASSWORD=<real password>
```

Only safe as a plain `kv put` (which replaces everything already at that path)
because nothing else has been written there yet. If any of these paths
already holds other resolved secrets (i.e. this isn't the first `configure
vps` run), use `bao kv patch` instead, which merges rather than replaces.

## CI/CD Pipeline

The GitHub Actions workflow (`.github/workflows/ci-cd.yml`) runs on every push and PR to `main`:

1. **Build job** - Compiles and runs tests
2. **Docker job** - Builds and pushes image to GitHub Container Registry (only on merge to main)
3. **Deploy job** - Deploys to VPS via SSH (only on merge to main)

### Required GitHub Secrets

Configure these in repository Settings → Secrets and variables → Actions:

| Secret | Description |
|--------|-------------|
| `VPS_HOST` | VPS hostname or IP address |
| `VPS_USER` | SSH username for VPS |
| `VPS_PASSWORD` | SSH password for VPS |
| `GH_PAT` | GitHub Personal Access Token with `read:packages` scope for pulling images on VPS |

### VPS Setup

1. Create config directory and files on VPS:
   ```bash
   sudo mkdir -p /opt/versola/config
   sudo nano /opt/versola/config/env.conf  # paste your config
   sudo chmod 600 /opt/versola/config/env.conf
   ```

3. Ensure Docker is installed on VPS

4. The deployment will automatically copy docker-compose.prod.yml and run the stack

## HTTP Server

Metrics, liveness, and readiness probes are served on the diagnostics port (`dport`, default 9345):
- `GET /metrics`
- `GET /liveness`
- `GET /readiness`

The application API is served on the main port (`port`, default 8080).

Both ports are configured via `PORT` and `DPORT` environment variables.