#!/usr/bin/env bash
#
# Asserts how the versola chart renders availability: HorizontalPodAutoscaler, PodDisruptionBudget,
# topology spread and priority classes (versolauth/versola#211).
#
# Why this exists: each of these fails silently. A PDB that selects no pods, an HPA fighting a
# Deployment that still states `replicas`, a spread constraint whose selector matches every service's
# pods together, or a priorityClassName naming a class nobody created -- all install cleanly and
# either do nothing or leave pods Pending, long after the release reported success.
#
# Expected values are literals, written by hand -- none is computed by rendering the chart a second way.

set -euo pipefail
cd "$(dirname "$0")/../.."

if ! python3 -c 'import yaml' 2>/dev/null; then
  echo "check-chart-availability: needs python3 with PyYAML (import yaml failed)" >&2
  exit 2
fi

python3 - <<'PY'
import os
import subprocess
import tempfile

import yaml

BASE = {
    "services": {
        "auth": {"config": {"existingSecret": "auth-config"}},
        "central": {"config": {"existingSecret": "central-config"}},
        "edge": {"config": {"existingSecret": "edge-config"}},
    },
    "secrets": {"existingSecret": "versola-secrets"},
}


def deep_merge(base, extra):
    out = dict(base)
    for k, v in extra.items():
        out[k] = deep_merge(out[k], v) if isinstance(out.get(k), dict) and isinstance(v, dict) else v
    return out


def helm(values, check=True):
    with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as f:
        yaml.safe_dump(deep_merge(BASE, values), f)
        path = f.name
    try:
        return subprocess.run(
            ["helm", "template", "v", "k8s/versola", "--namespace", "v", "-f", path],
            check=check, capture_output=True, text=True,
        )
    finally:
        os.unlink(path)


def render(values):
    docs = [d for d in yaml.safe_load_all(helm(values).stdout) if d]
    by_kind = {}
    for d in docs:
        by_kind.setdefault(d["kind"], {})[d["metadata"]["name"]] = d
    return by_kind


def selector(component):
    return {
        "app.kubernetes.io/name": "versola",
        "app.kubernetes.io/instance": "v",
        "app.kubernetes.io/component": component,
    }


def fails(values, fragment):
    result = helm(values, check=False)
    assert result.returncode != 0, f"expected the render to be refused for {fragment!r}"
    assert fragment in result.stderr, f"refused, but not for {fragment!r}: {result.stderr.strip()[-300:]}"


failures = []


def check(name, fn):
    try:
        fn()
        print(f"ok   {name}")
    except AssertionError as e:
        failures.append(name)
        print(f"FAIL {name}: {e}")


def defaults():
    r = render({})
    assert "HorizontalPodAutoscaler" not in r, "no HPA by default"
    assert "PriorityClass" not in r, "no PriorityClass by default"
    assert "PodDisruptionBudget" not in r, "one replica each: a PDB would protect nothing"
    for component in ("auth", "central", "edge"):
        dep = r["Deployment"][f"v-versola-{component}"]
        assert dep["spec"]["replicas"] == 1, f"{component}: replicas"
        pod = dep["spec"]["template"]["spec"]
        assert "priorityClassName" not in pod, f"{component}: no priorityClassName unless a class exists"
        keys = [c["topologyKey"] for c in pod["topologySpreadConstraints"]]
        assert keys == ["kubernetes.io/hostname", "topology.kubernetes.io/zone"], f"{component}: {keys}"
        for c in pod["topologySpreadConstraints"]:
            assert c["whenUnsatisfiable"] == "ScheduleAnyway", f"{component}: spread must be soft"
            assert c["labelSelector"] == {"matchLabels": selector(component)}, f"{component}: selector {c['labelSelector']}"


def pdb_for_replicas():
    r = render({"services": {"auth": {"replicaCount": 2}, "edge": {"replicaCount": 3}}})
    assert set(r["PodDisruptionBudget"]) == {"v-versola-auth", "v-versola-edge"}, list(r["PodDisruptionBudget"])
    auth = r["PodDisruptionBudget"]["v-versola-auth"]["spec"]
    assert auth["maxUnavailable"] == 1 and "minAvailable" not in auth, auth
    assert auth["selector"] == {"matchLabels": selector("auth")}, auth["selector"]
    r = render({"services": {"auth": {"replicaCount": 3, "podDisruptionBudget": {"minAvailable": 2, "maxUnavailable": None}}}})
    spec = r["PodDisruptionBudget"]["v-versola-auth"]["spec"]
    assert spec["minAvailable"] == 2 and "maxUnavailable" not in spec, spec
    r = render({"services": {"auth": {"replicaCount": 3, "podDisruptionBudget": {"enabled": False}}}})
    assert "v-versola-auth" not in r.get("PodDisruptionBudget", {}), "disabled"


def autoscaling():
    r = render({"services": {"edge": {"replicaCount": 1, "autoscaling": {"enabled": True, "minReplicas": 2, "maxReplicas": 5}}}})
    assert "replicas" not in r["Deployment"]["v-versola-edge"]["spec"], "an HPA'd Deployment must not state replicas"
    assert r["Deployment"]["v-versola-auth"]["spec"]["replicas"] == 1, "other services keep theirs"
    hpa = r["HorizontalPodAutoscaler"]["v-versola-edge"]["spec"]
    assert (hpa["minReplicas"], hpa["maxReplicas"]) == (2, 5), hpa
    assert hpa["scaleTargetRef"] == {"apiVersion": "apps/v1", "kind": "Deployment", "name": "v-versola-edge"}, hpa["scaleTargetRef"]
    assert hpa["metrics"] == [{"type": "Resource", "resource": {"name": "cpu", "target": {"type": "Utilization", "averageUtilization": 70}}}], hpa["metrics"]
    assert "v-versola-edge" in r["PodDisruptionBudget"], "PDB follows minReplicas under an HPA"
    custom = [{"type": "Pods", "pods": {"metric": {"name": "inflight"}, "target": {"type": "AverageValue", "averageValue": "20"}}}]
    r = render({"services": {"auth": {"autoscaling": {"enabled": True, "metrics": custom, "behavior": {"scaleDown": {"stabilizationWindowSeconds": 600}}}}}})
    hpa = r["HorizontalPodAutoscaler"]["v-versola-auth"]["spec"]
    assert hpa["metrics"] == custom, "explicit metrics replace the CPU default"
    assert hpa["behavior"] == {"scaleDown": {"stabilizationWindowSeconds": 600}}, hpa.get("behavior")


def priority_classes():
    r = render({"priorityClasses": {"create": True}})
    classes = {n: c["value"] for n, c in r["PriorityClass"].items()}
    assert classes == {"versola-customer-facing": 1000000, "versola-control-plane": 100000}, classes
    want = {"auth": "versola-customer-facing", "edge": "versola-customer-facing", "central": "versola-control-plane"}
    for component, name in want.items():
        got = r["Deployment"][f"v-versola-{component}"]["spec"]["template"]["spec"].get("priorityClassName")
        assert got == name, f"{component}: {got}"
    r = render({"priorityClasses": {"create": True}, "services": {"central": {"priorityClassName": "ours"}}})
    assert r["Deployment"]["v-versola-central"]["spec"]["template"]["spec"]["priorityClassName"] == "ours", "explicit wins"
    r = render({"services": {"auth": {"priorityClassName": "ours"}}})
    assert "PriorityClass" not in r, "naming a class does not create one"
    assert r["Deployment"]["v-versola-auth"]["spec"]["template"]["spec"]["priorityClassName"] == "ours"


def spread_replaces():
    own = [{"maxSkew": 2, "topologyKey": "rack", "whenUnsatisfiable": "DoNotSchedule"}]
    r = render({"services": {"edge": {"topologySpreadConstraints": own}}})
    edge = r["Deployment"]["v-versola-edge"]["spec"]["template"]["spec"]["topologySpreadConstraints"]
    assert [(c["topologyKey"], c["maxSkew"]) for c in edge] == [("rack", 2)], "a component's own list replaces global's"
    assert edge[0]["labelSelector"] == {"matchLabels": selector("edge")}
    auth = r["Deployment"]["v-versola-auth"]["spec"]["template"]["spec"]["topologySpreadConstraints"]
    assert len(auth) == 2, "other components inherit"
    r = render({"global": {"topologySpreadConstraints": []}})
    assert "topologySpreadConstraints" not in r["Deployment"]["v-versola-auth"]["spec"]["template"]["spec"], "[] turns it off"
    mine = {"matchLabels": {"app": "mine"}}
    r = render({"global": {"topologySpreadConstraints": [{"maxSkew": 1, "topologyKey": "rack", "whenUnsatisfiable": "ScheduleAnyway", "labelSelector": mine}]}})
    got = r["Deployment"]["v-versola-auth"]["spec"]["template"]["spec"]["topologySpreadConstraints"][0]["labelSelector"]
    assert got == mine, "a constraint's own selector is kept"


def refusals():
    fails({"services": {"edge": {"autoscaling": {"enabled": True, "minReplicas": 4, "maxReplicas": 2}}}}, "below minReplicas")
    fails({"services": {"edge": {"podDisruptionBudget": {"minAvailable": 1, "maxUnavailable": 1}}}}, "not both")
    fails(
        {"services": {"edge": {"autoscaling": {"enabled": True}, "resources": {"requests": {"cpu": None}}}}},
        "resources.requests.cpu is not set",
    )


for name, fn in [
    ("defaults: soft spread per component, nothing else", defaults),
    ("PDB only above one replica, one of minAvailable/maxUnavailable", pdb_for_replicas),
    ("HPA drops the Deployment's replicas, defaults to CPU, takes explicit metrics", autoscaling),
    ("priority classes: created on request, mapped per service, explicit name wins", priority_classes),
    ("topology spread: component replaces global, [] disables, own selector kept", spread_replaces),
    ("contradictory availability values are refused", refusals),
]:
    check(name, fn)

raise SystemExit(1 if failures else 0)
PY
