<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/wordmark-white.svg">
    <img alt="Versola" src="docs/brand/wordmark-ink.svg" height="36">
  </picture>
</h1>

![Coverage](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/goshacodes/bd1fd5151f5190b2f8325a56610909ef/raw/versola-coverage.json)
![CI/CD](https://github.com/versolauth/versola/actions/workflows/ci-cd.yml/badge.svg)

Versola is a platform that centralizes authentication, authorization, and account management, enabling teams to quickly build secure systems without developing their own identity infrastructure.

The name is inspired by the Italian word "verso", meaning the reverse (back) side. The suffix "la" was intentionally added to evoke flexibility and evolution. Secure authentication requires looking beyond the obvious.

- **License:** [Versola Community License v1.0](LICENCE.md) — free for Internal Authentication (an Organization's own employees/contractors) by organizations with fewer than 50 employees, and for evaluation use by any organization. Authenticating customers or end users, or offering the Software as a hosted/managed identity service, is not permitted under this free tier regardless of organization size. See the license file for full terms.

- **Compatibility:** we do not offer any compatibility until 1.0.0

## Architecture

Three Scala services share one codebase, plus a static admin SPA:

| Service | Role | Public-facing? |
|---|---|---|
| `auth` | OAuth 2.0 / OpenID Connect provider — authorization, token, introspection, JWKS, logout endpoints | Yes |
| `central` | Configuration store: tenants, clients, scopes, roles, permissions, forms, JWKS. Admin API. Source of truth `auth` syncs from. | No — reached only through `edge` |
| `edge` | Authenticating/authorizing reverse proxy that offloads authn/authz from resource servers and API backends; also the login entry point for the admin console | Yes |
| `central-ui` | Admin dashboard SPA (static assets, shipped in the `versola-tools` image and served by the reverse proxy in front of Versola) | Yes |

`util` holds code shared across `auth`/`central`/`edge` (HTTP, JSON Schema, CEL, core types). Each service also has a `-postgres-impl` module (e.g. `auth-postgres-impl`) that wires the service to its PostgreSQL implementation and provides the runnable app.

For the full request-routing picture and why things are split this way, see [`deploy.md`](deploy.md).

## Tech stack

- **Language:** Scala 3, built with ZIO
- **Database:** PostgreSQL, migrated with Flyway
- **Tracing:** OpenTelemetry
- **Secrets:** [OpenBao](https://openbao.org/) for managing per-environment secrets
- **Admin UI:** Lit, TypeScript, Vite (`central-ui`), tested with Playwright and Vitest
- **Login forms:** Solid.js, TypeScript (built by `central-ui`)
- **Packaging:** `sbt-native-packager` (`JavaAppPackaging`), Docker images (amd64 and arm64) published to `ghcr.io/versolauth/` by each release
- **Deployment:** Docker Compose on a VM driven by [`versola-cli`](https://github.com/versolauth/versola-cli), or the Helm charts in `k8s/`

## Getting started

### Try a release with `versola-cli`

The quickest way to run Versola is [`versola-cli`](https://github.com/versolauth/versola-cli): it pulls a released version, generates its configuration and secrets, and starts Postgres, `central`, `auth`, `edge`, the admin console and a reverse proxy with Docker Compose. It needs only Docker.

```bash
curl -fsSL https://raw.githubusercontent.com/versolauth/versola-cli/main/install.sh | sh
versola doctor                      # checks Docker, Compose, free port 2821, memory
versola bootstrap local <version>   # a release tag without a leading "v", e.g. 0.7.1
versola status
versola down                        # stop; `versola uninstall` removes everything
```

The console opens at `http://localhost:2821/central/admin/` and the admin login is printed when `bootstrap` finishes. The same CLI deploys to a server: see [`deploy.md`](deploy.md).

### Run from source

To work on Versola itself you run the services from sbt. You need a JDK, [sbt](https://www.scala-sbt.org/), Docker, [scala-cli](https://scala-cli.virtuslab.org/install), an OpenSSL 3 `openssl` on `PATH` (macOS's LibreSSL is too old), nginx, and Node.js/npm. In short:

```bash
scala-cli run scripts/gen-env.scala                      # answer "local" at the Name prompt
docker-compose -f services.yml up -d postgres
cd central-ui && npm install && npm run build:forms && cd ..
```

and then start `central`, `auth`, an nginx TLS terminator in front of `auth`, and `edge`, and open
`http://localhost:9005/login/central-admin` (`admin` / `Admin1234!`, OTP `123456`). The exact commands, ports
and flags are in [`develop.md`](develop.md#running-from-source).

## Testing

```bash
docker-compose -f services.yml up -d --build postgres   # unit tests need it (it serves TLS)
sbt test                 # unit tests for all modules
sbt e2e/test             # end-to-end tests against a staged stack (see develop.md); not part of the default test loop
```

`sbt test` needs the forms built (step 3 above). `central-ui` has its own suites: `cd central-ui && npm run test:unit` (Vitest) and `npm run test:ui` (Playwright).

## Project structure

```text
├── auth/                # OAuth 2.1 / OIDC provider (+ auth/implementations/postgres, auth/open-api specs)
├── central/             # Configuration/admin service (+ central/implementations/postgres, central/open-api spec)
├── central-ui/          # Admin dashboard SPA (Vite/TypeScript) + login forms served by auth
├── edge/                # Authn/authz-offloading reverse proxy for resource servers/APIs; also the admin console entry point
├── util/                # Shared library code (+ util/implementations/postgres)
├── e2e/                 # Cross-service end-to-end tests
├── migrate-tool/        # Runs every service's migrations (shipped in the versola-tools image)
├── loadgen/, mockapi/   # Load emulator and the mock upstream it drives
├── docker/              # Dockerfiles for each service and for versola-tools
├── k8s/                 # Helm charts: versola (the system) and loadgen
├── dashboards/          # Grafana dashboards
├── scripts/gen-env.scala # Generates per-environment HOCON configs + secrets
├── project/             # sbt build config (Dependencies.scala, plugins)
├── develop.md           # Local development, config generation, Docker builds, OpenBao secrets
├── deploy.md            # Production deployment runbook and troubleshooting
├── REVIEW.md            # Code review guide
├── SECURITY.md          # Vulnerability reporting and fix SLAs
└── CLAUDE.md            # Rules for AI-assisted work (plus .claude/rules/)
```

## API

Each service ships a hand-maintained OpenAPI 3.1 reference:

| Service | Spec | Covers |
|---|---|---|
| `auth` | [`auth/open-api/`](auth/open-api) | OAuth 2.1 / OIDC protocol endpoints, one file each: `authorize`, `par`, `token`, `introspect`, `revoke`, `userinfo`, `logout`, `jwks`, `metadata` |
| `central` | [`central/open-api/central.yaml`](central/open-api/central.yaml) | Admin API used by `central-ui`: tenants, clients, resources, scopes, permissions, roles, users, branding, keys |
| `edge` | [`edge/open-api/edge.yaml`](edge/open-api/edge.yaml) | Browser login/logout, resource proxy, `/permissions/me`, native-app (`/native/*`) back channel, probes |

Internal service-to-service endpoints (configuration sync/registry, the sign-in UI flow under `auth`'s `/challenge/*`) are intentionally not part of these specs. Update the relevant spec whenever you change a controller's routes, parameters, or responses.

## CI/CD

[`ci-cd.yml`](.github/workflows/ci-cd.yml) is the main pipeline:

- **Every push/PR to `main`:** compile, unit tests with coverage against Postgres, stage the services and run the end-to-end suite, plus the Helm chart checks and the `central-ui` type-check, Vitest and Playwright suites.
- **A published release:** builds and pushes the `versola-auth`, `versola-central`, `versola-edge` and `versola-tools` images to `ghcr.io/versolauth/`, tagged with the release tag. Merging to `main` publishes nothing.

[`security.yml`](.github/workflows/security.yml) runs the dependency, image, secret and code scanners. Nothing in CI deploys; see [`deploy.md`](deploy.md) and [`k8s/README.md`](k8s/README.md).
