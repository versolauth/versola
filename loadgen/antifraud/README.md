# Antifraud loadgen: the OTP flow with and without antifraud

Tracking issue: #771, task P0-7. `loadgen` with `role = antifraud` drives the phone sign-in (a code is requested,
entered, exchanged, refreshed) against one `auth`, in four scenarios, and writes what it measured. Run once with
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

Per scenario and per call (`otp-request`, `otp-verify`, `token`, `refresh`): count, failures, p50, p99; per
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
- **A client that solves the proof of work** (`antifraud.challenge`): this process posts forms and solves nothing,
  so a `Challenge` decision leaves its request on the code step with no code, which it reads as a refusal where a
  browser would get the code a few hundred milliseconds later. The `evaluate` profile therefore turns the challenge
  off and sets `challengeAt` to 0, and measures `Block` alone; a solving client (the `pow.ts` kernel in Scala) is
  what a `challenge` profile needs.
- **Slow-and-low**: the prefix attack at a few codes a minute for hours; a config of `prefix-attack` (`rate`,
  `duration`) but longer than a CI-sized run, and meaningful only against a budget window of an hour.
- **Hot-key behaviour of the velocity counters** and a **micro-benchmark of the `evaluate` path** (CPU, GC, DB
  reads and writes per instance, 2+ `auth` instances): a JMH benchmark is a new dependency
  (`project/Dependencies.scala`) that needs agreement first, and the second instance needs several `auth-url`s and
  summed metrics here.

## Profiles

`auth-profiles/off.conf` is the local dev config of `scripts/gen-env.scala` plus the two things the scenarios
need: `env = prod` (a non-prod `auth` sends no SMS and always issues the same code) and an `otp-provider` pointing
at the stub that `loadgen` serves on `:9100`. Each profile after it **includes the one before** and sets only its
own keys, so a run differs from the one it is compared with by exactly those:

| Profile | Adds | What the stored settings hold (`tuning/`) |
|---|---|---|
| `sms-budget` | `antifraud.sms-budget.prefix-length = 6` | `sms-budget.json`: `sentPerHour 200` per range, every limit 0 |
| `lists` | `decision.enabled`, `lists.enabled` (no entry: the cost of the lookup and of `evaluate`) | the same |
| `velocity` | `velocity.enabled` (counting, flushing, loading; no limit fires) | the same |
| `evaluate` | `challenge.enabled = false` (above) | `evaluate.json`: `blockAt 50`; phone 3/5/10/20 a minute/10 min/hour/day, weight 60; ip 30/100/300/1000, weight 50; prefix 150/600/2000/10000, weight 50 |
| `evaluate-no-budget` (the same profile) | nothing | `evaluate-no-budget.json`: `evaluate.json` with `sentPerHour 0`: the velocity limits alone |

The limits are stored settings, not keys of the config: in a deployment they are set through central's
`PUT /antifraud/settings` (deploy.md). The local stack is driven by the `utils` client, which has no antifraud
permission, so `tune.sh <json>` writes the row as the API would and bumps the version (`auth` rereads it within
`antifraud.tuning.refresh-interval`, 5 s). Run it after `auth` has started with the profile and before the run.

## Commands (the local stack)

Everything below runs from the repository root. It starts its own Postgres on 5433 and the services on the ports
`develop.md` names (9001-9011); stop whatever holds them first.

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
#    (stop auth, then the auth line of step 5 with auth-profiles/sms-budget.conf; wait for readiness)
loadgen/antifraud/tune.sh loadgen/antifraud/tuning/sms-budget.json
AF_LABEL=sms-budget AF_REPORT=loadgen/antifraud/results/sms-budget.json AF_BASELINE=loadgen/antifraud/results/off.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf

# 9. The same for lists.conf and velocity.conf (settings unchanged), then evaluate.conf with its limits
loadgen/antifraud/tune.sh loadgen/antifraud/tuning/evaluate.json
AF_LABEL=evaluate AF_REPORT=loadgen/antifraud/results/evaluate.json AF_BASELINE=loadgen/antifraud/results/off.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf
```

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

Against a deployed stack: set `targets.auth-url` and `antifraud.auth-metrics-url` (the diagnostics port), and make
the stub reachable from `auth`: `antifraud.sms-stub.bind-host` / `port` here and the same address in `auth`'s
`otp-provider.url`. The tenant needs `phoneRegions` that accept the numbers of `antifraud.numbers`.

`sut-migrations.sha256` fingerprints the migrations of the campaign tooling's seeder; this role reads no SUT
database and adds no migration, so it is untouched.

## Reading the numbers

- Everything runs on one machine in the runs recorded below (loadgen, `auth`, `central`, `edge`, Postgres): the
  absolute latencies are of a laptop, only differences between runs on the same machine mean anything, and
  `off-repeat` is what a difference of nothing looks like.
- Added p99 is a difference of two p99s of a few hundred to a few thousand samples: its error is several
  milliseconds. A pass says no more than "not shown to be over the ceiling".
- `device-attack` is **expected not to be stopped** by the SMS budget: it is the case the per-range budget does not
  cover, which the velocity counters (P0-3), the lists (P0-4) and the device identity (P1-1) are for. It is in the
  set so that those tasks have a number to improve.
- The event queue's peak is sampled every 250 ms; a peak shorter than that is missed, and `dropped` is exact.

## Results

> **Example output of one run on one machine, not a benchmark and not a property of any deployment.** The reports
> are committed so that the next profile has something to be compared with and the tool's output has an example;
> they are to be replaced by a run of the same commands on the machine that matters.

Recorded on 2026-10-29 on a MacBook (Apple silicon) with `loadgen`, `auth`, `central`, `edge`, the nginx terminator
and Postgres 18 (docker, plain) all on it, each a default-settings JVM from `stage`, built from `main` at `4b6ca47a`
(before #875). `auth` was restarted and `af_velocity` emptied before each run; every run was compared with `off`.
Complete reports (every call, every outcome, `auth`'s counters) are in `results/`, one `.md` and the `.json` it was
made from, per profile: `off`, `off-repeat` (the noise), `sms-budget`, `lists`, `velocity`, `evaluate` and
`evaluate-no-budget`.

**Latency** (added p99 against `off`, ms; p50 / p99 of `off` in the first column). Of the 19 call rows of each run
none is over its limit. `off-repeat` puts the noise of a run at about 4 ms either way; the one figure outside it,
`otp-verify` at +9.5 ms in `evaluate`, was -3.2 ms in the run after it with the same profile (`evaluate-no-budget`),
so it is noise too, not a cost of the limits. Nothing of antifraud is on the `/token` and refresh paths, and nothing
shows there.

| Call (scenario) | `off` p50 / p99 | `off-repeat` added p99 | `sms-budget` added p99 | `lists` added p99 | `velocity` added p99 | `evaluate` added p99 | `evaluate-no-budget` added p99 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `otp-request` (normal) | 3.6 / 9.2 | -2.3 | -1.2 | -2.2 | -0.8 | +0.7 | -0.1 |
| `otp-verify` (normal) | 9.4 / 19.4 | -3.6 | -0.2 | -4.8 | -2.3 | +9.5 | -3.2 |
| `token` (normal) | 3.4 / 9.6 | -3.1 | -0.9 | -3.6 | -2.9 | +0.0 | -2.1 |
| `refresh` (normal) | 2.9 / 7.5 | -1.6 | +0.6 | -2.1 | -1.4 | +1.1 | -1.4 |
| `token` (token-control) | 5.0 / 8.0 | -0.2 | +0.1 | -0.9 | +1.4 | +3.0 | -0.9 |
| `refresh` (token-control) | 6.9 / 13.5 | -1.4 | +0.6 | -1.3 | +1.6 | +0.8 | +1.2 |

**Effectiveness and false positives** (the checks judged on each run alone; limits: at most 10 % of an attack's codes
sent, at most 1 % of legitimate requests refused):

| Scenario | `off` | `off-repeat` | `sms-budget` | `lists` | `velocity` | `evaluate` | `evaluate-no-budget` |
|---|---|---|---|---|---|---|---|
| prefix-attack: share of codes sent | 1.000 **Fail** (2400 of 2400) | 1.000 **Fail** (2400 of 2400) | 0.068 Pass (162 of 2400) | 0.073 Pass (176 of 2400) | 0.069 Pass (165 of 2400) | 0.037 Pass (90 of 2400) | 0.037 Pass (90 of 2400) |
| device-attack: share of codes sent | 1.000 **Fail** (2400 of 2400) | 1.000 **Fail** (2400 of 2400) | 0.992 **Fail** (2380 of 2400) | 0.992 **Fail** (2381 of 2400) | 0.991 **Fail** (2379 of 2400) | 0.037 Pass (90 of 2400) | 0.037 Pass (90 of 2400) |
| distributed-attack: share of codes sent | 1.000 **Fail** (2400 of 2400) | 1.000 **Fail** (2400 of 2400) | 0.063 Pass (152 of 2400) | 0.062 Pass (149 of 2400) | 0.058 Pass (140 of 2400) | 0.071 Pass (171 of 2400) | 0.188 **Fail** (450 of 2400) |
| conversion-mimicking: share of codes sent | 1.000 **Fail** (1200 of 1200) | 1.000 **Fail** (1200 of 1200) | 0.098 Pass (117 of 1200) | 0.109 **Fail** (131 of 1200) | 0.102 **Fail** (122 of 1200) | 0.050 Pass (60 of 1200) | 0.050 Pass (60 of 1200) |
| targeted-phone: share of codes sent | 1.000 **Fail** (300 of 300) | 1.000 **Fail** (300 of 300) | 0.430 **Fail** (129 of 300) | 0.423 **Fail** (127 of 300) | 0.430 **Fail** (129 of 300) | 0.017 Pass (5 of 300) | 0.017 Pass (5 of 300) |
| verify-guessing: guesses accepted | 0 Pass (3000 guesses) | 0 Pass (3000 guesses) | 0 Pass (2850 guesses) | 0 Pass (2870 guesses) | 0 Pass (2895 guesses) | 0 Pass (300 guesses) | 0 Pass (300 guesses) |
| normal: legitimate refused | 0.000 Pass (0 of 2400) | 0.000 Pass (0 of 2400) | 0.000 Pass (0 of 2400) | 0.000 Pass (0 of 2400) | 0.000 Pass (0 of 2400) | 0.000 Pass (0 of 2400) | 0.000 Pass (0 of 2400) |
| mixed-attack: legitimate **in the attacked range** refused | 0.000 Pass (0 of 249) | 0.000 Pass (0 of 264) | 0.963 **Fail** (207 of 215) | 0.910 **Fail** (213 of 234) | 0.927 **Fail** (243 of 262) | 0.554 **Fail** (144 of 260) | 0.000 Pass (0 of 235) |
| mixed-attack: legitimate **elsewhere** refused | 0.000 Pass (0 of 2151) | 0.000 Pass (0 of 2136) | 0.029 **Fail** (63 of 2185) | 0.031 **Fail** (68 of 2166) | 0.032 **Fail** (68 of 2138) | 0.015 **Fail** (32 of 2140) | 0.003 Pass (7 of 2165) |

What it says:

- **The finished P0 stack stops every attack shape the scenarios have, at 20 requests a second from one place.**
  With the limits on (`evaluate`) the one-source attacks are cut to 90 codes of 2400 (`prefix-attack`,
  `device-attack`: the address limit, 30 a minute, is what fires; the three minutes of each run let 30 through three
  times), the targeted number to 5 of 300 (the phone limit: this is also what ends the lockout of its owner by
  SMS bombing), the conversion-mimicking range to 60 of 1200, and the guessing to 60 codes and no guess taken.
  Legitimate users with the attack running elsewhere are not touched (`normal`: 0 of 2400 refused).
- **The distributed attack is the one left standing, and it is a trade-off, not a limit to tune.** 500 sources at
  about five requests each are under every address limit; what stops them is the range: the velocity limit of 150
  a minute lets 450 of 2400 through (19 %, `evaluate-no-budget`), and the range budget on top of it closes the range
  for the hour after 200 codes and brings that down to 171 (7 %, `evaluate`). The price of the budget is the users in
  that range: in `mixed-attack` 144 of 260 (55 %) of the legitimate sign-ins to the attacked range were refused by
  the budget, and 32 of 2140 (1.5 %) elsewhere, in the two ranges earlier scenarios had closed within the hour.
  **Without the budget the velocity limits alone refuse no legitimate user in the attacked range (0 of 235) and 7 of
  2165 elsewhere**, and let the distributed pumping send 19 %. A deployment chooses between the two: a budget set
  above a range's real hourly peak (not 200) keeps most of the first without the second, and the finer answers
  (a device identity, so that distinct phones per device count: P1-1; the line type of the numbers: P3-1) are what
  make the choice unnecessary.
- **The lists and the counting cost nothing visible**: `lists` (the engine deciding on every request, no entry) and
  `velocity` (counting, flushing, loading, no limit) are within the noise on every call, and their verdicts are the
  budget's, as before.
- **The range budget alone** (`sms-budget`) is what it was: it stops the one-range attacks and nothing else
  (`device-attack` 99 %, `targeted-phone` 43 %), and refuses 96 % of the legitimate users in the range it closes.
- **The first `evaluate` run was thrown away**: `auth` reads the counts the previous instance flushed as another
  instance's, so it inherited the hour of attacks the `velocity` run had made and refused 4 % of `normal`'s users in
  the ranges attacked then. Empty `af_velocity` between runs; a deployment does not have this (its instances are
  meant to share), but a restart after an attack keeps the attack's counts for the hour, by design.
- **Not measured:** any rate above 20-50 requests a second; more than one `auth` instance (the budget is per
  instance, the counters converge within flush + load); a proof of work (above); the hot-key behaviour of the
  counters and the CPU, GC and database volume per instance; a real SMS provider's latency.
