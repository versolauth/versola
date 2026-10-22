# antifraud loadgen: `off`

Added p99 is this run's p99 less the baseline's; ceiling 10.0 ms on otp-request, otp-verify,
within +-5.0 ms (noise) on refresh, token.

### Latency

| scenario | call | n | errors | p50 ms | p99 ms | baseline p99 ms | added p99 ms | verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| normal | otp-request | 2400 | 0 | 3.2 | 6.4 | - | - | NoBaseline |
| normal | otp-verify | 2062 | 0 | 8.0 | 14.5 | - | - | NoBaseline |
| normal | refresh | 15426 | 0 | 3.0 | 5.6 | - | - | NoBaseline |
| normal | token | 2062 | 0 | 3.3 | 6.0 | - | - | NoBaseline |
| token-control | refresh | 3000 | 0 | 7.1 | 12.5 | - | - | NoBaseline |
| token-control | token | 1000 | 0 | 5.0 | 9.0 | - | - | NoBaseline |
| prefix-attack | otp-request | 2400 | 0 | 5.2 | 9.6 | - | - | NoBaseline |
| device-attack | otp-request | 2400 | 0 | 5.2 | 9.0 | - | - | NoBaseline |

### Outcomes and SMS

| scenario | arrivals | waited | requested | sent | refused | other | verified | rejected | not entered | failed | SMS sent | ranges | busiest range |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| normal | 2400 | 0 | 2400 | 2400 | 0 | 0 | 2062 | 0 | 338 | 0 | 2400 | 110 | 34 |
| token-control | 3000 | 0 | 1000 | 1000 | 0 | 0 | 1000 | 0 | 0 | 0 | 1000 | 110 | 20 |
| prefix-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 1 | 2400 |
| device-attack | 2400 | 0 | 2400 | 2400 | 0 | 0 | 0 | 0 | 0 | 0 | 2400 | 110 | 31 |

### Event log (auth)

| scenario | enqueued | written | dropped | refused by budget | budget table full | queue peak |
|---|---:|---:|---:|---:|---:|---:|
| normal | 4484 | 4600 | 0 | 0 | 0 | 1 |
| token-control | 2006 | 2042 | 0 | 0 | 0 | 0 |
| prefix-attack | 2400 | 2400 | 0 | 0 | 0 | 0 |
| device-attack | 2399 | 2379 | 0 | 0 | 0 | 0 |
