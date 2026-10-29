# antifraud loadgen: `sms-budget`

> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.6 | 8.0 | 9.2 | -1.2 | Pass |
| normal | otp-verify | 2039 | 0 | 8.6 | 19.2 | 19.4 | -0.2 | Pass |
| normal | refresh | 15249 | 0 | 3.2 | 8.0 | 7.5 | 0.6 | Pass |
| normal | token | 2039 | 0 | 3.5 | 8.7 | 9.6 | -0.9 | Pass |
| token-control | refresh | 3000 | 0 | 8.0 | 14.1 | 13.5 | 0.6 | Pass |
| token-control | token | 1000 | 0 | 4.9 | 8.1 | 8.0 | 0.1 | Pass |
| prefix-attack | otp-request | 2400 | 0 | 6.0 | 11.0 | 12.5 | -1.5 | Pass |
| device-attack | otp-request | 2400 | 0 | 6.8 | 12.3 | 12.4 | -0.1 | Pass |
| distributed-attack | otp-request | 2400 | 0 | 5.1 | 11.5 | 10.4 | 1.1 | Pass |
| mixed-attack | otp-request | 4800 | 0 | 3.1 | 7.3 | 6.3 | 0.9 | Pass |
| mixed-attack | otp-verify | 1795 | 0 | 8.7 | 17.5 | 16.4 | 1.1 | Pass |
| mixed-attack | refresh | 13438 | 0 | 3.0 | 6.4 | 5.9 | 0.5 | Pass |
| mixed-attack | token | 1794 | 0 | 3.6 | 7.9 | 7.0 | 0.9 | Pass |
| conversion-mimicking | otp-request | 1200 | 0 | 6.0 | 11.6 | 9.5 | 2.1 | Pass |
| conversion-mimicking | otp-verify | 38 | 0 | 15.2 | 24.3 | 22.4 | 1.9 | Pass |
| targeted-phone | otp-request | 300 | 0 | 6.6 | 13.1 | 10.5 | 2.6 | Pass |
| verify-guessing | otp-request | 600 | 0 | 6.5 | 11.5 | 10.4 | 1.1 | Pass |
| verify-guessing | otp-verify | 2850 | 0 | 2.5 | 5.8 | 5.5 | 0.4 | Pass |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2039 | 0 | 361 | 0 | 2400 | 110 | 34 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 17 |
| prefix-attack | 2400 | 0 | 2400 | 162 | 2238 | 0 | 0 | 0 | 0 | 0 | 162 | 1 | 162 |
| device-attack | 2400 | 0 | 2400 | 2380 | 20 | 0 | 0 | 0 | 0 | 0 | 2380 | 109 | 32 |
| distributed-attack | 2400 | 0 | 2400 | 152 | 2248 | 0 | 0 | 0 | 0 | 0 | 152 | 1 | 152 |
| mixed-attack | 4800 | 0 | 2400 | 139 | 2261 | 0 | 0 | 0 | 0 | 0 | 2269 | 108 | 148 |
| conversion-mimicking | 1200 | 0 | 1200 | 117 | 1083 | 0 | 38 | 0 | 0 | 0 | 117 | 1 | 117 |
| targeted-phone | 300 | 0 | 300 | 129 | 171 | 0 | 0 | 0 | 0 | 0 | 129 | 1 | 129 |
| verify-guessing | 600 | 0 | 600 | 570 | 30 | 0 | 0 | 2850 | 0 | 0 | 570 | 104 | 13 |

### Legitimate users during an attack

| scenario | users | requested | sent | refused | verified |
|---|---|---:|---:|---:|---:|
| mixed-attack | in the attacked range | 215 | 8 | 207 | 6 |
| mixed-attack | elsewhere | 2185 | 2122 | 63 | 1788 |

### Effectiveness and false positives

Judged on this run alone. An attack fails when more than its ceiling of the codes it asked for were sent,
guessing when a guess is taken, legitimate users when more than their ceiling of them were refused.

| scenario | check | value | limit | verdict |
|---|---|---:|---:|---|
| normal | legitimate: share refused | 0.000 | 0.010 | Pass |
| prefix-attack | attack: share of codes sent | 0.068 | 0.100 | Pass |
| device-attack | attack: share of codes sent | 0.992 | 0.100 | Fail |
| distributed-attack | attack: share of codes sent | 0.063 | 0.100 | Pass |
| mixed-attack | legitimate in the attacked range: share refused | 0.963 | 0.010 | Fail |
| mixed-attack | legitimate elsewhere: share refused | 0.029 | 0.010 | Fail |
| conversion-mimicking | attack: share of codes sent | 0.098 | 0.100 | Pass |
| targeted-phone | attack: share of codes sent | 0.430 | 0.100 | Fail |
| verify-guessing | guesses accepted | 0.000 | 0.000 | Pass |

### Event log (auth)

| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |
|---|---:|---:|---:|---:|---:|---:|
| normal | 4441 | 4481 | 0 | 0 | 0 | 0 |
| token-control | 2002 | 2026 | 0 | 0 | 0 | 0 |
| prefix-attack | 2392 | 2382 | 0 | 2230 | 0 | 0 |
| device-attack | 2408 | 2413 | 0 | 28 | 0 | 0 |
| distributed-attack | 2392 | 2392 | 0 | 2240 | 0 | 0 |
| mixed-attack | 6603 | 6584 | 0 | 2539 | 0 | 0 |
| conversion-mimicking | 1229 | 1234 | 0 | 1074 | 0 | 0 |
| targeted-phone | 309 | 326 | 0 | 180 | 0 | 0 |
| verify-guessing | 3442 | 3391 | 0 | 30 | 0 | 0 |
