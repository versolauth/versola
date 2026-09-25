#!/usr/bin/env bash
#
# Asserts how both charts render pod placement: nodeSelector, tolerations and
# affinity (versolauth/versola#381).
#
# Why this exists: placement is the one thing that lets the load emulator and
# the system under test share a cluster on separate node groups. Getting it
# wrong fails silently in the worst way -- a pod that ignores its placement
# still schedules, just on the wrong node, and every latency it measures or
# serves is then contaminated by whatever else runs there. Nothing errors.
#
# The contract, per workload, in both charts:
#   - `global.<field>` applies to every pod;
#   - a non-empty `<component>.<field>` REPLACES the global value -- it is not
#     merged with it, key by key or entry by entry;
#   - an empty `<component>.<field>` inherits the global one.
#
# One workload has placement of its own. The loadgen coordinator spreads its
# standby off the active replica's node with a PREFERRED podAntiAffinity when
# replicaCount > 1. That default is merged with the user's affinity per
# top-level key (nodeAffinity / podAffinity / podAntiAffinity), user's key
# winning -- so pinning the chart to a node group with nodeAffinity does not
# quietly drop the standby spread, and an explicit podAntiAffinity still
# replaces it.
#
# Expected values below are literals, written by hand -- none is computed by
# rendering the chart a second way.

set -euo pipefail
cd "$(dirname "$0")/../.."

if ! python3 -c 'import yaml' 2>/dev/null; then
  echo "check-chart-placement: needs python3 with PyYAML (import yaml failed)" >&2
  exit 2
fi

python3 - <<'PY'
import json
import os
import shutil
import subprocess
import sys
import tempfile

import yaml

POD_KINDS = {"Deployment", "StatefulSet", "DaemonSet", "Job"}

VERSOLA_BASE = {
    "services": {
        "auth": {"config": {"existingSecret": "auth-config"}},
        "central": {"config": {"existingSecret": "central-config"}},
        "edge": {"config": {"existingSecret": "edge-config"}},
    },
    "secrets": {"existingSecret": "versola-secrets"},
}
LOADGEN_BASE = {
    "driver": {"config": {"existingSecret": "driver-config"}},
    "coordinator": {"config": {"existingSecret": "coordinator-config"}},
}

# Workloads each chart is known to render. A subset check, not equality: a new
# workload must still receive placement (every test iterates over ALL rendered
# pods), but its mere arrival should not fail this script.
VERSOLA_KNOWN = {"versola-auth", "versola-central", "versola-edge", "versola-console"}
LOADGEN_KNOWN = {"loadgen-coordinator", "loadgen-driver", "loadgen-mockapi"}

COORDINATOR_SELECTOR = {
    "app.kubernetes.io/name": "loadgen",
    "app.kubernetes.io/instance": "loadgen",
    "app.kubernetes.io/component": "coordinator",
}
COORDINATOR_BUILTIN_ANTI_AFFINITY = {
    "preferredDuringSchedulingIgnoredDuringExecution": [
        {
            "weight": 100,
            "podAffinityTerm": {
                "topologyKey": "kubernetes.io/hostname",
                "labelSelector": {"matchLabels": COORDINATOR_SELECTOR},
            },
        }
    ]
}

SHARED_TOLERATION = {"key": "dedicated", "operator": "Equal", "value": "shared", "effect": "NoSchedule"}
MOCKAPI_TOLERATION = {"key": "dedicated", "operator": "Equal", "value": "mockapi", "effect": "NoSchedule"}
SHARED_NODE_AFFINITY = {
    "requiredDuringSchedulingIgnoredDuringExecution": {
        "nodeSelectorTerms": [
            {"matchExpressions": [{"key": "pool", "operator": "In", "values": ["shared"]}]}
        ]
    }
}


def deep_merge(base, extra):
    out = dict(base)
    for k, v in extra.items():
        out[k] = deep_merge(out[k], v) if isinstance(out.get(k), dict) and isinstance(v, dict) else v
    return out


def render(chart, release, base, values):
    with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as f:
        yaml.safe_dump(deep_merge(base, values), f)
        path = f.name
    try:
        out = subprocess.run(
            ["helm", "template", release, f"k8s/{chart}", "--namespace", release, "-f", path],
            check=True, capture_output=True, text=True,
        ).stdout
    finally:
        os.unlink(path)
    pods = {}
    for doc in yaml.safe_load_all(out):
        if doc and doc.get("kind") in POD_KINDS:
            pods[doc["metadata"]["name"]] = doc["spec"]["template"]["spec"]
    return pods


def versola(values=None):
    pods = render("versola", "versola", VERSOLA_BASE, values or {})
    missing = VERSOLA_KNOWN - pods.keys()
    assert not missing, f"versola rendered without {sorted(missing)}"
    return pods


def loadgen(values=None):
    pods = render("loadgen", "loadgen", LOADGEN_BASE, values or {})
    missing = LOADGEN_KNOWN - pods.keys()
    assert not missing, f"loadgen rendered without {sorted(missing)}"
    return pods


failures = []
checks = 0


def check(name, fn):
    global checks
    checks += 1
    try:
        fn()
        print(f"PASS  {name}")
    except AssertionError as e:
        print(f"FAIL  {name}\n      {e}")
        failures.append(name)


def eq(pod, field, got, want):
    assert got == want, f"{pod}: {field} = {got!r}, want {want!r}"


# Break caught: a template that never renders one of the three fields, or a
# workload added without them -- the pod still schedules, on the wrong node.
# `affinity` is compared whole, not just its nodeAffinity key, so that no
# extra key can ride along into a pod unnoticed.
def global_reaches_every_pod():
    placement = {
        "nodeSelector": {"pool": "shared"},
        "tolerations": [SHARED_TOLERATION],
        "affinity": {"nodeAffinity": SHARED_NODE_AFFINITY},
    }
    for pods in (versola({"global": placement}),
                 loadgen({"global": placement, "coordinator": {"replicaCount": 2}})):
        for name, spec in pods.items():
            eq(name, "nodeSelector", spec.get("nodeSelector"), {"pool": "shared"})
            eq(name, "tolerations", spec.get("tolerations"), [SHARED_TOLERATION])
            want = {"nodeAffinity": SHARED_NODE_AFFINITY}
            if name == "loadgen-coordinator":
                want = {**want, "podAntiAffinity": COORDINATOR_BUILTIN_ANTI_AFFINITY}
            eq(name, "affinity", spec.get("affinity"), want)


# Break caught: merging a component's value into the global one (the driver
# would inherit `zone: a` and schedule nowhere its own selector names), or an
# override that is read for some components and ignored for others.
def component_replaces_global():
    shared = {"pool": "shared", "zone": "a"}
    v = versola({
        "global": {"nodeSelector": shared},
        "services": {"edge": {"nodeSelector": {"pool": "edge"}}},
        "console": {"nodeSelector": {"pool": "console"}},
    })
    eq("versola-edge", "nodeSelector", v["versola-edge"].get("nodeSelector"), {"pool": "edge"})
    eq("versola-console", "nodeSelector", v["versola-console"].get("nodeSelector"), {"pool": "console"})
    for name in ("versola-auth", "versola-central"):
        eq(name, "nodeSelector", v[name].get("nodeSelector"), shared)

    lg = loadgen({
        "global": {"nodeSelector": shared, "tolerations": [SHARED_TOLERATION]},
        "driver": {"nodeSelector": {"pool": "drivers"}},
        "mockapi": {"tolerations": [MOCKAPI_TOLERATION]},
    })
    eq("loadgen-driver", "nodeSelector", lg["loadgen-driver"].get("nodeSelector"), {"pool": "drivers"})
    eq("loadgen-driver", "tolerations", lg["loadgen-driver"].get("tolerations"), [SHARED_TOLERATION])
    eq("loadgen-mockapi", "tolerations", lg["loadgen-mockapi"].get("tolerations"), [MOCKAPI_TOLERATION])
    eq("loadgen-mockapi", "nodeSelector", lg["loadgen-mockapi"].get("nodeSelector"), shared)
    eq("loadgen-coordinator", "nodeSelector", lg["loadgen-coordinator"].get("nodeSelector"), shared)


# Break caught: a default that is not actually empty, or a refactor that drops
# the coordinator's built-in standby spread (or applies it to one replica).
def defaults_add_nothing_but_the_coordinator_spread():
    for pods in (versola(), loadgen({"coordinator": {"replicaCount": 2}})):
        for name, spec in pods.items():
            eq(name, "nodeSelector", spec.get("nodeSelector"), None)
            eq(name, "tolerations", spec.get("tolerations"), None)
            want = {"podAntiAffinity": COORDINATOR_BUILTIN_ANTI_AFFINITY} if name == "loadgen-coordinator" else None
            eq(name, "affinity", spec.get("affinity"), want)
    single = loadgen({"coordinator": {"replicaCount": 1}})
    eq("loadgen-coordinator", "affinity (1 replica)", single["loadgen-coordinator"].get("affinity"), None)


# Break caught: the user's affinity replacing the coordinator's wholesale --
# pinning the chart to a node group would quietly put the standby on the
# active replica's node, and the failover it exists for would take both down.
def coordinator_keeps_spread_under_user_node_affinity():
    for values, where in (
        ({"global": {"affinity": {"nodeAffinity": SHARED_NODE_AFFINITY}}}, "global"),
        ({"coordinator": {"affinity": {"nodeAffinity": SHARED_NODE_AFFINITY}}}, "coordinator"),
    ):
        values = deep_merge(values, {"coordinator": {"replicaCount": 2}})
        got = loadgen(values)["loadgen-coordinator"].get("affinity")
        eq(f"loadgen-coordinator ({where} nodeAffinity)", "affinity", got,
           {"nodeAffinity": SHARED_NODE_AFFINITY, "podAntiAffinity": COORDINATOR_BUILTIN_ANTI_AFFINITY})
    single = loadgen({"global": {"affinity": {"nodeAffinity": SHARED_NODE_AFFINITY}},
                      "coordinator": {"replicaCount": 1}})
    eq("loadgen-coordinator (1 replica)", "affinity", single["loadgen-coordinator"].get("affinity"),
       {"nodeAffinity": SHARED_NODE_AFFINITY})


# Break caught: forcing or deep-merging the built-in spread over an explicit
# choice -- the user's required rule would gain a preferred term they did not
# ask for.
def explicit_anti_affinity_replaces_builtin():
    mine = {
        "requiredDuringSchedulingIgnoredDuringExecution": [
            {"topologyKey": "topology.kubernetes.io/zone",
             "labelSelector": {"matchLabels": COORDINATOR_SELECTOR}}
        ]
    }
    got = loadgen({"coordinator": {"replicaCount": 2, "affinity": {"podAntiAffinity": mine}}})
    eq("loadgen-coordinator", "affinity", got["loadgen-coordinator"].get("affinity"), {"podAntiAffinity": mine})


# Break caught: merging the standby spread into the affinity dict it was
# GIVEN rather than into a copy. `set` mutates, so global.affinity itself would
# gain a podAntiAffinity carrying the coordinator's labels, and every template
# Helm evaluates after coordinator-deployment.yaml would render it.
#
# No real workload can show this today, which is exactly why it needs its own
# check: Helm evaluates same-directory templates in reverse alphabetical order,
# so mockapi-deployment and driver-statefulset read global.affinity BEFORE the
# coordinator runs. The first pod template whose name sorts before
# "coordinator-deployment" would get the leak. So this renders a copy of the
# chart with a probe template that sorts first -- and is therefore evaluated
# last -- and reads global.affinity back out.
def coordinator_does_not_mutate_global_affinity():
    with tempfile.TemporaryDirectory() as tmp:
        chart = os.path.join(tmp, "loadgen")
        shutil.copytree("k8s/loadgen", chart)
        with open(os.path.join(chart, "templates", "a-probe.yaml"), "w") as f:
            f.write("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: probe\n"
                    "data:\n  affinity: {{ .Values.global.affinity | toJson | quote }}\n")
        with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as vf:
            yaml.safe_dump(deep_merge(LOADGEN_BASE, {
                "global": {"affinity": {"nodeAffinity": SHARED_NODE_AFFINITY}},
                "coordinator": {"replicaCount": 2},
            }), vf)
        try:
            out = subprocess.run(["helm", "template", "loadgen", chart, "-f", vf.name],
                                 check=True, capture_output=True, text=True).stdout
        finally:
            os.unlink(vf.name)
    probe = next(d for d in yaml.safe_load_all(out)
                 if d and d.get("kind") == "ConfigMap" and d["metadata"]["name"] == "probe")
    eq("global.affinity", "after rendering the coordinator", json.loads(probe["data"]["affinity"]),
       {"nodeAffinity": SHARED_NODE_AFFINITY})


check("global placement reaches every pod in both charts", global_reaches_every_pod)
check("a component's value replaces the global one, never merges", component_replaces_global)
check("defaults add no placement beyond the coordinator's standby spread", defaults_add_nothing_but_the_coordinator_spread)
check("coordinator keeps its standby spread when the user adds nodeAffinity", coordinator_keeps_spread_under_user_node_affinity)
check("an explicit podAntiAffinity replaces the coordinator's built-in one", explicit_anti_affinity_replaces_builtin)
check("rendering the coordinator leaves global.affinity untouched", coordinator_does_not_mutate_global_affinity)

if failures:
    print(f"\n{len(failures)} of {checks} placement checks failed")
    sys.exit(1)
print(f"\nall {checks} placement checks passed")
PY
