#!/usr/bin/env bash
#
# Asserts that k8s/versola refuses to render a plain Ingress without an
# explicit `ingress.className` (versolauth/versola#379).
#
# Why this exists: an empty className falls back to the cluster's default
# IngressClass, and a cluster whose only controller is a stock ingress-nginx
# install has none -- that chart does not mark its own class as default. The
# Ingress would render, look correct, and be adopted by no controller: ADDRESS
# stays empty and every request gets a bare 404, which reads as a routing bug
# rather than an unclaimed resource. Nothing else catches this: the other
# chart checks render the defaults, where `ingress.enabled` is false, so the
# guard could be dropped and stay green.
#
# The guard is scoped to the plain-Ingress path. The Gateway API path requires
# `parentRefs` explicitly and has no "default class" to fall back to, so it
# must keep rendering with no className at all -- hence the last scenario.

set -euo pipefail
cd "$(dirname "$0")/../.."

CHART=k8s/versola
# The chart's unrelated required values, so a scenario fails on the ingress
# contract and nothing else.
BASE=(
  --set secrets.existingSecret=versola-secrets
  --set services.auth.config.existingSecret=auth-config
  --set services.central.config.existingSecret=central-config
  --set services.edge.config.existingSecret=edge-config
)
HOST=(--set ingress.hosts[0].host=id.example.com --set ingress.hosts[0].routes[0]=oidc)

errors=0
checked=0

# Renders the chart and prints stdout on success, stderr on failure, so a
# scenario can assert on either the manifests or the failure message.
render() {
  helm template versola "$CHART" --namespace versola "${BASE[@]}" "$@" 2>/tmp/chart-ingress-err.txt
}

fail() {
  echo "    $1" >&2
  errors=$((errors + 1))
}

renders_without_a_class_is_refused() {
  if render --set ingress.enabled=true "${HOST[@]}" >/dev/null; then
    fail "ingress.enabled=true with no className rendered; it must fail"
    return
  fi
  grep -q 'ingress.className is required' /tmp/chart-ingress-err.txt \
    || fail "failed, but not with the className message: $(cat /tmp/chart-ingress-err.txt)"
  return 0
}

named_class_reaches_the_ingress() {
  local out
  out="$(render --set ingress.enabled=true --set ingress.className=nginx "${HOST[@]}")" || {
    fail "ingress.enabled=true with className=nginx failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  grep -q '^kind: Ingress$' <<<"$out" || fail "no Ingress rendered"
  grep -q '^  ingressClassName: nginx$' <<<"$out" || fail "Ingress carries no ingressClassName: nginx"
  return 0
}

disabled_ingress_needs_no_class() {
  local out
  out="$(render --set ingress.enabled=false)" || {
    fail "ingress.enabled=false failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  if grep -q '^kind: Ingress$' <<<"$out"; then fail "ingress.enabled=false rendered an Ingress"; fi
}

gateway_api_needs_no_class() {
  local out
  out="$(render --set ingress.enabled=true --set ingress.gatewayAPI.enabled=true \
                --set ingress.gatewayAPI.parentRefs[0].name=public-gateway "${HOST[@]}")" || {
    fail "gatewayAPI with no className failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  grep -q '^kind: HTTPRoute$' <<<"$out" || fail "no HTTPRoute rendered"
  if grep -q '^kind: Ingress$' <<<"$out"; then fail "gatewayAPI path rendered a plain Ingress too"; fi
}

native_routes_reach_edge() {
  local out
  out="$(render --set ingress.enabled=true --set ingress.className=nginx \
                --set ingress.hosts[0].host=id.example.com --set 'ingress.hosts[0].routes={oidc,native}')" || {
    fail "the native route group failed to render: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  grep -q 'path: /native/' <<<"$out" || fail "no /native/ path rendered for the native group"
  # The path must lead to edge, not auth: edge is what fronts a native client.
  awk '/path: \/native\//{f=1} f && /name:/{print; exit}' <<<"$out" | grep -q 'versola-edge' \
    || fail "/native/ is not routed to edge"
  return 0
}

central_mtls_is_unpublished_by_default() {
  local out
  out="$(render)" || { fail "default render failed: $(cat /tmp/chart-ingress-err.txt)"; return; }
  if grep -q 'central-mtls$' <<<"$out" && grep -q 'ssl-passthrough' <<<"$out"; then fail "central's mutual-TLS port is published with no mutualTlsExposure"; fi
  if grep -q '^kind: TLSRoute$' <<<"$out"; then fail "a TLSRoute rendered by default"; fi
  return 0
}

central_mtls_load_balancer() {
  local out
  out="$(render --set services.central.mutualTlsExposure.type=loadBalancer \
                --set 'services.central.mutualTlsExposure.sourceRanges={203.0.113.0/24}')" || {
    fail "loadBalancer failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  grep -q 'type: LoadBalancer' <<<"$out" || fail "no LoadBalancer Service rendered"
  grep -q '203.0.113.0/24' <<<"$out" || fail "sourceRanges did not reach the Service"
  return 0
}

central_mtls_passthrough_never_terminates() {
  local out
  out="$(render --set services.central.mutualTlsExposure.type=ingressPassthrough \
                --set services.central.mutualTlsExposure.host=central.example.com \
                --set services.central.mutualTlsExposure.className=nginx)" || {
    fail "ingressPassthrough failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  grep -q 'ssl-passthrough: "true"' <<<"$out" || fail "the Ingress does not ask for TLS passthrough"
  # A `tls:` block would have the controller terminate the handshake: central would never see the edge's certificate.
  # The document is picked out by its annotation, then searched for the block.
  if awk 'BEGIN{RS="---\n"} /ssl-passthrough/' <<<"$out" | grep -q '^  tls:'; then fail "the passthrough Ingress carries a tls block"; fi
  awk '/central.example.com/{f=1} f && /number:/{print; exit}' <<<"$out" | grep -q '8093' || fail "the Ingress does not lead to central's mutual-TLS port"
  return 0
}

central_mtls_passthrough_needs_a_host_and_class() {
  if render --set services.central.mutualTlsExposure.type=ingressPassthrough --set services.central.mutualTlsExposure.className=nginx >/dev/null; then
    fail "ingressPassthrough without host rendered; it must fail"
  fi
  if render --set services.central.mutualTlsExposure.type=ingressPassthrough --set services.central.mutualTlsExposure.host=central.example.com >/dev/null; then
    fail "ingressPassthrough without className rendered; it must fail"
  fi
  return 0
}

central_mtls_tls_route() {
  local out
  out="$(render --set services.central.mutualTlsExposure.type=gatewayTLSRoute \
                --set services.central.mutualTlsExposure.host=central.example.com \
                --set services.central.mutualTlsExposure.parentRefs[0].name=public-gateway)" || {
    fail "gatewayTLSRoute failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  grep -q '^kind: TLSRoute$' <<<"$out" || fail "no TLSRoute rendered"
  if render --set services.central.mutualTlsExposure.type=gatewayTLSRoute --set services.central.mutualTlsExposure.host=central.example.com >/dev/null; then
    fail "gatewayTLSRoute without parentRefs rendered; it must fail"
  fi
  return 0
}

central_mtls_unknown_type_is_refused() {
  if render --set services.central.mutualTlsExposure.type=nodePort >/dev/null; then
    fail "an unknown exposure type rendered; it must fail"
  fi
  return 0
}

check() {
  local name="$1" before="$errors"
  checked=$((checked + 1))
  "$2"
  if [ "$errors" -eq "$before" ]; then echo "PASS  ${name}"; else echo "FAIL  ${name}"; fi
}

check "a plain Ingress without className is refused" renders_without_a_class_is_refused
check "a named className reaches the rendered Ingress" named_class_reaches_the_ingress
check "a disabled ingress needs no className" disabled_ingress_needs_no_class
check "the Gateway API path renders without a className" gateway_api_needs_no_class
check "the native route group routes /native/ to edge" native_routes_reach_edge
check "central's mutual-TLS port is unpublished by default" central_mtls_is_unpublished_by_default
check "central's mutual-TLS port can be a LoadBalancer" central_mtls_load_balancer
check "central's mutual-TLS Ingress passes TLS through and never terminates it" central_mtls_passthrough_never_terminates
check "central's mutual-TLS Ingress needs a host and a class" central_mtls_passthrough_needs_a_host_and_class
check "central's mutual-TLS port can be a Gateway TLSRoute" central_mtls_tls_route
check "an unknown mutual-TLS exposure type is refused" central_mtls_unknown_type_is_refused

rm -f /tmp/chart-ingress-err.txt

echo
if [ "$errors" -ne 0 ]; then
  echo "FAIL: ${errors} of ${checked} ingress checks failed" >&2
  exit 1
fi
echo "OK: all ${checked} ingress checks passed."
