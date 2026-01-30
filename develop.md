## Local Development

1. Compilation - `compile`
2. Test compilation - `Test / compile`
3. Run tests - `test`. First, you need to start postgres - `docker-compose -f services.yml up -d postgres`
4. Start server locally
    - `docker-compose -f services.yml up -d postgres` - Database
    - `docker-compose -f services.yml up -d jaeger` - Jaeger (optional)
    - `sbt -Denv.path=auth/dev/env.conf "project postgres-impl; run"` - postgres impl

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

Metrics are available on port 9345 at path `/metrics`
Liveness probe is available on port 9345 at path `/liveness`
Readiness probe is available on port 9345 at path `/readiness`
The application itself runs on port 8080