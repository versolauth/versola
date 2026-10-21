#!/bin/sh
# Runs entrypoint.sh with a stub `java` (so no JVM and no gen-env) to check what it does with
# MTLS_DOMAIN and CENTRAL_MTLS_DOMAIN: refuse anything that is not a plain DNS name before it
# reaches sed or a shell, refuse them for any target but vps, and put the accepted ones into the
# compose fragment's `mtls-init` environment. Plain POSIX sh, no docker:
#
#   sh docker/versola-tools/test/entrypoint-mtls-domain.test.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
tools=$(cd "$here/.." && pwd)
work=$(mktemp -d)
failures=0

pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }

# The bundle input the image has in its working directory, with the real templates and scripts.
mkdir -p "$work/app/central-ui" "$work/bin"
for f in entrypoint.sh compose.fragment.vps.yml.template compose.fragment.yml.template openbao.hcl.vps.template openbao.hcl.template \
  nginx.conf.template proxy_params.conf.template mtls-init.sh step-ca-intermediates.sh; do
  cp "$tools/$f" "$work/app/$f"
done
# gen-env's output, as the entrypoint copies it; the stub leaves what the domains were in its environment.
cat > "$work/bin/java" <<STUB
#!/bin/sh
dir=.local/env/\$(cat)
mkdir -p "\$dir"
for f in auth.conf central.conf edge.conf auth.generated-secrets.env central.generated-secrets.env edge.generated-secrets.env \\
  utils.private-key.jwk secrets.schema.json; do : > "\$dir/\$f"; done
printf '%s|%s\n' "\${MTLS_DOMAIN-unset}" "\${CENTRAL_MTLS_DOMAIN-unset}" > "$work/java.env"
printf '%s\n' "\$@" > "$work/java.args"
STUB
chmod +x "$work/bin/java"

# run_entrypoint <target> [VAR=value ...]: exit status of one run, output in $work/out
run_entrypoint() {
  target=$1; shift
  rm -rf "$work/out.d" "$work/app/.local"
  (cd "$work/app" && env PATH="$work/bin:$PATH" OUT_DIR="$work/out.d" VERSION=9.9.9 TARGET="$target" \
    AUTH_URL=https://auth.example.com POSTGRES_HOST=127.0.0.1:5432 "$@" sh ./entrypoint.sh --some-flag=1 > "$work/out" 2>&1)
}

accepts() {
  if run_entrypoint vps "$@"; then pass "accepted: $*"; else fail "accepted: $* (exit $?; $(tail -1 "$work/out"))"; fi
}
refuses() {
  if run_entrypoint "$@"; then fail "refused: $*"; elif [ -e "$work/java.env" ] && [ "$work/java.env" -nt "$work/out" ]; then fail "refused before gen-env: $*"; else pass "refused: $*"; fi
  rm -f "$work/java.env"
}

long_label=$(printf 'a%.0s' $(seq 1 64))
ok_label=$(printf 'a%.0s' $(seq 1 63))
name_253=$ok_label.$ok_label.$ok_label.$(printf 'a%.0s' $(seq 1 61))
name_254=$ok_label.$ok_label.$ok_label.$(printf 'a%.0s' $(seq 1 62))

accepts MTLS_DOMAIN=id-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
accepts MTLS_DOMAIN=localhost
accepts MTLS_DOMAIN=a1-b2.c3.example.org
accepts MTLS_DOMAIN="$ok_label.example.com"
accepts MTLS_DOMAIN="$name_253"
accepts
accepts MTLS_DOMAIN= CENTRAL_MTLS_DOMAIN=

for bad in 'ID.example.com' 'id_mtls.example.com' '-id.example.com' 'id-.example.com' 'id.-example.com' 'id.example-.com' '.example.com' 'example.com.' \
  'a..example.com' "$long_label.example.com" "$name_254" 'id mtls.example.com' 'a.example.com;touch /tmp/x' '$(id).example.com' '`id`.example.com' \
  'a.example.com&b' 'a/b.example.com' 'a.example.com\' 'a.example.com|x' 'ünï.example.com' '*.example.com' 'a.example.com:443' 'https://a.example.com' '-' '.'; do
  refuses vps MTLS_DOMAIN="$bad"
  refuses vps CENTRAL_MTLS_DOMAIN="$bad"
done
refuses docker-local MTLS_DOMAIN=id-mtls.example.com
refuses docker-local CENTRAL_MTLS_DOMAIN=central-mtls.example.com
grep -q "only supported when TARGET=vps" "$work/out" && pass "the message names the target" || fail "the message names the target"
refuses vps MTLS_DOMAIN='a.example.com;x'
grep -q "not a valid DNS name" "$work/out" && pass "the message says what is wrong" || fail "the message says what is wrong"

# What an accepted run leaves behind.
run_entrypoint vps MTLS_DOMAIN=id-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
frag=$work/out.d/compose.fragment.yml
grep -qF 'MTLS_DOMAIN: "id-mtls.example.com"' "$frag" && pass "the compose fragment carries MTLS_DOMAIN" || fail "the compose fragment carries MTLS_DOMAIN"
grep -qF 'CENTRAL_MTLS_DOMAIN: "central-mtls.example.com"' "$frag" && pass "the compose fragment carries CENTRAL_MTLS_DOMAIN" || fail "the compose fragment carries CENTRAL_MTLS_DOMAIN"
! grep -q '@MTLS_DOMAIN@\|@CENTRAL_MTLS_DOMAIN@\|@MTLS_CERT_DAYS@\|\${VERSION}' "$frag" && pass "no placeholder is left" || fail "no placeholder is left"
[ "$(cat "$work/java.env")" = "id-mtls.example.com|central-mtls.example.com" ] && pass "gen-env gets both names in its environment" || fail "gen-env gets both names in its environment"
[ "$(tail -1 "$work/java.args")" = "--some-flag=1" ] && pass "the entrypoint's own arguments still reach gen-env" || fail "the entrypoint's own arguments still reach gen-env"
cmp -s "$tools/mtls-init.sh" "$work/out.d/mtls-init.sh" && cmp -s "$tools/step-ca-intermediates.sh" "$work/out.d/step-ca-intermediates.sh" \
  && pass "the scripts are in the bundle" || fail "the scripts are in the bundle"
[ "$(stat -f %Lp "$work/out.d/mtls-init.sh" 2>/dev/null || stat -c %a "$work/out.d/mtls-init.sh")" = 644 ] && pass "the scripts are world-readable" || fail "the scripts are world-readable"
run_entrypoint vps
grep -qF 'MTLS_DOMAIN: ""' "$frag" && grep -qF 'CENTRAL_MTLS_DOMAIN: ""' "$frag" && pass "without names the environment is empty" || fail "without names the environment is empty"
# MTLS_CERT_DAYS: digits, at most 100 years, vps only, and it reaches mtls-init's environment.
run_entrypoint vps MTLS_CERT_DAYS=90
grep -qF 'MTLS_CERT_DAYS: "90"' "$frag" && pass "the lifetime reaches mtls-init" || fail "the lifetime reaches mtls-init"
run_entrypoint vps MTLS_CERT_DAYS=0
grep -qF 'MTLS_CERT_DAYS: "0"' "$frag" && pass "0 (unlimited) reaches mtls-init" || fail "0 (unlimited) reaches mtls-init"
run_entrypoint vps
grep -qF 'MTLS_CERT_DAYS: ""' "$frag" && pass "no lifetime: the default is mtls-init's" || fail "no lifetime: the default is mtls-init's"
for bad in abc -1 '1;rm' 36501 123456 '9 9'; do
  if run_entrypoint vps MTLS_CERT_DAYS="$bad"; then fail "refused lifetime: $bad"; else pass "refused lifetime: $bad"; fi
done
if run_entrypoint docker-local MTLS_CERT_DAYS=90; then fail "refused lifetime for docker-local"; else pass "refused lifetime for docker-local"; fi
run_entrypoint docker-local
cmp -s "$work/out.d/compose.fragment.yml" "$tools/compose.fragment.yml.template" && fail "docker-local: version not substituted" || pass "docker-local fragment is the plain template with the version"
[ ! -e "$work/out.d/mtls-init.sh" ] && pass "docker-local gets no vps scripts" || fail "docker-local gets no vps scripts"

rm -rf "${work:?}"
if [ "$failures" -ne 0 ]; then echo "$failures check(s) failed" >&2; exit 1; fi
echo "all entrypoint checks passed"
