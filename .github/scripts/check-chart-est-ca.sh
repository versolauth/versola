#!/usr/bin/env bash
#
# Asserts that the file auth's EST `cacerts` hands to a bank (`est.ca-certificates`, mounted at
# /app/mtls/client-trust/ca.crt) never comes from the root: the leaf Secret's `ca.crt` is the ROOT, which signs the edge
# and server roles too, and a client must be told to trust the client CA alone. Renders the chart in each mode:
#
#   1. trust-manager on: the volume is the client-only bundle ConfigMap;
#   2. trust-manager off, EST not asserted: no volume at all (not the root as a fallback);
#   3. trust-manager off, `pki.est.enabled`: rendering fails with the reason;
#   4. `pki.est.caSecret` set: that Secret and key, in either mode.
#
# Needs `helm`; run from the repo root.
set -euo pipefail

CHART=k8s/versola
BASE=(--set migrations.enabled=false --set secrets.existingSecret=x
  --set services.auth.config.existingSecret=x --set services.central.config.existingSecret=x --set services.edge.config.existingSecret=x)

render() { helm template r "$CHART" "${BASE[@]}" "$@" 2>&1; }

# The `mtls-client-trust` volume of the rendered manifests (the lines of its block), empty if there is none.
volume() { awk '/^ +- name: mtls-client-trust$/ && !/mountPath/ {f=1; indent=match($0,/-/); print; next} f { if (match($0,/[^ ]/) <= indent && $0 !~ /^ *$/) {f=0} else print }'; }

fail() { echo "check-chart-est-ca: $*" >&2; exit 1; }

out=$(render --set pki.trustBundle.enabled=true)
block=$(printf '%s\n' "$out" | volume)
grep -q "configMap" <<<"$block" || fail "with trust-manager the volume is not a ConfigMap: $block"
grep -q "r-versola-client-only-ca" <<<"$block" || fail "with trust-manager the volume is not the client-only bundle: $block"
grep -q "auth-mtls" <<<"$block" && fail "the volume reads the leaf Secret (whose ca.crt is the root)"

out=$(render --set pki.trustBundle.enabled=false)
block=$(printf '%s\n' "$out" | volume)
[ -z "$block" ] || fail "without trust-manager and without a source the volume must be absent, got: $block"
grep -q "mtls-client-trust" <<<"$out" && fail "without a source the mount must be absent too"

if out=$(render --set pki.trustBundle.enabled=false --set pki.est.enabled=true); then
  fail "pki.est.enabled without a source must fail rendering"
fi
grep -q "pki.est.enabled needs a source" <<<"$out" || fail "the failure does not say why: $out"

for mode in true false; do
  out=$(render --set pki.trustBundle.enabled=$mode --set pki.est.enabled=true \
    --set pki.est.caSecret.name=bank-ca --set pki.est.caSecret.key=client.crt)
  block=$(printf '%s\n' "$out" | volume)
  grep -q "secretName: bank-ca" <<<"$block" || fail "caSecret not used (trustBundle=$mode): $block"
  grep -q "key: client.crt" <<<"$block" || fail "caSecret key not used (trustBundle=$mode): $block"
  grep -q "auth-mtls" <<<"$block" && fail "the volume reads the leaf Secret (trustBundle=$mode)"
done

echo "check-chart-est-ca: ok"
