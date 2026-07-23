# Deployment

How Versola is deployed to the shared production host. For local development see
[`develop.md`](develop.md).

> **Scope.** This document describes the *current* deployment: a single VPS that also serves the
> marketing site, blog and docs. It is a runbook, not a product installation guide — commands
> here assume you have `sudo` on that host.

---

## 1. Topology

The host runs three different kinds of workload. Only one of them is ours to restart freely.

| Component | How it runs | Managed by |
|---|---|---|
| PostgreSQL 18 | native, `apt` + systemd, bound to `127.0.0.1:5432` | `systemctl` |
| `auth`, `central`, `edge` | Docker, `docker compose`, `network_mode: host` | `/opt/versola/docker-compose.prod.yml` |
| nginx (TLS termination, static sites) | native, `apt` + systemd | the [`nginx`](https://github.com/versolauth/nginx) repo, deployed by its own workflow |

Because the services use `network_mode: host`, each one binds directly to a localhost port on the
host and there is no Docker network between them. They reach Postgres and each other over
`127.0.0.1`.

### Ports

The compose file lives on the server and is not in git (see [Known gaps](#9-known-gaps)), so if
this table looks wrong, `cat /opt/versola/docker-compose.prod.yml` is the source of truth.

| Service | App port (`PORT`) | Diagnostics port (`DPORT`) | Exposed publicly |
|---|---|---|---|
| `auth` | 8080 | 8081 | yes — `https://id.versola.kz` via nginx |
| `central` | 8090 | 8091 | no — admin API, reached through `edge` |
| `edge` | 8095 | 8096 | not yet — needs a domain, see [Known gaps](#9-known-gaps) |

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
a 502 on `id.versola.kz` until `auth` succeeds.

---

## 2. Prerequisites

- Docker and the Compose plugin on the host.
- PostgreSQL 18 reachable at `127.0.0.1:5432`.
- [scala-cli](https://scala-cli.virtuslab.org/install) *on your workstation* — used once to
  generate configs. It is not needed on the server.
- Published images in `ghcr.io/versolauth/`: `versola-auth`, `versola-central`, `versola-edge`.
  All three packages are public, so the host does not need `docker login`.

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
> [Troubleshooting](#81-flywayvalidateexception-on-startup).

### 3.2 Generate configuration

On your workstation, in a checkout of this repo:

```bash
scala-cli run scripts/gen-env.scala
```

Answer `prod` (or any name that is **not** `local`) at the `Name:` prompt — `local` runs
non-interactively and silently writes development defaults. Output lands in
`.local/env/<name>/{auth,central,edge}.conf`.

`.local/` is gitignored and must stay that way: these files contain RSA private keys, the shared
`central` secret and the Postgres password. **Never commit them.**

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
by hand:

```bash
sudo sed -i 's|\(postgres[^\n]*url = "jdbc:postgresql://[^"]*\)"|\1?currentSchema=auth"|' \
  /opt/versola/config/auth.conf
# ...and the same for central.conf → currentSchema=central, edge.conf → currentSchema=edge
```

Verify — this must print three lines, one per file:

```bash
sudo grep -h currentSchema /opt/versola/config/*.conf
```

### 3.4 Install the configs on the host

```bash
sudo mkdir -p /opt/versola/config
# copy the three .conf files across (scp), then:
sudo chmod 600 /opt/versola/config/*.conf
```

`chmod 600` is not optional — these files contain private keys.

### 3.5 Compose file

`/opt/versola/docker-compose.prod.yml`, as currently deployed (0.1.1):

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
[Troubleshooting](#83-container-dies-with-no-error-in-its-own-log)).

**Two things this file should still grow:**

- The image tag is hardcoded three times, so deploying means editing the file (`sed -i
  's/:0\.1\.0/:0.1.1/g' docker-compose.prod.yml`, or `sudo nano`). Parameterising it as
  `:${VERSION:-latest}` makes a deploy a single command and is a precondition for driving this
  from a pipeline.
- `container_name` is pinned, which prevents ever running two instances side by side. Harmless
  today, worth knowing before attempting a zero-downtime rollout.

When editing this file directly (no version control, see [Known gaps](#9-known-gaps)), back it up
and diff before applying — `cp docker-compose.prod.yml docker-compose.prod.yml.bak`, then `diff`
after, to catch accidental changes to lines you didn't mean to touch.

### 3.6 nginx

Do **not** hand-edit nginx on the server. The config is version-controlled in the
[`nginx`](https://github.com/versolauth/nginx) repo (`envs/dev/versola.conf`) and its workflow
copies it over, runs `nginx -t` and reloads on merge to `main`. Editing the file in place means
the next deploy silently reverts you.

---

## 4. Deploying a new version

Images are built and pushed **only** by the `release: published` event — merging to `main` runs
tests but publishes nothing. So a deploy always starts with cutting a release.

1. **Cut a release.** GitHub → Releases → *Draft a new release* → new tag, target `main` →
   *Generate release notes* → *Publish*.
2. **Wait for the images.** In Actions, `docker-auth`, `docker-central` and `docker-edge` all run
   on every release, regardless of which service actually changed. All three must be green.
3. **Deploy on the host.** The tag is hardcoded in the compose file, so edit it first:

   ```bash
   cd /opt/versola
   sudo nano docker-compose.prod.yml       # bump the tag on the service(s) being deployed
   docker compose pull
   docker compose up -d
   ```

   To deploy a single service, add its name: `docker compose up -d --no-deps central`.

4. **Verify:**

   ```bash
   docker compose ps                         # all Up
   curl -sf http://127.0.0.1:8081/readiness  # auth    → 200
   curl -sf http://127.0.0.1:8091/readiness  # central → 200
   curl -sf http://127.0.0.1:8096/readiness  # edge    → 200
   curl -sI https://id.versola.kz/
   ```

> **Tag naming.** The image tag is the git tag verbatim (`version=${{ github.event.release.tag_name }}`
> in `ci-cd.yml`, no normalisation). The `0.1.0` images in the registry carry **no** `v` prefix, so
> `docker pull ...:v0.1.0` fails while `...:0.1.0` works. Pick a convention and keep it — the tag
> you type into a release is the tag you will have to type into `docker pull` forever after.

### `RUN_MIGRATIONS`

Every service runs Flyway on startup. `RUN_MIGRATIONS` controls whether it does:

- unset → migrations run (the historical behaviour; existing deployments are unaffected)
- `true` / `false` → as specified, case-insensitive
- anything else → the service **fails to start** with an explanatory error, deliberately. A flag
  whose whole purpose is to skip migrations must not silently run them because of a typo.

Set it to `false` when you want a rollout to be strictly a code change — for example when
deploying a hotfix and you want to be certain nothing touches the schema.

---

## 5. Recreating the database from scratch

Flyway is configured with `cleanDisabled(true)`, so the application cannot drop anything. This is
deliberate; do it by hand.

```bash
cd /opt/versola
docker compose down                     # release the connections first
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
[8.5](#85-authedge-never-become-ready-after-restarting-the-whole-stack-together) for why starting
all three at once is risky.

> Run the statements one at a time. Pasting a multi-line block that mixes `psql` meta-commands
> (`\c`) with SQL leads to confusing parse errors.

---

## 6. Configuration reference

Environment variables read by the containers:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | application port |
| `DPORT` | `8081` | diagnostics port: `/metrics`, `/liveness`, `/readiness` |
| `CONFIG_PATH` | `/app/config/env.conf` | path to the mounted HOCON config |
| `RUN_MIGRATIONS` | `true` | run Flyway on startup |
| `JAVA_OPTS` | `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0` | JVM flags |

Everything else — secrets, keys, database credentials, bootstrap data — lives in the HOCON file,
not in environment variables.

---

## 7. Routine operations

```bash
cd /opt/versola
docker compose logs -f --tail=100 central   # follow one service
docker compose restart auth                 # restart in place
docker stats --no-stream                    # live memory use
```

Logs are structured JSON, one object per line. To read them comfortably:

```bash
docker compose logs --tail=200 auth | jq -r '"\(.timestamp) \(.level) \(.message)"'
```

---

## 8. Troubleshooting

These are real failures from the first production rollout, kept because each one cost hours.

### 8.1 `FlywayValidateException` on startup

```
Validate failed: Migrations have failed validation
Detected applied migration not resolved locally: ...
```

Two services are sharing one Postgres schema and therefore one `flyway_schema_history` table.
Each sees the other's migrations as foreign. Fix: give each service its own schema via
`?currentSchema=` in its JDBC URL ([3.3](#33-pin-each-service-to-its-own-schema)), then reset the
affected database ([section 5](#5-recreating-the-database-from-scratch)) so history is rebuilt
cleanly.

### 8.2 `central` crashes with `FileNotFoundException: forms/common.css`

The admin login forms under `central/src/main/resources/forms/` are *generated* by
`central-ui` (`npm run build:forms`) and are gitignored. If the Docker image is built without
that step, the files are simply absent and `BootstrapService` fails the moment it tries to seed
them — which only happens on an empty database, so the image looks fine until the first bootstrap.

Fixed in CI: the `docker-central` job runs `npm install && npm run build:forms` before building
the image. If you see this again, the CI step has been lost.

### 8.3 Container dies with no error in its own log

The log just stops. Confirm with:

```bash
sudo dmesg | tail -20 | grep -i 'killed process'
```

If the kernel OOM-killed it, the JVM never got a chance to log anything — the cgroup limit was
too low for the startup spike. Raise `mem_limit`. Note this looks completely different from a
JVM-level `OutOfMemoryError`, which *is* logged with a stack trace and means the heap is too small
relative to a limit that is otherwise being respected.

### 8.4 502 from `id.versola.kz`

Check `central` first. `auth` blocks on its initial sync from `central` during startup, so a
stopped or unhealthy `central` keeps `auth` from ever becoming ready. Start `central`, then
`auth`.

A bare `curl -sI https://id.versola.kz/` returning **404** (not 502) is expected and fine — there
is no route registered at `/`, this is an OAuth2/OIDC service, not a website. 404 here means nginx
successfully reached `auth` and `auth` answered. Only 502/000 indicates a real problem.

### 8.5 `auth`/`edge` never become ready after restarting the whole stack together

Both `auth` and `edge` read configuration from `central` once, synchronously, during their own
startup — `auth` syncs OAuth clients, `edge` syncs OAuth clients *and* authorization presets. If
`central` isn't listening yet at that exact moment (a real risk when all three come up together —
observed on 0.1.1: `central` became ready roughly 0.4s **after** `auth` had already given up), the
failing service's startup effect fails and is logged as `Could not start application` — but **the
container does not exit and does not restart**. Each service's diagnostics server (`auth`: 8081,
`edge`: 8096) starts *before* the failing part and keeps running independently, holding the JVM
alive on its own non-daemon threads. The process sits there indefinitely: `docker inspect` shows
`Running: true`, `FinishedAt` stuck at the zero value, `/liveness` answers, `/readiness` never
does. `restart: unless-stopped` never fires because the container never actually exits.

Confirm with:
```bash
docker inspect versola-auth --format='{{json .State}}'    # or versola-edge
```
`FinishedAt` at the zero timestamp with a `StartedAt` well in the past confirms this — the process
has been running since the original failed attempt, not since some later automatic restart.

There is no self-healing here. Fix: once `central` is confirmed ready, restart the affected
service by hand —

```bash
docker compose -f docker-compose.prod.yml restart auth   # and/or edge
```

— this is a one-shot retry, not a loop, so it only works once `central` is actually up. This is a
real gap in the application (`VersolaApp.run`'s dependency construction has no retry around it,
and a failed `run` doesn't terminate the process when a background fiber — here, the diagnostics
server — is still holding the JVM open); worth raising as a fix rather than treating the manual
restart as the permanent answer.

**Practical rule until this is fixed in code:** never bring up all three services in one
`docker compose up -d`. Start `central` alone, wait for `curl 127.0.0.1:8091/readiness` to return
200, *then* bring up `auth` and `edge`. If you do restart everything together anyway, expect to
have to manually restart `auth` and/or `edge` afterward and check `docker inspect` on each rather
than trusting `docker compose ps`'s `Up` status — `Up` only means the container hasn't exited, not
that the application inside is actually ready.

### 8.6 `docker pull` says the tag is not found

Check the prefix. Registry tags currently have no leading `v` even though the git tags do — see
[Tag naming](#4-deploying-a-new-version). `docker buildx imagetools inspect` or the package page
on GitHub will show what actually exists.

### 8.7 Recovering a user event stuck in the outbox dead letter table

`central` propagates user changes to `auth` via an outbox (`central.user_outbox`) with retries;
after `user-outbox.max-attempts` failed attempts (5 in prod) the event is moved to
`central.user_outbox_dead` and **stops retrying permanently**. This can happen as a side effect of
[8.5](#85-authedge-never-become-ready-after-restarting-the-whole-stack-together) — `central`
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

---

## 9. Known gaps

- **The compose file is not in git.** It lives only at `/opt/versola/docker-compose.prod.yml`. It
  contains no secrets, so there is no reason for it not to be version-controlled alongside the
  code, in the way the nginx config already is. Doing so would also stop this document drifting
  from the deployment it describes.
- **`auth`/`edge` don't recover from a failed startup sync with `central`** — see
  [8.5](#85-authedge-never-become-ready-after-restarting-the-whole-stack-together). This is the
  biggest real gap found so far: it's an application-level issue (`VersolaApp.run` has no retry
  around dependency construction, and a failed `run` doesn't terminate the process), not something
  fixable from the deployment side alone. Worth raising as a proper fix rather than permanently
  relying on the manual-restart workaround.
- **No public domain for `edge`.** Until one exists the admin console cannot be reached from a
  browser. Needs a DNS record, an nginx server block, a certificate, and `edgeUrl` in
  `edge.conf` regenerated to match.
- **Deployment is manual.** A `workflow_dispatch` pipeline (version / service / run-migrations)
  is planned; `RUN_MIGRATIONS` exists specifically so its migration checkbox can mean something.
  The [`nginx`](https://github.com/versolauth/nginx) repo's `deploy.yml` is a working precedent
  for the SSH half.
- **`central-ui/package-lock.json` is gitignored**, so the `npm install` in CI is not reproducible
  between builds.
- **Host access is by password.** Should be a deploy key in `authorized_keys` instead — required
  anyway before a deployment pipeline can authenticate.
