---
paths: [".github/**", "docker/**", "k8s/**", "services.yml", "scripts/**", "migrate-tool/**", "deploy.md"]
---

# CI/CD, Docker, Kubernetes

## What runs where

- `.github/workflows/ci-cd.yml` on push and PR to `main`, on `release: published`, and on manual
  `workflow_dispatch` (requires a `test_tag`):
  - `build`: compile, the measured-DB-access check, tests against Postgres (including a TLS
    Postgres), coverage, dev config generation, forms build, staging, the migrate-tool dry run, e2e.
  - `docker-*`: build and push images. **Images are published only by a release** (or a manual
    `test_tag` run, which never pushes `:latest`); merging to `main` publishes nothing. Tags carry no
    `v` prefix.
  - `helm-*`: chart checks (images, secret vars, placement, ingress).
  - `ui`: `central-ui` type-check, Vitest and Playwright (incl. the axe accessibility scan) against
    mocked API responses; runs beside `build`, no backend needed, skipped on releases.
- `.github/workflows/security.yml`: scanners (see `security.md`).
- `.github/scripts/check-*.sh` are the CI checks as scripts. When you touch what one guards (secret
  schema, chart values, startup ordering, migrate dry run, key reuse), run that script locally or say
  you could not.

## Changing a workflow

- You cannot run Actions locally. Say so, and say what proves the change (a PR run, or a
  `workflow_dispatch` with a `test_tag`); do not report a workflow change as verified.
- Keep least privilege: top-level `permissions: contents: read`, widened per job only where needed
  (the `docker-*` jobs that push packages).
- Pin new actions to a commit SHA with the version in a comment; never interpolate untrusted
  `${{ ... }}` values (PR titles, branch names, inputs) straight into a `run:` body; pass them through
  `env:` and validate, as `ci-cd.yml` does for `test_tag`.
- The existing files explain *why* in comments, at length. Keep that style; a non-obvious step
  without its reason is a trap for the next person.

## Docker images

- Images contain no sbt. CI stages the app (`sbt "<module>/stage"`) and the Dockerfile copies
  `target/universal/stage` onto a JRE pinned by digest. To build locally: stage first (and
  `npm run build:forms` for `central`), then `docker build -f docker/Dockerfile.<name> .`.
- Bump a base image digest deliberately, not as a side effect.
- `JAVA_OPTS` in `Dockerfile.*` encodes a memory budget that `k8s/versola/values.yaml`'s limits
  are derived from. Read the comment above it before changing heap or any non-heap cap, and change
  both together.
- Keep `exec` in the entrypoint (PID 1 must receive SIGTERM) and the `/diagnostics` volume
  assumptions; the comments explain each.
- Ports: the API on `PORT`, diagnostics on `DPORT` (`/metrics`, `/liveness`, `/readiness`),
  internal surface on `APORT`, auth's mTLS listener on `MPORT` (`develop.md`).

## Helm charts (`k8s/`)

- `k8s/versola` deploys auth, central, edge and the console; `k8s/loadgen` deploys the load
  emulator and never the system under test. The charts deliberately bundle no Postgres, no secret
  backend and no Prometheus/Grafana; do not add them.
- The chart's required secret variables must match what `scripts/gen-env.scala` generates
  (`check-chart-secret-vars.sh`). Values and templates change together.
- This chart is not how the shared production host runs (Docker Compose via `versola-cli`,
  `deploy.md`); the two share the configuration format only.

## Deploying

Deployment is a human act (`deploy.md`): a release is cut, images are built, and an operator
runs `versola configure`/`migrate`/`up` on the host. Do not cut releases, tag, push images or run
deployment commands against a real environment. Local `docker-compose -f services.yml` for Postgres
and Jaeger is fine.
