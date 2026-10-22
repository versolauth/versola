# antifraud loadgen: `off-repeat`

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.2 | 6.9 | 6.4 | 0.5 | Pass |
| normal | otp-verify | 2028 | 0 | 8.0 | 14.5 | 14.5 | 0.1 | Pass |
| normal | refresh | 15162 | 0 | 3.0 | 5.7 | 5.6 | 0.1 | Pass |
| normal | token | 2028 | 0 | 3.3 | 6.3 | 6.0 | 0.3 | Pass |
| token-control | refresh | 3000 | 0 | 7.1 | 12.5 | 12.5 | 0.0 | Pass |
| token-control | token | 1000 | 0 | 4.9 | 9.4 | 9.0 | 0.4 | Pass |
| prefix-attack | otp-request | 2400 | 0 | 5.3 | 10.1 | 9.6 | 0.4 | Pass |
| device-attack | otp-request | 2400 | 0 | 5.3 | 11.0 | 9.0 | 2.1 | Pass |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2028 | 0 | 372 | 0 | 2400 | 110 | 36 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 20 |
| prefix-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 1 | 2400 |
| device-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 110 | 35 |

### Event log (auth)

| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |
|---|---:|---:|---:|---:|---:|---:|
| normal | 4608 | 4718 | 0 | 0 | 0 | 0 |
| token-control | 2000 | 2006 | 0 | 0 | 0 | 0 |
| prefix-attack | 2397 | 2387 | 0 | 0 | 0 | 0 |
| device-attack | 2403 | 2413 | 0 | 0 | 0 | 0 |
