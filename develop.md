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
    - go to http://localhost:9005/login/central-admin, enter admin/Admin1234!


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