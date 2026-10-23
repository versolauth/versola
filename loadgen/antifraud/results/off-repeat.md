# antifraud loadgen: `off-repeat`

> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.7 | 6.9 | 7.1 | -0.2 | Pass |
| normal | otp-verify | 2037 | 0 | 8.6 | 15.1 | 18.0 | -2.8 | Pass |
| normal | refresh | 15263 | 0 | 3.2 | 6.0 | 6.0 | -0.1 | Pass |
| normal | token | 2037 | 0 | 3.5 | 6.4 | 6.9 | -0.5 | Pass |
| token-control | refresh | 3000 | 0 | 8.5 | 12.6 | 16.8 | -4.2 | Pass |
| token-control | token | 1000 | 0 | 5.0 | 8.2 | 8.7 | -0.5 | Pass |
| prefix-attack | otp-request | 2400 | 0 | 6.5 | 9.9 | 13.3 | -3.4 | Pass |
| device-attack | otp-request | 2400 | 0 | 6.6 | 11.2 | 13.3 | -2.2 | Pass |
| mixed-attack | otp-request | 4800 | 0 | 3.6 | 6.6 | 7.4 | -0.8 | Pass |
| mixed-attack | otp-verify | 2054 | 0 | 8.9 | 15.6 | 18.7 | -3.0 | Pass |
| mixed-attack | refresh | 15363 | 0 | 3.7 | 6.6 | 6.5 | 0.0 | Pass |
| mixed-attack | token | 2045 | 0 | 4.0 | 7.6 | 7.4 | 0.2 | Pass |
| conversion-mimicking | otp-request | 1200 | 0 | 6.1 | 10.1 | 13.9 | -3.7 | Pass |
| conversion-mimicking | otp-verify | 369 | 0 | 16.0 | 22.5 | 27.1 | -4.6 | Pass |
| targeted-phone | otp-request | 300 | 0 | 6.6 | 8.7 | 15.0 | -6.4 | Pass |
| verify-guessing | otp-request | 600 | 0 | 6.4 | 10.4 | 12.9 | -2.5 | Pass |
| verify-guessing | otp-verify | 3000 | 0 | 2.7 | 5.4 | 6.7 | -1.3 | Pass |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2037 | 0 | 363 | 0 | 2400 | 110 | 33 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 16 |
| prefix-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 1 | 2400 |
| device-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 110 | 34 |
| mixed-attack | 4800 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 4800 | 110 | 2655 |
| conversion-mimicking | 1200 | 0 | 1200 | 1200 | 0 | 0 | 369 | 0 | 0 | 0 | 1200 | 1 | 1200 |
| targeted-phone | 300 | 0 | 300 | 300 | 0 | 0 | 0 | 0 | 0 | 0 | 300 | 1 | 300 |
| verify-guessing | 600 | 0 | 600 | 600 | 0 | 0 | 0 | 3000 | 0 | 0 | 600 | 110 | 15 |

#### Legitimate users during an attack

| scenario | users | requested | sent | refused | verified |
|---|---|---:|---:|---:|---:|
| mixed-attack | in the attacked range | 238 | 238 | 0 | 197 |
| mixed-attack | elsewhere | 2162 | 2162 | 0 | 1848 |

### Effectiveness and false positives

Judged on this run alone. An attack fails when more than its ceiling of the codes it asked for were sent,
guessing when a guess is taken, legitimate users when more than their ceiling of them were refused.

| scenario | check | value | limit | verdict |
|---|---|---:|---:|---|
| normal | legitimate: share refused | 0.000 | 0.010 | Pass |
| prefix-attack | attack: share of codes sent | 1.000 | 0.100 | Fail |
| device-attack | attack: share of codes sent | 1.000 | 0.100 | Fail |
| mixed-attack | legitimate in the attacked range: share refused | 0.000 | 0.010 | Pass |
| mixed-attack | legitimate elsewhere: share refused | 0.000 | 0.010 | Pass |
| conversion-mimicking | attack: share of codes sent | 1.000 | 0.100 | Fail |
| targeted-phone | attack: share of codes sent | 1.000 | 0.100 | Fail |
| verify-guessing | guesses accepted | 0.000 | 0.000 | Pass |

### Event log (auth)

| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |
|---|---:|---:|---:|---:|---:|---:|
| normal | 4487 | 4574 | 0 | 0 | 0 | 0 |
| token-control | 2010 | 2039 | 0 | 0 | 0 | 0 |
| prefix-attack | 2400 | 2386 | 0 | 0 | 0 | 0 |
| device-attack | 2395 | 2398 | 0 | 0 | 0 | 0 |
| mixed-attack | 6859 | 6850 | 0 | 0 | 0 | 0 |
| conversion-mimicking | 1564 | 1583 | 0 | 0 | 0 | 0 |
| targeted-phone | 305 | 306 | 0 | 0 | 0 | 0 |
| verify-guessing | 3594 | 3558 | 0 | 0 | 0 | 0 |
