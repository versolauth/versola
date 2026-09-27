#!/usr/bin/env bash
#
# Asserts that the secret env vars k8s/versola injects are exactly the ones
# scripts/gen-env.scala generates, per service.
#
# Why this exists: the chart's `versola.requiredSecretVars` (templates/_helpers.tpl)
# and gen-env.scala's writeGeneratedSecrets calls are two independent enumerations
# of one thing -- the `${VAR}` placeholders a service's env.conf resolves against
# the process environment. Nothing tied them together, so they drifted: the DPoP
# work added DPOP_NONCES_SECRET to auth and EDGE_INTERNAL_SECRET /
# EDGE_DPOP_NONCE_SALT to edge, the chart's list was not updated, and every pod
# installed with a generated config died on an unresolved HOCON substitution.
#
# The two directions fail differently, and both are failures:
#   - generated but not injected: the pod CrashLoopBackOffs at startup, because
#     `${VAR}` has nothing to resolve against.
#   - injected but not generated: `helm install` fails on a missing Secret key,
#     and whoever hits it has to invent a value the generator never produced.
#
# This compares GENERATED ARTIFACTS, not source text. An earlier draft parsed the
# Seq literals out of gen-env.scala and got the wrong answer: POSTGRES_PASSWORD and
# ADMIN_BOOTSTRAP_PASSWORD arrive via separate `authExtras`/`centralExtras`/`edgeExtras`
# sequences appended with `++`, so a reader of the literal alone under-reports every
# service. Running the generator and reading what it wrote cannot drift from what it
# does.
#
# Requires: helm, scala-cli (CI has both -- see ci-cd.yml's "Set up Scala CLI").
#
# Usage: .github/scripts/check-chart-secret-vars.sh

set -euo pipefail

CHART="k8s/versola"
SERVICES=(auth central edge)

repo_root="$(git rev-parse --show-toplevel)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# `vps` is the only non-interactive target that placeholders secrets out and so
# writes the *.generated-secrets.env files at all; `local` and `docker-local` do
# not produce the artifact this compares against. ENV_NAME is set because vps
# otherwise resolves `env` to "prod", and the values below are never connected
# to -- the generator only writes them into a config file.
#
# Run from a scratch directory, not the repo: gen-env writes .local/env/<target>/
# relative to the working directory, and .local is not gitignored.
generate_secrets() {
  (
    cd "$work"
    ENV_NAME=ci-check \
    AUTH_URL=https://ci.invalid \
    POSTGRES_HOST=ci.invalid:5432 \
      scala-cli run "$repo_root/scripts/gen-env.scala" <<< "vps" >/dev/null
  )
}

# One service at a time: the rendered manifest holds three Deployments, and the
# point is to check each service's own list, not their union.
injected_vars() {
  local svc="$1" enable_auth=false enable_central=false enable_edge=false
  case "$svc" in
    auth) enable_auth=true ;;
    central) enable_central=true ;;
    edge) enable_edge=true ;;
  esac

  helm template check "$repo_root/$CHART" \
    --set secrets.existingSecret=ci-check \
    --set console.enabled=false \
    --set "services.auth.enabled=$enable_auth" \
    --set "services.central.enabled=$enable_central" \
    --set "services.edge.enabled=$enable_edge" \
    --set "services.$svc.config.existingSecret=ci-check" \
  | awk '
      # `- name: FOO` remembers FOO; the secretKeyRef naming our test Secret
      # three lines later is what confirms FOO came from secrets.existingSecret
      # rather than being one of the chart’s plain env vars (PORT, DPORT, ...).
      /^[[:space:]]*-[[:space:]]*name:[[:space:]]*[A-Z_]+[[:space:]]*$/ {
        candidate = $NF; next
      }
      /^[[:space:]]*name:[[:space:]]*ci-check[[:space:]]*$/ {
        if (candidate != "") { print candidate; candidate = "" }
      }
    ' \
  | sort -u
}

generated_vars() {
  cut -d= -f1 "$work/.local/env/vps/$1.generated-secrets.env" | sort -u
}

echo "==> generating secrets with scripts/gen-env.scala (target vps)"
generate_secrets

failed=0
for svc in "${SERVICES[@]}"; do
  generated="$(generated_vars "$svc")"
  injected="$(injected_vars "$svc")"

  if [ -z "$injected" ]; then
    echo "    ${svc}: chart injected no secret env vars -- check the chart or this script's parsing" >&2
    failed=1
    continue
  fi

  missing="$(comm -23 <(echo "$generated") <(echo "$injected"))"
  extra="$(comm -13 <(echo "$generated") <(echo "$injected"))"

  if [ -z "$missing" ] && [ -z "$extra" ]; then
    echo "    ok      ${svc} ($(echo "$generated" | wc -l | tr -d ' ') vars)"
    continue
  fi

  failed=1
  [ -n "$missing" ] && echo "    ${svc}: generated but NOT injected by the chart: $(echo "$missing" | tr '\n' ' ')" >&2
  [ -n "$extra" ] && echo "    ${svc}: injected by the chart but NOT generated: $(echo "$extra" | tr '\n' ' ')" >&2
done

echo
if [ "$failed" -ne 0 ]; then
  cat >&2 <<'MSG'
FAIL: k8s/versola and scripts/gen-env.scala disagree about a service's secrets.

Update `versola.requiredSecretVars` in k8s/versola/templates/_helpers.tpl to
match what the generator writes. Check the key naming too: versola.secretKeyFor
prefixes a var with its service unless the kebab-cased name already starts with
it, and leaves CENTRAL_SECRET_KEY / CLIENT_SECRETS_SECRET bare because auth and
central must read the identical value.
MSG
  exit 1
fi

echo "OK: chart and generator agree for ${#SERVICES[@]} services."
