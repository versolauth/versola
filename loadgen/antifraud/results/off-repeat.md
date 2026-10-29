# antifraud loadgen: `off-repeat`

> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.5 | 6.9 | 9.2 | -2.3 | Pass |
| normal | otp-verify | 2028 | 0 | 8.5 | 15.7 | 19.4 | -3.6 | Pass |
| normal | refresh | 15264 | 0 | 3.1 | 5.9 | 7.5 | -1.6 | Pass |
| normal | token | 2028 | 0 | 3.6 | 6.4 | 9.6 | -3.1 | Pass |
| token-control | refresh | 3000 | 0 | 8.2 | 12.1 | 13.5 | -1.4 | Pass |
| token-control | token | 1000 | 0 | 4.7 | 7.9 | 8.0 | -0.2 | Pass |
| prefix-attack | otp-request | 2400 | 0 | 5.8 | 11.2 | 12.5 | -1.4 | Pass |
| device-attack | otp-request | 2400 | 0 | 5.8 | 11.9 | 12.4 | -0.5 | Pass |
| distributed-attack | otp-request | 2400 | 0 | 6.4 | 13.2 | 10.4 | 2.8 | Pass |
| mixed-attack | otp-request | 4800 | 0 | 3.0 | 6.8 | 6.3 | 0.5 | Pass |
| mixed-attack | otp-verify | 2017 | 0 | 8.3 | 16.6 | 16.4 | 0.2 | Pass |
| mixed-attack | refresh | 15118 | 0 | 2.9 | 6.9 | 5.9 | 1.0 | Pass |
| mixed-attack | token | 2017 | 0 | 3.4 | 7.2 | 7.0 | 0.3 | Pass |
| conversion-mimicking | otp-request | 1200 | 0 | 5.1 | 10.7 | 9.5 | 1.2 | Pass |
| conversion-mimicking | otp-verify | 373 | 0 | 14.6 | 36.1 | 22.4 | 13.7 | Fail |
| targeted-phone | otp-request | 300 | 0 | 6.2 | 13.7 | 10.5 | 3.2 | Pass |
| verify-guessing | otp-request | 600 | 0 | 4.9 | 18.7 | 10.4 | 8.3 | Pass |
| verify-guessing | otp-verify | 3000 | 0 | 1.9 | 8.4 | 5.5 | 3.0 | Pass |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2028 | 0 | 372 | 0 | 2400 | 110 | 36 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 17 |
| prefix-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 1 | 2400 |
| device-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 110 | 35 |
| distributed-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 1 | 2400 |
| mixed-attack | 4800 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 4800 | 110 | 2676 |
| conversion-mimicking | 1200 | 0 | 1200 | 1200 | 0 | 0 | 373 | 0 | 0 | 0 | 1200 | 1 | 1200 |
| targeted-phone | 300 | 0 | 300 | 300 | 0 | 0 | 0 | 0 | 0 | 0 | 300 | 1 | 300 |
| verify-guessing | 600 | 0 | 600 | 600 | 0 | 0 | 0 | 3000 | 0 | 0 | 600 | 110 | 16 |

### Legitimate users during an attack

| scenario | users | requested | sent | refused | verified |
|---|---|---:|---:|---:|---:|
| mixed-attack | in the attacked range | 264 | 264 | 0 | 212 |
| mixed-attack | elsewhere | 2136 | 2136 | 0 | 1805 |

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
| normal | 4470 | 4564 | 0 | 0 | 0 | 1 |
| token-control | 2010 | 2044 | 0 | 0 | 0 | 1 |
| prefix-attack | 2400 | 2400 | 0 | 0 | 0 | 0 |
| device-attack | 2399 | 2398 | 0 | 0 | 0 | 0 |
| distributed-attack | 2401 | 2392 | 0 | 0 | 0 | 0 |
| mixed-attack | 6814 | 6810 | 0 | 0 | 0 | 1 |
| conversion-mimicking | 1576 | 1582 | 0 | 0 | 0 | 0 |
| targeted-phone | 300 | 307 | 0 | 0 | 0 | 0 |
| verify-guessing | 3576 | 3547 | 0 | 0 | 0 | 0 |
