# Deployment

How Versola is deployed to the shared production host. For local development see
[`develop.md`](develop.md).

> **Scope.** This document describes the *current* deployment: a single VPS that also serves the
> marketing site, blog and docs. It is a runbook, not a product installation guide — commands
> here assume you have `sudo` on that host, and the automated steps assume you have access to run
> the `Deploy` GitHub Actions workflow.

---

## 1. Topology

The host runs three different kinds of workload. Only one of them is ours to restart freely.

| Component | How it runs | Managed by |
|---|---|---|
| PostgreSQL 18 | native, `apt` + systemd, bound to `127.0.0.1:5432` | `systemctl` |
| `auth`, `central`, `edge` | Docker, `docker compose`, `network_mode: host` | `/opt/versola/docker-compose.prod.yml`, driven by the [`Deploy`](#4-deploying-a-new-version) workflow |
| nginx (TLS termination, static sites) | native, `apt` + systemd | the [`nginx`](https://github.com/versolauth/nginx) repo, deployed by its own workflow |

Because the services use `network_mode: host`, each one binds directly to a localhost port on the
host and there is no Docker network between them. They reach Postgres and each other over
`127.0.0.1`.

### Ports

The compose file lives on the server and is not in git (see [Known gaps](#10-known-gaps)), so if
this table looks wrong, `cat /opt/versola/docker-compose.prod.yml` is the source of truth.

| Service | App port (`PORT`) | Diagnostics port (`DPORT`) | Exposed publicly |
|---|---|---|---|
| `auth` | 8080 | 8081 | yes — `https://id.versola.kz` via nginx |
| `central` | 8090 | 8091 | no — admin API, reached through `edge` |
| `edge` | 8095 | 8096 | not yet — needs a domain, see [Known gaps](#10-known-gaps) |

Note that the Dockerfiles `EXPOSE 8080 9345`, but `9345` is not what the deployment actually
uses — with `network_mode: host` the `EXPOSE` directive is inert and `DPORT` decides.

Nothing binds to `0.0.0.0`. Public access is exclusively through nginx.

### Why these three services

- **`central`** — configuration store: tenants, clients, scopes, roles, permissions, forms, JWKS.
  Serves the admin API and bootstraps the initial dataset on an empty database.
- **`auth`** — the OAuth2/OIDC endpoint users hit (`id.versola.kz`). Signs tokens; syncs its
  client/scope configuration *from* `central`.
- **`edge`** — authorising reverse proxy in front of `central`'s admin API, and the login entry
  point for the admin console.

**Startup ordering matters:** `auth` cannot finish starting if `central` is unreachable — it
blocks on its initial config sync. If you restart both, bring `central` up first, or you will get
a 502 on `id.versola.kz` until `auth` succeeds. The [`Deploy`](#4-deploying-a-new-version)
workflow enforces this ordering automatically; see [9.5](#95-authedge-never-become-ready-after-restarting-the-whole-stack-together)
for what happens if you don't.

---

## 2. Prerequisites

- Docker and the Compose plugin on the host.
- PostgreSQL 18 reachable at `127.0.0.1:5432`.
- [scala-cli](https://scala-cli.virtuslab.org/install) *on your workstation* — used once to
  generate configs. It is not needed on the server.
- Published images in `ghcr.io/versolauth/`: `versola-auth`, `versola-central`, `versola-edge`.
  All three packages are public, so the host does not need `docker login`.
- Read access to the private [`env-config`](#5-the-env-config-repository) repository, if you need
  to change a service's runtime configuration.
- To run deploys through the pipeline rather than by hand: **Actions** access on this repository.
  The workflow itself needs five repository secrets — `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`,
  `VPS_HOST_FINGERPRINT`, `ENV_CONFIG_PAT` — already configured; you don't need to touch these to
  run a deploy, only to change *how* deploys work.

---

## 3. First-time setup

### 3.1 Database role and schemas

All three services share **one** Postgres database, `auth` — not three separate databases. What
isolates them is schema, not database. This is easy to get wrong by analogy with "one database per
service" — check the actual JDBC URL in a running container's logs if in doubt:
`jdbc:postgresql://127.0.0.1:5432/auth?currentSchema=central`. Same database (`auth`), different
`currentSchema` per service.

Connect as the `postgres` superuser:

```sql
CREATE ROLE versola_app WITH LOGIN PASSWORD '<generated>';
CREATE DATABASE auth OWNER versola_app;
```

Generate the password rather than inventing one: `openssl rand -base64 32`.

Then create the three schemas **inside that one database**:

```sql
\c auth
CREATE SCHEMA IF NOT EXISTS auth    AUTHORIZATION versola_app;
CREATE SCHEMA IF NOT EXISTS central AUTHORIZATION versola_app;
CREATE SCHEMA IF NOT EXISTS edge    AUTHORIZATION versola_app;
```

> **Why separate schemas — do not skip this.** Flyway tracks applied migrations in a
> `flyway_schema_history` table *inside the schema it is pointed at*. If two services share one
> schema they share one history table, and the second service to start finds migration checksums
> it does not recognise and aborts with `FlywayValidateException`. This is not hypothetical — it
> is exactly how the first deployment failed. See
> [Troubleshooting](#91-flywayvalidateexception-on-startup).

### 3.2 Generate configuration

On your workstation, in a checkout of this repo:

```bash
scala-cli run scripts/gen-env.scala
```

Answer `prod` (or any name that is **not** `local`) at the `Name:` prompt — `local` runs
non-interactively and silently writes development defaults. Output lands in
`.local/env/<name>/{auth,central,edge}.conf`.

`.local/` is gitignored **in this repository** and must stay that way — these files contain RSA
private keys, the shared `central` secret and the Postgres password, and this repo (`versola`) is
where the application code lives, not where runtime secrets belong. The generated files' final
home is the separate [`env-config`](#5-the-env-config-repository) repository — see there for how
they get from your workstation to the server.

Values that must not be left at their defaults:

| Prompt | Default | Use instead |
|---|---|---|
| Auth URL | `http://localhost:9003` | `https://id.versola.kz` |
| Central URL | `http://localhost:9001` | `http://127.0.0.1:8090` (internal only) |
| Edge URL | `http://localhost:9005` | the public edge domain once it exists |
| Postgres user / password | `dev` / `1234` | `versola_app` / the generated password |
| Admin bootstrap password | `Admin1234!` | a real password |

The three configs are **not** independent — `gen-env.scala` generates one RSA key pair and one
shared `secret-key` and distributes the halves across the files. Regenerating one file alone will
break the trust between the services. Always regenerate all three together.

### 3.3 Pin each service to its own schema

`gen-env.scala` does not know about schema isolation, so append `currentSchema` to each JDBC URL
by hand, on your workstation, **before** the files go into `env-config`:

```bash
sed -i 's|\(postgres[^\n]*url = "jdbc:postgresql://[^"]*\)"|\1?currentSchema=auth"|' \
  .local/env/prod/auth.conf
# ...and the same for central.conf → currentSchema=central, edge.conf → currentSchema=edge
```

Verify — this must print three lines, one per file:

```bash
grep -h currentSchema .local/env/prod/*.conf
```

### 3.4 Publish the configs to env-config

Runtime configuration is version-controlled in the private
[`env-config`](#5-the-env-config-repository) repository and installed on the host automatically by
the [`Deploy`](#4-deploying-a-new-version) workflow — there is no manual `scp` step anymore.

```bash
git clone https://github.com/versolauth/env-config.git
cp .local/env/prod/{auth,central,edge}.conf env-config/prod/
cd env-config
git add prod/
git commit -m "update prod env configs"
git push
```

The next run of the `Deploy` workflow for a given service pulls that service's file from
`env-config` and installs it on the host with `chmod 600`, backing up whatever was there before.
See [5](#5-the-env-config-repository) for the full mechanics, including why the file is stored in
plaintext rather than encrypted.

### 3.5 Compose file

`/opt/versola/docker-compose.prod.yml` — a representative snapshot; the image tags change on every
deploy, so treat the tags below as illustrative, not current:

```yaml
services:
  auth:
    image: ghcr.io/versolauth/versola-auth:0.1.1
    container_name: versola-auth
    restart: unless-stopped
    network_mode: host
    environment:
      PORT: 8080
      DPORT: 8081
      CONFIG_PATH: /app/config/env.conf
      RUN_MIGRATIONS: ${RUN_MIGRATIONS:-true}
    volumes:
      - /opt/versola/config/auth.conf:/app/config/env.conf:ro
    mem_limit: 512m

  central:
    image: ghcr.io/versolauth/versola-central:0.1.1
    container_name: versola-central
    restart: unless-stopped
    network_mode: host
    environment:
      PORT: 8090
      DPORT: 8091
      CONFIG_PATH: /app/config/env.conf
      RUN_MIGRATIONS: ${RUN_MIGRATIONS:-true}
    volumes:
      - /opt/versola/config/central.conf:/app/config/env.conf:ro
    mem_limit: 768m

  edge:
    image: ghcr.io/versolauth/versola-edge:0.1.1
    container_name: versola-edge
    restart: unless-stopped
    network_mode: host
    environment:
      PORT: 8095
      DPORT: 8096
      CONFIG_PATH: /app/config/env.conf
      RUN_MIGRATIONS: ${RUN_MIGRATIONS:-true}
    volumes:
      - /opt/versola/config/edge.conf:/app/config/env.conf:ro
    mem_limit: 384m
```

There is no `postgres` service here on purpose — Postgres is native on this host.

**Memory limits.** The Dockerfiles set `-XX:MaxRAMPercentage=75.0`. Without `mem_limit` each JVM
sees the *host's* total memory and claims 75% of it — three services would target 225% between
them. With `mem_limit` set, the JVM reads the cgroup limit instead and the percentage becomes
meaningful. Limits must still cover the cold-start spike: a limit that is merely tight shows up as
the container being killed by the kernel with no application-level error at all (see
[Troubleshooting](#93-container-dies-with-no-error-in-its-own-log)).

**The image tag is hardcoded three times.** This used to mean every deploy required hand-editing
this file. It no longer does — the [`Deploy`](#4-deploying-a-new-version) workflow patches the tag
for the service being deployed with `sed`, backs up the file first, and verifies the substitution
actually matched before proceeding. Editing this file by hand is still supported (see
[4.2](#42-manual-deploy-fallback)) and remains the only option if the pipeline itself is down.

`container_name` is still pinned, which prevents ever running two instances side by side. Harmless
today, worth knowing before attempting a zero-downtime rollout.

If you ever do edit this file directly, back it up and diff before applying —
`cp docker-compose.prod.yml docker-compose.prod.yml.bak`, then `diff` after, to catch accidental
changes to lines you didn't mean to touch. This is exactly what the pipeline does automatically on
every run.

### 3.6 nginx

Do **not** hand-edit nginx on the server. The config is version-controlled in the
[`nginx`](https://github.com/versolauth/nginx) repo (`envs/dev/versola.conf`) and its workflow
copies it over, runs `nginx -t` and reloads on merge to `main`. Editing the file in place means
the next deploy silently reverts you.

---

## 4. Deploying a new version

Images are built and pushed **only** by the `release: published` event — merging to `main` runs
tests but publishes nothing. So a deploy always starts with cutting a release, regardless of which
path you take afterward.

1. **Cut a release.** GitHub → Releases → *Draft a new release* → new tag, target `main` →
   *Generate release notes* → *Publish*.
2. **Wait for the images.** In Actions, `docker-auth`, `docker-central` and `docker-edge` all run
   on every release, regardless of which service actually changed. All three must be green.

From here, use the pipeline (4.1) unless it's unavailable, in which case fall back to a manual
deploy (4.2) — both end up changing the same file on the same host, so pick one per deploy, not
both.

### 4.1 Automated deploy (the `Deploy` workflow)

**Actions → Deploy → Run workflow**, then fill in:

| Input | Meaning |
|---|---|
| `version` | the image tag to deploy — the release tag from step 1, **no** leading `v` (see [Tag naming](#tag-naming) below) |
| `service` | `auth`, `central`, `edge`, or `all` |
| `run_migrations` | whether the service(s) being deployed run Flyway on startup |

The workflow (`.github/workflows/deploy.yml`) runs as up to three jobs:

- **`deploy-central`** runs first, only if `service` is `central` or `all`.
- **`deploy-auth`** and **`deploy-edge`** run after `deploy-central` (in parallel with each
  other), gated on it having either succeeded or been intentionally skipped — so deploying `auth`
  alone doesn't force a `central` redeploy, but `all` always brings `central` up first, matching
  the [startup ordering](#why-these-three-services) requirement.

Each job, for its one service:

1. Validates `version` against `^[A-Za-z0-9._-]+$` and refuses to proceed on anything else —
   this value ends up in a shell command on the server, so it is never interpolated directly into
   the script; it's passed through as an environment variable instead.
2. Checks out [`env-config`](#5-the-env-config-repository) read-only, with credential persistence
   disabled so the access token isn't left behind in the runner's git config for later steps to
   pick up.
3. Copies that service's `.conf` file to a **per-service** staging directory on the host
   (`~/incoming-config-auth`, `~/incoming-config-central`, `~/incoming-config-edge`) — separate
   directories exist specifically so that `deploy-auth` and `deploy-edge`, which run concurrently,
   can never race over a shared one.
4. Over SSH (host key verified against a pinned fingerprint, not just trust-on-first-use):
   - installs a cleanup trap for the staging directory *before* doing anything else, so a staged
     secret is removed from the host even if the very next command fails;
   - for `auth`/`edge`, confirms `central` is ready **before** touching any local state — a bad
     health check means nothing on disk changes;
   - refuses to proceed if the staged config is empty or missing;
   - backs up the previous config (a real backup failure is not swallowed — only "there was
     nothing to back up yet" is treated as fine) and installs the new one with `chmod 600`;
   - backs up `docker-compose.prod.yml`, patches in the new image tag with `sed`, and verifies the
     substitution actually matched before continuing — a `sed` that silently matches nothing would
     otherwise redeploy the old image and report success;
   - runs `docker compose -f docker-compose.prod.yml pull` and
     `up -d --no-deps` for that service only;
   - polls `/readiness` for up to a minute and dumps the last 50 log lines if it never comes up.
5. Runs a final cleanup step unconditionally (`if: always()`), independent of step 4 — this is
   what removes the staged secret on the host if the SSH connection itself never succeeded, which
   the trap inside step 4's script can't cover because it never got to run.

All three GitHub Actions used (`actions/checkout`, `appleboy/scp-action`, `appleboy/ssh-action`)
are pinned to a full commit SHA rather than a mutable tag.

Concurrent runs are serialized (`concurrency: production-deploy`, `cancel-in-progress: false`) —
a second dispatch queues behind the first rather than racing it or cancelling it.

**If a run fails partway,** nothing upstream of the failure was changed (config swap and compose
edit both happen after their preconditions are confirmed, not before), so it's safe to fix the
underlying problem and re-run. Use **Run workflow** again with the same inputs — not **Re-run
jobs** on the failed run, which replays the *old* workflow file pinned to that run's original
commit, not whatever is currently on `main`.

### 4.2 Manual deploy (fallback)

Only needed if Actions itself is unavailable, or you're deploying to a host the pipeline doesn't
know about yet.

```bash
cd /opt/versola
sudo nano docker-compose.prod.yml       # bump the tag on the service(s) being deployed
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

To deploy a single service, add its name: `docker compose -f docker-compose.prod.yml up -d --no-deps central`.
If you also need to update that service's runtime config, copy the relevant file from
[`env-config`](#5-the-env-config-repository) to `/opt/versola/config/<service>.conf` yourself
first (`chmod 600` afterward) — the manual path does not do this for you.

**Verify:**

```bash
docker compose -f docker-compose.prod.yml ps             # all Up
curl -sf http://127.0.0.1:8081/readiness  # auth    → 200
curl -sf http://127.0.0.1:8091/readiness  # central → 200
curl -sf http://127.0.0.1:8096/readiness  # edge    → 200
curl -sI https://id.versola.kz/
```

#### Tag naming

The image tag is the git tag verbatim (`version=${{ github.event.release.tag_name }}`
in `ci-cd.yml`, no normalisation). The `0.1.0` images in the registry carry **no** `v` prefix, so
`docker pull ...:v0.1.0` fails while `...:0.1.0` works. Pick a convention and keep it — the tag
you type into a release is the tag you will have to type into `docker pull` (or the `version`
input above) forever after.

### `RUN_MIGRATIONS`

Every service runs Flyway on startup. `RUN_MIGRATIONS` controls whether it does:

- unset → migrations run (the historical behaviour; existing deployments are unaffected)
- `true` / `false` → as specified, case-insensitive
- anything else → the service **fails to start** with an explanatory error, deliberately. A flag
  whose whole purpose is to skip migrations must not silently run them because of a typo.

Set it to `false` when you want a rollout to be strictly a code change — for example when
deploying a hotfix and you want to be certain nothing touches the schema. In the automated
pipeline this is the `run_migrations` input; manually, it's an exported shell variable before
`docker compose -f docker-compose.prod.yml up`.

---

## 5. The env-config repository

Runtime configuration for `auth`, `central` and `edge` — RSA private keys, the shared `central`
secret, the Postgres password, bootstrap data — lives in the private repository
[`versolauth/env-config`](https://github.com/versolauth/env-config), not in `versola` and not only
on the server.

```
env-config/
├── dev/
│   └── env.conf          # shared local-development config
└── prod/
    ├── auth.conf
    ├── central.conf
    └── edge.conf
```

**Why a separate repository, and why not encrypted.** `versola` is where application code lives
and is read by everyone with repo access; `env-config` is scoped narrowly and separately so that
read access to one doesn't imply read access to the other. The files are committed in **plaintext**
— this was a deliberate, discussed tradeoff rather than an oversight: it keeps the deploy pipeline
simple (no decryption step, no key management for a tool like `sops` or `git-crypt`) at the cost of
anyone with read access to `env-config` being able to read every secret it holds. Access to that
repository should be treated as equivalent to production database access. See
[Known gaps](#10-known-gaps) for the honest accounting of what this costs.

### Updating a config

1. Generate or hand-edit the relevant `.conf` (see [3.2](#32-generate-configuration) and
   [3.3](#33-pin-each-service-to-its-own-schema) if generating from scratch).
2. Commit it to `env-config` under `prod/<service>.conf`.
3. Run the [`Deploy`](#4-deploying-a-new-version) workflow for that service — any deploy, even one
   that isn't otherwise changing the image, picks up the current file in `env-config` and installs
   it on the host.

There is currently no way to push a config change to the host *without* going through a deploy of
that service — if you need to change configuration without bumping the image, deploy the same
`version` that's already running.

### Access

The `Deploy` workflow reads `env-config` using a fine-grained GitHub personal access token, stored
as the `ENV_CONFIG_PAT` repository secret on `versola`, scoped to **read-only Contents access on
`env-config` only**. Two things to know if this ever needs to be reissued:

- Fine-grained tokens scoped to an organization's repository require that organization's approval
  before they become usable — a newly created token sits in **Pending** status
  (visible at `github.com/settings/tokens?type=beta`) until an owner of `versolauth` approves it
  under **Org Settings → Personal access tokens → Pending requests**. A token in this state fails
  checkouts with a bare `Error: Not Found`, which looks identical to a missing or wrong-repo token
  — see [9.11](#911-checkout-of-env-config-fails-with-error-not-found).
- Recommended rotation: 90 days. Reissuing means generating a new fine-grained token scoped the
  same way, getting it approved, and updating the `ENV_CONFIG_PAT` secret — the workflow itself
  needs no changes.

---

## 6. Recreating the database from scratch

Flyway is configured with `cleanDisabled(true)`, so the application cannot drop anything. This is
deliberate; do it by hand.

```bash
cd /opt/versola
docker compose -f docker-compose.prod.yml down          # release the connections first
```

`DROP DATABASE` fails with *"is being accessed by other users"* while any service still holds a
pool connection, which is why the stack goes down before, not after.

```bash
sudo -u postgres psql
```

```sql
DROP DATABASE auth;
CREATE DATABASE auth OWNER versola_app;
```

Then recreate the three schemas exactly as in [3.1](#31-database-role-and-schemas) and bring the
stack back up. `central` will re-run its bootstrap and reseed clients, roles, forms and the admin
user from the values in `central.conf`.

Start `central` alone first and wait for it to be ready before bringing up `auth`/`edge` — see
[9.5](#95-authedge-never-become-ready-after-restarting-the-whole-stack-together) for why starting
all three at once is risky, and note that the automated pipeline already enforces this ordering
for you.

> Run the statements one at a time. Pasting a multi-line block that mixes `psql` meta-commands
> (`\c`) with SQL leads to confusing parse errors.

---

## 7. Configuration reference

Environment variables read by the containers:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | application port |
| `DPORT` | `8081` | diagnostics port: `/metrics`, `/liveness`, `/readiness` |
| `CONFIG_PATH` | `/app/config/env.conf` | path to the mounted HOCON config |
| `RUN_MIGRATIONS` | `true` | run Flyway on startup |
| `JAVA_OPTS` | `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0` | JVM flags |

Everything else — secrets, keys, database credentials, bootstrap data — lives in the HOCON file
(see [5](#5-the-env-config-repository)), not in environment variables.

---

## 8. Routine operations

```bash
cd /opt/versola
docker compose -f docker-compose.prod.yml logs -f --tail=100 central   # follow one service
docker compose -f docker-compose.prod.yml restart auth                 # restart in place
docker stats --no-stream                                               # live memory use
```

Logs are structured JSON, one object per line. To read them comfortably:

```bash
docker compose -f docker-compose.prod.yml logs --tail=200 auth | jq -r '"\(.timestamp) \(.level) \(.message)"'
```

---

## 9. Troubleshooting

These are real failures from production and from hardening the deploy pipeline, kept because each
one cost real time.

### 9.1 `FlywayValidateException` on startup

```
Validate failed: Migrations have failed validation
Detected applied migration not resolved locally: ...
```

Two services are sharing one Postgres schema and therefore one `flyway_schema_history` table.
Each sees the other's migrations as foreign. Fix: give each service its own schema via
`?currentSchema=` in its JDBC URL ([3.3](#33-pin-each-service-to-its-own-schema)), then reset the
affected database ([6](#6-recreating-the-database-from-scratch)) so history is rebuilt cleanly.

### 9.2 `central` crashes with `FileNotFoundException: forms/common.css`

The admin login forms under `central/src/main/resources/forms/` are *generated* by
`central-ui` (`npm run build:forms`) and are gitignored. If the Docker image is built without
that step, the files are simply absent and `BootstrapService` fails the moment it tries to seed
them — which only happens on an empty database, so the image looks fine until the first bootstrap.

Fixed in CI: the `docker-central` job runs `npm install && npm run build:forms` before building
the image. If you see this again, the CI step has been lost.

### 9.3 Container dies with no error in its own log

The log just stops. Confirm with:

```bash
sudo dmesg | tail -20 | grep -i 'killed process'
```

If the kernel OOM-killed it, the JVM never got a chance to log anything — the cgroup limit was
too low for the startup spike. Raise `mem_limit`. Note this looks completely different from a
JVM-level `OutOfMemoryError`, which *is* logged with a stack trace and means the heap is too small
relative to a limit that is otherwise being respected.

### 9.4 502 from `id.versola.kz`

Check `central` first. `auth` blocks on its initial sync from `central` during startup, so a
stopped or unhealthy `central` keeps `auth` from ever becoming ready. Start `central`, then
`auth`.

A bare `curl -sI https://id.versola.kz/` returning **404** (not 502) is expected and fine — there
is no route registered at `/`, this is an OAuth2/OIDC service, not a website. 404 here means nginx
successfully reached `auth` and `auth` answered. Only 502/000 indicates a real problem.

### 9.5 `auth`/`edge` never become ready after restarting the whole stack together

**Status: fixed at the application level** (`VersolaApp.run`'s failure handlers now call
`System.exit(1)` instead of just returning `ExitCode.failure`), but the section below is kept
because the *cause* is still worth understanding, and because it fully applies if you're ever
running an older image that predates the fix.

Both `auth` and `edge` read configuration from `central` once, synchronously, during their own
startup — `auth` syncs OAuth clients, `edge` syncs OAuth clients *and* authorization presets. If
`central` isn't listening yet at that exact moment (a real risk when all three come up together —
observed in production: `central` became ready roughly 0.4s **after** `auth` had already given
up), the failing service's startup effect fails and is logged as `Could not start application`.

Before the fix, the container did not exit and did not restart: each service's diagnostics server
(`auth`: 8081, `edge`: 8096) starts *before* the failing part and kept running independently,
holding the JVM alive on its own non-daemon threads, so `restart: unless-stopped` never fired.
With the fix, a failed startup now force-terminates the process (`System.exit(1)`), which *does*
trigger `restart: unless-stopped` — Docker's restart policy now provides the retry loop that the
application previously lacked. This turns a permanent zombie into a bounded number of automatic
restarts, which is normally enough once `central` is actually up, but is still a blunt retry (no
backoff, no cap) rather than a graceful wait-and-retry inside the application.

If you do see a container stuck (pre-fix image, or some other stall), confirm with:
```bash
docker inspect versola-auth --format='{{json .State}}'    # or versola-edge
```
`FinishedAt` at the zero timestamp with a `StartedAt` well in the past means the process has been
running since the original failed attempt, with no restart at all — that specifically indicates
the pre-fix behaviour. Recovery is the same either way: confirm `central` is ready, then
```bash
docker compose -f docker-compose.prod.yml restart auth   # and/or edge
```

**Practical rule, still true even with the fix:** never bring up all three services in one
`docker compose -f docker-compose.prod.yml up -d`. Start `central` alone, wait for `curl 127.0.0.1:8091/readiness` to return
200, *then* bring up `auth` and `edge`. The automated pipeline already does this for you by
deploying `central` first and gating `auth`/`edge` on it.

### 9.6 `docker pull` says the tag is not found

Check the prefix. Registry tags currently have no leading `v` even though the git tags do — see
[Tag naming](#tag-naming). `docker buildx imagetools inspect` or the package page on GitHub will
show what actually exists.

### 9.7 Recovering a user event stuck in the outbox dead letter table

`central` propagates user changes to `auth` via an outbox (`central.user_outbox`) with retries;
after `user-outbox.max-attempts` failed attempts (5 in prod) the event is moved to
`central.user_outbox_dead` and **stops retrying permanently**. This can happen as a side effect of
[9.5](#95-authedge-never-become-ready-after-restarting-the-whole-stack-together) — `central`
starts dispatching outbox events as soon as it's ready, and if `auth` is one of the services stuck
in the zombie state at that moment, the dispatch keeps failing until it's dead-lettered, even after
`auth` recovers.

Check for stuck events:

```sql
SELECT id, user_id, event_type, attempts, failed_at, error FROM central.user_outbox_dead;
```

Once the target service (`auth`) is confirmed ready, requeue by hand — there is no automatic
reprocessing of the dead letter table:

```sql
INSERT INTO central.user_outbox (id, user_id, event_type, payload, attempts, next_attempt_at)
SELECT id, user_id, event_type, payload, 0, NOW()
FROM central.user_outbox_dead
WHERE id = '<event-id>';

DELETE FROM central.user_outbox_dead WHERE id = '<event-id>';
```

`central`'s outbox processor picks it up on its next poll. Verify with `SELECT count(*) FROM
central.user_outbox_dead;` — should drop back to the count you started with minus the requeued
row (0, if this was the only one).

### 9.8 `ssh: handshake failed: ssh: host key fingerprint mismatch`

The `Deploy` workflow verifies the VPS host key against `secrets.VPS_HOST_FINGERPRINT` rather than
trusting it on first connect. If the host's SSH key ever changes (host rebuild, key rotation) or
the stored fingerprint was wrong to begin with, every deploy fails at the SSH step with this error
before touching anything on the server — which is the point of checking it, but it needs fixing to
proceed.

Get the current fingerprint from a machine that already has the host in its `known_hosts`:

```bash
ssh-keygen -lf ~/.ssh/known_hosts -F <host>
```

This prints one line per key type (ED25519, RSA, ECDSA) — try them if you're not sure which one
the workflow expects; in practice the ECDSA fingerprint has been the one that works here.
`ssh-keyscan` piped into `ssh-keygen -lf -` is the usual alternative, but can fail on Windows'
OpenSSH client with `unsupported KEX method sntrup761x25519-sha512@openssh.com` — prefer reading
from an existing `known_hosts` if you hit that.

Update the `VPS_HOST_FINGERPRINT` secret with the `SHA256:...` value (format: `SHA256:<base64>`).

### 9.9 `sed: couldn't open temporary file ... Permission denied`

`/opt/versola` and its contents are owned such that the deploy user needs `sudo` to write there —
`sed -i` creates a temp file in the *same directory* as the file it's editing, so this fails even
though the target file itself might look writable. Every in-place edit the pipeline makes
(`docker-compose.prod.yml`, the `.conf` files) is prefixed with `sudo -n` for this reason; if
you're extending the pipeline or doing this by hand, don't forget the same prefix. `-n` specifically
so a missing NOPASSWD sudo rule fails fast instead of hanging on a password prompt that can never
be answered non-interactively.

### 9.10 `docker compose`: `no configuration file provided: not found`

`docker compose` only auto-discovers a compose file named `compose.yaml` or `docker-compose.yml`
in the current directory — this deployment's file is explicitly named
`docker-compose.prod.yml`, so every `docker compose` invocation needs `-f docker-compose.prod.yml`
or it silently looks for the wrong filename and fails immediately, before touching any containers.
This is easy to lose when adding a new `docker compose` call to the pipeline or a runbook command —
all commands in this document already include the flag; if you add a new one, don't drop it.

### 9.11 Checkout of `env-config` fails with `Error: Not Found`

```
Error: Not Found - https://docs.github.com/rest/repos/repos#get-a-repository
```

`actions/checkout` can't resolve the repository with the token it was given. In order of how often
each cause has actually happened here:

1. **The `ENV_CONFIG_PAT` repository secret is missing** on `versola` — check
   Settings → Secrets and variables → Actions.
2. **The token is in `Pending` status**, awaiting organization approval — see
   [5 / Access](#access) for why fine-grained tokens scoped to an org repo need this, and where to
   approve it (`versolauth` org owner, Org Settings → Personal access tokens → Pending requests).
   No new token is needed once approved — just re-run the workflow.
3. **The token's Repository permissions don't include Contents: Read** on `env-config` — this is a
   separate step from selecting the repository when creating a fine-grained token.

---

## 10. Known gaps

- **The compose file is not in git.** It lives only at `/opt/versola/docker-compose.prod.yml`. It
  contains no secrets, so there is no reason for it not to be version-controlled alongside the
  code, in the way the nginx config already is and the way `env-config` now handles the `.conf`
  files. The pipeline edits it safely (backup + verified substitution), but the file itself is
  still not the source of truth anywhere — the server is.
- **`env-config` secrets are stored in plaintext.** Read access to that repository is equivalent
  to read access to production credentials — RSA private keys, the Postgres password, the shared
  `central` secret. This was a deliberate tradeoff (see [5](#5-the-env-config-repository)) in
  favour of pipeline simplicity, not an oversight, but it means repository access control on
  `env-config` *is* the security boundary and should be reviewed accordingly, and it's worth
  revisiting if the team or access list grows — encrypting at rest with something like `sops` or
  `git-crypt` would remove this exposure at the cost of a decryption step in the pipeline.
- **No public domain for `edge`.** Until one exists the admin console cannot be reached from a
  browser. Needs a DNS record, an nginx server block, a certificate, and `edgeUrl` in
  `edge.conf` regenerated to match.
- **`central-ui/package-lock.json` is gitignored**, so the `npm install` in CI is not reproducible
  between builds.
- **Interactive host access (for people, not the pipeline) is by password.** The `Deploy` workflow
  already authenticates with its own dedicated SSH key (`VPS_SSH_KEY`), which is unaffected by
  this. This gap is specifically about individual engineers' own logins to the host, which should
  move to key-based `authorized_keys` entries instead.
- **`docker-compose.prod.yml.bak` and the per-service `.conf.bak` files accumulate** on the host —
  every deploy overwrites the previous backup rather than keeping history, so at most one prior
  version is ever recoverable this way. Fine for the "did my last edit break the substitution"
  check they're there for; not a substitute for real version control or an audit trail.
