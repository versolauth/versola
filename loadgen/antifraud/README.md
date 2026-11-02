# Antifraud loadgen: the OTP flow with and without antifraud

Tracking issue: #771, task P0-7. `loadgen` with `role = antifraud` drives the phone sign-in (a code is requested,
entered, exchanged, refreshed) against one `auth` (or several, see "Two or more `auth` instances"), in nine scenarios and
a tenth that is run by name (`fake-timing`), and writes what it measured. Run once with
antifraud off and once with it on, the second run says what the switch added.

Whether antifraud is on is `auth`'s own configuration, not this process's: each run is made against an `auth` that
was started with one **profile** (`auth-profiles/`), and `AF_LABEL` names it in the report.

## What it measures

| Scenario | What it does | What it shows |
|---|---|---|
| `normal` | 20 sign-ins a second to new numbers: code requested, entered by 85 %, then `/token` and 5-10 refreshes (the target profile) | the latency of the protected calls on ordinary traffic; the SMS an ordinary user costs; the legitimate users refused |
| `token-control` | 1000 sign-ins (their code exchanges are measured, the rest of them is not), then 50 refreshes a second over them | `/token` and refresh, which antifraud must not touch: their added p99 must be zero (within noise) |
| `prefix-attack` | 20 codes a second to numbers of **one** range (country code + 4 digits), none entered | SMS pumping: how many codes are sent, how many refused |
| `device-attack` | 20 codes a second from **one source** to numbers all over the 110 ranges, none entered | what a per-range budget does not stop: a sender who spreads |
| `distributed-attack` | the prefix attack from **500 sources** (about five requests each), none entered | what a per-source limit does not see: a botnet or a proxy pool on one range |
| `mixed-attack` | the prefix attack **and** 20 legitimate sign-ins a second at the same time, a tenth of the legitimate users dialling inside the attacked range | the false positives: the share of legitimate users refused in the attacked range and outside it ("a closed range refuses everyone in it") |
| `conversion-mimicking` | an attack on one range whose sender enters the code for a third of the numbers | whether a range with a conversion is still closed |
| `targeted-phone` | 5 codes a second for **one** number | SMS bombing / lockout of its owner: what the submission limit lets through |
| `verify-guessing` | codes requested for new numbers, then five random codes entered for each | a guess taken (must be none) and what the verify limit does |
| `fake-timing` (by name only) | `requests` codes to numbers of a `fake` list entry and as many to real ones, alternating, each from a source of its own; the response time of the call that decides, of each group | whether a fake send is told from a real one by timing: the medians must not differ by more than `fake-timing.tolerance` (25 ms). See "Fake timing" |

Per scenario and per call (`otp-request`, `otp-resend` of a plain client, `otp-verify`, `token`, `refresh`): count, failures, p50, p99; per
scenario: codes the SMS stub was asked to send (the stub counts them, per range), requests refused ("access
denied") and `auth`'s `antifraud_*` metrics over the run (events enqueued, written, dropped by reason, requests
refused by the budget, the event queue's largest size).

Three kinds of verdict, in the report and in the exit code (non-zero when any fails):

1. **Added latency**, against a baseline report (`antifraud.baseline-path`): the added p99 of each call, against
   `added-latency-ceiling` (10 ms) for `otp-request` and `otp-verify` and `control-tolerance` (5 ms, the noise of a
   run) for `token` and `refresh`.
2. **Effectiveness**, on the run alone: an attack scenario fails when more than `attack-sms-ceiling` (0.1) of the
   codes it asked for were sent (the pumping loss); `verify-guessing` fails when a guess is taken.
3. **False positives**, on the run alone: `normal`, and the legitimate users of `mixed-attack` (in the attacked range
   and outside it), fail when more than `legit-refusal-ceiling` (0.01) of their requests were refused.

Checks 2 and 3 are judged on **every** run, the baseline's included: with antifraud off the attacks fail check 2 by
design, and with only the per-range budget on, `device-attack` and `targeted-phone` still do and `mixed-attack`
fails check 3. A red result there is the finding, not a defect of the tool; it is what the later mechanisms are to
turn green.

How the load is shaped: arrivals are on a schedule (open loop): the n-th session of a scenario starts at
`start + n / rate` whether or not the earlier ones have finished, so a slow system does not slow the offered load
down. A call's latency is measured from the moment the loadgen sends it (it includes waiting for a pooled
connection) to the end of its response, **not from the scheduled time**: if the loadgen itself fell behind its
schedule that delay is not in the latency. The one place that can happen is `max-in-flight` (256 sessions at once),
and every arrival that had to wait for it is counted in `waited`; a run where `waited` is not 0 measured the
loadgen. Latencies are of the successful calls only; failures are counted and listed with their cause.

Where the requests come from: every request leaves the loadgen's one address (loopback in the local stack), and
`auth` reads the client's address from the header the tenant's `ipHeader` names (`X-Forwarded-For` for the
provisioned tenant, the last value of it, from whoever connects: in a deployment the proxy sets it, here this
process does). `antifraud.sources` says what each session puts there: a legitimate session an address from a pool
of `legit` (2000 for 2400 sign-ins: a few share one, as behind a NAT), the distributed attack one of `distributed`
(500), every other attack one address of its own for the scenario, so that a source one scenario got blocked is not
met by the next. With `header = ""` nothing is sent and the whole run is one client to the counters and the lists,
which is how the runs before `velocity` were made. No device identifier is sent (none exists before P1-1).

### Attack shapes not covered (follow-ups)

- **Many devices**: the sources vary (above), the device does not, since nothing issues one before P1-1; a
  scenario of one address and many device ids waits for that.
- **A weaker or a smarter client than the two**: `script` solves with one thread per proof at the JVM's speed (a few million
  hashes a second per core), which is slower than the browser's kernel on a desktop and faster than a phone; a GPU or an
  ASIC, or a client that solves and then discards the conversation, is what an attacker with money has. `challenge.max-difficulty`
  is the loadgen's stand-in for "what the attacker will pay": a challenge over it is left unanswered and counted.
- **Slow-and-low**: the prefix attack at a few codes a minute for hours; a config of `prefix-attack` (`rate`,
  `duration`) but longer than a CI-sized run, and meaningful only against a budget window of an hour.
- **Hot-key behaviour of the velocity counters** and a **micro-benchmark of the `evaluate` path** (CPU, GC, DB
  reads and writes per instance): a JMH benchmark is a new dependency (`project/Dependencies.scala`) that needs agreement
  first. Several instances are driven now (below).

## Profiles

`auth-profiles/off.conf` is the local dev config of `scripts/gen-env.scala` plus the two things the scenarios
need: `env = prod` (a non-prod `auth` sends no SMS and always issues the same code) and an `otp-provider` pointing
at the stub that `loadgen` serves on `:9100`. Each profile after it **includes the one before** and sets only its
own keys, so a run differs from the one it is compared with by exactly those:

| Profile | Adds | The stored settings it was recorded with |
|---|---|---|
| `sms-budget` | `antifraud.sms-budget.prefix-length = 6` | `sentPerHour 200` per range, every limit 0 |
| `lists` | `decision.enabled`, `lists.enabled` (no entry: the cost of the lookup and of `evaluate`) | the same |
| `velocity` | `velocity.enabled` (counting, flushing, loading; no limit fires) | the same |
| `evaluate` | `challenge.enabled = false`, `challengeAt 0` (above) | `blockAt 50`; phone 3/5/10/20 a minute/10 min/hour/day, weight 60; ip 30/100/300/1000, weight 50; prefix 150/600/2000/10000, weight 50 |
| `evaluate-no-budget` (the same profile) | nothing | the `evaluate` settings with `sentPerHour 0`: the velocity limits alone |
| `challenge` | `challenge.enabled = true`, `pause` (the proof of work and the pause are on; the profile includes `evaluate`) | `"mode": "enforce"`, `challengeAt` above 0 and below `blockAt`, the velocity limits of `evaluate` (the runbook's `tuning/challenge.json`) |

The limits are stored settings, not keys of the config: in a deployment they are set through central's
`PUT /antifraud/settings` (deploy.md), and `auth` rereads them within `antifraud.tuning.refresh-interval` (5 s).
The local stack is driven by the `utils` client, which has no antifraud permission, so the settings of each profile
are written straight into `af_settings` by the `tune.sh` of the runbook (below), after `auth` has started with the
profile and before the run (`AF_TUNE_VIA=api` makes it `PATCH /antifraud/settings` through central instead, once the admin API
of versolauth/versola#884 is on `main`). A settings document needs `"mode": "enforce"` since versolauth/versola#882: without it
the decision is computed and recorded but nothing is acted on.

**The scripts that drive a whole ladder of runs, the settings of each profile and the recorded runs are not here:**
they are in [versolauth/loadgen-runbook](https://github.com/versolauth/loadgen-runbook), `scripts/antifraud-local/`
(the stack on ports of its own, `run-profile.sh` per profile, `tune.sh`, the settings JSON, the table maker) and
`docs/12-antifraud-p0-local.md` (the runs of 2026-10-29 and what they say). This folder holds what the tool itself
needs: the scenarios, the profiles and the commands to run one by hand.

## The two kinds of client, and the challenge

A `Challenge` decision is a request for work, and what the loadgen does with it is a property of the **client**, set with
`antifraud.otp.client` (`AF_OTP_CLIENT`):

| `otp.client` | What it posts | When it is challenged |
|---|---|---|
| `script` (default) | the form of a page with script: `POST /challenge/phone` with `proof_inline=1`, as `central-ui/forms/shared/inline.ts` sends it | `auth` answers a JSON `InlineStep` with the challenge (`kind`, `token`, `params.seed`, `params.difficulty`); the client solves it (`Pow.scala`: SHA-256 of `seed:nonce` with `difficulty` leading zero bits, the `pow.ts` kernel on the JVM) and posts the same form again with `proof_token` and `proof_solution`, up to `challenge.max-rounds` (3) times in a row, then gets the code |
| `plain` | the form with no script: no `proof_inline` | cannot be given a proof, so the request is **left pending** (the code page, nothing sent); the client waits `challenge.pause` (+ `pause-slack`) and asks for the code again with `POST /challenge/otp/resend`, which `auth` honours only after its own pause (versolauth/versola#882, "the pause is the price of a client that cannot do work"). An ask that is early is answered with the same page; the client asks again every `pause-slack`, at most `challenge.max-asks` times |

`challenge.pause` of this process and `antifraud.challenge.pause` of the auth profile are the same number, set twice: keep
them equal. Without the pause in `auth` (a build before #882) a plain client's ask is never honoured and ends as `other` (`pending`).

What the report says, kept apart from a refusal (a challenged request that pays is not a refused one):

- outcomes: `challenged` (put a proof, or left pending), `solved` (did the work and was sent the code), `paid by waiting`
  (a plain client that waited and was sent the code), `refused` (a Block or the budget), `other` (`challenge unanswered`:
  a kind the client cannot solve, more rounds than `max-rounds`, or a difficulty over `challenge.max-difficulty`; `pending`: a
  plain ask that was never honoured);
- the **Challenges** section: challenges put, solved, unanswered, the solve time p50/p99 (this process's CPU: with more challenges
  than `challenge.solver-threads` the wait for a thread is not in it), hashes in all and per challenge p50/p99, plain requests left
  pending, asks honoured and asks that were too early, and the **time of the whole request including the solving or the wait**
  (what the person waited), where `otp-request` is the server's time over the posts of the request and so stays comparable with
  a run that was never challenged (`otp-resend` is the ask after the pause). So an attack that pays with work reads as one:
  `solved` is high, `refused` low, and the SMS it got through is `SMS sent`.

A plain client holds a session for the pause: at 20 a second and 20 s that is 400 sessions at once, so run it with
`max-in-flight = 1024` (`AF_MAX_IN_FLIGHT`; the run warns, and `waited` says it if it was too low). The default stays `script`, and
a run is only comparable with a baseline made with the same client: the posts of the **earlier runs recorded in the runbook were
plain form posts** (no `proof_inline`), so re-record `off` with the client the rung is run with.

## Two or more `auth` instances

`targets.auth-urls` (a list; `auth-url` alone is the list of one) names the instances and `antifraud.auth-metrics-urls` their
diagnostics (one each; the counters, the dropped events and the queue sizes are summed per scenario). Each session is given
an instance in turn (round robin) **and stays on it for all its calls**: a conversation, an authorization code and a refresh
token are in Postgres (`auth_conversations`, with an optimistic version; the code and session repositories), and the cookie is signed
with a key from the shared configuration, so another instance could continue one, but every call of a session going to one
instance is what a balancer with a sticky cookie does, and hopping inside a session is not exercised by the tool. What is in
**memory per instance** is what the second instance changes in a run: the SMS budget windows (a range is closed after
`sentPerHour` codes *at that instance*, so the effective limit is up to the number of instances times it), the velocity counters
between a flush and the other instances' load (`flush-interval + load-interval`), and the lists until the next refresh.
Round robin splits a scenario's load evenly, so a `distributed-attack` or `normal` run against two instances measures exactly that.
The report names the instances. The runbook's `stack.sh start_auth2` starts the second one on its own ports against the same Postgres.

## Fake timing

`auth` answers a request for a number on a `fake` list entry as it would answer a real one and sends nothing; to a person
timing the answers a fake that is faster (or slower) than a real send says which codes are never delivered. The `fake-timing`
scenario is a black-box check of that and is **run only when named** (`scenarios = ["fake-timing"]`), since it needs an entry:
make one that covers a range, `fake-timing.fake-prefix` (or one `phone_hash` entry per number, `fake-numbers`), before the run
(the runbook's `seed-fake-entry.sh`, the SQL or the admin API), keep the velocity limits and the budget from deciding (the
settings of `sms-budget.json`, so that only the entry acts), and give the SMS stub the delay of a real send
(`sms-stub.response-delay`, `AF_SMS_DELAY`: `auth` makes a fake wait as long as the real ones have been taking, so with a stub that
answers at once there is nothing to match). It makes `requests` requests to each kind, in random order, each from a source of its
own; the call timed is the post of the phone, over the requests that were sent. The verdict is **Fail** when

- the medians differ by more than `fake-timing.tolerance` (25 ms) (p90 and p99 gaps are reported, not judged), or
- a fake number was sent a code (what reached the stub: the entry is not seeded, not reloaded yet, or does not match), or no real
  number was (the stub is not what `auth` sends to), or fewer than 20 of a group were sent: a pass would mean nothing then.

## Commands (the local stack)

Everything below runs from the repository root. It starts its own Postgres on 5433 and the services on the ports
`develop.md` names (9001-9011); stop whatever holds them first. (The runbook's ladder, `scripts/antifraud-local/`, lives on other ports
instead, 9101-9118 and Postgres on 5500, so as to run beside this stack: use its `stack.sh` and `run-profile.sh` for the ladder
and the commands below for one run by hand.)

```bash
# 1. Postgres of its own, and the dev configs pointed at it
docker run -d --name versola-pg-af-loadgen -e POSTGRES_USER=dev -e POSTGRES_PASSWORD=1234 -e POSTGRES_DB=auth -p 127.0.0.1:5433:5432 postgres:17-alpine
echo local | scala-cli run --server=false scripts/gen-env.scala
sed -i.bak 's/localhost:5432/localhost:5433/' auth/dev/env.conf central/dev/env.conf edge/dev/env.conf

# 2. The forms first (central seeds them at boot; an auth without them answers every page 404), then the stage
(cd central-ui && npm ci && npm run build:forms)
sbt "central-postgres-impl/stage" "auth-postgres-impl/stage" "edge-postgres-impl/stage" "loadgen/stage"

# 3. Central, edge, and auth with the DEV config for now: provisioning forces auth to re-read central through its
#    non-prod /service/configuration/sync, which an auth in prod does not have (central then fails the call with a 500)
PORT=9001 DPORT=9002 MPORT=9011 RUN_MIGRATIONS=true central/implementations/postgres/target/universal/stage/bin/central-postgres-impl -Denv.path=central/dev/env.conf
PORT=9003 DPORT=9004 APORT=9007 MPORT=9008 RUN_MIGRATIONS=true auth/implementations/postgres/target/universal/stage/bin/auth-postgres-impl -Denv.path=auth/dev/env.conf
PORT=9005 DPORT=9006 MPORT=8083 RUN_MIGRATIONS=true edge/implementations/postgres/target/universal/stage/bin/edge-postgres-impl -Denv.path=edge/dev/env.conf

# 4. The tenant, the clients and the phone-OTP flow (once; central keeps them, so they survive an auth restart)
loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/provision.conf

# 5. Now stop auth and start it again with the profile under test (env = prod, the SMS stub, the antifraud keys);
#    it reads the configuration from central at boot
PORT=9003 DPORT=9004 APORT=9007 MPORT=9008 RUN_MIGRATIONS=true auth/implementations/postgres/target/universal/stage/bin/auth-postgres-impl -Denv.path=loadgen/antifraud/auth-profiles/off.conf
```

After the restart in step 5 wait for `auth`'s readiness (`curl localhost:9004/readiness`) before the first run.

```bash
# 6. Antifraud off: the baseline. Writes results/off.json and results/off.md
AF_LABEL=off AF_REPORT=loadgen/antifraud/results/off.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf

# 7. The same auth again, to know the noise: the added p99 of off against off is what a difference has to exceed
#    (restart auth first, so that both runs begin cold)
AF_LABEL=off-repeat AF_REPORT=loadgen/antifraud/results/off-repeat.json AF_BASELINE=loadgen/antifraud/results/off.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf

# 8. Restart auth with the profile switched on, put its settings in, and run against the baseline
#    (stop auth, then the auth line of step 5 with auth-profiles/sms-budget.conf; wait for readiness; write the
#    profile's settings into af_settings and bump af_settings_version, which is what the runbook's tune.sh does)
AF_LABEL=sms-budget AF_REPORT=loadgen/antifraud/results/sms-budget.json AF_BASELINE=loadgen/antifraud/results/off.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf

# 9. The same for lists.conf and velocity.conf (settings unchanged), then evaluate.conf with its limits
AF_LABEL=evaluate AF_REPORT=loadgen/antifraud/results/evaluate.json AF_BASELINE=loadgen/antifraud/results/off.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf
```

```bash
# 10. The proof of work: auth-profiles/challenge.conf with the settings of the runbook's tuning/challenge.json, once with a client
#     that solves and once with one that waits (AF_MAX_IN_FLIGHT: a plain client holds its sessions for the pause)
AF_LABEL=challenge-script AF_OTP_CLIENT=script AF_REPORT=loadgen/antifraud/results/challenge-script.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf
AF_LABEL=challenge-plain AF_OTP_CLIENT=plain AF_MAX_IN_FLIGHT=1024 AF_REPORT=loadgen/antifraud/results/challenge-plain.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf
```

`results/` is not committed (`.gitignore`): the first run of a ladder is always `off`, and it is the baseline of the
ones after it. The runs that were recorded are in the runbook (above).

The process exits non-zero when a verdict is `Fail`. Restart `auth` between runs of different profiles; the budget
remembers an hour, and the `prefix-attack` range is drawn afresh for each run so that a second run does not meet
the first one's closed range. **The velocity counters survive the restart** (`af_velocity`: the new instance reads
the old one's rows as another instance's), so before a run with limits on, `TRUNCATE af_velocity` on the stack's
Postgres, or the run inherits the hour of attacks the last one made (its `normal` users then get refused in the
ranges that were attacked: that is what happened on the first `evaluate` run here).

Shorter or lighter runs: put the keys to change in a file that includes `loadgen.conf` (`include required(file(...))`,
then `antifraud { normal { rate = 5.0, duration = 10s } }`) and pass it as `-Denv.path`. `antifraud.scenarios`
chooses which scenarios run; `otp.source = fixed` drives a non-prod `auth` (nothing is sent then, so no send is
counted, but the latencies and the budget's refusals still are).

Against a deployed stack: set `targets.auth-url` (or `targets.auth-urls`) and `antifraud.auth-metrics-url` (or `auth-metrics-urls`: the diagnostics port), and make
the stub reachable from `auth`: `antifraud.sms-stub.bind-host` / `port` here and the same address in `auth`'s
`otp-provider.url`. The tenant needs `phoneRegions` that accept the numbers of `antifraud.numbers`.

`sut-migrations.sha256` fingerprints the migrations of the campaign tooling's seeder; this role reads no SUT
database and adds no migration, so it is untouched.

## Reading the numbers

- Everything runs on one machine in the runs the runbook records (loadgen, `auth`, `central`, `edge`, Postgres): the
  absolute latencies are of a laptop, only differences between runs on the same machine mean anything, and
  `off-repeat` is what a difference of nothing looks like.
- Added p99 is a difference of two p99s of a few hundred to a few thousand samples: its error is several
  milliseconds. A pass says no more than "not shown to be over the ceiling".
- `device-attack` is **expected not to be stopped** by the SMS budget: it is the case the per-range budget does not
  cover, which the velocity counters (P0-3), the lists (P0-4) and the device identity (P1-1) are for. It is in the
  set so that those tasks have a number to improve.
- The event queue's peak is sampled every 250 ms; a peak shorter than that is missed, and `dropped` is exact.

## Results

The recorded runs (seven profiles on one machine, 2026-10-29, built from `main` at `4b6ca47a`) and what they say are
in the runbook, `docs/12-antifraud-p0-local.md`, with every report (`.json` and `.md`) next to it. In short: the
finished P0 stack stops every one-source attack and SMS bombing of one number and refuses no legitimate user outside
the attacked range; the distributed attack is a trade-off between the range budget and the users of the range.
