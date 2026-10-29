# antifraud loadgen: `evaluate`

> Output of one run on one machine: an example of what the tool reports, not a benchmark of any deployment.

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.9 | 9.9 | 9.2 | 0.7 | Pass |
| normal | otp-verify | 2048 | 0 | 8.5 | 28.8 | 19.4 | 9.5 | Pass |
| normal | refresh | 15423 | 0 | 2.9 | 8.5 | 7.5 | 1.1 | Pass |
| normal | token | 2048 | 0 | 3.4 | 9.6 | 9.6 | 0.0 | Pass |
| token-control | refresh | 3000 | 0 | 7.2 | 14.3 | 13.5 | 0.8 | Pass |
| token-control | token | 1000 | 0 | 5.1 | 11.0 | 8.0 | 3.0 | Pass |
| prefix-attack | otp-request | 2400 | 0 | 5.5 | 12.2 | 12.5 | -0.3 | Pass |
| device-attack | otp-request | 2400 | 0 | 5.3 | 11.6 | 12.4 | -0.8 | Pass |
| distributed-attack | otp-request | 2400 | 0 | 5.8 | 11.9 | 10.4 | 1.6 | Pass |
| mixed-attack | otp-request | 4800 | 0 | 3.2 | 7.8 | 6.3 | 1.5 | Pass |
| mixed-attack | otp-verify | 1889 | 0 | 8.4 | 16.6 | 16.4 | 0.2 | Pass |
| mixed-attack | refresh | 14158 | 0 | 2.9 | 6.2 | 5.9 | 0.3 | Pass |
| mixed-attack | token | 1889 | 0 | 3.5 | 7.1 | 7.0 | 0.1 | Pass |
| conversion-mimicking | otp-request | 1200 | 0 | 5.8 | 12.1 | 9.5 | 2.5 | Pass |
| conversion-mimicking | otp-verify | 21 | 0 | 14.2 | 20.8 | 22.4 | -1.6 | Pass |
| targeted-phone | otp-request | 300 | 0 | 5.9 | 12.0 | 10.5 | 1.4 | Pass |
| verify-guessing | otp-request | 600 | 0 | 5.9 | 11.0 | 10.4 | 0.6 | Pass |
| verify-guessing | otp-verify | 300 | 0 | 2.4 | 6.8 | 5.5 | 1.3 | Pass |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2048 | 0 | 352 | 0 | 2400 | 110 | 39 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 17 |
| prefix-attack | 2400 | 0 | 2400 | 90 | 2310 | 0 | 0 | 0 | 0 | 0 | 90 | 1 | 90 |
| device-attack | 2400 | 0 | 2400 | 90 | 2310 | 0 | 0 | 0 | 0 | 0 | 90 | 66 | 3 |
| distributed-attack | 2400 | 0 | 2400 | 171 | 2229 | 0 | 0 | 0 | 0 | 0 | 171 | 1 | 171 |
| mixed-attack | 4800 | 0 | 2400 | 41 | 2359 | 0 | 0 | 0 | 0 | 0 | 2265 | 109 | 162 |
| conversion-mimicking | 1200 | 0 | 1200 | 60 | 1140 | 0 | 21 | 0 | 0 | 0 | 60 | 1 | 60 |
| targeted-phone | 300 | 0 | 300 | 5 | 295 | 0 | 0 | 0 | 0 | 0 | 5 | 1 | 5 |
| verify-guessing | 600 | 0 | 600 | 60 | 540 | 0 | 0 | 300 | 0 | 0 | 60 | 48 | 3 |

### Legitimate users during an attack

| scenario | users | requested | sent | refused | verified |
|---|---|---:|---:|---:|---:|
| mixed-attack | in the attacked range | 260 | 116 | 144 | 101 |
| mixed-attack | elsewhere | 2140 | 2108 | 32 | 1788 |

### Effectiveness and false positives

Judged on this run alone. An attack fails when more than its ceiling of the codes it asked for were sent,
guessing when a guess is taken, legitimate users when more than their ceiling of them were refused.

| scenario | check | value | limit | verdict |
|---|---|---:|---:|---|
| normal | legitimate: share refused | 0.000 | 0.010 | Pass |
| prefix-attack | attack: share of codes sent | 0.038 | 0.100 | Pass |
| device-attack | attack: share of codes sent | 0.038 | 0.100 | Pass |
| distributed-attack | attack: share of codes sent | 0.071 | 0.100 | Pass |
| mixed-attack | legitimate in the attacked range: share refused | 0.554 | 0.010 | Fail |
| mixed-attack | legitimate elsewhere: share refused | 0.015 | 0.010 | Fail |
| conversion-mimicking | attack: share of codes sent | 0.050 | 0.100 | Pass |
| targeted-phone | attack: share of codes sent | 0.017 | 0.100 | Pass |
| verify-guessing | guesses accepted | 0.000 | 0.000 | Pass |

### Event log (auth)

| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |
|---|---:|---:|---:|---:|---:|---:|
| normal | 4572 | 4633 | 0 | 0 | 0 | 0 |
| token-control | 2000 | 2019 | 0 | 0 | 0 | 0 |
| prefix-attack | 2392 | 2382 | 0 | 0 | 0 | 0 |
| device-attack | 2408 | 2412 | 0 | 0 | 0 | 0 |
| distributed-attack | 2391 | 2391 | 0 | 190 | 0 | 0 |
| mixed-attack | 6698 | 6677 | 0 | 204 | 0 | 0 |
| conversion-mimicking | 1212 | 1237 | 0 | 0 | 0 | 0 |
| targeted-phone | 309 | 309 | 0 | 0 | 0 | 0 |
| verify-guessing | 897 | 899 | 0 | 0 | 0 | 0 |
