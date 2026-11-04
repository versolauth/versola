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
(`auth`, `central`) as well as `namespace`, since both services write their own `audit_event` table with the same
writer and the same metric names.

| Alert | Fires when | For | Severity |
| --- | --- | --- | --- |
| `AuditEventsDropped` | `audit_events_dropped_total` increases: `overflow = drop` lost events, or a best-effort insert failed | 5m | critical |
| `AuditEventsRejected` | `audit_events_rejected_total` increases (auth): the database refused events one by one and they are not stored | none | critical |
| `AuditEventsBlocked` | `audit_events_blocked_total` increases: requests waited for room in the queue (`overflow = block`) | 5m | warning |
| `AuditPartitionsRunningOut` | `audit_partitions_ahead` below 3 | 5m | critical |
| `AuditEventsInDefault` | `audit_events_in_default` above 0: a day's events sit in the default partition and are never moved | 30m | warning |
| `AuditPartitionOwnerMismatch` | `audit_partition_owner_mismatch` is 1: the service's role does not own `audit_event` | 10m | warning |

The file passes `promtool check rules` (6 rules); the tool is not in the repository, so a reviewer runs it.

Counters appear with their first increment, so `increase()` does not see the very first failure of a new pod; a failure
that repeats is seen. The two stale-age rules read gauges the service sets after each successful refresh, so they stay
silent while lists are off (the default), and they catch a refresh that hangs, which moves no failure counter.
`antifraud_lists_refreshed_timestamp_seconds`, `antifraud_tuning_refreshed_timestamp_seconds`,
`antifraud_partition_days_ahead` and `antifraud_counters_reset_failed_total` arrive with the antifraud shadow and store changes.
