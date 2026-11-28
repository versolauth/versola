# Alert rules

`versola-antifraud.rules.yaml` holds the first alert rules of the repository, for the antifraud module of `auth`. It is in the
Prometheus rule-group format, so Prometheus and VictoriaMetrics' vmalert read the same file. Each rule has a `for` and a
`severity` label (`warning`, or `critical` where the writer is about to lose events) and a `summary` and a `description`.
The board [`versola-antifraud`](../grafana/versola-antifraud.json) draws the same signals.

## Loading

Prometheus: add the file to `rule_files` in the configuration and reload.

```yaml
rule_files:
  - /etc/prometheus/rules/versola-antifraud.rules.yaml
```

vmalert (the cluster scrapes with vmagent, see [`k8s/monitoring/`](../../k8s/monitoring/vmagent-values.yaml)):

```bash
vmalert -rule=versola-antifraud.rules.yaml -datasource.url=<vmselect or victoria-metrics url> -notifier.url=<alertmanager url>
```

Check the file before loading it (`promtool check rules` for Prometheus, `vmalert -rule=... -dryRun` for vmalert):

```bash
promtool check rules dashboards/alerts/versola-antifraud.rules.yaml
```

The rules select by metric name and sum by `namespace`; they do not assume a `job`.

## What is in it

| Alert | Fires when | For | Severity |
| --- | --- | --- | --- |
| `AntifraudPartitionMaintenanceFailed` | partition maintenance of the event log failed | 15m | warning |
| `AntifraudPartitionsRunningOut` | `antifraud_partition_days_ahead` below 2 | 5m | critical |
| `AntifraudPartitionOwnerMismatch` | `antifraud_partition_owner_mismatch` is 1: the service's role does not own `af_event` | 10m | warning |
| `AntifraudEventsMovedFromDefault` | a day ran without its partition and its events were moved out of the default partition | none | warning |
| `AntifraudTuningRefreshFailed` | the tuning could not be read | 5m | warning |
| `AntifraudListRefreshFailed` | the lists could not be read | 5m | warning |
| `AntifraudListsStale` | the lists were last read more than 10 minutes ago | 5m | warning |
| `AntifraudTuningStale` | the tuning was last read more than 10 minutes ago | 5m | warning |
| `AntifraudStatsRollupFailed` | the statistics rollup failed | 10m | warning |
| `AntifraudDetectorFailed` | a detector failed (by kind) | 5m | warning |
| `AntifraudProposalHousekeepingFailed` | expiring or deleting proposals failed | 5m | warning |
| `AntifraudVelocityUntracked` | requests were not counted, a key could not be admitted to the full table | 5m | warning |
| `AntifraudSmsBudgetFull` | ranges went untracked, the SMS budget table is full | 5m | warning |
| `AntifraudEventsDropped` | events dropped with reason `write_failed` or `queue_full` | 10m | warning |
| `AntifraudVelocityFailed` | the velocity counters failed to flush, load or prune | 10m | warning |
| `AntifraudEvaluationFailed` | evaluations gave no decision (by reason) | 5m | warning |
| `AntifraudCountersResetFailed` | a counter reset did not complete | 5m | warning |

The file passes `promtool check rules` (15 rules, run by the reviewer of the PR that added it).

## `versola-audit.rules.yaml`

The audit log of `auth` and `central` (#469): the same format, loaded the same way. Every rule groups by `source`
(`auth`, `central`) as well as `namespace`, since both services write their own table (`auth_audit_event`, `central_audit_event`) with the same
writer and the same metric names.

| Alert | Fires when | For | Severity |
| --- | --- | --- | --- |
| `AuditEventsDropped` | `audit_events_dropped_total` increases: `overflow = drop` dropped events, or a best-effort insert failed | 5m | critical |
| `AuditEventsRejected` | `audit_events_rejected_total` increases: the database refused events one by one and they are not stored | none | critical |
| `AuditEventsFailed` | `audit_events_failed_total` increases: a batch the writer could not write, or a write that was interrupted | none | critical |
| `AuditEventsUnavailable` | `audit_events_unavailable_total` increases: requests were answered `503 audit_unavailable` | 5m | critical |
| `AuditQueueHigh` | `audit_queue_size` above 25000 (the queue holds 50000) | 5m | warning |
| `AuditEventsBlocked` | `audit_events_blocked_total` increases: requests waited for room in the queue (`overflow = block`) | 5m | warning |
| `AuditPartitionsRunningOut` | `audit_partitions_ahead` below 3 | 5m | critical |
| `AuditEventsInDefault` | `audit_events_in_default` above 0: a day's events sit in the default partition and are never moved | 30m | warning |
| `AuditPartitionOwnerMismatch` | `audit_partition_owner_mismatch` is 1: the service's role does not own its audit table | 10m | warning |
| `AuditReplicationSlotRetainsWal` | `db_replication_slot_retained_bytes` of a slot above 10 GiB: a slot (the audit log's replication is one) is not read and the primary keeps its WAL | 15m | warning |

The file passes `promtool check rules` (10 rules); the tool is not in the repository, so a reviewer runs it.

The last rule reads the gauges the services publish about the database's replication slots (`db_replication_slot_retained_bytes{slot}`, `db_replication_slot_active{slot}`); there is no metric of whether a subscription to an audit table exists at all, so a replication that was never set up, or whose slot was dropped, raises no alert.

Counters appear with their first increment, so `increase()` does not see the very first failure of a new pod; a failure
that repeats is seen. The two stale-age rules read gauges the service sets after each successful refresh, so they stay
silent while lists are off (the default), and they catch a refresh that hangs, which moves no failure counter.
`antifraud_lists_refreshed_timestamp_seconds`, `antifraud_tuning_refreshed_timestamp_seconds`,
`antifraud_partition_days_ahead` and `antifraud_counters_reset_failed_total` arrive with the antifraud shadow and store changes.

## Central's audit log

`versola-audit-central.rules.yaml` holds the alerts of what central records of administrators' actions. The audit writer's own
alerts are in `versola-audit.rules.yaml`.

| Alert | Fires when | For | Severity |
| --- | --- | --- | --- |
| `AuditActorMissing` | `audit_actor_missing_total` grows: a call to the admin API had no actor, or one that is no user and no client (refused with `401`) (`reason`: `absent`, `invalid`), or an address that is none (`invalid_ip`) | 1h | warning |
| `AuditReportWithoutAuth` | `audit_report_partial_total` grows: the per-user report was served without auth's part (`reason`: `endpoint_missing`, `unavailable`, `unreadable`) | 1h | warning |
| `AuditEventWithoutTarget` | `audit_event_without_target_total{kind}` grows: a change succeeded and its row has no target (or no user on a user's route) | 1h | warning |

## Certificates

`versola-certificates.rules.yaml` holds the alerts of the certificates Versola holds and renews (#1001). The thresholds are not days: the
services count the certificates past the middle of their renewal window (`certificates_past_renewal_midpoint{kind}`), which they work out from
the lifetimes of [`deploy.md`](../../deploy.md) and their own settings, and a listener's certificate is judged against its own length. `kind` is
`client` (the certificates `central` issues and renews for edge-fronted clients), `edge` (the identity of an edge) or `est` (a client's,
obtained through EST). Nothing is labelled by client or edge; the certificate behind an alert is found by its `cert.renewed` events in the
audit log.

| Alert | Fires when | For | Severity |
| --- | --- | --- | --- |
| `CertificateRenewalOverdue` | `certificates_past_renewal_midpoint` is above 0: a certificate is past the middle of its renewal window and still not renewed | 15m | warning |
| `CertificateRenewalsFailing` | more than one renewal of a kind ended without a certificate in 3 hours (`certificate_renewals_total`: `outcome=failure`, and for `kind=client` also `denied`) | 5m | warning |
| `ListenerCertificateExpiring` | less than a sixth of the life of a service's mutual-TLS listener certificate is left (the middle of the last third) | 1h | warning |
| `ListenerCertificateExpiringSoon` | less than an eighteenth of it is left, or none | 15m | critical |

The file passes `promtool check rules` (4 rules), and the expressions were run against synthetic series with `promtool test rules` (a kind with nothing
past the middle is silent, one failure does not fire `CertificateRenewalsFailing`, two do, a refusal of an edge or EST caller does not, a listener certificate at 50 of 365
days fires the warning only); those tests are not kept in the repository (the rule of the TLS cipher policy below is the first to keep its test).

`central` reads the count from its own tables (`client_certificate_issuance`, `edge_certificates`, `client_est_certificates`) every five minutes, with one
aggregate query for each kind, so what is exported does not grow with the number of clients; every replica publishes the same number. The edges of a
suspended partner are not counted: they are refused renewal on purpose and their certificates are meant to run out. A certificate that expired is counted
until its edge or client is deleted (an EST client: until it is enrolled again). The scope is the failure of the automatic renewal: for `client` and `edge`
the count is the sign that it broke, and for `est` it is the only sign that a client, which renews itself, fell behind. No gauge of the earliest end of a
kind is exported: with renewal working it is always far away, and a renewal that does not work is caught by the count.

A renewal that never reaches `central` (an edge that cannot connect to it) is not counted in `certificate_renewals_total`; the edge's warning in its log and
`CertificateRenewalOverdue` are what show it. The listener gauges are set once, when a service starts: they are the end of the certificate it serves now, so a
renewed file counts only after the restart. Certificates that an edge generates for an enrolled client (`enrollEdgeClientCertificate`) are not tracked: `central`
stores only their last serial, not their end.

## TLS cipher policy

`versola-tls.rules.yaml` (#1049): `auth`, `central` and `edge` refuse the TLS 1.2 AES-CBC cipher suites (RFC 9325 §4.2, which FAPI 2.0 asks for through BCP 195).
Each exports the gauge `tls_cbc_refused` once, at start: 1 when the policy is in force, 0 when `VERSOLA_TLS_CIPHER_POLICY=jdk` turned it off. A service that is asked
for the policy and cannot apply it does not start, so there is no third value. The rule groups by namespace, like the others, and names no service: the one running
without the policy is the one whose own `tls_cbc_refused` is 0.

| Alert | Fires when | For | Severity |
| --- | --- | --- | --- |
| `TlsCbcPolicyOff` | `tls_cbc_refused` is 0 for some service of a namespace: the policy was opted out | 10m | warning |

The file passes `promtool check rules` (1 rule), and its test, kept in [`tests/versola-tls.rules.test.yaml`](tests/versola-tls.rules.test.yaml), runs against synthetic series:

```bash
promtool test rules dashboards/alerts/tests/versola-tls.rules.test.yaml
```

(a namespace where one service exports 0 fires after the `for`, one where every service exports 1 does not, and the alert clears when the policy comes back).
On an installation that does not have to meet FAPI 2.0 an opt-out is a choice, not a fault: leave this rule out of the rules that page.
A service that exports nothing (it is down, or runs an image from before the policy) is not seen by this rule; the rules for what is down are elsewhere.

## The limit on `/native/start` of `edge`

`versola-edge-rate-limits.rules.yaml`: the limit per source address on `/native/start` (`rateLimits.nativeStart` of an edge, or the default while it
is not set) is counted in a limiter of its own, `edge_native_start_rate_limit_*`, with `policy` `native-start` for a limit that was set and
`native-start-default` for the default. The rules group by namespace. The board [`versola`](../grafana/versola.json) draws the same series in
"edge — Rate limits".

| Alert | Fires when | For | Severity |
| --- | --- | --- | --- |
| `NativeStartLimitOverflow` | `edge_native_start_rate_limit_overflow_hits_total` grew: a pod holds 100,000 addresses and new ones share one bucket | none | warning |
| `NativeStartRefusedByDefault` | the default, where it is enforced, refuses more than 1% of the starts it counts | 15m | warning |

The first is a caller that changes its address per call: block the prefix in front of the edge, do not raise the limit. The second is a flood or
real users behind one address (a carrier's shared address, or an edge that reads its proxy's address): the access log of the edge says which.
A limit that somebody set (`native-start`) refusing is that limit working, and no rule fires on it.

The file passes `promtool check rules` (2 rules), and its test runs against synthetic series (5% refused in one namespace and 0.1% in another;
the cap passed from minute 20):

```bash
promtool test rules dashboards/alerts/tests/versola-edge-rate-limits.rules.test.yaml
```
