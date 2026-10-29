# antifraud loadgen: `evaluate-no-budget`

> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.9 | 9.1 | 9.2 | -0.1 | Pass |
| normal | otp-verify | 2001 | 0 | 8.7 | 16.2 | 19.4 | -3.2 | Pass |
| normal | refresh | 14913 | 0 | 3.0 | 6.1 | 7.5 | -1.4 | Pass |
| normal | token | 2001 | 0 | 3.5 | 7.4 | 9.6 | -2.1 | Pass |
| token-control | refresh | 3000 | 0 | 8.8 | 14.6 | 13.5 | 1.2 | Pass |
| token-control | token | 1000 | 0 | 4.9 | 7.1 | 8.0 | -0.9 | Pass |
| prefix-attack | otp-request | 2400 | 0 | 5.5 | 15.6 | 12.5 | 3.1 | Pass |
| device-attack | otp-request | 2400 | 0 | 5.7 | 11.6 | 12.4 | -0.8 | Pass |
| distributed-attack | otp-request | 2400 | 0 | 5.4 | 11.8 | 10.4 | 1.5 | Pass |
| mixed-attack | otp-request | 4800 | 0 | 3.0 | 6.8 | 6.3 | 0.5 | Pass |
| mixed-attack | otp-verify | 1996 | 0 | 8.3 | 15.0 | 16.4 | -1.4 | Pass |
| mixed-attack | refresh | 15010 | 0 | 2.9 | 5.7 | 5.9 | -0.2 | Pass |
| mixed-attack | token | 1996 | 0 | 3.3 | 6.3 | 7.0 | -0.6 | Pass |
| conversion-mimicking | otp-request | 1200 | 0 | 5.3 | 11.1 | 9.5 | 1.6 | Pass |
| conversion-mimicking | otp-verify | 17 | 0 | 14.5 | 21.8 | 22.4 | -0.6 | Pass |
| targeted-phone | otp-request | 300 | 0 | 6.6 | 13.4 | 10.5 | 2.8 | Pass |
| verify-guessing | otp-request | 600 | 0 | 6.7 | 16.4 | 10.4 | 6.0 | Pass |
| verify-guessing | otp-verify | 300 | 0 | 2.3 | 5.6 | 5.5 | 0.1 | Pass |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2001 | 0 | 399 | 0 | 2400 | 110 | 36 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 18 |
| prefix-attack | 2400 | 0 | 2400 | 90 | 2310 | 0 | 0 | 0 | 0 | 0 | 90 | 1 | 90 |
| device-attack | 2400 | 0 | 2400 | 90 | 2310 | 0 | 0 | 0 | 0 | 0 | 90 | 65 | 4 |
| distributed-attack | 2400 | 0 | 2400 | 450 | 1950 | 0 | 0 | 0 | 0 | 0 | 450 | 1 | 450 |
| mixed-attack | 4800 | 0 | 2400 | 90 | 2310 | 0 | 0 | 0 | 0 | 0 | 2483 | 110 | 345 |
| conversion-mimicking | 1200 | 0 | 1200 | 60 | 1140 | 0 | 17 | 0 | 0 | 0 | 60 | 1 | 60 |
| targeted-phone | 300 | 0 | 300 | 5 | 295 | 0 | 0 | 0 | 0 | 0 | 5 | 1 | 5 |
| verify-guessing | 600 | 0 | 600 | 60 | 540 | 0 | 0 | 300 | 0 | 0 | 60 | 45 | 3 |

### Legitimate users during an attack

| scenario | users | requested | sent | refused | verified |
|---|---|---:|---:|---:|---:|
| mixed-attack | in the attacked range | 235 | 235 | 0 | 196 |
| mixed-attack | elsewhere | 2165 | 2158 | 7 | 1800 |

### Effectiveness and false positives

Judged on this run alone. An attack fails when more than its ceiling of the codes it asked for were sent,
guessing when a guess is taken, legitimate users when more than their ceiling of them were refused.

| scenario | check | value | limit | verdict |
|---|---|---:|---:|---|
| normal | legitimate: share refused | 0.000 | 0.010 | Pass |
| prefix-attack | attack: share of codes sent | 0.038 | 0.100 | Pass |
| device-attack | attack: share of codes sent | 0.038 | 0.100 | Pass |
| distributed-attack | attack: share of codes sent | 0.188 | 0.100 | Fail |
| mixed-attack | legitimate in the attacked range: share refused | 0.000 | 0.010 | Pass |
| mixed-attack | legitimate elsewhere: share refused | 0.003 | 0.010 | Pass |
| conversion-mimicking | attack: share of codes sent | 0.050 | 0.100 | Pass |
| targeted-phone | attack: share of codes sent | 0.017 | 0.100 | Pass |
| verify-guessing | guesses accepted | 0.000 | 0.000 | Pass |

### Event log (auth)

| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |
|---|---:|---:|---:|---:|---:|---:|
| normal | 4485 | 4613 | 0 | 0 | 0 | 0 |
| token-control | 2000 | 2000 | 0 | 0 | 0 | 4 |
| prefix-attack | 2400 | 2384 | 0 | 0 | 0 | 0 |
| device-attack | 2391 | 2392 | 0 | 0 | 0 | 0 |
| distributed-attack | 2409 | 2412 | 0 | 0 | 0 | 0 |
| mixed-attack | 6773 | 6783 | 0 | 0 | 0 | 0 |
| conversion-mimicking | 1240 | 1234 | 0 | 0 | 0 | 0 |
| targeted-phone | 298 | 301 | 0 | 0 | 0 | 0 |
| verify-guessing | 902 | 907 | 0 | 0 | 0 | 0 |
