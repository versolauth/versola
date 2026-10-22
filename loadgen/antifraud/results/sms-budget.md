# antifraud loadgen: `sms-budget`

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.3 | 6.2 | 6.4 | -0.2 | Pass |
| normal | otp-verify | 2034 | 0 | 8.1 | 14.5 | 14.5 | 0.1 | Pass |
| normal | refresh | 15246 | 0 | 3.0 | 5.7 | 5.6 | 0.1 | Pass |
| normal | token | 2034 | 0 | 3.3 | 6.5 | 6.0 | 0.4 | Pass |
| token-control | refresh | 3000 | 0 | 7.2 | 12.7 | 12.5 | 0.2 | Pass |
| token-control | token | 1000 | 0 | 4.9 | 7.9 | 9.0 | -1.1 | Pass |
| prefix-attack | otp-request | 2400 | 0 | 4.8 | 8.4 | 9.6 | -1.2 | Pass |
| device-attack | otp-request | 2400 | 0 | 5.4 | 9.5 | 9.0 | 0.5 | Pass |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2034 | 0 | 366 | 0 | 2400 | 110 | 35 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 18 |
| prefix-attack | 2400 | 0 | 2400 | 171 | 2229 | 0 | 0 | 0 | 0 | 0 | 171 | 1 | 171 |
| device-attack | 2400 | 0 | 2400 | 2383 | 17 | 0 | 0 | 0 | 0 | 0 | 2383 | 109 | 33 |

### Event log (auth)

| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |
|---|---:|---:|---:|---:|---:|---:|
| normal | 4542 | 4600 | 0 | 0 | 0 | 0 |
| token-control | 2000 | 2006 | 0 | 0 | 0 | 0 |
| prefix-attack | 2400 | 2386 | 0 | 2229 | 0 | 0 |
| device-attack | 2391 | 2396 | 0 | 17 | 0 | 0 |
