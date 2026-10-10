#!/usr/bin/env python3
"""Generates the single Grafana board in this directory: versola.json, covering auth, central and edge.

    python3 dashboards/grafana/generate.py

One board, so nobody switches between dashboards to follow a request across services. Top to bottom:

  1. Overview     a status matrix, two lines of four tiles per service, green/yellow/red at a glance.
                  A CTO stops reading here.
  2. auth, central, edge   one section each, same skeleton in every service:
       traffic (requests, errors, latency per endpoint) -> what the service exists to do
       (auth: sign-in and token refresh; edge: proxying and the revocation cache; central: config
       syncs and admin writes). Then two collapsed rows, Database & dependencies and Runtime, where a
       developer goes once the rows above point at a service.

The board is generated, not hand-edited: edit this script, re-run it, commit the JSON.

Deliberately absent: anything about the load generator. The board describes the system under test.

Every series is selected by `namespace` and `app_kubernetes_io_component` (the pod label the chart
sets and the vmagent job in k8s/monitoring/vmagent-values.yaml copies onto every series). All three
services are scraped under one `job`, so `job` cannot tell them apart.

Thresholds: only where a number is defensible without a written SLA -- the auth /token p99 target
(120 ms), "any 5xx is worth a look", pool and heap saturation. Anything else is plotted without one.
"""
from __future__ import annotations

import json
from pathlib import Path

OUT_DIR = Path(__file__).parent
DS = {"type": "prometheus", "uid": "${datasource}"}

GREEN, YELLOW, RED = "green", "#EAB839", "red"


def steps(*pairs: tuple[float | None, str]) -> dict:
    return {"mode": "absolute", "steps": [{"color": c, "value": v} for v, c in pairs]}


class Board:
    def __init__(self, uid: str, title: str, description: str):
        self.uid, self.title, self.description = uid, title, description
        self.panels: list[dict] = []
        self._id = 1
        self._x = 0
        self._y = 0
        self._row_h = 0
        self._collapsed: dict | None = None  # open collapsed row panels are appended to
        self._resume_y = 0
        self.component = ""
        self.phase = "overview"
        self.sel = ""

    def use(self, component: str) -> None:
        """Every query that follows selects this service; see module docstring."""
        self.component = component
        self.sel = f'namespace="$namespace", app_kubernetes_io_component="{component}"'

    # -- layout -----------------------------------------------------------------------------

    def _place(self, panel: dict, w: int, h: int) -> dict:
        if self._x + w > 24:
            self._x, self._y, self._row_h = 0, self._y + self._row_h, 0
        panel["id"] = self._id
        panel["gridPos"] = {"h": h, "w": w, "x": self._x, "y": self._y}
        self._id += 1
        self._x += w
        self._row_h = max(self._row_h, h)
        (self._collapsed["panels"] if self._collapsed is not None else self.panels).append(panel)
        return panel

    def row(self, title: str, collapsed: bool = False) -> None:
        if self._collapsed is not None:
            # a collapsed row takes one line on the page however much it holds
            self._y, self._collapsed = self._resume_y, None
        else:
            self._y += self._row_h
        self._x, self._row_h = 0, 0
        row = {"id": self._id, "type": "row", "title": title, "collapsed": collapsed, "panels": [], "gridPos": {"h": 1, "w": 24, "x": 0, "y": self._y}}
        self._id += 1
        self.panels.append(row)
        self._y += 1
        if collapsed:
            self._collapsed, self._resume_y = row, self._y

    def text(self, markdown: str, h: int = 2) -> None:
        self._place({"type": "text", "title": "", "options": {"mode": "markdown", "content": markdown}, "transparent": True}, 24, h)

    # -- panels -----------------------------------------------------------------------------

    def stat(self, title: str, expr: str, unit: str = "short", desc: str = "", thresholds: dict | None = None, w: int = 6, decimals: int | None = None) -> None:
        defaults: dict = {"unit": unit, "thresholds": thresholds or steps((None, "text"))}
        if decimals is not None:
            defaults["decimals"] = decimals
        self._place({
            "type": "stat", "title": title, "description": desc, "datasource": DS,
            "fieldConfig": {"defaults": defaults, "overrides": []},
            "options": {
                "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                "colorMode": "background" if thresholds else "none",
                "graphMode": "none", "textMode": "value",
            },
            "targets": [{"refId": "A", "datasource": DS, "expr": expr, "instant": False}],
        }, w, 3)

    def ts(self, title: str, queries: list[tuple[str, str]], unit: str = "short", desc: str = "", w: int = 12, h: int = 8,
           stack: bool = False, line: dict | None = None, minimum: float | None = None, maximum: float | None = None) -> None:
        """`line` draws a dashed threshold on the chart; it never colors the data."""
        custom: dict = {"drawStyle": "line", "lineWidth": 1, "fillOpacity": 25 if stack else 0, "showPoints": "never", "spanNulls": False}
        if stack:
            custom["stacking"] = {"mode": "normal", "group": "A"}
        defaults: dict = {"unit": unit, "custom": custom}
        if minimum is not None:
            defaults["min"] = minimum
        if maximum is not None:
            defaults["max"] = maximum
        if line:
            custom["thresholdsStyle"] = {"mode": "dashed"}
            defaults["thresholds"] = line
        self._place({
            "type": "timeseries", "title": title, "description": desc, "datasource": DS,
            "fieldConfig": {"defaults": defaults, "overrides": []},
            "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True}, "tooltip": {"mode": "multi", "sort": "desc"}},
            "targets": [{"refId": chr(ord("A") + i), "datasource": DS, "expr": e, "legendFormat": legend} for i, (e, legend) in enumerate(queries)],
        }, w, h)

    # -- query helpers ----------------------------------------------------------------------

    def http(self, extra: str = "") -> str:
        return f'{{{self.sel}{extra}}}'

    def rate(self, metric: str, extra: str = "") -> str:
        return f'rate({metric}{self.http(extra)}[$__rate_interval])'

    def req_rate(self, extra: str = "") -> str:
        return self.rate("http_server_requests_total", extra)

    def quantile(self, q: float, extra: str = "", by: str = "") -> str:
        group = f"le, {by}" if by else "le"
        return f'histogram_quantile({q}, sum by ({group}) ({self.rate("http_server_request_duration_seconds_bucket", extra)}))'

    def ratio(self, part: str, total: str) -> str:
        return f"(sum({part}) or vector(0)) / clamp_min(sum({total}), 0.001)"

    # -- output -----------------------------------------------------------------------------

    def render(self) -> dict:
        return {
            "uid": self.uid,
            "title": self.title,
            "description": self.description,
            "tags": ["versola", "versola-service"],
            "editable": True,
            "graphTooltip": 1,
            "schemaVersion": 39,
            "version": 1,
            "refresh": "30s",
            "time": {"from": "now-1h", "to": "now"},
            "timezone": "utc",
            "links": [{"type": "dashboards", "title": "Services", "tags": ["versola-service"], "asDropdown": False, "includeVars": True, "keepTime": True, "icon": "external link"}],
            "templating": {"list": [
                {"name": "datasource", "label": "Data source", "type": "datasource", "query": "prometheus", "current": {}, "hide": 0},
                {
                    "name": "namespace", "label": "Environment", "type": "query", "datasource": DS,
                    "query": 'label_values(up{app_kubernetes_io_component=~"auth|central|edge"}, namespace)',
                    "refresh": 1, "multi": False, "includeAll": False, "hide": 0,
                },
            ]},
            "panels": self.panels,
        }


# -- shared sections --------------------------------------------------------------------------

POOL_PCT = 'sum(db_client_connection_count{{{sel}, state="used"}}) / clamp_min(sum(db_client_connection_max{{{sel}}}), 1)'
HEAP_PCT = 'max(jvm_memory_used_bytes{{{sel}, area="heap"}} / jvm_memory_max_bytes{{{sel}, area="heap"}})'

NO_POOL_DATA = " Empty if `postgres.pool-metrics-interval` is not set for this service."


def overview(b: Board, specific: list[tuple]) -> None:
    """Eight tiles per service in two lines of four: four every service has, two that are its own, two saturation tiles."""
    if b.phase != "overview":
        return
    b.row(f"Overview — {b.component}")
    b.stat("Pods up", f"sum(up{b.http()})", desc="Replicas Prometheus can scrape right now. Fewer than expected means a crashed, restarting or unschedulable pod.",
           thresholds=steps((None, RED), (1, GREEN)))
    b.stat("Requests/s", f"sum({b.req_rate()})", unit="reqps", desc="All HTTP requests this service answers, any status.")
    b.stat("Server errors", b.ratio(b.req_rate(', status_class="5xx"'), b.req_rate()), unit="percentunit", decimals=2,
           desc="Share of requests answered 5xx -- the service failing, not the caller erring. Yellow from 0.1%, red from 1%.",
           thresholds=steps((None, GREEN), (0.001, YELLOW), (0.01, RED)))
    b.stat("Latency p99", f"{b.quantile(0.99)}", unit="s", desc="99 of 100 requests are faster than this, over every endpoint of the service.")
    for args in specific:
        b.stat(args[0], args[1], **args[2])
    b.stat("DB pool", POOL_PCT.format(sel=b.sel), unit="percentunit", decimals=0,
           desc="Share of database connections checked out. Near 100% requests start queueing for a connection." + NO_POOL_DATA,
           thresholds=steps((None, GREEN), (0.7, YELLOW), (0.9, RED)))
    b.stat("Heap", HEAP_PCT.format(sel=b.sel), unit="percentunit", decimals=0,
           desc="JVM heap used / heap limit on the fullest pod. Sustained near 100% means GC thrashing, then OOM.",
           thresholds=steps((None, GREEN), (0.8, YELLOW), (0.95, RED)))


def traffic(b: Board, latency_line: dict | None = None, title: str = "Traffic, errors, latency", extra: str = "", what: str = "",
            by_resource: bool = False) -> None:
    """The same eight panels for a slice of a service's endpoints. `extra` is a label matcher
    (", route=~...") narrowing every query to that slice; empty means the whole service."""
    b.row(f"{b.component} — {title}")
    where = f" ({what})" if what else ""
    b.ts("Requests / s by endpoint", [(f"sum by (method, route) ({b.req_rate(extra)})", "{{method}} {{route}}")], unit="reqps", stack=True,
         desc="Where the traffic goes. `/token?grant_type=…` is split by grant type." + where)
    b.ts("Server errors (5xx) / s by endpoint", [(f'sum by (method, route) ({b.req_rate(extra + ", status_class=\"5xx\"")})', "{{method}} {{route}}")], unit="reqps", stack=True,
         desc="Empty is healthy. Every series here is a bug or an outage of a dependency; the endpoint says where to start.")
    b.ts("Latency", [(b.quantile(0.5, extra), "p50"), (b.quantile(0.95, extra), "p95"), (b.quantile(0.99, extra), "p99")], unit="s", line=latency_line,
         desc="Typical (p50), slow (p95) and worst-case (p99) request. Watch p99 -- averages hide the users who are suffering.")
    b.ts("Slowest endpoints (p99)", [(f"topk(5, {b.quantile(0.99, extra, by='method, route')})", "{{method}} {{route}}")], unit="s",
         desc="The five endpoints with the highest p99. If overall latency rose, this says which endpoint to blame.")
    b.ts("Client errors (4xx) / s by endpoint", [(f'sum by (method, route, status) ({b.req_rate(extra + ", status_class=\"4xx\"")})', "{{method}} {{route}} {{status}}")], unit="reqps", stack=True,
         desc="Callers sending bad or unauthorized requests. Not an outage, but a sudden jump is a broken client, an expired credential or probing.")
    if by_resource:
        # /resources/<id>/<path>: the id is configuration, so grouping by it is bounded
        b.ts("Requests / s by resource", [(f'sum by (resource) (label_replace({b.req_rate(extra)}, "resource", "$1", "route", "/resources/([^/]+)/.*"))', "{{resource}}")], unit="reqps", stack=True,
             desc="The same traffic grouped by protected resource (fapi-core, fapi-pay, central, …) -- which API is being used.")
    else:
        route_filter = extra.replace("route=~", "route=~")
        b.ts("Requests being served right now", [(f"sum by (pod) (http_server_active_requests{b.http(route_filter)})", "{{pod}}")],
             desc="In-flight requests per pod. A pod whose line keeps climbing is stuck on something slow; a flat uneven split means poor load balancing.")


def cleanup(b: Board, volume: list[tuple[str, str]], expiring: str) -> None:
    """How much data there is and how much of it has expired, then how cleanup is doing. auth and edge run the
    cleanup manager (central does not).

    `volume` is (tile title, table) for the tables worth a number of their own; `expiring` is a regex of
    those among them that expire, for the live-versus-expired panel. Sizes are the database's row estimate
    (db_table_rows_estimate), the expired counts come from cleanup_expired_rows, which is capped; every
    replica reports the same database, hence max by (table) throughout.
    """
    b.row(f"{b.component} — Data volume and cleanup of expired rows")
    s = b.sel
    est = "approximate: the database's own row estimate, refreshed by autovacuum, not a count"
    for title, table in volume:
        b.stat(f"{title} (approx.)", f'max(db_table_rows_estimate{{{s}, table="{table}"}})', desc=f"Rows in `{table}`, {est}.", w=6 if len(volume) < 4 else 4)
    b.stat("Expired rows waiting", f"sum(max by (table) (cleanup_expired_rows{{{s}}}))",
           desc="Rows past their expiry that are still in the database, across every table with an expires_at. Counted up to a cap per table, so a very large backlog reads as at least that. Falling or flat near 0 is healthy.",
           w=6 if len(volume) < 4 else 4)
    b.ts("Live and expired rows", [
        (f'clamp_min(max by (table) (db_table_rows_estimate{{{s}, table=~"{expiring}"}}) - on (table) max by (table) (cleanup_expired_rows{{{s}, table=~"{expiring}"}}), 0)', "{{table}} live"),
        (f'max by (table) (cleanup_expired_rows{{{s}, table=~"{expiring}"}})', "{{table}} expired"),
    ], unit="short", minimum=0,
         desc=f"Rows still valid and rows past their expiry that are not yet removed, per table. Live is the size estimate minus expired, so it is {est.split(':')[0]}. Expired should fall back towards 0 after each cleanup run.")
    b.ts("Expired rows still in the database, by table", [(f"max by (table) (cleanup_expired_rows{{{s}}})", "{{table}}")], unit="short", minimum=0,
         desc="Per table, counted up to a cap. This is the backlog: it should shrink after each cleanup run. A line that only climbs is a table cleanup is losing to.")
    b.ts("How overdue the oldest expired row is", [(f"max by (table) (cleanup_oldest_expired_age_seconds{{{s}}})", "{{table}}")], unit="s", minimum=0,
         desc="How long ago the oldest row that should already be gone expired. Cannot exceed roughly the table's cleanup interval plus the time one run takes if cleanup keeps up.")
    b.ts("Expired rows removed / s", [(f"sum by (table) (rate(cleanup_rows_deleted_total{{{s}}}[$__rate_interval]))", "{{table}}")], unit="ops", stack=True,
         desc="Rows the cleanup job deleted, per table. Should follow the rate rows are created at; flat zero on a table that is being written to means it is not being cleaned.")
    b.ts("Expired rows in tables with no cleanup", [(f"max by (table) (cleanup_expired_rows{{{s}}}) and on (table) (max by (table) (cleanup_configured{{{s}}}) == 0)", "{{table}}")], unit="short", minimum=0,
         desc="Tables that have an expires_at column, but no cleanup is configured for them, so what expires stays. Empty is good; anything here only ever grows.")
    b.ts("Time since each table was last cleaned", [(f"time() - max by (table) (cleanup_last_success_timestamp_seconds{{{s}}})", "{{table}}")], unit="s", minimum=0,
         desc="Per table. Compare with the table's cleanup interval in the service config: a line that keeps climbing past it means cleanup of that table has stopped.")
    b.ts("Cleanup batch duration (p99)", [(f'histogram_quantile(0.99, sum by (le, operation) (rate(db_client_operation_duration_seconds_bucket{{{s}, operation=~"cleanup-batch-.*"}}[$__rate_interval])))', "{{operation}}")], unit="s",
         desc="How long one delete batch takes, per table. Growing duration with a stable batch size means the table is bloating or the index is not keeping up.")
    b.ts("Failed cleanup batches / s", [(f'sum by (operation) (rate(db_client_operation_duration_seconds_count{{{s}, operation=~"cleanup-batch-.*", outcome="failure"}}[$__rate_interval]))', "{{operation}}")], unit="ops",
         line=steps((None, GREEN), (0.0001, RED)),
         desc="Empty is healthy. A failed batch is logged and that table is tried again at its next interval.")


def dependencies(b: Board) -> None:
    b.row(f"{b.component} — Database and dependencies", collapsed=True)
    sel = b.sel
    b.ts("DB pool in use", [(f'sum by (pool_name) (db_client_connection_count{{{sel}, state="used"}}) / clamp_min(sum by (pool_name) (db_client_connection_max{{{sel}}}), 1)', "{{pool_name}}")],
         unit="percentunit", minimum=0, maximum=1, line=steps((None, GREEN), (0.9, RED)),
         desc="Checked-out connections / pool size, per pool. Pinned at 100% means the pool is the bottleneck, not the database." + NO_POOL_DATA)
    b.ts("Waiting for a DB connection", [
        (f"histogram_quantile(0.99, sum by (le) (rate(db_client_connection_wait_time_seconds_bucket{{{sel}}}[$__rate_interval])))", "wait p99"),
        (f"sum(db_client_connection_pending_requests{{{sel}}})", "threads waiting"),
    ], unit="s", desc="Time a request spends queued before it even reaches the database (seconds), and how many are queued. Should be ~0; anything visible is added straight to user latency.")
    b.ts("Slowest queries (p99)", [(f"topk(5, histogram_quantile(0.99, sum by (le, repository, operation) (rate(db_client_operation_duration_seconds_bucket{{{sel}}}[$__rate_interval]))))", "{{repository}}.{{operation}}")], unit="s",
         desc="The five slowest database operations by repository and method. First place to look when latency rises but the pool is fine.")
    b.ts("Failed queries / s", [(f'sum by (repository, operation) (rate(db_client_operation_duration_seconds_count{{{sel}, outcome!="success"}}[$__rate_interval]))', "{{repository}}.{{operation}}")], unit="ops",
         desc="Database operations that did not succeed. Empty is healthy.")
    b.ts("Calls to other services — latency p99", [(f"histogram_quantile(0.99, sum by (le, peer) (rate(http_client_request_duration_seconds_bucket{{{sel}}}[$__rate_interval])))", "{{peer}}")], unit="s",
         desc="How long this service waits for each service it calls. Part of our own latency.")
    b.ts("Calls to other services — failures / s", [(f'sum by (peer) (rate(http_client_requests_total{{{sel}, status_class=~"error|5xx"}}[$__rate_interval]))', "{{peer}}")], unit="reqps",
         desc="Calls that failed: connection errors or a 5xx from the other side. Empty is healthy.")


def runtime(b: Board) -> None:
    b.row(f"{b.component} — Runtime (per pod)", collapsed=True)
    sel = b.sel
    b.ts("CPU", [(f"sum by (pod) (rate(process_cpu_seconds_total{{{sel}}}[$__rate_interval]))", "{{pod}}")], unit="short",
         desc="CPU cores in use per pod. Compare with the pod's CPU limit: at the limit the pod is throttled and latency climbs.")
    b.ts("Heap", [
        (f'sum by (pod) (jvm_memory_used_bytes{{{sel}, area="heap"}})', "{{pod}} used"),
        (f'sum by (pod) (jvm_memory_max_bytes{{{sel}, area="heap"}})', "{{pod}} limit"),
    ], unit="bytes", minimum=0, desc="Heap in use against its limit. A sawtooth is normal (GC working); a floor that keeps rising is a leak.")
    b.ts("Time spent in garbage collection", [(f"sum by (pod) (rate(jvm_gc_collection_seconds_sum{{{sel}}}[$__rate_interval]))", "{{pod}}")], unit="percentunit", minimum=0,
         desc="Share of wall-clock time the JVM is paused for GC. Above a few percent the service is starved of memory.")
    b.ts("Threads", [(f"sum by (pod) (jvm_threads_current{{{sel}}})", "{{pod}}")],
         desc="Live JVM threads per pod. Unbounded growth is a thread leak.")


# -- the three boards -----------------------------------------------------------------------

def add_auth(b: Board, phase: str) -> None:
    b.use("auth")
    refresh = ', route=~"/token.grant_type=refresh_token"'
    overview(b, [
        ("Refreshes/s", f"sum({b.req_rate(refresh)})", dict(unit="reqps", desc="Clients exchanging a refresh token for a new access token -- how many sessions are being kept alive.")),
        ("Refresh rejected", b.ratio(b.req_rate(refresh + ', status_class!="2xx"'), b.req_rate(refresh)), dict(
            unit="percentunit", decimals=1,
            desc="Share of refresh attempts that were turned down: token expired, revoked, already used, or server error. Users behind these are signed out.",
            thresholds=steps((None, GREEN), (0.01, YELLOW), (0.05, RED)))),
    ])
    if phase == "overview":
        return
    traffic(b, latency_line=steps((None, GREEN), (0.12, RED)))

    b.row("auth — Token refresh")
    b.ts("Refreshes / s by result", [
        (f'sum by (status_class) ({b.req_rate(refresh)})', "{{status_class}}"),
    ], unit="reqps", stack=True,
         desc="2xx = new tokens issued. 4xx = refresh refused (expired, revoked, or a refresh token replayed -- compare with revocations on the right). 5xx = our failure.")
    b.ts("Refresh latency", [(b.quantile(0.5, refresh), "p50"), (b.quantile(0.95, refresh), "p95"), (b.quantile(0.99, refresh), "p99")], unit="s",
         line=steps((None, GREEN), (0.12, RED)), desc="Time to answer a refresh. The dashed line is the 120 ms target for /token.")
    b.ts("Refresh rejections / s by status", [(f'sum by (status) ({b.req_rate(refresh + ", status_class!=\"2xx\"")})', "HTTP {{status}}")], unit="reqps", stack=True,
         desc="Why refreshes fail, by HTTP status. 400/401 are the token being refused; 5xx is the service.")
    b.ts("Refresh tokens revoked / s", [(f'sum by (status_class) ({b.req_rate(", route=~\"/revoke.token_type=refresh\"")})', "{{status_class}}")], unit="reqps",
         desc="Clients and users explicitly revoking a refresh token (logout, 'sign out everywhere'). A spike is followed by rejected refreshes.")
    b.ts("Token requests / s by grant type", [(f'sum by (route) ({b.req_rate(", route=~\"/token.*\"")})', "{{route}}")], unit="reqps", stack=True,
         desc="authorization_code = a fresh sign-in finishing, refresh_token = an existing session renewing, client_credentials = service-to-service. A healthy system is dominated by refreshes.")
    b.ts("Token issuance latency p99 by grant type", [(b.quantile(0.99, ', route=~"/token.*"', by="route"), "{{route}}")], unit="s",
         line=steps((None, GREEN), (0.12, RED)), desc="p99 of each token grant. The dashed line is the 120 ms /token target.")

    b.row("auth — Sign-in")
    s = b.sel
    b.ts("Authorize requests / s by outcome", [(f"sum by (outcome) (rate(auth_authorize_total{{{s}}}[$__rate_interval]))", "{{outcome}}")], unit="ops", stack=True,
         desc="silent = the user already had a session and went straight through; interactive = the user must sign in; error = the request was rejected (reasons on the right).")
    b.ts("Authorize errors / s by reason", [(f'sum by (reason) (rate(auth_authorize_total{{{s}, outcome="error"}}[$__rate_interval]))', "{{reason}}")], unit="ops", stack=True,
         desc="Why /authorize refused. Mostly integration mistakes in a client application (bad redirect URI, missing PKCE, …).")
    b.ts("Sign-ins started vs completed", [
        (f"sum(rate(auth_conversation_started_total{{{s}}}[$__rate_interval]))", "started"),
        (f"sum(rate(auth_conversation_completed_total{{{s}}}[$__rate_interval]))", "completed"),
    ], unit="ops", desc="Users who began signing in vs users who finished. The gap is abandonment and failure -- the conversion of the funnel.")
    b.ts("Sign-in completion rate", [(f"sum(rate(auth_conversation_completed_total{{{s}}}[$__rate_interval])) / clamp_min(sum(rate(auth_conversation_started_total{{{s}}}[$__rate_interval])), 0.001)", "completed / started")],
         unit="percentunit", minimum=0, maximum=1, desc="Share of started sign-ins that completed. A drop with no traffic change means users are getting stuck -- check the step failures next.")
    b.ts("Failed attempts by step", [(f'sum by (step) (rate(auth_conversation_step_total{{{s}, result="failed"}}[$__rate_interval])) / clamp_min(sum by (step) (rate(auth_conversation_step_total{{{s}}}[$__rate_interval])), 0.001)', "{{step}}")],
         unit="percentunit", minimum=0, desc="For each sign-in step (password, OTP, passkey, …), the share of attempts that failed. Points at the factor users trip over.")
    b.ts("Edge assertions rejected (DPoP)", [(f"sum(rate(dpop_edge_assertion_rejections_total{{{s}}}[$__rate_interval]))", "rejected"), (f"sum(rate(dpop_edge_assertion_exemptions_total{{{s}}}[$__rate_interval]))", "exempted")], unit="ops",
         desc="Requests from edge whose proof-of-possession check failed. Any rejection is a misconfigured edge or an attack.")
    cleanup(b, volume=[("Users", "users"), ("Sessions", "sso_sessions"), ("Refresh tokens", "refresh_tokens")], expiring="sso_sessions|refresh_tokens")
    dependencies(b)
    runtime(b)


def add_edge(b: Board, phase: str) -> None:
    b.use("edge")
    proxied = ', route=~"/resources/.*"'
    web = ', route=~"/login.*|/complete|/logout.*|/permissions.*"'
    mobile = ', route=~"/native/.*"'
    overview(b, [
        ("Proxied/s", f"sum({b.req_rate(proxied)})", dict(unit="reqps", desc="Requests edge forwards to protected resources -- the traffic edge exists to carry.")),
        ("Revocation sync lag", f"max(revocation_cache_staleness_seconds{b.http()})", dict(
            unit="s", thresholds=steps((None, GREEN), (1, RED)),
            desc="0 = the last attempt to refresh the revocation list succeeded. Above 0 = refreshes are failing, and this is how long edge has gone without one: a token revoked in that time is still accepted.")),
    ])
    if phase == "overview":
        return
    s = b.sel
    traffic(b, title="Resources (API proxying, /resources/*)", extra=proxied, by_resource=True,
            what="web and mobile apps share this path, so it cannot be split further")
    # edge's own cost has no metric of its own; it is what is left of the proxied request after the calls
    # edge makes on its behalf (resource backend, auth userinfo) are taken off. All three series share
    # one denominator (proxied requests), so the pieces add up to the whole.
    res_srv = f'{{{s}, route=~"/resources/.*"}}'
    backend = f'{{{s}, peer!~"versola-(auth|central)[:.].*"}}'
    auth_lookup = f'{{{s}, peer=~"versola-auth[:.].*", route="userinfo"}}'
    n = f"sum(rate(http_server_request_duration_seconds_count{res_srv}[$__rate_interval]))"
    whole = f"sum(rate(http_server_request_duration_seconds_sum{res_srv}[$__rate_interval])) / {n}"
    in_backend = f"sum(rate(http_client_request_duration_seconds_sum{backend}[$__rate_interval])) / {n}"
    in_auth = f"sum(rate(http_client_request_duration_seconds_sum{auth_lookup}[$__rate_interval])) / {n}"
    b.ts("Where a proxied request spends its time (mean)", [
        (in_backend, "resource backend"),
        (in_auth, "auth lookup (userinfo)"),
        (f"{whole} - {in_backend} - {in_auth}", "edge itself"),
    ], unit="s", stack=True, minimum=0,
         desc="Average time per proxied request, split into the resource's own answer, the token lookup at auth, and what edge adds on top. The three add up to the proxy latency.")
    b.ts("Edge overhead (measured)", [
        (f"histogram_quantile(0.5, sum by (le) (rate(edge_proxy_overhead_seconds_bucket{{{s}}}[$__rate_interval])))", "p50"),
        (f"histogram_quantile(0.99, sum by (le) (rate(edge_proxy_overhead_seconds_bucket{{{s}}}[$__rate_interval])))", "p99"),
    ], unit="s", line=steps((None, GREEN), (0.015, RED)),
         desc="Time edge spends on a request before it reaches the resource: token and DPoP checks, revocation, permissions, rules, the token lookup at auth. The wait for the resource is not included. Measured per request in edge (edge_proxy_overhead_seconds), so p99 here is the real p99. Dashed line: 15 ms budget. Empty until edge runs a build that includes the metric.")
    b.ts("Edge overhead (estimated from backend latency)", [
        (f"{b.quantile(0.99, ', route=~\"/resources/.*\"')} - histogram_quantile(0.99, sum by (le) (rate(http_client_request_duration_seconds_bucket{backend}[$__rate_interval])))", "proxy p99 − backend p99"),
        (f"{whole} - {in_backend}", "proxy mean − backend mean"),
    ], unit="s", line=steps((None, GREEN), (0.015, RED)),
         desc="What edge adds over calling the backend directly, at the tail (p99) and on average. The mean includes the auth lookup. The dashed line is the 15 ms budget. Excludes /resources/central, which goes through central, not a backend.")

    traffic(b, title="Web apps (browser sign-in and sign-out)", extra=web,
            what="/login and /complete finish a sign-in; /complete includes a code exchange with auth, so it carries auth's latency")
    traffic(b, title="Mobile apps (/native/*)", extra=mobile,
            what="start and complete = sign-in, token = refresh, revoke = sign-out; a 401 with a DPoP nonce is part of the normal handshake, not a failure")

    b.row("edge — Token revocation")
    b.ts("Revoked tokens held in cache", [(f"sum(revocation_cache_entries{{{s}}})", "entries")], desc="Entries in the revocation list edge checks every token against.")
    b.ts("Revocation sync lag", [(f"max by (pod) (revocation_cache_staleness_seconds{{{s}}})", "{{pod}}")], unit="s", minimum=0, line=steps((None, GREEN), (1, RED)),
         desc="Per pod. Flat at 0 is healthy: the last refresh succeeded. It is only written when a refresh happens, so a non-zero value means refreshes are failing and shows how long the pod has been without one.")
    b.ts("Revocation list refresh failures / s", [(f"sum(rate(revocation_cache_reload_failures_total{{{s}}}[$__rate_interval]))", "failures")], unit="ops",
         line=steps((None, GREEN), (0.001, RED)), desc="Any failure means revocations are not reaching edge, and revoked tokens may still be accepted.")
    b.ts("Replay protection (DPoP) pressure", [
        (f"sum(rate(dpop_shared_ring_fallbacks_total{{{s}}}[$__rate_interval]))", "fell back to shared check"),
        (f"sum(rate(dpop_local_ring_capacity_hits_total{{{s}}}[$__rate_interval]))", "local ring full"),
    ], unit="ops", desc="How often the in-memory replay-proof store overflowed. Empty is healthy; sustained values mean it is undersized for the traffic.")
    cleanup(b, volume=[("Sessions", "edge_sessions"), ("Pending logins", "pending_logins"), ("Revocations", "revocations")], expiring="edge_sessions|pending_logins|revocations")
    dependencies(b)
    runtime(b)


def add_central(b: Board, phase: str) -> None:
    b.use("central")
    sync = ', route=~".*/sync"'
    writes = ', method=~"POST|PUT|PATCH|DELETE"'
    overview(b, [
        ("Config syncs/s", f"sum({b.req_rate(sync)})", dict(unit="reqps", desc="Auth and edge pulling configuration (clients, resources, forms, locales). If this stops, they are running on stale config.")),
        ("Changes/min", f"sum({b.req_rate(writes)}) * 60", dict(unit="short", decimals=1, desc="Admin writes: create, update and delete across all configuration.")),
    ])
    if phase == "overview":
        return
    traffic(b)

    b.row("central — Configuration")
    s = b.sel
    b.ts("Config syncs / s by resource", [(f"sum by (route) ({b.req_rate(sync)})", "{{route}}")], unit="reqps", stack=True,
         desc="Pulls by auth and edge, per configuration kind. Expect a steady rate per replica of those services.")
    b.ts("Config sync latency p99", [(b.quantile(0.99, sync, by="route"), "{{route}}")], unit="s",
         desc="How long auth and edge wait for configuration. Slow syncs mean slow propagation of every admin change.")
    b.ts("Config changes / s by endpoint", [(f"sum by (method, route) ({b.req_rate(writes)})", "{{method}} {{route}}")], unit="reqps", stack=True,
         desc="Admin changes by endpoint. A burst here is a deployment or a bulk edit -- expect syncs and cache reloads in auth/edge shortly after.")
    b.ts("Config changes by result", [(f"sum by (status_class) ({b.req_rate(writes)})", "{{status_class}}")], unit="reqps", stack=True,
         desc="Writes that took effect (2xx) vs refused (4xx: validation, permissions) vs failed (5xx).")

    b.row("central — Change notifications")
    b.ts("Notification listener connected", [(f"min by (pod) (db_notification_listener_connected{{{s}}})", "{{pod}}")], minimum=0, maximum=1,
         desc="1 = this pod is listening for database change notifications, 0 = it is not and may serve stale config until it reconnects.")
    b.ts("Notifications received / s", [(f"sum(rate(db_notifications_received_total{{{s}}}[$__rate_interval]))", "received")], unit="ops",
         desc="Change notifications arriving from the database.")
    b.ts("Listener trouble / s", [
        (f"sum(rate(db_notification_listener_reconnects_total{{{s}}}[$__rate_interval]))", "reconnects"),
        (f"sum(rate(db_notification_listener_silent_total{{{s}}}[$__rate_interval]))", "silent windows"),
        (f"sum(rate(db_notification_listener_queue_overflow_total{{{s}}}[$__rate_interval]))", "queue overflows"),
    ], unit="ops", desc="Reconnects, long stretches without any message, and dropped notifications. Empty is healthy.")
    dependencies(b)
    runtime(b)


def build() -> Board:
    b = Board(
        "versola", "Versola",
        "Whole-system health: auth, central and edge on one page. Read the Overview matrix first; open a service's rows only for the part that is not green.",
    )
    services = (add_auth, add_central, add_edge)
    for add in services:
        b.phase = "overview"
        add(b, "overview")
    for add in services:
        b.phase = "detail"
        add(b, "detail")
    return b


def main() -> None:
    board = build()
    out = OUT_DIR / "versola.json"
    out.write_text(json.dumps(board.render(), indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
