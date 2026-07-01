## Environment Config Generation

The `scripts/gen-env.scala` script generates HOCON config files for all three services
(`auth`, `central`, `edge`) with freshly generated RSA-2048 key pairs and random secrets.
It requires [scala-cli](https://scala-cli.virtuslab.org/install).

```bash
scala-cli run scripts/gen-env.scala
```

The script first asks for the environment **Name** (default `local`):

- **`local`** — runs non-interactively. All remaining prompts are skipped and defaults are
  used. Files are written to the service dev directories consumed by `sbt` (see below):
    - `auth/dev/env.conf`
    - `central/dev/env.conf \
    - `central/dev/env.conf`
    - `edge/dev/env.conf`
- **any other name** — runs interactively, prompting for service URLs and Postgres
  credentials. Files are written to `.local/env/<name>/` (`auth.conf`, `central.conf`,
  `edge.conf`).

## Local Development

1. Compilation - `compile`
2. Test compilation - `Test / compile`
3. Run tests - `testFull`. First, you need to start postgres - `docker-compose -f services.yml up -d postgres`. Since sbt 2, the `test` task is incremental, so use `testFull` if you want to run all tests.
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
    - go to https://localhost:9005/login?pid=central-admin, enter admin/Admin1234!


## Docker

### Build Locally

Build the Docker image locally:
```bash
docker build -t versola-auth .
```

Run the Docker image (mount config file):
```bash
docker run -p 8080:8080 -p 9345:9345 \
  -v $(pwd)/auth/dev/env.conf:/app/config/env.conf:ro \
  versola-auth
```

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
