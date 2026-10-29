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

| Profile | Adds | The stored settings it was recorded with |
|---|---|---|
| `sms-budget` | `antifraud.sms-budget.prefix-length = 6` | `sentPerHour 200` per range, every limit 0 |
| `lists` | `decision.enabled`, `lists.enabled` (no entry: the cost of the lookup and of `evaluate`) | the same |
| `velocity` | `velocity.enabled` (counting, flushing, loading; no limit fires) | the same |
| `evaluate` | `challenge.enabled = false` (above) | `blockAt 50`; phone 3/5/10/20 a minute/10 min/hour/day, weight 60; ip 30/100/300/1000, weight 50; prefix 150/600/2000/10000, weight 50 |
| `evaluate-no-budget` (the same profile) | nothing | the `evaluate` settings with `sentPerHour 0`: the velocity limits alone |

The limits are stored settings, not keys of the config: in a deployment they are set through central's
`PUT /antifraud/settings` (deploy.md), and `auth` rereads them within `antifraud.tuning.refresh-interval` (5 s).
The local stack is driven by the `utils` client, which has no antifraud permission, so the settings of each profile
are written straight into `af_settings` by the `tune.sh` of the runbook (below), after `auth` has started with the
profile and before the run.

**The scripts that drive a whole ladder of runs, the settings of each profile and the recorded runs are not here:**
they are in [versolauth/loadgen-runbook](https://github.com/versolauth/loadgen-runbook), `scripts/antifraud-local/`
(the stack on ports of its own, `run-profile.sh` per profile, `tune.sh`, the settings JSON, the table maker) and
`docs/12-antifraud-p0-local.md` (the runs of 2026-10-29 and what they say). This folder holds what the tool itself
needs: the scenarios, the profiles and the commands to run one by hand.

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
#    (stop auth, then the auth line of step 5 with auth-profiles/sms-budget.conf; wait for readiness; write the
#    profile's settings into af_settings and bump af_settings_version, which is what the runbook's tune.sh does)
AF_LABEL=sms-budget AF_REPORT=loadgen/antifraud/results/sms-budget.json AF_BASELINE=loadgen/antifraud/results/off.json \
  loadgen/target/universal/stage/bin/loadgen -Denv.path=loadgen/antifraud/loadgen.conf

# 9. The same for lists.conf and velocity.conf (settings unchanged), then evaluate.conf with its limits
AF_LABEL=evaluate AF_REPORT=loadgen/antifraud/results/evaluate.json AF_BASELINE=loadgen/antifraud/results/off.json \
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

Against a deployed stack: set `targets.auth-url` and `antifraud.auth-metrics-url` (the diagnostics port), and make
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
