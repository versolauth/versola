# antifraud loadgen: `off`

> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.6 | 7.1 | - | - | NoBaseline |
| normal | otp-verify | 2058 | 0 | 9.6 | 18.0 | - | - | NoBaseline |
| normal | refresh | 15456 | 0 | 3.1 | 6.0 | - | - | NoBaseline |
| normal | token | 2058 | 0 | 3.5 | 6.9 | - | - | NoBaseline |
| token-control | refresh | 3000 | 0 | 10.4 | 16.8 | - | - | NoBaseline |
| token-control | token | 1000 | 0 | 5.2 | 8.7 | - | - | NoBaseline |
| prefix-attack | otp-request | 2400 | 0 | 6.8 | 13.3 | - | - | NoBaseline |
| device-attack | otp-request | 2400 | 0 | 7.1 | 13.3 | - | - | NoBaseline |
| mixed-attack | otp-request | 4800 | 0 | 3.5 | 7.4 | - | - | NoBaseline |
| mixed-attack | otp-verify | 2027 | 0 | 9.7 | 18.7 | - | - | NoBaseline |
| mixed-attack | refresh | 15054 | 0 | 3.1 | 6.5 | - | - | NoBaseline |
| mixed-attack | token | 2022 | 0 | 3.8 | 7.4 | - | - | NoBaseline |
| conversion-mimicking | otp-request | 1200 | 0 | 6.2 | 13.9 | - | - | NoBaseline |
| conversion-mimicking | otp-verify | 335 | 0 | 16.7 | 27.1 | - | - | NoBaseline |
| targeted-phone | otp-request | 300 | 0 | 8.4 | 15.0 | - | - | NoBaseline |
| verify-guessing | otp-request | 600 | 0 | 7.1 | 12.9 | - | - | NoBaseline |
| verify-guessing | otp-verify | 3000 | 0 | 2.8 | 6.7 | - | - | NoBaseline |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2058 | 0 | 342 | 0 | 2400 | 110 | 33 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 16 |
| prefix-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 1 | 2400 |
| device-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 110 | 36 |
| mixed-attack | 4800 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 4800 | 110 | 2667 |
| conversion-mimicking | 1200 | 0 | 1200 | 1200 | 0 | 0 | 335 | 0 | 0 | 0 | 1200 | 1 | 1200 |
| targeted-phone | 300 | 0 | 300 | 300 | 0 | 0 | 0 | 0 | 0 | 0 | 300 | 1 | 300 |
| verify-guessing | 600 | 0 | 600 | 600 | 0 | 0 | 0 | 3000 | 0 | 0 | 600 | 110 | 11 |

#### Legitimate users during an attack

| scenario | users | requested | sent | refused | verified |
|---|---|---:|---:|---:|---:|
| mixed-attack | in the attacked range | 241 | 241 | 0 | 192 |
| mixed-attack | elsewhere | 2159 | 2159 | 0 | 1830 |

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
| normal | 4614 | 4641 | 0 | 0 | 0 | 0 |
| token-control | 2000 | 2013 | 0 | 0 | 0 | 0 |
| prefix-attack | 2390 | 2371 | 0 | 0 | 0 | 0 |
| device-attack | 2400 | 2399 | 0 | 0 | 0 | 1 |
| mixed-attack | 6827 | 6831 | 0 | 0 | 0 | 0 |
| conversion-mimicking | 1533 | 1514 | 0 | 0 | 0 | 0 |
| targeted-phone | 299 | 323 | 0 | 0 | 0 | 0 |
| verify-guessing | 3603 | 3586 | 0 | 0 | 0 | 0 |
