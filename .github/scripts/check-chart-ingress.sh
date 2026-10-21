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

# Everything under `exposure` (design-edge-network.md, H2-H4, H10). The contract:
#   - nothing is published by default, and the enrollment listener is not even bound;
#   - a mutual-TLS host is carried by passthrough, never terminated: no `tls:` block on its Ingress;
#   - a host needs pki.enabled and a limit, or the render is refused;
#   - the chart renders no ingress-nginx limit annotation for a passthrough, because none would
#     apply -- a limit annotation there would be a claim the controller does not honour;
#   - the enrollment Ingress terminates TLS and leads to the enrollment port, on one path.
MTLS_AUTH=(--set exposure.mtls.auth.host=id-mtls.example.com)
MTLS_BOTH=("${MTLS_AUTH[@]}" --set exposure.mtls.central.host=central-mtls.example.com)
MTLS_PASSTHROUGH=(--set exposure.mtls.className=nginx --set 'exposure.mtls.limits.enforcedBy=NLB security group sg-0abc')
ENROLL=(--set exposure.central.host=central.example.com --set exposure.central.className=nginx --set exposure.central.limits.connectionsPerIP=10)

exposure_is_off_by_default() {
  local out
  out="$(render)" || { fail "default render failed: $(cat /tmp/chart-ingress-err.txt)"; return; }
  if grep -q 'ssl-passthrough' <<<"$out"; then fail "a passthrough Ingress rendered by default"; fi
  if grep -q '^kind: TLSRoute$' <<<"$out"; then fail "a TLSRoute rendered by default"; fi
  if grep -q 'type: LoadBalancer' <<<"$out"; then fail "a LoadBalancer Service rendered by default"; fi
  if grep -q 'name: EPORT' <<<"$out"; then fail "central binds the enrollment listener by default"; fi
  if grep -q '/edge/enroll' <<<"$out"; then fail "the enrollment route is exposed by default"; fi
  return 0
}

mtls_passthrough_never_terminates() {
  local out
  out="$(render "${MTLS_BOTH[@]}" "${MTLS_PASSTHROUGH[@]}")" || {
    fail "ingressPassthrough failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  [ "$(grep -c 'ssl-passthrough: "true"' <<<"$out")" -eq 2 ] || fail "expected one passthrough Ingress per host"
  # A `tls:` block would have the controller terminate the handshake: the pod would never see the client's certificate.
  if awk 'BEGIN{RS="---\n"} /ssl-passthrough/' <<<"$out" | grep -q '^  tls:'; then fail "a passthrough Ingress carries a tls block"; fi
  # No limit annotation: ingress-nginx's act in the HTTP stage, which a passthrough never reaches.
  if awk 'BEGIN{RS="---\n"} /ssl-passthrough/' <<<"$out" | grep -Eq 'limit-connections|limit-rps|whitelist-source-range'; then
    fail "a passthrough Ingress carries a limit annotation that cannot apply"
  fi
  awk '/id-mtls.example.com/{f=1} f && /number:/{print; exit}' <<<"$out" | grep -q '8083' || fail "id-mtls does not lead to auth's mutual-TLS port"
  awk '/central-mtls.example.com/{f=1} f && /number:/{print; exit}' <<<"$out" | grep -q '8093' || fail "central-mtls does not lead to central's mutual-TLS port"
  # The public names are in the listener certificates' names, or the pin and the client's check could not succeed.
  grep -q '"id-mtls.example.com"' <<<"$out" || fail "id-mtls is not among auth's certificate names"
  grep -q '"central-mtls.example.com"' <<<"$out" || fail "central-mtls is not among central's certificate names"
  return 0
}

mtls_passthrough_needs_a_class() {
  if render "${MTLS_AUTH[@]}" --set 'exposure.mtls.limits.enforcedBy=x' >/dev/null; then
    fail "ingressPassthrough without className rendered; it must fail"
  fi
  return 0
}

mtls_load_balancer() {
  local out
  out="$(render --set exposure.mtls.type=loadBalancer "${MTLS_BOTH[@]}" \
                --set 'exposure.mtls.limits.sourceRanges={203.0.113.0/24}')" || {
    fail "loadBalancer failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  [ "$(grep -c 'type: LoadBalancer' <<<"$out")" -eq 2 ] || fail "expected one LoadBalancer Service per host"
  grep -q '203.0.113.0/24' <<<"$out" || fail "sourceRanges did not reach the Service"
  return 0
}

mtls_tls_route() {
  local out
  out="$(render --set exposure.mtls.type=gatewayTLSRoute "${MTLS_BOTH[@]}" \
                --set 'exposure.mtls.parentRefs[0].name=public-gateway' \
                --set 'exposure.mtls.limits.enforcedBy=gateway policy')" || {
    fail "gatewayTLSRoute failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  [ "$(grep -c '^kind: TLSRoute$' <<<"$out")" -eq 2 ] || fail "expected one TLSRoute per host"
  if render --set exposure.mtls.type=gatewayTLSRoute "${MTLS_AUTH[@]}" --set 'exposure.mtls.limits.enforcedBy=x' >/dev/null; then
    fail "gatewayTLSRoute without parentRefs rendered; it must fail"
  fi
  return 0
}

mtls_unknown_type_is_refused() {
  if render --set exposure.mtls.type=nodePort >/dev/null; then
    fail "an unknown exposure type rendered; it must fail"
  fi
  return 0
}

# H10: a public host without a limit, or without pki, is refused -- by values.schema.json, before a template runs.
mtls_host_without_a_limit_is_refused() {
  local variant
  if render "${MTLS_AUTH[@]}" --set exposure.mtls.className=nginx >/dev/null; then
    fail "a mutual-TLS host with no limit rendered; it must fail"
  fi
  grep -q 'exposure' /tmp/chart-ingress-err.txt || fail "refused, but not naming exposure: $(cat /tmp/chart-ingress-err.txt)"
  if render --set exposure.mtls.type=loadBalancer "${MTLS_AUTH[@]}" >/dev/null; then
    fail "a LoadBalancer host with no sourceRanges and no enforcedBy rendered; it must fail"
  fi
  # sourceRanges on a passthrough would look like a limit and enforce nothing.
  if render "${MTLS_AUTH[@]}" --set exposure.mtls.className=nginx --set 'exposure.mtls.limits.sourceRanges={10.0.0.0/8}' --set exposure.mtls.limits.enforcedBy=x >/dev/null; then
    fail "sourceRanges with a passthrough type rendered; it must fail, nothing would enforce it"
  fi
  return 0
}

exposure_without_pki_is_refused() {
  local args
  for args in "exposure.mtls.auth.host=id-mtls.example.com" "exposure.mtls.central.host=central-mtls.example.com" "exposure.central.host=central.example.com"; do
    if render --set pki.enabled=false --set "$args" --set exposure.mtls.className=nginx --set exposure.central.className=nginx \
              --set exposure.mtls.limits.enforcedBy=x --set exposure.central.limits.enforcedBy=x >/dev/null; then
      fail "${args} rendered with pki.enabled=false; it must fail"
    fi
  done
  return 0
}

enrollment_ingress_terminates_and_serves_one_route() {
  local out
  out="$(render "${ENROLL[@]}" --set 'exposure.central.tls[0].secretName=central-tls' --set 'exposure.central.tls[0].hosts={central.example.com}')" || {
    fail "the enrollment host failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  local doc
  doc="$(awk 'BEGIN{RS="---\n"} /central-enroll/ && /kind: Ingress/' <<<"$out")"
  [ -n "$doc" ] || { fail "no enrollment Ingress rendered"; return; }
  grep -q '^  tls:' <<<"$doc" || fail "the enrollment Ingress carries no tls block: it must terminate TLS"
  if grep -q 'ssl-passthrough' <<<"$doc"; then fail "the enrollment Ingress passes TLS through"; fi
  grep -q 'path: /edge/enroll' <<<"$doc" || fail "the enrollment Ingress does not name /edge/enroll"
  grep -q 'pathType: Exact' <<<"$doc" || fail "the enrollment path is not Exact"
  [ "$(grep -c 'path:' <<<"$doc")" -eq 1 ] || fail "the enrollment Ingress carries more than one path"
  grep -q 'number: 8094' <<<"$doc" || fail "the enrollment Ingress does not lead to the enrollment port"
  grep -q 'limit-connections: "10"' <<<"$doc" || fail "limit-connections did not reach the enrollment Ingress"
  # The pod binds and the Service carries the port only because a host asked.
  grep -q 'name: EPORT' <<<"$out" || fail "central is not told EPORT"
  return 0
}

enrollment_host_needs_a_limit_and_a_class() {
  if render --set exposure.central.host=central.example.com --set exposure.central.className=nginx >/dev/null; then
    fail "an enrollment host with no limit rendered; it must fail"
  fi
  if render --set exposure.central.host=central.example.com --set exposure.central.limits.enforcedBy=waf >/dev/null; then
    fail "an enrollment host with no className rendered; it must fail"
  fi
  # gatewayAPI has no per-IP annotation here, so a count alone is not a limit.
  if render --set exposure.central.type=gatewayAPI --set exposure.central.host=central.example.com \
            --set 'exposure.central.parentRefs[0].name=gw' --set exposure.central.limits.connectionsPerIP=10 >/dev/null; then
    fail "a gatewayAPI enrollment host limited only by connectionsPerIP rendered; it must fail"
  fi
  local out
  out="$(render --set exposure.central.type=gatewayAPI --set exposure.central.host=central.example.com \
                --set 'exposure.central.parentRefs[0].name=gw' --set exposure.central.limits.enforcedBy=waf)" || {
    fail "gatewayAPI enrollment host failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  grep -q '^kind: HTTPRoute$' <<<"$out" || fail "no HTTPRoute rendered for the enrollment host"
  return 0
}

network_policy_names_the_controller() {
  local out
  if render --set networkPolicy.enabled=true "${MTLS_BOTH[@]}" "${MTLS_PASSTHROUGH[@]}" >/dev/null; then
    fail "networkPolicy with a published host and no ingressControllerSelector rendered; it would cut the route off"
  fi
  out="$(render --set networkPolicy.enabled=true "${MTLS_BOTH[@]}" "${MTLS_PASSTHROUGH[@]}" \
                --set 'networkPolicy.ingressControllerSelector.namespaceSelector.matchLabels.kubernetes\.io/metadata\.name=ingress-nginx')" || {
    fail "networkPolicy failed: $(cat /tmp/chart-ingress-err.txt)"
    return
  }
  [ "$(grep -c '^kind: NetworkPolicy$' <<<"$out")" -eq 2 ] || fail "expected a NetworkPolicy for auth and one for central"
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
check "nothing is published by default" exposure_is_off_by_default
check "mutual-TLS hosts are passed through, never terminated, with no limit annotation" mtls_passthrough_never_terminates
check "a passthrough host needs a class" mtls_passthrough_needs_a_class
check "mutual-TLS hosts can be LoadBalancers" mtls_load_balancer
check "mutual-TLS hosts can be Gateway TLSRoutes" mtls_tls_route
check "an unknown mutual-TLS exposure type is refused" mtls_unknown_type_is_refused
check "a mutual-TLS host without a limit is refused" mtls_host_without_a_limit_is_refused
check "a public host without pki.enabled is refused" exposure_without_pki_is_refused
check "the enrollment host terminates TLS and serves /edge/enroll only" enrollment_ingress_terminates_and_serves_one_route
check "the enrollment host needs a limit and a class" enrollment_host_needs_a_limit_and_a_class
check "networkPolicy needs the ingress controller named" network_policy_names_the_controller

rm -f /tmp/chart-ingress-err.txt

echo
if [ "$errors" -ne 0 ]; then
  echo "FAIL: ${errors} of ${checked} ingress checks failed" >&2
  exit 1
fi
echo "OK: all ${checked} ingress checks passed."
