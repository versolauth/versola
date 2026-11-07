#!/usr/bin/env bash
#
# Asserts that every pod the three charts render is hardened: it cannot run as root, cannot gain
# privileges, has no Linux capabilities, a read-only root filesystem and the runtime's default
# seccomp profile, and the volumes that a read-only root needs are there.
#
# Why this exists: a securityContext is the one thing a chart template can silently lose. A new
# workload copied from an old one, a refactor of a helper, a `{}` left in values -- each renders a
# valid manifest that runs as root, and nothing fails. The images run as uid 10001 (docker/Dockerfile.*),
# so a container that loses `runAsNonRoot` still works and is simply not protected.
#
# Every container of every Pod (a helm test pod is the classic way to lose a context), Deployment,
# StatefulSet, DaemonSet, Job and CronJob is checked, init containers included,
# across the configurations that render different pods: the versola chart with PKI, the certificate
# sync and the migration Job on; the edge chart; the loadgen chart with the mockapi gateway on.
# Expected values are literals written by hand, not computed by rendering the chart a second way.
#
# Opting out is a values choice, and the last case asserts that it renders nothing rather than a
# half-set context. The opt-out is `null`, written in a values file: Helm merges maps, so an empty
# `{}` keeps the chart's defaults and does not opt out (the second to last case asserts that too, so
# a documented `{}` can never come back).

set -euo pipefail
cd "$(dirname "$0")/../.."

if ! python3 -c 'import yaml' 2>/dev/null; then
  echo "check-chart-security-context: needs python3 with PyYAML (import yaml failed)" >&2
  exit 2
fi
if ! command -v helm >/dev/null; then
  echo "check-chart-security-context: needs helm" >&2
  exit 2
fi

python3 - <<'PY'
import json
import os
import subprocess
import sys
import tempfile

import yaml

POD_KINDS = {"Pod", "Deployment", "StatefulSet", "DaemonSet", "Job", "CronJob"}

VERSOLA = {
    "services": {
        "auth": {"config": {"existingSecret": "auth-config"}},
        "central": {"config": {"existingSecret": "central-config"}},
        "edge": {"config": {"existingSecret": "edge-config"}},
    },
    "secrets": {"existingSecret": "versola-secrets"},
    "migrations": {"enabled": True},
    "pki": {
        "enabled": True,
        "certSync": {"enabled": True, "resourceSecret": {"name": "sync-secret", "key": "k"}},
    },
}
EDGE = {
    "central": {
        "mtlsUrl": "https://central-mtls.example.com",
        "enrollment": {"url": "https://central.example.com", "token": "ci-check"},
        "pins": ["-----BEGIN CERTIFICATE-----"],
    },
    "config": {"existingSecret": "edge-config"},
    "secrets": {"existingSecret": "edge-secrets"},
}
LOADGEN = {
    "driver": {"config": {"existingSecret": "driver-config"}},
    "coordinator": {"config": {"existingSecret": "coordinator-config"}},
}

failures = []


def render(chart, values, overrides=None):
    # `overrides` is a second values file (JSON is YAML), the way an operator's `-f` file reaches the chart.
    paths = []
    try:
        for v in (values, overrides):
            if v is None:
                continue
            with tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False) as f:
                json.dump(v, f)
                paths.append(f.name)
        cmd = ["helm", "template", "t", chart]
        for p in paths:
            cmd += ["-f", p]
        out = subprocess.run(cmd, capture_output=True, text=True)
    finally:
        for p in paths:
            os.unlink(p)
    if out.returncode != 0:
        failures.append(f"{chart}: helm template failed: {out.stderr.strip()}")
        return []
    return [d for d in yaml.safe_load_all(out.stdout) if d]


def pod_spec(d):
    if d["kind"] == "Pod":
        return d["spec"]
    if d["kind"] == "CronJob":
        return d["spec"]["jobTemplate"]["spec"]["template"]["spec"]
    return d["spec"]["template"]["spec"]


def pods(docs):
    for d in docs:
        if d["kind"] in POD_KINDS:
            yield f'{d["kind"]}/{d["metadata"]["name"]}', pod_spec(d)


def claim_names(docs, name):
    # A StatefulSet's volumeClaimTemplates are volumes of its pods without being in `volumes`.
    for d in docs:
        if f'{d["kind"]}/{d["metadata"]["name"]}' == name:
            return {c["metadata"]["name"] for c in d["spec"].get("volumeClaimTemplates", [])}
    return set()


def check(chart, docs, expect_workloads):
    seen = set()
    for name, spec in pods(docs):
        seen.add(name.split("/", 1)[1])
        pod = spec.get("securityContext") or {}
        claims = claim_names(docs, name)
        if pod.get("runAsNonRoot") is not True:
            failures.append(f"{chart} {name}: pod securityContext.runAsNonRoot is not true")
        if not isinstance(pod.get("runAsUser"), int) or pod["runAsUser"] == 0:
            failures.append(f"{chart} {name}: pod securityContext.runAsUser is missing or 0")
        if not isinstance(pod.get("runAsGroup"), int) or pod["runAsGroup"] == 0:
            failures.append(f"{chart} {name}: pod securityContext.runAsGroup is missing or 0")
        if (pod.get("seccompProfile") or {}).get("type") != "RuntimeDefault":
            failures.append(f"{chart} {name}: pod seccompProfile is not RuntimeDefault")
        volumes = {v["name"] for v in spec.get("volumes", [])} | claims
        for kind in ("initContainers", "containers"):
            for c in spec.get(kind, []):
                where = f'{chart} {name} {kind[:-1]} {c["name"]}'
                sc = c.get("securityContext") or {}
                if sc.get("allowPrivilegeEscalation") is not False:
                    failures.append(f"{where}: allowPrivilegeEscalation is not false")
                if sc.get("readOnlyRootFilesystem") is not True:
                    failures.append(f"{where}: readOnlyRootFilesystem is not true")
                if (sc.get("capabilities") or {}).get("drop") != ["ALL"]:
                    failures.append(f"{where}: capabilities.drop is not [ALL]")
                if "privileged" in sc and sc["privileged"]:
                    failures.append(f"{where}: privileged")
                mounts = {m["mountPath"]: m["name"] for m in c.get("volumeMounts", [])}
                if "/tmp" not in mounts:
                    failures.append(f"{where}: no volume at /tmp, which a read-only root filesystem needs")
                for m in mounts.values():
                    if m not in volumes:
                        failures.append(f"{where}: mounts the undefined volume {m}")
    missing = expect_workloads - seen
    if missing:
        failures.append(f"{chart}: expected workloads not rendered: {sorted(missing)}")


# The versola chart: services, console (initContainer + nginx), certificate sync, migration Job.
docs = render("k8s/versola", VERSOLA)
check("versola", docs, {"t-versola-auth", "t-versola-central", "t-versola-edge", "t-versola-console",
                        "t-versola-cert-sync"})
migrate = [n for n, _ in pods(docs) if "migrate" in n]
if not migrate:
    failures.append("versola: no migration Job rendered")
# The console runs as nginx's own user, not the services'.
for name, spec in pods(docs):
    if name.endswith("-console") and spec["securityContext"].get("runAsUser") != 101:
        failures.append(f"versola {name}: expected the nginx user 101")
    if name.endswith(("-auth", "-central", "-edge")) and spec["securityContext"].get("runAsUser") != 10001:
        failures.append(f"versola {name}: expected uid 10001, the image's user")
    if name.endswith(("-auth", "-central", "-edge")) and spec["securityContext"].get("fsGroup") != 10001:
        failures.append(f"versola {name}: expected fsGroup 10001")

# The edge chart: one StatefulSet with the identity volume.
docs = render("k8s/versola-edge", EDGE)
check("versola-edge", docs, {"t-versola-edge"})
for name, spec in pods(docs):
    if spec["securityContext"].get("fsGroup") != 10001:
        failures.append(f"versola-edge {name}: the identity volume needs fsGroup 10001")

# The loadgen chart, with the mockapi gateway sidecar.
docs = render("k8s/loadgen", LOADGEN)
check("loadgen", docs, {"t-loadgen-coordinator", "t-loadgen-driver", "t-loadgen-mockapi"})
for name, spec in pods(docs):
    if "mockapi" in name and "resource-gateway" not in {c["name"] for c in spec["containers"]}:
        failures.append(f"loadgen {name}: the gateway sidecar is not rendered, so it was not checked")

# Opting out. `null` in a values file removes the chart's default (Helm deletes a key set to null while
# coalescing); `{}` does not, because maps are merged: the defaults survive and the pod stays hardened.
OPT_OUT = {"global": {"podSecurityContext": None, "containerSecurityContext": None}}
EMPTY_MAP = {"global": {"podSecurityContext": {}, "containerSecurityContext": {}}}
docs = render("k8s/versola", VERSOLA, OPT_OUT)
for name, spec in pods(docs):
    if name.endswith("-auth") and "securityContext" in spec:
        failures.append(f"versola {name}: global.podSecurityContext: null should render no pod securityContext")
    if name.endswith("-auth") and "securityContext" in spec["containers"][0]:
        failures.append(f"versola {name}: global.containerSecurityContext: null should render no container securityContext")
docs = render("k8s/versola", VERSOLA, EMPTY_MAP)
for name, spec in pods(docs):
    if name.endswith("-auth") and spec.get("securityContext", {}).get("runAsNonRoot") is not True:
        failures.append(f"versola {name}: an empty map was expected to keep the defaults (the documented opt-out is null)")

if failures:
    print("check-chart-security-context FAILED:", file=sys.stderr)
    for f in failures:
        print("  - " + f, file=sys.stderr)
    sys.exit(1)
print("check-chart-security-context: ok")
PY
