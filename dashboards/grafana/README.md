# Grafana dashboard

`versola.json` is one board for the whole system: auth, central and edge.

1. **Overview** — two lines of four tiles per service (pods, requests, 5xx share, p99, two
   service-specific tiles, DB pool, heap). Green/yellow/red; read this first.
2. **A section per service** — traffic, errors and latency per endpoint, then the service's own
   signals: auth (sign-in funnel, token refresh), edge (resources, web, mobile `/native`, token
   revocation), central (config syncs, admin writes, change notifications). The database and
   runtime rows are collapsed.

Nothing on it concerns the load generator.

## Loading it

Import `versola.json` in Grafana (Dashboards → New → Import), or provision it from a ConfigMap
with the label your Grafana sidecar watches. The only variables are the Prometheus data source and
the namespace. The dashboard's uid is `versola`, so importing it again overwrites it.

## What it expects from the scrape

Series are selected by `namespace` and `app_kubernetes_io_component` (the pod label
`app.kubernetes.io/component`, `auth|central|edge`). All three services are scraped under one `job`,
so `job` cannot tell them apart; the scrape configuration must copy pod labels onto the series
(`labelmap`, as in [`k8s/monitoring/vmagent-values.yaml`](../../k8s/monitoring/vmagent-values.yaml)).

Some panels are empty by design until their source exists: the database pool panels need
`postgres.pool-metrics-interval`, the "Edge overhead (measured)" panel needs `edge_proxy_overhead_seconds`,
and the panels that count failures are empty while nothing fails.

## Changing it

The JSON is generated. Edit `generate.py`, run it, commit both:

```bash
python3 dashboards/grafana/generate.py
```

A threshold is drawn only where a number is defensible without a written SLA.
