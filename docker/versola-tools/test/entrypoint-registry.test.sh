#!/bin/sh
# Runs entrypoint.sh with a stub `java` (so no JVM and no gen-env) to check what it does with
# IMAGE_REGISTRY: leave the compose fragment on ghcr.io/versolauth when it is unset, put an accepted
# registry in front of every versola-* image of both targets' fragments, and refuse anything that is not
# a registry host and path before it reaches sed. Plain POSIX sh, no docker:
#
#   sh docker/versola-tools/test/entrypoint-registry.test.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
tools=$(cd "$here/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0

pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }

mkdir -p "$work/app/central-ui" "$work/bin"
for f in entrypoint.sh compose.fragment.vps.yml.template compose.fragment.yml.template openbao.hcl.vps.template openbao.hcl.template \
  nginx.conf.template proxy_params.conf.template mtls-init.sh step-ca-intermediates.sh; do
  cp "$tools/$f" "$work/app/$f"
done
# gen-env's output, as the entrypoint copies it; the stub leaves a marker so a run can be told to have
# got as far as gen-env.
cat > "$work/bin/java" <<STUB
#!/bin/sh
dir=.local/env/\$(cat)
mkdir -p "\$dir"
for f in auth.conf central.conf edge.conf auth.generated-secrets.env central.generated-secrets.env edge.generated-secrets.env \\
  utils.private-key.jwk secrets.schema.json; do : > "\$dir/\$f"; done
: > "$work/java.ran"
STUB
chmod +x "$work/bin/java"

# run_entrypoint <target> [VAR=value ...]: exit status of one run, output in $work/out
run_entrypoint() {
  target=$1; shift
  rm -rf "$work/out.d" "$work/app/.local" "$work/java.ran"
  (cd "$work/app" && env -u IMAGE_REGISTRY PATH="$work/bin:$PATH" OUT_DIR="$work/out.d" VERSION=9.9.9 TARGET="$target" \
    AUTH_URL=https://auth.example.com POSTGRES_HOST=127.0.0.1:5432 "$@" sh ./entrypoint.sh > "$work/out" 2>&1)
}

# images <target> [VAR=value ...]: the versola-* image lines of the generated fragment, one per line.
images() {
  run_entrypoint "$@" || { cat "$work/out" >&2; return 1; }
  grep -E '^\s*image: .*/versola-' "$work/out.d/compose.fragment.yml" | sed 's/^ *image: //' | sort
}

expect_images() {
  label=$1; want=$2; shift 2
  got=$(images "$@") || { fail "$label (the run failed)"; return; }
  if [ "$got" = "$want" ]; then pass "$label"; else fail "$label: got
$got"; fi
}

for target in docker-local vps; do
  default=$(printf '%s\n' ghcr.io/versolauth/versola-auth:9.9.9 ghcr.io/versolauth/versola-central:9.9.9 \
    ghcr.io/versolauth/versola-edge:9.9.9 ghcr.io/versolauth/versola-tools:9.9.9)
  custom=$(printf '%s\n' cr.yandex/crp1a2b3c/versola-auth:9.9.9 cr.yandex/crp1a2b3c/versola-central:9.9.9 \
    cr.yandex/crp1a2b3c/versola-edge:9.9.9 cr.yandex/crp1a2b3c/versola-tools:9.9.9)
  expect_images "$target: unset keeps ghcr.io/versolauth" "$default" "$target"
  expect_images "$target: empty keeps ghcr.io/versolauth" "$default" "$target" IMAGE_REGISTRY=
  expect_images "$target: the default spelled out" "$default" "$target" IMAGE_REGISTRY=ghcr.io/versolauth
  expect_images "$target: a registry prefix reaches every versola image" "$custom" "$target" IMAGE_REGISTRY=cr.yandex/crp1a2b3c
done

expect_images "a registry with a port" "$(printf '%s\n' localhost:5000/team/versola-auth:9.9.9 localhost:5000/team/versola-central:9.9.9 \
  localhost:5000/team/versola-edge:9.9.9 localhost:5000/team/versola-tools:9.9.9)" vps IMAGE_REGISTRY=localhost:5000/team

# Nothing but the versola-* images changes, and no placeholder is left.
run_entrypoint vps IMAGE_REGISTRY=cr.yandex/crp1a2b3c
if grep -qF '${REGISTRY}' "$work/out.d/compose.fragment.yml"; then fail "the vps fragment has no placeholder left"; else pass "the vps fragment has no placeholder left"; fi
if grep -qF 'ghcr.io/versolauth' "$work/out.d/compose.fragment.yml"; then fail "the vps fragment names no ghcr.io/versolauth image"; else pass "the vps fragment names no ghcr.io/versolauth image"; fi
grep -qF 'image: openbao/openbao:' "$work/out.d/compose.fragment.yml" && grep -qF 'image: smallstep/step-ca:' "$work/out.d/compose.fragment.yml" \
  && pass "third-party images are left alone" || fail "third-party images are left alone"

refuses() {
  if run_entrypoint "$@"; then fail "refused: $*"; elif [ -e "$work/java.ran" ]; then fail "refused before gen-env: $*"; else pass "refused: $*"; fi
}
for bad in 'CR.yandex/team' 'cr.yandex/team/' '/cr.yandex' 'cr.yandex//team' '-cr.yandex' '.cr.yandex' ':5000/team' 'cr yandex/team' \
  'cr.yandex/team;touch /tmp/x' 'cr.yandex/$(id)' 'cr.yandex/`id`' 'cr.yandex/a|b' 'cr.yandex/a&b' 'cr.yandex/a\b' 'https://cr.yandex/team' \
  'cr.yandex/team:latest' 'cr.yandex:5000/team:latest' '*'; do
  refuses vps IMAGE_REGISTRY="$bad"
  refuses docker-local IMAGE_REGISTRY="$bad"
done
refuses vps IMAGE_REGISTRY='cr.yandex/team;x'
grep -q "not a registry host and path" "$work/out" && pass "the message says what is wrong" || fail "the message says what is wrong"
refuses vps IMAGE_REGISTRY='cr.yandex/team:latest'
grep -q "':' outside the host" "$work/out" && pass "the message names the tag" || fail "the message names the tag"

if [ "$failures" -ne 0 ]; then echo "$failures failed" >&2; exit 1; fi
echo "all passed"
