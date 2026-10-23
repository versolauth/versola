# antifraud loadgen: `sms-budget`

> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.4 | 6.7 | 7.1 | -0.3 | Pass |
| normal | otp-verify | 2044 | 0 | 8.3 | 14.7 | 18.0 | -3.2 | Pass |
| normal | refresh | 15498 | 0 | 3.0 | 5.5 | 6.0 | -0.6 | Pass |
| normal | token | 2044 | 0 | 3.3 | 6.0 | 6.9 | -0.9 | Pass |
| token-control | refresh | 3000 | 0 | 6.9 | 12.9 | 16.8 | -3.8 | Pass |
| token-control | token | 1000 | 0 | 5.0 | 8.3 | 8.7 | -0.4 | Pass |
| prefix-attack | otp-request | 2400 | 0 | 4.8 | 9.0 | 13.3 | -4.3 | Pass |
| device-attack | otp-request | 2400 | 0 | 6.1 | 11.2 | 13.3 | -2.1 | Pass |
| mixed-attack | otp-request | 4800 | 0 | 2.9 | 6.5 | 7.4 | -0.9 | Pass |
| mixed-attack | otp-verify | 1821 | 0 | 8.4 | 16.5 | 18.7 | -2.1 | Pass |
| mixed-attack | refresh | 13658 | 0 | 3.0 | 6.3 | 6.5 | -0.2 | Pass |
| mixed-attack | token | 1821 | 0 | 3.4 | 7.1 | 7.4 | -0.3 | Pass |
| conversion-mimicking | otp-request | 1200 | 0 | 4.5 | 8.4 | 13.9 | -5.4 | Pass |
| conversion-mimicking | otp-verify | 45 | 0 | 12.9 | 22.6 | 27.1 | -4.5 | Pass |
| targeted-phone | otp-request | 300 | 0 | 5.6 | 10.2 | 15.0 | -4.8 | Pass |
| verify-guessing | otp-request | 600 | 0 | 4.6 | 10.0 | 12.9 | -2.9 | Pass |
| verify-guessing | otp-verify | 2850 | 0 | 2.0 | 4.9 | 6.7 | -1.8 | Pass |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2044 | 0 | 356 | 0 | 2400 | 110 | 36 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 17 |
| prefix-attack | 2400 | 0 | 2400 | 179 | 2221 | 0 | 0 | 0 | 0 | 0 | 179 | 1 | 179 |
| device-attack | 2400 | 0 | 2400 | 2377 | 23 | 0 | 0 | 0 | 0 | 0 | 2377 | 109 | 32 |
| mixed-attack | 4800 | 0 | 2400 | 136 | 2264 | 0 | 0 | 0 | 0 | 0 | 2269 | 109 | 144 |
| conversion-mimicking | 1200 | 0 | 1200 | 130 | 1070 | 0 | 45 | 0 | 0 | 0 | 130 | 1 | 130 |
| targeted-phone | 300 | 0 | 300 | 128 | 172 | 0 | 0 | 0 | 0 | 0 | 128 | 1 | 128 |
| verify-guessing | 600 | 0 | 600 | 570 | 30 | 0 | 0 | 2850 | 0 | 0 | 570 | 105 | 11 |

#### Legitimate users during an attack

| scenario | users | requested | sent | refused | verified |
|---|---|---:|---:|---:|---:|
| mixed-attack | in the attacked range | 227 | 6 | 221 | 4 |
| mixed-attack | elsewhere | 2173 | 2127 | 46 | 1817 |

### Effectiveness and false positives

Judged on this run alone. An attack fails when more than its ceiling of the codes it asked for were sent,
guessing when a guess is taken, legitimate users when more than their ceiling of them were refused.

| scenario | check | value | limit | verdict |
|---|---|---:|---:|---|
| normal | legitimate: share refused | 0.000 | 0.010 | Pass |
| prefix-attack | attack: share of codes sent | 0.075 | 0.100 | Pass |
| device-attack | attack: share of codes sent | 0.990 | 0.100 | Fail |
| mixed-attack | legitimate in the attacked range: share refused | 0.974 | 0.010 | Fail |
| mixed-attack | legitimate elsewhere: share refused | 0.021 | 0.010 | Fail |
| conversion-mimicking | attack: share of codes sent | 0.108 | 0.100 | Fail |
| targeted-phone | attack: share of codes sent | 0.427 | 0.100 | Fail |
| verify-guessing | guesses accepted | 0.000 | 0.000 | Pass |

### Event log (auth)

| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |
|---|---:|---:|---:|---:|---:|---:|
| normal | 4476 | 4608 | 0 | 0 | 0 | 0 |
| token-control | 2008 | 2016 | 0 | 0 | 0 | 0 |
| prefix-attack | 2400 | 2400 | 0 | 2221 | 0 | 0 |
| device-attack | 2396 | 2394 | 0 | 23 | 0 | 0 |
| mixed-attack | 6625 | 6618 | 0 | 2531 | 0 | 2 |
| conversion-mimicking | 1241 | 1230 | 0 | 1066 | 0 | 0 |
| targeted-phone | 304 | 323 | 0 | 176 | 0 | 0 |
| verify-guessing | 3449 | 3397 | 0 | 30 | 0 | 0 |
