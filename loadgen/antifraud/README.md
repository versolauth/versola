# Antifraud loadgen: the OTP flow with and without antifraud

Tracking issue: #771, task P0-7. `loadgen` with `role = antifraud` drives the phone sign-in (a code is requested,
entered, exchanged, refreshed) against one `auth`, in four scenarios, and writes what it measured. Run once with
antifraud off and once with it on, the second run says what the switch added.

Whether antifraud is on is `auth`'s own configuration, not this process's: each run is made against an `auth` that
was started with one **profile** (`auth-profiles/`), and `AF_LABEL` names it in the report.

## What it measures

| Scenario | What it does | What it shows |
|---|---|---|
| `normal` | 20 sign-ins a second to new numbers: code requested, entered by 85 %, then `/token` and 5-10 refreshes (the target profile) | the latency of the protected calls on ordinary traffic; the SMS an ordinary user costs |
| `token-control` | 1000 sign-ins (their code exchanges are measured, the rest of them is not), then 50 refreshes a second over them | `/token` and refresh, which antifraud must not touch: their added p99 must be zero (within noise) |
| `prefix-attack` | 20 codes a second to numbers of **one** range (country code + 4 digits), none entered | SMS pumping: how many codes are sent, how many refused |
| `device-attack` | 20 codes a second from **one source** to numbers all over the 110 ranges, none entered | what a per-range budget does not stop: a sender who spreads |

Per scenario and per call (`otp-request`, `otp-verify`, `token`, `refresh`): count, failures, p50, p99; per
scenario: codes the SMS stub was asked to send (the stub counts them, per range), requests refused ("access
denied") and `auth`'s `antifraud_*` metrics over the run (events enqueued, written, dropped by reason, requests
refused by the budget, the event queue's largest size). With a baseline report: the **added p99** of each call and
a verdict, against `added-p99-ceiling` (10 ms) for `otp-request` and `otp-verify` and `control-tolerance` (5 ms,
the noise of a run) for `token` and `refresh`.

Arrivals are on a schedule (open loop): a slow system does not slow the offered load, and `waited` counts the
arrivals that had to wait for a free slot (`max-in-flight`): a run where it is not 0 measured the loadgen.
Latencies are of the successful calls only; failures are counted and listed with their cause.

## Profiles

`auth-profiles/off.conf` is the local dev config of `scripts/gen-env.scala` plus the two things the scenarios
need: `env = prod` (a non-prod `auth` sends no SMS and always issues the same code) and an `otp-provider` pointing
at the stub that `loadgen` serves on `:9100`. `sms-budget.conf` includes it and adds `antifraud.sms-budget`
(`prefix-length = 6`, `sent-per-hour = 200`; the reasoning is in the file).

The next task that adds a mechanism adds a profile that **includes the previous one** and sets only its own keys
(lists, velocity counters, `evaluate`), so the run of a profile differs from the run before it by exactly those
keys: copy `sms-budget.conf`, include it, add the block, run with `AF_BASELINE` set to the `off` report.

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

# 3. Central, edge, and auth with the profile (each in its own terminal, or with nohup ... &)
PORT=9001 DPORT=9002 MPORT=9011 RUN_MIGRATIONS=true central/implementations/postgres/target/universal/stage/bin/central-postgres-impl -Denv.path=central/dev/env.conf
PORT=9003 DPORT=9004 APORT=9007 MPORT=9008 RUN_MIGRATIONS=true auth/implementations/postgres/target/universal/stage/bin/auth-postgres-impl -Denv.path=loadgen/antifraud/auth-profiles/off.conf
PORT=9005 DPORT=9006 MPORT=8083 RUN_MIGRATIONS=true edge/implementations/postgres/target/universal/stage/bin/edge-postgres-impl -Denv.path=edge/dev/env.conf

# 4. The tenant, the clients and the phone-OTP flow (once; central is where they are kept, so it survives an auth restart)
loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/provision.conf
```

Provisioning is read by a running `auth` from central within `configuration-cache-refresh-interval` (a minute in
the dev config); `auth` in prod has no `/service/configuration/sync` to force it, so wait a minute before the first run.

```bash
# 5. Antifraud off: the baseline. Writes results/off.json and results/off.md
AF_LABEL=off AF_REPORT=loadgen/antifraud/results/off.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf

# 6. The same auth again, to know the noise: the added p99 of off against off is what a difference has to exceed
#    (restart auth first, so that both runs begin cold)
AF_LABEL=off-repeat AF_REPORT=loadgen/antifraud/results/off-repeat.json AF_BASELINE=loadgen/antifraud/results/off.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf

# 7. Restart auth with the profile switched on, and run against the baseline
#    (stop auth, then the auth line of step 3 with auth-profiles/sms-budget.conf)
AF_LABEL=sms-budget AF_REPORT=loadgen/antifraud/results/sms-budget.json AF_BASELINE=loadgen/antifraud/results/off.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf
```

The process exits non-zero when a verdict is `Fail`. Restart `auth` between runs of different profiles; the budget
remembers an hour, and the `prefix-attack` range is drawn afresh for each run so that a second run does not meet
the first one's closed range.

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

Recorded on 2026-10-23, on a MacBook (Apple silicon, macOS 24.6 / Darwin) with `loadgen`, `auth`, `central`, `edge`
and Postgres 17 (docker, plain) all on it, each a default-settings JVM from `stage`, built from `cb833739` plus
the commit that adds this directory. `auth` was restarted before each run. Complete reports (every call, every
outcome, `auth`'s counters): [`results/off.md`](results/off.md), [`results/off-repeat.md`](results/off-repeat.md),
[`results/sms-budget.md`](results/sms-budget.md), with the JSON they were made from.

Added p99 in ms against `off` (`off-repeat` is `off` again: the noise, which is also the only thing the numbers of
that column can be read against):

| Scenario / call | `off` p50 / p99 | `off-repeat` added p99 | `sms-budget` added p99 | Acceptance |
|---|---:|---:|---:|---|
| normal / `otp-request` | 3.2 / 6.4 | +0.5 | -0.2 | <= 10: met |
| normal / `otp-verify` | 8.0 / 14.5 | +0.1 | +0.1 | <= 10: met |
| normal / `token` | 3.3 / 6.0 | +0.3 | +0.4 | zero: within noise |
| normal / `refresh` | 3.0 / 5.6 | +0.1 | +0.1 | zero: within noise |
| token-control / `token` | 5.0 / 9.0 | +0.4 | -1.1 | zero: within noise |
| token-control / `refresh` | 7.1 / 12.5 | 0.0 | +0.2 | zero: within noise |
| prefix-attack / `otp-request` | 5.2 / 9.6 | +0.4 | -1.2 | <= 10: met |
| device-attack / `otp-request` | 5.2 / 9.0 | +2.1 | +0.5 | <= 10: met |

Codes sent by the SMS stub, refusals and the event log (`sms-budget` = `sent-per-hour 200`, `prefix-length 6`):

| Scenario | Codes requested | SMS sent `off` | SMS sent `sms-budget` | Refused `sms-budget` | Dropped events / queue peak (both runs) |
|---|---:|---:|---:|---:|---|
| normal | 2400 | 2400 | 2400 | 0 | 0 / 0-1 |
| token-control (setup logins) | 1000 | 1000 | 1000 | 0 | 0 / 0 |
| prefix-attack | 2400 | 2400 | 171 | 2229 | 0 / 0 |
| device-attack | 2400 | 2400 | 2383 | 17 | 0 / 0 |

What it says, and what it does not:

- **SMS pumping to one range** is cut from 2400 codes to 171: the range closed after 200 codes of the hour, and the 29 short of that
  are most likely codes the normal and control scenarios, earlier in the same hour, had sent to the same range (a
  range of 110 got about 22 and 9 of them on average). No code of the
  normal scenario was refused.
- **A closed range refuses everyone in it**: the 17 refusals of `device-attack` are its numbers that fell in the
  range the prefix attack had closed, the cost `deploy.md` describes ("a closed range refuses *every* code to it").
  The rest of that scenario, spread over the other 109 ranges, was not touched: 2383 of 2400 codes went out. The
  budget does not stop a sender who spreads; that is for counters, lists and device identity.
- **Latency**: no call showed an added p99 beyond the noise of `off` against itself (largest: +2.1 ms in
  `off-repeat`'s `device-attack`, -1.2 ms in `sms-budget`'s `prefix-attack`). The ceiling of 10 ms on the protected
  calls is met by a wide margin and `/token` and refresh are indistinguishable from unchanged. That `/token`
  and refresh are untouched is also true by construction: nothing of antifraud is on their path.
- **Event log**: with `af_event` always written (it is on in `off` too), 2000-4600 events a scenario were enqueued and
  written and none dropped; the queue's size was 0-1 at every 250 ms sample. At 20 requests a second it is nowhere near
  its 20000 limit, so this says the writer keeps up at that rate, not what happens when it does not.
- **Not measured**: any rate above 20 requests a second; more than one `auth` instance (the budget is per instance);
  the velocity counters, lists, `evaluate` and the other rules of #771, which do not exist yet (the profiles for
  them are to be added as they land); a real SMS provider's latency (the stub answers at once, `sms-stub.response-delay`
  adds one); a real network, since everything is on one machine; the hot-key behaviour of counters.
