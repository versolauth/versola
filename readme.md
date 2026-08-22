# Versola

![Coverage](https://img.shields.io/endpoint?url=https://gist.githubusercontent.com/goshacodes/bd1fd5151f5190b2f8325a56610909ef/raw/versola-coverage.json)
![CI/CD](https://github.com/versolauth/versola/actions/workflows/ci-cd.yml/badge.svg)

Versola is a platform that centralizes authentication, authorization, and account management, enabling teams to quickly build secure systems without developing their own identity infrastructure.

The name is inspired by the Italian word "verso", meaning the reverse (back) side. The suffix "la" was intentionally added to evoke flexibility and evolution. Secure authentication requires looking beyond the obvious.

**We do what matters, you do what business needs.**

- **License:** [Versola Community License v1.0](LICENCE.md) — free for Internal Authentication (an Organization's own employees/contractors) by organizations with fewer than 50 employees, and for evaluation use by any organization. Authenticating customers or end users, or offering the Software as a hosted/managed identity service, is not permitted under this free tier regardless of organization size. See the license file for full terms.

## Architecture

Three Scala services share one codebase and one Postgres database (isolated by schema), plus a static admin SPA:

| Service | Role | Public-facing? |
|---|---|---|
| `auth` | OAuth 2.1 / OpenID Connect provider — authorization, token, introspection, JWKS, logout endpoints | Yes |
| `central` | Configuration store: tenants, clients, scopes, roles, permissions, forms, JWKS. Admin API. Source of truth `auth` syncs from. | No — reached only through `edge` |
| `edge` | Authenticating/authorizing reverse proxy that offloads authn/authz from resource servers and API backends; also the login entry point for the admin console | Yes |
| `central-ui` | Admin dashboard SPA (not a Docker service — static assets served by nginx) | Yes |

`util` holds code shared across `auth`/`central`/`edge` (HTTP, JSON Schema, CEL, core types). Each service also has a `-postgres-impl` module (e.g. `auth-postgres-impl`) that wires the service to its PostgreSQL implementation and provides the runnable app.

For the full request-routing picture and why things are split this way, see [`deploy.md`](deploy.md).

## Tech stack

- **Language:** Scala 3, built with ZIO
- **Database:** PostgreSQL, migrated with Flyway
- **Tracing:** OpenTelemetry
- **Secrets:** [OpenBao](https://openbao.org/) for managing per-environment secrets
- **Admin UI:** Solid.js, TypeScript, Vite (`central-ui`), tested with Playwright and Vitest
- **Packaging:** `sbt-native-packager` (`JavaAppPackaging`), Docker images published to `ghcr.io/versolauth/`

## Getting started

Requires a JDK, [sbt](https://www.scala-sbt.org/), Docker, [scala-cli](https://scala-cli.virtuslab.org/install) (for local config generation), and Node.js/npm (to build `central-ui`'s login forms, which `central` needs to render `/login/central-admin`).

```bash
# 1. Generate local dev config for auth/central/edge (writes auth/dev/env.conf, etc.)
scala-cli run scripts/gen-env.scala   # answer "local" at the Name prompt

# 2. Start Postgres
docker-compose -f services.yml up -d postgres

# 3. Run each service (in separate terminals)
PORT=9001 DPORT=9002 sbt -Denv.path=central/dev/env.conf "project central-postgres-impl; run"
PORT=9003 DPORT=9004 sbt -Denv.path=auth/dev/env.conf "project auth-postgres-impl; run"
PORT=9005 DPORT=9006 sbt -Denv.path=edge/dev/env.conf "project edge-postgres-impl; run"
```

Before starting `central`, build the login forms it serves:

```bash
cd central-ui
npm install
npm run build:forms   # compiles forms into central/src/main/resources/forms
```

Then open `http://localhost:9005/login/central-admin` (`admin` / `Admin1234!`, OTP `123456`).

## Testing

```bash
sbt test                 # unit tests for all modules
sbt e2e/test             # end-to-end tests (run explicitly, not part of the default test loop)
```

`central-ui` has its own suites: `cd central-ui && npm run test:unit` (Vitest) and `npm run test:ui` (Playwright).

## Project structure

```text
├── auth/                # OAuth 2.1 / OIDC provider (+ auth/implementations/postgres, auth/open-api specs)
├── central/             # Configuration/admin service (+ central/implementations/postgres)
├── central-ui/          # Admin dashboard SPA (Vite/TypeScript) + login forms served by auth
├── edge/                # Authn/authz-offloading reverse proxy for resource servers/APIs; also the admin console entry point
├── util/                # Shared library code (+ util/implementations/postgres)
├── e2e/                 # Cross-service end-to-end tests
├── docker/              # Dockerfiles for each service
├── scripts/gen-env.scala # Generates per-environment HOCON configs + secrets
├── project/             # sbt build config (Dependencies.scala, plugins)
├── develop.md           # Local development, Docker builds, OpenBao secrets setup
└── deploy.md            # Deployment runbook and troubleshooting
```

## API

`auth` exposes its endpoints as OpenAPI specs under [`auth/open-api/`](auth/open-api) (`authorize`, `token`, `introspect`, `revoke`, `jwks`, `userinfo`, `logout`, `par`, `metadata`).

## CI/CD

[`ci-cd.yml`](.github/workflows/ci-cd.yml) is the main build pipeline. It runs on every push/PR to `main`:

1. Compile all modules and run the unit test suite (`sbt test`) against a Postgres service container.
2. Stage `auth` and `central`, then run the end-to-end suite (`sbt e2e/test`) against the staged services.
