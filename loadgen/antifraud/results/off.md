# antifraud loadgen: `off`

> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.6 | 9.2 | - | - | NoBaseline |
| normal | otp-verify | 2019 | 0 | 9.4 | 19.4 | - | - | NoBaseline |
| normal | refresh | 14948 | 0 | 2.9 | 7.5 | - | - | NoBaseline |
| normal | token | 2019 | 0 | 3.4 | 9.6 | - | - | NoBaseline |
| token-control | refresh | 3000 | 0 | 6.9 | 13.5 | - | - | NoBaseline |
| token-control | token | 1000 | 0 | 5.0 | 8.0 | - | - | NoBaseline |
| prefix-attack | otp-request | 2400 | 0 | 6.0 | 12.5 | - | - | NoBaseline |
| device-attack | otp-request | 2400 | 0 | 6.0 | 12.4 | - | - | NoBaseline |
| distributed-attack | otp-request | 2400 | 0 | 5.8 | 10.4 | - | - | NoBaseline |
| mixed-attack | otp-request | 4800 | 0 | 3.1 | 6.3 | - | - | NoBaseline |
| mixed-attack | otp-verify | 2027 | 0 | 8.5 | 16.4 | - | - | NoBaseline |
| mixed-attack | refresh | 15191 | 0 | 3.0 | 5.9 | - | - | NoBaseline |
| mixed-attack | token | 2022 | 0 | 3.4 | 7.0 | - | - | NoBaseline |
| conversion-mimicking | otp-request | 1200 | 0 | 5.4 | 9.5 | - | - | NoBaseline |
| conversion-mimicking | otp-verify | 362 | 0 | 15.3 | 22.4 | - | - | NoBaseline |
| targeted-phone | otp-request | 300 | 0 | 6.7 | 10.5 | - | - | NoBaseline |
| verify-guessing | otp-request | 600 | 0 | 6.3 | 10.4 | - | - | NoBaseline |
| verify-guessing | otp-verify | 3000 | 0 | 2.5 | 5.5 | - | - | NoBaseline |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2019 | 0 | 381 | 0 | 2400 | 110 | 36 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 16 |
| prefix-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 1 | 2400 |
| device-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 110 | 38 |
| distributed-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 1 | 2400 |
| mixed-attack | 4800 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 4800 | 110 | 2671 |
| conversion-mimicking | 1200 | 0 | 1200 | 1200 | 0 | 0 | 362 | 0 | 0 | 0 | 1200 | 1 | 1200 |
| targeted-phone | 300 | 0 | 300 | 300 | 0 | 0 | 0 | 0 | 0 | 0 | 300 | 1 | 300 |
| verify-guessing | 600 | 0 | 600 | 600 | 0 | 0 | 0 | 3000 | 0 | 0 | 600 | 110 | 11 |

### Legitimate users during an attack

| scenario | users | requested | sent | refused | verified |
|---|---|---:|---:|---:|---:|
| mixed-attack | in the attacked range | 249 | 249 | 0 | 210 |
| mixed-attack | elsewhere | 2151 | 2151 | 0 | 1812 |

### Effectiveness and false positives

Judged on this run alone. An attack fails when more than its ceiling of the codes it asked for were sent,
guessing when a guess is taken, legitimate users when more than their ceiling of them were refused.

| scenario | check | value | limit | verdict |
|---|---|---:|---:|---|
| normal | legitimate: share refused | 0.000 | 0.010 | Pass |
| prefix-attack | attack: share of codes sent | 1.000 | 0.100 | Fail |
| device-attack | attack: share of codes sent | 1.000 | 0.100 | Fail |
| distributed-attack | attack: share of codes sent | 1.000 | 0.100 | Fail |
| mixed-attack | legitimate in the attacked range: share refused | 0.000 | 0.010 | Pass |
| mixed-attack | legitimate elsewhere: share refused | 0.000 | 0.010 | Pass |
| conversion-mimicking | attack: share of codes sent | 1.000 | 0.100 | Fail |
| targeted-phone | attack: share of codes sent | 1.000 | 0.100 | Fail |
| verify-guessing | guesses accepted | 0.000 | 0.000 | Pass |

### Event log (auth)

| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |
|---|---:|---:|---:|---:|---:|---:|
| normal | 4535 | 4534 | 0 | 0 | 0 | 1 |
| token-control | 2000 | 2009 | 0 | 0 | 0 | 0 |
| prefix-attack | 2393 | 2383 | 0 | 0 | 0 | 0 |
| device-attack | 2407 | 2411 | 0 | 0 | 0 | 0 |
| distributed-attack | 2394 | 2392 | 0 | 0 | 0 | 1 |
| mixed-attack | 6833 | 6811 | 0 | 0 | 0 | 0 |
| conversion-mimicking | 1552 | 1577 | 0 | 0 | 0 | 0 |
| targeted-phone | 310 | 312 | 0 | 0 | 0 | 0 |
| verify-guessing | 3588 | 3532 | 0 | 0 | 0 | 1 |
