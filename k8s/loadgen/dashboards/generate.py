#!/usr/bin/env python3
"""Generates the Grafana boards in this directory that are a mechanical stack of
rate/quantile/gauge panels over one metric family each, rather than the curated,
pass-criterion-driven layout of sut-red.json/driver-red.json/db-pools.json (those
stay hand-authored -- see k8s/README.md's Observability section for why boards live
here at all: #280's D8 and D9, checked in and imported by whatever stack the
campaign runs, never generated at deploy time).

Run after adding a `Metric.*` registration anywhere in the codebase that this
script's CATALOG below does not yet cover:

    python3 k8s/loadgen/dashboards/generate.py

Each entry in CATALOG is the metric exactly as its own module registers it --
name, type and labels copied from the source cited in its `source` field, not
guessed from usage. A metric this script has no business drawing a threshold for
(no documented SLA) gets none: a fabricated threshold is worse than an absent one,
since a red panel someone starts trusting is harder to walk back than a panel with
no color at all.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

OUT_DIR = Path(__file__).parent


def datasource_var() -> dict:
    return {"name": "datasource", "label": "Data source", "type": "datasource", "query": "prometheus", "current": {}, "hide": 0}


def query_var(name: str, label: str, query: str) -> dict:
    return {
        "name": name,
        "label": label,
        "type": "query",
        "datasource": {"type": "prometheus", "uid": "${datasource}"},
        "query": query,
        "refresh": 1,
        "includeAll": True,
        "allValue": ".*",
        "multi": True,
        "current": {"text": "All", "value": "$__all"},
        "hide": 0,
    }


def intro_panel(markdown: str) -> dict:
    return {
        "id": 1,
        "type": "text",
        "title": "What this board reads",
        "gridPos": {"h": 7, "w": 24, "x": 0, "y": 0},
        "options": {"mode": "markdown", "content": markdown},
    }


@dataclass
class Layout:
    """Two panels per row, id/gridPos assigned in call order -- the same rhythm
    sut-red.json and driver-red.json lay their own panels out by hand."""

    next_id: int = 2
    x: int = 0
    y: int = 7
    row_h: int = 8

    def place(self, panel: dict) -> dict:
        panel["id"] = self.next_id
        panel["gridPos"] = {"h": self.row_h, "w": 12, "x": self.x, "y": self.y}
        self.next_id += 1
        if self.x == 0:
            self.x = 12
        else:
            self.x = 0
            self.y += self.row_h
        return panel

    def place_wide(self, panel: dict) -> dict:
        if self.x != 0:
            self.x = 0
            self.y += self.row_h
        panel["id"] = self.next_id
        panel["gridPos"] = {"h": self.row_h, "w": 24, "x": 0, "y": self.y}
        self.next_id += 1
        self.y += self.row_h
        return panel


def _base_target(expr: str, legend: str, ref_id: str = "A") -> dict:
    return {"refId": ref_id, "datasource": {"type": "prometheus", "uid": "${datasource}"}, "expr": expr, "legendFormat": legend}


def rate_panel(title: str, metric: str, by: list[str], selector: str = "", unit: str = "short", desc: str | None = None, thresholds: dict | None = None) -> dict:
    group = ", ".join(by) if by else None
    expr_selector = f'{{namespace="$namespace"{selector}}}'
    expr = f"rate({metric}{expr_selector}[$__rate_interval])"
    if group:
        expr = f"sum by ({group}) ({expr})"
        legend = " ".join(f"{{{{{label}}}}}" for label in by)
    else:
        expr = f"sum ({expr})"
        legend = title
    defaults = {"unit": unit, "custom": {"drawStyle": "line", "lineWidth": 1, "fillOpacity": 0, "showPoints": "never"}}
    if thresholds:
        defaults["custom"]["thresholdsStyle"] = {"mode": "line"}
        defaults["thresholds"] = thresholds
    panel = {
        "type": "timeseries",
        "title": title,
        "datasource": {"type": "prometheus", "uid": "${datasource}"},
        "fieldConfig": {"defaults": defaults, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True}, "tooltip": {"mode": "multi", "sort": "desc"}},
        "targets": [_base_target(expr, legend)],
    }
    if desc:
        panel["description"] = desc
    return panel


def gauge_panel(title: str, metric: str, by: list[str], selector: str = "", unit: str = "short", desc: str | None = None, thresholds: dict | None = None) -> dict:
    group = ", ".join(by) if by else None
    expr_selector = f'{{namespace="$namespace"{selector}}}'
    if group:
        expr = f"sum by ({group}) ({metric}{expr_selector})"
        legend = " ".join(f"{{{{{label}}}}}" for label in by)
    else:
        expr = f"sum ({metric}{expr_selector})"
        legend = title
    defaults = {"unit": unit, "custom": {"drawStyle": "line", "lineWidth": 1, "fillOpacity": 10, "showPoints": "never"}}
    if thresholds:
        defaults["custom"]["thresholdsStyle"] = {"mode": "line"}
        defaults["thresholds"] = thresholds
    panel = {
        "type": "timeseries",
        "title": title,
        "datasource": {"type": "prometheus", "uid": "${datasource}"},
        "fieldConfig": {"defaults": defaults, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True}, "tooltip": {"mode": "multi", "sort": "desc"}},
        "targets": [_base_target(expr, legend)],
    }
    if desc:
        panel["description"] = desc
    return panel


def quantile_panel(title: str, bucket_metric: str, by: list[str], selector: str = "", unit: str = "s", desc: str | None = None, quantiles: tuple[float, ...] = (0.50, 0.99)) -> dict:
    group = ", ".join(["le"] + by)
    label_part = " ".join(f"{{{{{label}}}}}" for label in by)
    targets = []
    for i, q in enumerate(quantiles):
        ref_id = chr(ord("A") + i)
        expr = f'histogram_quantile({q}, sum by ({group}) (rate({bucket_metric}{{namespace="$namespace"{selector}}}[$__rate_interval])))'
        legend = f"{label_part} p{int(q * 100)}".strip()
        targets.append(_base_target(expr, legend, ref_id))
    panel = {
        "type": "timeseries",
        "title": title,
        "datasource": {"type": "prometheus", "uid": "${datasource}"},
        "fieldConfig": {"defaults": {"unit": unit, "custom": {"drawStyle": "line", "lineWidth": 1, "fillOpacity": 0, "showPoints": "never"}}, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True}, "tooltip": {"mode": "multi", "sort": "desc"}},
        "targets": targets,
    }
    if desc:
        panel["description"] = desc
    return panel


def nonzero_is_bad() -> dict:
    """The one threshold shape this script allows itself: a count that is only
    ever produced by something going wrong, so any value above zero is a real
    signal regardless of what a not-yet-written SLA would say."""
    return {"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "red", "value": 0.001}]}


@dataclass
class Board:
    uid: str
    title: str
    description: str
    tags: list[str]
    extra_vars: list[dict]
    panels_wide: list[dict] = field(default_factory=list)
    panels: list[dict] = field(default_factory=list)

    def render(self) -> dict:
        layout = Layout()
        panels = [intro_panel(self.description)]
        for p in self.panels_wide:
            panels.append(layout.place_wide(p))
        for p in self.panels:
            panels.append(layout.place(p))
        return {
            "uid": self.uid,
            "title": self.title,
            "description": self.description,
            "tags": self.tags,
            "editable": True,
            "graphTooltip": 1,
            "schemaVersion": 39,
            "version": 1,
            "refresh": "30s",
            "time": {"from": "now-1h", "to": "now"},
            "timezone": "utc",
            "templating": {"list": [datasource_var()] + self.extra_vars},
            "panels": panels,
        }


def namespace_var(seed_metric: str) -> dict:
    return query_var("namespace", "Namespace", f"label_values({seed_metric}, namespace)")


def job_var(seed_metric: str) -> dict:
    return query_var("job", "Service", f"label_values({seed_metric}{{namespace=\"$namespace\"}}, job)")


AUTH_FUNNEL = Board(
    uid="versola-auth-funnel",
    title="Versola — auth funnel",
    description=(
        "auth's own business counters, as distinct from the SUT RED board's transport-level "
        "http_server_* metrics: whether a login actually authenticated, not just whether the "
        "HTTP call succeeded. Metric names and label sets come from "
        "auth/src/main/scala/versola/oauth/AuthMetrics.scala — `outcome`/`reason`/`kind`/`step`/"
        "`result` are that file's own enums, not reproduced here, so read them there rather than "
        "guessing from a legend. namespace and job are scrape-time labels, as on every other board."
    ),
    tags=["versola", "auth"],
    extra_vars=[namespace_var("auth_authorize_total"), job_var("auth_authorize_total")],
    panels=[
        rate_panel(
            "Authorize outcomes",
            "auth_authorize_total",
            by=["outcome"],
            selector=', job=~"$job"',
            unit="short",
            desc="Every /authorize decision auth reaches, by its own outcome label — see AuthMetrics.scala for the enum.",
        ),
        rate_panel(
            "Authorize rejections by reason",
            "auth_authorize_total",
            by=["reason"],
            selector=', job=~"$job", outcome!="success"',
            unit="short",
            desc='Same counter, split by `reason` and filtered to outcome!="success" -- the label auth carries only ' 'the failed decisions to explain.',
        ),
        rate_panel(
            "Conversations started vs completed, by kind",
            "auth_conversation_started_total",
            by=["kind"],
            selector=', job=~"$job"',
            unit="short",
            desc="login/step_up/reauth, per AuthMetrics.scala. Compare against the matching series on "
            "auth_conversation_completed_total below -- a started line with no matching completed line "
            "is an abandoned conversation, not a failed one, so it never appears on the outcomes panel.",
        ),
        rate_panel(
            "Conversations completed, by kind",
            "auth_conversation_completed_total",
            by=["kind"],
            selector=', job=~"$job"',
            unit="short",
        ),
        rate_panel(
            "Conversation steps by step and result",
            "auth_conversation_step_total",
            by=["step", "result"],
            selector=', job=~"$job"',
            unit="short",
            desc="Every factor challenge along the way (otp submit, password submit, passkey assertion, "
            "...), by AuthMetrics.scala's own `step`/`result` pair -- this is where a single factor "
            "failing disproportionately shows up, upstream of the conversation-level outcome above.",
        ),
    ],
)

SECURITY_DPOP_REVOCATION = Board(
    uid="versola-security-dpop-revocation",
    title="Versola — DPoP & revocation security signals",
    description=(
        "Two independent defenses and how often each one actually has to act: DPoP's replay-nonce "
        "checks (auth's own assertion path plus edge's nonce ring) and edge's token-revocation cache. "
        "Metric names come from auth/src/main/scala/versola/oauth/dpop/EdgeAssertionService.scala, "
        "edge/src/main/scala/versola/edge/dpop/DpopMetrics.scala and "
        "edge/src/main/scala/versola/edge/revocation/RevocationMetrics.scala. None of these carry a "
        "documented SLA yet, so only revocation_cache_reload_failures_total gets a threshold below -- "
        "a cache that cannot reload is unconditionally a problem regardless of what a latency budget "
        "would say; the rest are plotted without one rather than guessing a number."
    ),
    tags=["versola", "security"],
    extra_vars=[namespace_var("revocation_cache_entries"), job_var("revocation_cache_entries")],
    panels=[
        rate_panel(
            "auth: DPoP assertion exemptions & rejections",
            "dpop_edge_assertion_exemptions_total",
            by=[],
            selector=', job=~"$job"',
            desc="EdgeAssertionService's own two counters, both label-less -- plotted together with the "
            "rejection series added as a second target below.",
        ),
        rate_panel(
            "edge: DPoP nonce ring fallbacks & capacity hits",
            "dpop_shared_ring_fallbacks_total",
            by=[],
            selector=', job=~"$job"',
            desc="DpopMetrics.scala's shared-ring fallback and local-ring capacity counters, label-less "
            "like their auth-side counterparts.",
        ),
        gauge_panel(
            "edge: revocation cache size",
            "revocation_cache_entries",
            by=[],
            selector=', job=~"$job"',
            desc="RevocationMetrics.scala's own gauge of the cache's current entry count.",
        ),
        gauge_panel(
            "edge: revocation cache staleness",
            "revocation_cache_staleness_seconds",
            by=[],
            selector=', job=~"$job"',
            unit="s",
            desc="How far behind central's own state the cache's last successful reload left it. No "
            "threshold set here -- add one once an SLA for acceptable staleness is written down.",
        ),
        rate_panel(
            "edge: revocation cache reload failures",
            "revocation_cache_reload_failures_total",
            by=[],
            selector=', job=~"$job"',
            desc="A cache that cannot reload is serving revocation decisions off data whose staleness "
            "the panel above can no longer be trusted to report. Any nonzero rate here is a real signal.",
            thresholds=nonzero_is_bad(),
        ),
    ],
)


def add_exemptions_and_rejections_target():
    """auth's two DPoP counters are both label-less, so they read best as two series on one panel
    rather than two panels each showing a single flat line -- patched in after Board construction
    since the panel builders above are one-metric-per-call."""
    panel = SECURITY_DPOP_REVOCATION.panels[0]
    panel["targets"].append(
        _base_target('sum (rate(dpop_edge_assertion_rejections_total{namespace="$namespace", job=~"$job"}[$__rate_interval]))', "rejections", "B"),
    )
    panel["targets"][0]["legendFormat"] = "exemptions"

    fallback_panel = SECURITY_DPOP_REVOCATION.panels[1]
    fallback_panel["targets"].append(
        _base_target(
            'sum (rate(dpop_local_ring_capacity_hits_total{namespace="$namespace", job=~"$job"}[$__rate_interval]))',
            "local ring capacity hits",
            "B",
        ),
    )
    fallback_panel["targets"][0]["legendFormat"] = "shared ring fallbacks"


add_exemptions_and_rejections_target()

CATALOG = [AUTH_FUNNEL, SECURITY_DPOP_REVOCATION]


def main() -> None:
    for board in CATALOG:
        out = OUT_DIR / f"{board.uid.removeprefix('versola-')}.json"
        out.write_text(json.dumps(board.render(), indent=2, ensure_ascii=False) + "\n")
        print(f"wrote {out}")


if __name__ == "__main__":
    main()
