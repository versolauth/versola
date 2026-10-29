# antifraud loadgen: `velocity`

> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.9 | 8.4 | 9.2 | -0.8 | Pass |
| normal | otp-verify | 2041 | 0 | 8.6 | 17.0 | 19.4 | -2.3 | Pass |
| normal | refresh | 15233 | 0 | 2.9 | 6.0 | 7.5 | -1.4 | Pass |
| normal | token | 2041 | 0 | 3.4 | 6.7 | 9.6 | -2.9 | Pass |
| token-control | refresh | 3000 | 0 | 8.3 | 15.1 | 13.5 | 1.6 | Pass |
| token-control | token | 1000 | 0 | 4.8 | 9.4 | 8.0 | 1.4 | Pass |
| prefix-attack | otp-request | 2400 | 0 | 5.9 | 13.3 | 12.5 | 0.8 | Pass |
| device-attack | otp-request | 2400 | 0 | 6.6 | 12.4 | 12.4 | -0.0 | Pass |
| distributed-attack | otp-request | 2400 | 0 | 5.9 | 11.4 | 10.4 | 1.0 | Pass |
| mixed-attack | otp-request | 4800 | 0 | 3.4 | 7.8 | 6.3 | 1.5 | Pass |
| mixed-attack | otp-verify | 1785 | 0 | 8.9 | 18.1 | 16.4 | 1.8 | Pass |
| mixed-attack | refresh | 13497 | 0 | 3.0 | 6.4 | 5.9 | 0.5 | Pass |
| mixed-attack | token | 1785 | 0 | 3.6 | 7.9 | 7.0 | 0.9 | Pass |
| conversion-mimicking | otp-request | 1200 | 0 | 5.8 | 12.0 | 9.5 | 2.5 | Pass |
| conversion-mimicking | otp-verify | 38 | 0 | 16.3 | 24.2 | 22.4 | 1.8 | Pass |
| targeted-phone | otp-request | 300 | 0 | 6.8 | 13.1 | 10.5 | 2.5 | Pass |
| verify-guessing | otp-request | 600 | 0 | 7.2 | 14.2 | 10.4 | 3.8 | Pass |
| verify-guessing | otp-verify | 2895 | 0 | 2.6 | 6.5 | 5.5 | 1.1 | Pass |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2041 | 0 | 359 | 0 | 2400 | 110 | 33 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 21 |
| prefix-attack | 2400 | 0 | 2400 | 165 | 2235 | 0 | 0 | 0 | 0 | 0 | 165 | 1 | 165 |
| device-attack | 2400 | 0 | 2400 | 2379 | 21 | 0 | 0 | 0 | 0 | 0 | 2379 | 109 | 35 |
| distributed-attack | 2400 | 0 | 2400 | 140 | 2260 | 0 | 0 | 0 | 0 | 0 | 140 | 1 | 140 |
| mixed-attack | 4800 | 0 | 2400 | 135 | 2265 | 0 | 0 | 0 | 0 | 0 | 2224 | 108 | 155 |
| conversion-mimicking | 1200 | 0 | 1200 | 122 | 1078 | 0 | 38 | 0 | 0 | 0 | 122 | 1 | 122 |
| targeted-phone | 300 | 0 | 300 | 129 | 171 | 0 | 0 | 0 | 0 | 0 | 129 | 1 | 129 |
| verify-guessing | 600 | 0 | 600 | 579 | 21 | 0 | 0 | 2895 | 0 | 0 | 579 | 104 | 12 |

### Legitimate users during an attack

| scenario | users | requested | sent | refused | verified |
|---|---|---:|---:|---:|---:|
| mixed-attack | in the attacked range | 262 | 19 | 243 | 15 |
| mixed-attack | elsewhere | 2138 | 2070 | 68 | 1770 |

### Effectiveness and false positives

Judged on this run alone. An attack fails when more than its ceiling of the codes it asked for were sent,
guessing when a guess is taken, legitimate users when more than their ceiling of them were refused.

| scenario | check | value | limit | verdict |
|---|---|---:|---:|---|
| normal | legitimate: share refused | 0.000 | 0.010 | Pass |
| prefix-attack | attack: share of codes sent | 0.069 | 0.100 | Pass |
| device-attack | attack: share of codes sent | 0.991 | 0.100 | Fail |
| distributed-attack | attack: share of codes sent | 0.058 | 0.100 | Pass |
| mixed-attack | legitimate in the attacked range: share refused | 0.927 | 0.010 | Fail |
| mixed-attack | legitimate elsewhere: share refused | 0.032 | 0.010 | Fail |
| conversion-mimicking | attack: share of codes sent | 0.102 | 0.100 | Fail |
| targeted-phone | attack: share of codes sent | 0.430 | 0.100 | Fail |
| verify-guessing | guesses accepted | 0.000 | 0.000 | Pass |

### Event log (auth)

| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |
|---|---:|---:|---:|---:|---:|---:|
| normal | 4541 | 4611 | 0 | 0 | 0 | 0 |
| token-control | 2000 | 2018 | 0 | 0 | 0 | 0 |
| prefix-attack | 2400 | 2400 | 0 | 2235 | 0 | 0 |
| device-attack | 2392 | 2373 | 0 | 20 | 0 | 0 |
| distributed-attack | 2408 | 2412 | 0 | 2261 | 0 | 0 |
| mixed-attack | 6565 | 6575 | 0 | 2564 | 0 | 0 |
| conversion-mimicking | 1258 | 1253 | 0 | 1090 | 0 | 0 |
| targeted-phone | 298 | 308 | 0 | 169 | 0 | 0 |
| verify-guessing | 3497 | 3486 | 0 | 23 | 0 | 0 |
