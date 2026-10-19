#!/usr/bin/env bash
#
# Asserts how the versola chart renders the migration Job (#209).
#
# What each case guards:
#   - a hook Job is `pre-install,pre-upgrade` and replaces its failed predecessor, so a failed
#     upgrade is retried instead of dying on "already exists";
#   - without the hook it is an ordinary Job whose name follows what it runs, because a Job's
#     pod template cannot be changed in place;
#   - one container per enabled service, each seeing only its own env.conf and its own database
#     password. Every env.conf says ${POSTGRES_PASSWORD} with a different value per service, so
#     one shared container could not serve all three, and a shared one would hand each schema's
#     credentials to the others;
#   - none of the services' other secrets reach the Job;
#   - a service's own volumes (the CA `postgres.ssl-root-cert` points at) are mounted where the
#     service mounts them;
#   - the chart refuses to render a Job that would have nothing to read, and renders none when
#     migrations are off.
#
# Expected values are literals written by hand, not computed by rendering the chart another way.
set -euo pipefail
cd "$(dirname "$0")/../.."

if ! python3 -c 'import yaml' 2>/dev/null; then
  echo "check-chart-migrations: needs python3 with PyYAML (import yaml failed)" >&2
  exit 2
fi

python3 - <<'PY'
import subprocess
import sys

import yaml

BASE = [
    "--set", "secrets.existingSecret=sec",
    "--set", "services.auth.config.existingSecret=auth-cfg",
    "--set", "services.central.config.existingSecret=central-cfg",
    "--set", "services.edge.config.existingSecret=edge-cfg",
]
failures = []


def render(*extra, base=BASE):
    proc = subprocess.run(
        ["helm", "template", "check", "k8s/versola", "-s", "templates/migrate-job.yaml", *base, *extra],
        capture_output=True, text=True,
    )
    return proc.returncode, proc.stdout, proc.stderr


def job(*extra):
    code, out, err = render(*extra)
    if code != 0:
        failures.append(f"render failed for {extra}: {err.strip()}")
        print(f"FAIL  render failed for {extra}")
        sys.exit(1)
    return yaml.safe_load(out)


def check(name, ok):
    print(("PASS  " if ok else "FAIL  ") + name)
    if not ok:
        failures.append(name)


def pod_of(j):
    return j["spec"]["template"]["spec"]


j = job()
pod = pod_of(j)
ann = j["metadata"]["annotations"]
check("hook: pre-install,pre-upgrade", ann.get("helm.sh/hook") == "pre-install,pre-upgrade")
check("hook: failed predecessor replaced, finished Job removed",
      ann.get("helm.sh/hook-delete-policy") == "before-hook-creation,hook-succeeded")
check("hook: fixed name", j["metadata"]["name"] == "check-versola-migrate")
check("never restarts a pod in place", pod["restartPolicy"] == "Never")
check("deadline stays below Helm's default 5m timeout", j["spec"]["activeDeadlineSeconds"] < 300)

names = [c["name"] for c in pod["containers"]]
check("one container per service, in order", names == ["migrate-auth", "migrate-central", "migrate-edge"])

for c, svc in zip(pod["containers"], ["auth", "central", "edge"]):
    check(f"{svc}: migrates only its own schema", c["args"] == ["migrate", "--service", svc])
    env = {e["name"]: e for e in c["env"]}
    pw = env["POSTGRES_PASSWORD"]["valueFrom"]["secretKeyRef"]
    check(f"{svc}: its own database password", pw == {"name": "sec", "key": f"{svc}-postgres-password"})
    check(f"{svc}: no secret but the database password",
          [n for n, e in env.items() if "valueFrom" in e] == ["POSTGRES_PASSWORD"])
    check(f"{svc}: only its own env.conf",
          [(m["name"], m["mountPath"]) for m in c["volumeMounts"]] == [(f"config-{svc}", "/opt/versola-tools/config")])

vols = {v["name"]: v for v in pod["volumes"]}
check("each env.conf is mounted as <service>.conf from the service's own Secret",
      all(vols[f"config-{s}"]["secret"] == {"secretName": f"{s}-cfg", "items": [{"key": "env.conf", "path": f"{s}.conf"}]}
          for s in ["auth", "central", "edge"]))

j = job("--set", "services.edge.enabled=false")
check("a disabled service is not migrated",
      [c["name"] for c in pod_of(j)["containers"]] == ["migrate-auth", "migrate-central"])

j = job("--set", "migrations.hook=false")
check("no hook: no hook annotations", "annotations" not in j["metadata"])
name = j["metadata"]["name"]
check("no hook: name carries a hash of what it runs",
      name.startswith("check-versola-migrate-") and len(name) == len("check-versola-migrate-") + 8)
check("no hook: same spec, same name", job("--set", "migrations.hook=false")["metadata"]["name"] == name)
check("no hook: new image, new name",
      job("--set", "migrations.hook=false", "--set", "migrations.image.tag=9.9.9")["metadata"]["name"] != name)

j = job("--set", "migrations.hook=false", "--set", "migrations.annotations.argocd\\.argoproj\\.io/hook=PreSync")
check("annotations are passed through", j["metadata"]["annotations"] == {"argocd.argoproj.io/hook": "PreSync"})

j = job("--set", "migrations.dryRun=true")
check("dryRun adds --dry-run", all(c["args"][-1] == "--dry-run" for c in pod_of(j)["containers"]))

j = job("--set", "global.nodeSelector.pool=a", "--set", "migrations.tolerations[0].key=k")
check("placement: global applies, own replaces",
      pod_of(j)["nodeSelector"] == {"pool": "a"} and pod_of(j)["tolerations"] == [{"key": "k"}])

j = job("--set", "services.auth.extraVolumes[0].name=ca", "--set", "services.auth.extraVolumes[0].secret.secretName=pgca",
        "--set", "services.auth.extraVolumeMounts[0].name=ca", "--set", "services.auth.extraVolumeMounts[0].mountPath=/ca")
p = pod_of(j)
check("a service's own volume is mounted where the service mounts it",
      {"name": "ca", "mountPath": "/ca"} in p["containers"][0]["volumeMounts"]
      and not any(m["name"] == "ca" for m in p["containers"][1]["volumeMounts"])
      and any(v["name"] == "ca" for v in p["volumes"]))

code, out, err = render("--set", "migrations.enabled=false")
check("migrations off: no Job", code != 0 and "could not find template" in err)

code, out, err = render(base=[
    "--set", "services.auth.config.existingSecret=a",
    "--set", "services.central.config.existingSecret=c",
    "--set", "services.edge.config.existingSecret=e"])
check("no secrets.existingSecret: refused", code != 0 and "secrets.existingSecret" in err)

code, out, err = render(base=["--set", "secrets.existingSecret=sec"])
check("no config.existingSecret: refused", code != 0 and "config.existingSecret" in err)

if failures:
    print(f"\n{len(failures)} migration check(s) failed", file=sys.stderr)
    sys.exit(1)
print("\nall migration checks passed")
PY
