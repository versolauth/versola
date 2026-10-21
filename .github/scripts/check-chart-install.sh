#!/usr/bin/env bash
#
# Install-level checks for k8s/loadgen and k8s/versola, against whatever cluster
# kubectl currently points at (CI: an ephemeral kind cluster; locally: any
# throwaway kind/k3d cluster -- this script installs and uninstalls releases in
# namespaces of its own, but never creates or deletes the cluster).
#
# Why this exists (versolauth/versola#391): every other chart check asserts on
# `helm template` output. The failures the charts actually hit rendered cleanly
# and broke only once installed -- an Ingress adopted by no controller (#379),
# placement values the scheduler would ignore (#381), a pod that crash-loops on
# config it cannot resolve. This is the wiring level: the API server accepts the
# manifests and the scheduler honours them.
#
# Needs the cluster's single node to be schedulable (kind's default) and able to
# pull ghcr.io/versolauth/versola-mockapi. It labels and taints that node, and
# restores both on exit.
#
# Usage: .github/scripts/check-chart-install.sh

set -euo pipefail
cd "$(dirname "$0")/../.."

# This labels and taints a node: never against a real cluster.
CONTEXT="$(kubectl config current-context)"
case "$CONTEXT" in
  kind-* | k3d-*) ;;
  *) echo "refusing to run against context '${CONTEXT}': only kind-* and k3d-* contexts are allowed" >&2; exit 2 ;;
esac

RUN="chart-install-$$"
NS_LOADGEN="${RUN}-loadgen"
NS_VERSOLA="${RUN}-versola"
NODE="$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}')"
LABEL_KEY="versola.io/chart-install-check"
TAINT_KEY="versola.io/chart-install-check"
TMP="$(mktemp -d)"

errors=0
checked=0

cleanup() {
  helm uninstall -n "$NS_LOADGEN" --ignore-not-found loadgen >/dev/null 2>&1 || true
  helm uninstall -n "$NS_VERSOLA" --ignore-not-found versola >/dev/null 2>&1 || true
  kubectl delete ns "$NS_LOADGEN" "$NS_VERSOLA" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kubectl label node "$NODE" "${LABEL_KEY}-" >/dev/null 2>&1 || true
  kubectl taint node "$NODE" "${TAINT_KEY}-" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

fail() {
  echo "    $1" >&2
  errors=$((errors + 1))
}

check() {
  local name="$1" before="$errors"
  checked=$((checked + 1))
  "$2"
  if [ "$errors" -eq "$before" ]; then echo "PASS  ${name}"; else echo "FAIL  ${name}"; fi
}

# What a mockapi-only install needs: no coordinator or drivers (they need a
# Secret and a database), one small replica so a 2-core runner has room for it.
LOADGEN=(
  --set coordinator.enabled=false
  --set driver.enabled=false
  --set mockapi.replicaCount=1
  --set mockapi.resources.requests.cpu=100m
  --set mockapi.resources.requests.memory=256Mi
  --set mockapi.resources.limits.memory=512Mi
)

install_loadgen() {
  helm upgrade --install loadgen k8s/loadgen -n "$NS_LOADGEN" --create-namespace "${LOADGEN[@]}" "$@"
}

mockapi_pods() {
  kubectl get pods -n "$NS_LOADGEN" -l app.kubernetes.io/component=mockapi -o name
}

# Polls until `$1` (a command string) succeeds, for at most `$2` seconds.
wait_for() {
  local deadline=$((SECONDS + $2))
  until eval "$1" >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$deadline" ] || return 1
    sleep 2
  done
}

mockapi_installs_and_becomes_ready() {
  install_loadgen --wait --timeout 5m >"$TMP/out" 2>&1 || {
    fail "helm install --wait failed: $(tail -5 "$TMP/out")"
    kubectl get pods -n "$NS_LOADGEN" -o wide >&2 || true
    kubectl describe pods -n "$NS_LOADGEN" >&2 || true
    return
  }
  local ready
  ready="$(kubectl get pods -n "$NS_LOADGEN" -l app.kubernetes.io/component=mockapi \
    -o jsonpath='{.items[*].status.conditions[?(@.type=="Ready")].status}')"
  [ "$ready" = "True" ] || fail "mockapi pod is not Ready: '${ready}'"
}

# The check #381 did by hand: a selector matching no node must leave the pod
# Pending with a FailedScheduling event, not land it somewhere anyway.
unmatched_selector_leaves_pod_pending() {
  helm uninstall -n "$NS_LOADGEN" loadgen >/dev/null
  install_loadgen --set "global.nodeSelector.${LABEL_KEY//./\\.}=absent" >"$TMP/out" 2>&1 || {
    fail "helm install failed: $(tail -5 "$TMP/out")"
    return
  }
  wait_for "[ -n \"\$(kubectl get events -n $NS_LOADGEN --field-selector reason=FailedScheduling -o name)\" ]" 90 \
    || fail "no FailedScheduling event within 90s"
  local phase node
  phase="$(kubectl get pods -n "$NS_LOADGEN" -l app.kubernetes.io/component=mockapi -o jsonpath='{.items[0].status.phase}')"
  node="$(kubectl get pods -n "$NS_LOADGEN" -l app.kubernetes.io/component=mockapi -o jsonpath='{.items[0].spec.nodeName}')"
  [ "$phase" = "Pending" ] || fail "pod phase is '${phase}', expected Pending"
  [ -z "$node" ] || fail "pod was bound to node '${node}' despite an unmatched nodeSelector"
}

# The same selector, once a node carries the label, must schedule: this proves
# the Pending above came from the selector and not from a broken cluster.
matched_selector_schedules_pod() {
  kubectl label node "$NODE" "${LABEL_KEY}=present" --overwrite >/dev/null
  install_loadgen --set "global.nodeSelector.${LABEL_KEY//./\\.}=present" --wait --timeout 5m >"$TMP/out" 2>&1 || {
    fail "helm upgrade --wait failed after labelling the node: $(tail -5 "$TMP/out")"
    return
  }
  local node
  node="$(kubectl get pods -n "$NS_LOADGEN" -l app.kubernetes.io/component=mockapi -o jsonpath='{.items[0].spec.nodeName}')"
  [ "$node" = "$NODE" ] || fail "pod is on '${node}', expected '${NODE}'"
}

# Tolerations: a tainted node refuses the pod until the chart's tolerations say
# otherwise.
tolerations_admit_pod_to_tainted_node() {
  kubectl taint node "$NODE" "${TAINT_KEY}=reserved:NoSchedule" --overwrite >/dev/null
  helm uninstall -n "$NS_LOADGEN" loadgen >/dev/null
  install_loadgen >"$TMP/out" 2>&1 || { fail "helm install failed: $(tail -5 "$TMP/out")"; return; }
  wait_for "[ -n \"\$(kubectl get events -n $NS_LOADGEN --field-selector reason=FailedScheduling -o name)\" ]" 90 \
    || fail "an untolerating pod was not refused by the tainted node"
  install_loadgen --wait --timeout 5m \
    --set "global.tolerations[0].key=${TAINT_KEY}" --set "global.tolerations[0].operator=Equal" \
    --set "global.tolerations[0].value=reserved" --set "global.tolerations[0].effect=NoSchedule" \
    >"$TMP/out" 2>&1 || fail "pod did not schedule with a matching toleration: $(tail -5 "$TMP/out")"
}

# #379. The chart refuses a plain Ingress without a className, so `helm install`
# itself must fail and leave nothing behind; with a className the API server
# accepts the Ingress and it carries the class.
VERSOLA=(
  --set secrets.existingSecret=versola-secrets
  --set services.auth.config.existingSecret=auth-config
  --set services.central.config.existingSecret=central-config
  --set services.edge.config.existingSecret=edge-config
  # The default pki.enabled=true renders cert-manager Certificates and fails to install on a cluster
  # without its CRDs (documented in values.yaml); the PKI is not what is under test here.
  --set pki.enabled=false
  # The migration Job is a pre-install hook that waits for a database this cluster does not have
  # (and for config Secrets that hold real connection settings), so with it on `helm install` fails
  # with DeadlineExceeded. Its rendering is asserted by check-chart-migrations.sh; what it does is
  # covered by the install on a real cluster described in the PR that added it.
  --set migrations.enabled=false
  --set ingress.enabled=true
  --set ingress.hosts[0].host=id.example.com
  --set ingress.hosts[0].routes[0]=oidc
)

ingress_without_class_is_refused_at_install() {
  if helm install versola k8s/versola -n "$NS_VERSOLA" --create-namespace "${VERSOLA[@]}" >"$TMP/out" 2>&1; then
    fail "helm install with ingress.enabled and no className succeeded; it must fail"
    return
  fi
  grep -q 'ingress.className is required' "$TMP/out" || fail "failed, but not with the className message: $(tail -3 "$TMP/out")"
  [ -z "$(kubectl get ingress -n "$NS_VERSOLA" -o name 2>/dev/null)" ] || fail "an Ingress was created by the refused install"
  [ -z "$(helm list -n "$NS_VERSOLA" -q)" ] || fail "a release was recorded for the refused install"
}

ingress_with_class_is_accepted() {
  # No --wait: the pods cannot start (the Secrets are placeholders); only the
  # API server's acceptance of the objects is under test here.
  helm install versola k8s/versola -n "$NS_VERSOLA" --create-namespace "${VERSOLA[@]}" \
    --set ingress.className=nginx >"$TMP/out" 2>&1 || {
    fail "helm install with className=nginx failed: $(tail -5 "$TMP/out")"
    return
  }
  local class
  class="$(kubectl get ingress -n "$NS_VERSOLA" -o jsonpath='{.items[0].spec.ingressClassName}')"
  [ "$class" = "nginx" ] || fail "installed Ingress has ingressClassName '${class}', expected nginx"
}

check "k8s/loadgen with only mockapi installs and becomes Ready" mockapi_installs_and_becomes_ready
check "an unmatched global.nodeSelector leaves the pod Pending with FailedScheduling" unmatched_selector_leaves_pod_pending
check "a matching global.nodeSelector schedules the pod on the labelled node" matched_selector_schedules_pod
check "global.tolerations admit the pod to a tainted node" tolerations_admit_pod_to_tainted_node
check "k8s/versola refuses an Ingress with no className at install" ingress_without_class_is_refused_at_install
check "k8s/versola installs an Ingress carrying its className" ingress_with_class_is_accepted

echo
if [ "$errors" -ne 0 ]; then
  echo "FAIL: ${errors} of ${checked} install checks failed" >&2
  exit 1
fi
echo "OK: all ${checked} install checks passed."
