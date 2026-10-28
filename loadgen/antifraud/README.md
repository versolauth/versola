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

Where the requests come from: every request is made from the loadgen's one address (loopback in the local stack),
and the harness sets no `X-Forwarded-For`/`X-Real-IP` header and no device identifier, so for `auth` there is one
client IP and one device. The "one source" of `device-attack` is exactly that. Distinct source addresses are not
simulated.

### Attack shapes not covered (follow-ups)

- **Distributed** (many IPs/devices, one range) and any scenario that needs a request's source to vary: needs the
  loadgen's HTTP client to send `X-Forwarded-For` (or `X-Real-IP`) per session, and the stack to trust it from the
  loadgen's address; `HttpAuthClient` has no header hook today.
- **Slow-and-low**: the prefix attack at a few codes a minute for hours; a config of `prefix-attack` (`rate`,
  `duration`) but longer than a CI-sized run, and meaningful only against a budget window of an hour.
- **Hot-key behaviour of the velocity counters** and a **micro-benchmark of the `evaluate` path** (CPU, GC, DB
  reads and writes per instance, 2+ `auth` instances): neither the counters nor the lists are on this branch yet, and
  a JMH benchmark is a new dependency (`project/Dependencies.scala`) that needs agreement first. When they land
  the benchmark should take whatever rule sets are registered, and the profiles here gain an `evaluate` one.

## Profiles

`auth-profiles/off.conf` is the local dev config of `scripts/gen-env.scala` plus the two things the scenarios
need: `env = prod` (a non-prod `auth` sends no SMS and always issues the same code) and an `otp-provider` pointing
at the stub that `loadgen` serves on `:9100`. `sms-budget.conf` includes it and sets `antifraud.sms-budget.prefix-length = 6`; the limit itself (`sentPerHour = 200`) is a stored setting
set through central's `PUT /antifraud/settings`, no longer a key of the config (the reasoning is in the file).

The next task that adds a mechanism adds a profile that **includes the previous one** and sets only its own keys
(lists, velocity counters, `evaluate`), so the run of a profile differs from the run before it by exactly those
settings: copy `sms-budget.conf`, include it, set them through the admin API, run with `AF_BASELINE` set to the `off` report.

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

# 8. Restart auth with the profile switched on, and run against the baseline
#    (stop auth, then the auth line of step 5 with auth-profiles/sms-budget.conf)
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

> **Example output of one run on one machine, not a benchmark and not a property of any deployment.** The reports
> are committed so that the next profile has something to be compared with and the tool's output has an example;
> they are to be replaced by a run of the same commands on the machine that matters.

Recorded on 2026-10-23 on a MacBook (Apple silicon) with `loadgen`, `auth`, `central`, `edge` and Postgres 17
(docker, plain) all on it, each a default-settings JVM from `stage`, built from this branch after merging
`feat/antifraud-core` at `83adfb53`. `auth` was restarted before each run. Complete reports (every call, every
outcome, `auth`'s counters): [`results/off.md`](results/off.md), [`results/off-repeat.md`](results/off-repeat.md),
[`results/sms-budget.md`](results/sms-budget.md), with the JSON they were made from. `off-repeat` is `off` run again
(the noise), `sms-budget` is `sent-per-hour 200`, `prefix-length 6`.

**Latency** (added p99 against `off`, ms; p50 / p99 of `off` in the first column). Of the 17 call rows of each run
none is over its limit; the added p99 ranges from -6.4 to +0.2 ms in `off-repeat` and from -5.4 to -0.2 ms in
`sms-budget`, that is, below zero: the later runs were faster than the first (possibly its JIT warm-up), so these differences say "no regression shown", not "it is faster". The calls that
matter:

| Call (scenario) | `off` p50 / p99 | `off-repeat` added p99 | `sms-budget` added p99 | Acceptance |
|---|---:|---:|---:|---|
| `otp-request` (normal) | 3.6 / 7.1 | -0.2 | -0.3 | <= 10: met |
| `otp-verify` (normal) | 8.8 / 18.0 | -2.8 | -3.2 | <= 10: met |
| `token` (normal) | 3.5 / 6.9 | -0.5 | -0.9 | zero: within noise |
| `refresh` (normal) | 3.1 / 6.0 | -0.1 | -0.6 | zero: within noise |
| `token` (token-control) | 5.0 / 8.7 | -0.5 | -0.4 | zero: within noise |
| `refresh` (token-control) | 7.5 / 16.8 | -4.2 | -3.8 | zero: within noise |

**Effectiveness and false positives** (the checks judged on each run alone; limits: at most 10 % of an attack's codes
sent, at most 1 % of legitimate requests refused):

| Scenario | `off` | `sms-budget` |
|---|---|---|
| prefix-attack: share of codes sent | 1.000 **Fail** (2400 of 2400) | 0.075 Pass (179 of 2400) |
| device-attack: share of codes sent | 1.000 **Fail** | 0.990 **Fail** (2377 of 2400) |
| conversion-mimicking: share of codes sent | 1.000 **Fail** | 0.108 **Fail** (130 of 1200; 45 entered codes) |
| targeted-phone: share of codes sent | 1.000 **Fail** (300 of 300) | 0.427 **Fail** (128 of 300) |
| verify-guessing: guesses accepted | 0 Pass (3000 guesses) | 0 Pass (2850 guesses) |
| normal: legitimate refused | 0.000 Pass | 0.000 Pass |
| mixed-attack: legitimate **in the attacked range** refused | 0.000 Pass (0 of 241) | 0.974 **Fail** (221 of 227) |
| mixed-attack: legitimate **elsewhere** refused | 0.000 Pass | 0.021 **Fail** (46 of 2173) |

What it says:

- **The budget stops SMS pumping to one range (2400 -> 179 codes) and does little else.** It does not stop a sender
  who spreads (`device-attack`: 2377 of 2400 sent, 23 refused only where numbers fell in the range the prefix attack
  closed earlier), nor one who keeps a conversion (`conversion-mimicking` is cut to 130 codes only because the
  range's 200 codes of the hour are used up; the conversion breaker does not apply where registration is open), and
  nothing limits one number being asked for codes (`targeted-phone`: all 300 sent with antifraud off; with the budget
  128 and then its range closed). Those are what counters (P0-3), lists (P0-4) and device identity (P1-1) are to
  turn green, and these numbers are what they are compared with.
- **A closed range refuses its legitimate users: 221 of 227 (97 %) in the attacked range.** This is the cost
  `deploy.md` describes, measured: while the attack was going on, all but six of the legitimate sign-ins to the
  range failed. The 46 refusals "elsewhere" (2.1 %) are the same
  thing: that group dials numbers anywhere, so about one in 110 fell by chance in the attacked range, and about one in
  110 in the range the earlier `prefix-attack` closed (the budget remembers an hour and the scenarios of a run share
  the hour); two closed ranges of 110 are about 2 %. Neither number is a rate for a deployment, whose ranges are many and
  whose attackers are not this one; the point is that the budget alone gives them a non-zero value and the checks
  now fail on it.
- **Latency** is within noise on every call, including `/token` and refresh (untouched by construction as well: no
  antifraud code is on their path), at 20 requests a second.
- **Event log**: 300-6900 events a scenario enqueued and written, none dropped, queue peak 0-1 (sampled every
  250 ms). At this rate it is nowhere near its limit; it does not say what happens when the writer cannot keep up.
- **Not measured:** any rate above 20-50 requests a second; more than one `auth` instance (the budget is per
  instance); velocity counters, lists and `evaluate` (not on this branch's base: add a profile as each lands); a real
  SMS provider's latency or network (one machine, the stub answers at once; `sms-stub.response-delay` adds one);
  counters' hot-key behaviour; the CPU, GC and database volume of the event log and of `evaluate` per instance
  (follow-up, above).
