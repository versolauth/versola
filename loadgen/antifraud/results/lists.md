# antifraud loadgen: `lists`

> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.6 | 7.0 | 9.2 | -2.2 | Pass |
| normal | otp-verify | 2036 | 0 | 8.4 | 14.6 | 19.4 | -4.8 | Pass |
| normal | refresh | 15247 | 0 | 3.0 | 5.4 | 7.5 | -2.1 | Pass |
| normal | token | 2036 | 0 | 3.4 | 6.0 | 9.6 | -3.6 | Pass |
| token-control | refresh | 3000 | 0 | 8.3 | 12.2 | 13.5 | -1.3 | Pass |
| token-control | token | 1000 | 0 | 4.7 | 7.1 | 8.0 | -0.9 | Pass |
| prefix-attack | otp-request | 2400 | 0 | 5.7 | 9.3 | 12.5 | -3.3 | Pass |
| device-attack | otp-request | 2400 | 0 | 6.6 | 11.1 | 12.4 | -1.3 | Pass |
| distributed-attack | otp-request | 2400 | 0 | 5.8 | 10.9 | 10.4 | 0.5 | Pass |
| mixed-attack | otp-request | 4800 | 0 | 3.3 | 7.5 | 6.3 | 1.2 | Pass |
| mixed-attack | otp-verify | 1789 | 0 | 8.8 | 17.2 | 16.4 | 0.9 | Pass |
| mixed-attack | refresh | 13457 | 0 | 3.0 | 6.4 | 5.9 | 0.5 | Pass |
| mixed-attack | token | 1789 | 0 | 3.7 | 7.7 | 7.0 | 0.8 | Pass |
| conversion-mimicking | otp-request | 1200 | 0 | 5.5 | 12.4 | 9.5 | 2.9 | Pass |
| conversion-mimicking | otp-verify | 52 | 0 | 16.9 | 23.1 | 22.4 | 0.7 | Pass |
| targeted-phone | otp-request | 300 | 0 | 6.7 | 12.1 | 10.5 | 1.5 | Pass |
| verify-guessing | otp-request | 600 | 0 | 7.4 | 14.9 | 10.4 | 4.5 | Pass |
| verify-guessing | otp-verify | 2870 | 0 | 2.6 | 7.0 | 5.5 | 1.5 | Pass |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2036 | 0 | 364 | 0 | 2400 | 110 | 38 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 18 |
| prefix-attack | 2400 | 0 | 2400 | 176 | 2224 | 0 | 0 | 0 | 0 | 0 | 176 | 1 | 176 |
| device-attack | 2400 | 0 | 2400 | 2381 | 19 | 0 | 0 | 0 | 0 | 0 | 2381 | 109 | 41 |
| distributed-attack | 2400 | 0 | 2400 | 149 | 2251 | 0 | 0 | 0 | 0 | 0 | 149 | 1 | 149 |
| mixed-attack | 4800 | 0 | 2400 | 127 | 2273 | 0 | 0 | 0 | 0 | 0 | 2246 | 108 | 148 |
| conversion-mimicking | 1200 | 0 | 1200 | 131 | 1069 | 0 | 52 | 0 | 0 | 0 | 131 | 1 | 131 |
| targeted-phone | 300 | 0 | 300 | 127 | 173 | 0 | 0 | 0 | 0 | 0 | 127 | 1 | 127 |
| verify-guessing | 600 | 0 | 600 | 574 | 26 | 0 | 0 | 2870 | 0 | 0 | 574 | 105 | 13 |

### Legitimate users during an attack

| scenario | users | requested | sent | refused | verified |
|---|---|---:|---:|---:|---:|
| mixed-attack | in the attacked range | 234 | 21 | 213 | 21 |
| mixed-attack | elsewhere | 2166 | 2098 | 68 | 1768 |

### Effectiveness and false positives

Judged on this run alone. An attack fails when more than its ceiling of the codes it asked for were sent,
guessing when a guess is taken, legitimate users when more than their ceiling of them were refused.

| scenario | check | value | limit | verdict |
|---|---|---:|---:|---|
| normal | legitimate: share refused | 0.000 | 0.010 | Pass |
| prefix-attack | attack: share of codes sent | 0.073 | 0.100 | Pass |
| device-attack | attack: share of codes sent | 0.992 | 0.100 | Fail |
| distributed-attack | attack: share of codes sent | 0.062 | 0.100 | Pass |
| mixed-attack | legitimate in the attacked range: share refused | 0.910 | 0.010 | Fail |
| mixed-attack | legitimate elsewhere: share refused | 0.031 | 0.010 | Fail |
| conversion-mimicking | attack: share of codes sent | 0.109 | 0.100 | Fail |
| targeted-phone | attack: share of codes sent | 0.423 | 0.100 | Fail |
| verify-guessing | guesses accepted | 0.000 | 0.000 | Pass |

### Event log (auth)

| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |
|---|---:|---:|---:|---:|---:|---:|
| normal | 4496 | 4563 | 0 | 0 | 0 | 0 |
| token-control | 2016 | 2053 | 0 | 0 | 0 | 0 |
| prefix-attack | 2400 | 2400 | 0 | 2224 | 0 | 0 |
| device-attack | 2397 | 2391 | 0 | 19 | 0 | 0 |
| distributed-attack | 2403 | 2409 | 0 | 2251 | 0 | 0 |
| mixed-attack | 6581 | 6548 | 0 | 2551 | 0 | 0 |
| conversion-mimicking | 1260 | 1293 | 0 | 1072 | 0 | 0 |
| targeted-phone | 299 | 297 | 0 | 172 | 0 | 0 |
| verify-guessing | 3446 | 3394 | 0 | 26 | 0 | 0 |
