#!/bin/sh
# Runs entrypoint.sh with a stub `java` (no JVM, no gen-env) to check POSTGRES_MODE: `external` (the
# default) must leave the vps bundle as it was before the mode existed, `bundled` must add the
# postgres service, its first-start script and the external data volume, and anything that would
# make the bundled container reachable or misconfigured must be refused before gen-env runs.
# Plain POSIX sh; the last block uses docker only if it is installed:
#
#   sh docker/versola-tools/test/entrypoint-postgres-mode.test.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
tools=$(cd "$here/.." && pwd)
work=$(mktemp -d)
failures=0

pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }

mkdir -p "$work/app/central-ui" "$work/bin"
for f in entrypoint.sh compose.fragment.vps.yml.template compose.fragment.yml.template openbao.hcl.vps.template openbao.hcl.template \
  nginx.conf.template proxy_params.conf.template mtls-init.sh step-ca-intermediates.sh postgres-init-schemas.sh; do
  cp "$tools/$f" "$work/app/$f"
done
# gen-env's output as the entrypoint copies it. The configs carry the postgres block's `user`, which
# is what the entrypoint reads for the container's role; STUB_USER_<service> overrides one service's.
cat > "$work/bin/java" <<'STUB'
#!/bin/sh
dir=.local/env/$(cat)
mkdir -p "$dir"
for s in auth central edge; do
  eval "u=\${STUB_USER_$s:-\${STUB_USER:-versola_app}}"
  printf 'postgres {\n  url = "jdbc:postgresql://127.0.0.1:5432/auth"\n  user = "%s"\n  password = ${POSTGRES_PASSWORD}\n}\n' "$u" > "$dir/$s.conf"
  : > "$dir/$s.generated-secrets.env"
done
for f in utils.private-key.jwk secrets.schema.json; do : > "$dir/$f"; done
printf '%s\n' "${POSTGRES_HOST-unset}" > "$JAVA_HOST_OUT"
STUB
chmod +x "$work/bin/java"

# run_entrypoint [VAR=value ...]: TARGET=vps unless TARGET is given; output in $work/out
run_entrypoint() {
  rm -rf "$work/out.d" "$work/app/.local"
  (cd "$work/app" && env PATH="$work/bin:$PATH" OUT_DIR="$work/out.d" JAVA_HOST_OUT="$work/java.host" VERSION=9.9.9 TARGET=vps \
    AUTH_URL=https://auth.example.com "$@" sh ./entrypoint.sh > "$work/out" 2>&1)
}
refuses() {
  rm -f "$work/java.host"
  if run_entrypoint "$@"; then fail "refused: $*"; elif [ -e "$work/java.host" ]; then fail "refused before gen-env: $*"; else pass "refused: $*"; fi
}
frag=$work/out.d/compose.fragment.yml
has() { grep -qF -- "$1" "$frag"; }

# ---- external: today's output --------------------------------------------------------------------
run_entrypoint POSTGRES_HOST=db.example.com:5432
ext=$(cat "$frag")
run_entrypoint POSTGRES_HOST=db.example.com:5432 POSTGRES_MODE=external
[ "$(cat "$frag")" = "$ext" ] && pass "external is the default" || fail "external is the default"
expected=$(grep -v '^#@PG@' "$tools/compose.fragment.vps.yml.template" | sed -e 's/\${VERSION}/9.9.9/g' -e 's|\${REGISTRY}|ghcr.io/versolauth|g' \
  -e 's/@MTLS_DOMAIN@//g' -e 's/@CENTRAL_MTLS_DOMAIN@//g' -e 's/@MTLS_CERT_DAYS@//g')
[ "$ext" = "$expected" ] && pass "external: the template without its bundled lines, nothing else" || fail "external: the template without its bundled lines"
if grep -q '#@PG@\|@POSTGRES_\|^  postgres:\|postgres.secrets.env\|versola-postgres-data' "$frag"; then
  fail "external: no trace of the bundled service"
else
  pass "external: no trace of the bundled service"
fi
[ ! -e "$work/out.d/postgres-init-schemas.sh" ] && pass "external: no init script in the bundle" || fail "external: no init script in the bundle"
[ "$(cat "$work/java.host")" = "db.example.com:5432" ] && pass "external: POSTGRES_HOST reaches gen-env as given" || fail "external: POSTGRES_HOST reaches gen-env as given"
refuses POSTGRES_MODE=external
grep -q "POSTGRES_HOST is required" "$work/out" && pass "external: POSTGRES_HOST is still required" || fail "external: POSTGRES_HOST is still required"

# ---- bundled -------------------------------------------------------------------------------------
run_entrypoint POSTGRES_MODE=bundled
[ "$(cat "$work/java.host")" = "127.0.0.1:5432" ] && pass "bundled: gen-env gets the loopback address, so the URLs say sslmode=disable" || fail "bundled: gen-env gets the loopback address"
has '  postgres:' && pass "bundled: the postgres service" || fail "bundled: the postgres service"
grep -qE '^    image: postgres:[0-9]+\.[0-9]+@sha256:[0-9a-f]{64}$' "$frag" && pass "bundled: the image is pinned to major.minor and a digest" || fail "bundled: the image is pinned to major.minor and a digest"
has 'listen_addresses=127.0.0.1' && pass "bundled: listens on loopback only" || fail "bundled: listens on loopback only"
if grep -q '^    ports:' "$frag"; then fail "bundled: no published port"; else pass "bundled: no published port"; fi
has 'pg_isready' && has 'restart: unless-stopped' && pass "bundled: healthcheck and restart policy" || fail "bundled: healthcheck and restart policy"
has './postgres.secrets.env' && pass "bundled: the password comes from postgres.secrets.env" || fail "bundled: the password comes from postgres.secrets.env"
has 'POSTGRES_USER: "versola_app"' && has 'POSTGRES_DB: "auth"' && pass "bundled: role and database" || fail "bundled: role and database"
has 'versola-postgres-data-vps:/var/lib/postgresql' && pass "bundled: the data volume is mounted" || fail "bundled: the data volume is mounted"
tail -4 "$frag" | tr -d ' \n' | grep -q 'versola-postgres-data-vps:external:true' && pass "bundled: the data volume is external" || fail "bundled: the data volume is external"
# One `postgres:` entry under depends_on each, followed by its healthy condition: migrate, central, auth, edge.
waits=$(grep -c '^      postgres:$' "$frag" || true)
healthy_after=$(grep -A1 '^      postgres:$' "$frag" | grep -c 'condition: service_healthy' || true)
[ "$waits" = "4" ] && [ "$healthy_after" = "4" ] && pass "bundled: migrate, central, auth and edge wait for the healthcheck" || fail "bundled: migrate, central, auth and edge wait for the healthcheck ($waits, $healthy_after)"
has 'start_period: 30s' && pass "bundled: the healthcheck has a start period" || fail "bundled: the healthcheck has a start period"
if grep -q '#@PG@\|@POSTGRES_\|\${VERSION}' "$frag"; then fail "bundled: no placeholder is left"; else pass "bundled: no placeholder is left"; fi
cmp -s "$tools/postgres-init-schemas.sh" "$work/out.d/postgres-init-schemas.sh" && pass "bundled: the init script is in the bundle" || fail "bundled: the init script is in the bundle"
mode=$(stat -c %a "$work/out.d/postgres-init-schemas.sh" 2>/dev/null || stat -f %Lp "$work/out.d/postgres-init-schemas.sh")
[ "$mode" = 644 ] && pass "bundled: the init script is readable by the container" || fail "bundled: the init script is readable by the container"
for s in auth central edge; do
  grep -q "CREATE SCHEMA IF NOT EXISTS $s AUTHORIZATION" "$tools/postgres-init-schemas.sh" && pass "init script: schema $s" || fail "init script: schema $s"
done
# The password reaches the container through the env_file only: never a value, never a variable of the fragment.
if grep -v '^ *#' "$frag" | grep -qi 'POSTGRES_PASSWORD'; then fail "bundled: no password in the fragment"; else pass "bundled: no password in the fragment"; fi
if grep -qi 'password' "$work/out"; then fail "bundled: nothing secret on stdout"; else pass "bundled: nothing secret on stdout"; fi

run_entrypoint POSTGRES_MODE=bundled POSTGRES_DB=versola_main POSTGRES_HOST=127.0.0.1:5432
has 'POSTGRES_DB: "versola_main"' && pass "bundled: POSTGRES_DB reaches the container" || fail "bundled: POSTGRES_DB reaches the container"
run_entrypoint POSTGRES_MODE=bundled STUB_USER=idp_app
has 'POSTGRES_USER: "idp_app"' && pass "bundled: the role is the one gen-env wrote" || fail "bundled: the role is the one gen-env wrote"
# A reused OUT_DIR must not keep a stale init script once the mode is external again.
rm -rf "$work/out.d" "$work/app/.local"
mkdir -p "$work/out.d"
: > "$work/out.d/postgres-init-schemas.sh"
(cd "$work/app" && env PATH="$work/bin:$PATH" OUT_DIR="$work/out.d" JAVA_HOST_OUT="$work/java.host" VERSION=9.9.9 TARGET=vps \
  AUTH_URL=https://auth.example.com POSTGRES_HOST=db:5432 sh ./entrypoint.sh > "$work/out" 2>&1)
[ ! -e "$work/out.d/postgres-init-schemas.sh" ] && pass "a reused bundle directory loses a stale init script" || fail "a reused bundle directory loses a stale init script"

# ---- refusals ------------------------------------------------------------------------------------
refuses POSTGRES_MODE=embedded POSTGRES_HOST=127.0.0.1:5432
refuses POSTGRES_MODE=Bundled POSTGRES_HOST=127.0.0.1:5432
refuses TARGET=docker-local POSTGRES_MODE=bundled
grep -q "only supported when TARGET=vps" "$work/out" && pass "the message names the target" || fail "the message names the target"
refuses POSTGRES_MODE=bundled POSTGRES_HOST=db.example.com:5432
refuses POSTGRES_MODE=bundled POSTGRES_HOST=0.0.0.0:5432
refuses POSTGRES_MODE=bundled POSTGRES_HOST=127.0.0.1:5433
printf 'x' > "$work/ca.crt"
refuses POSTGRES_MODE=bundled POSTGRES_CA_FILE="$work/ca.crt"
# Not a role name the container can create, or services that disagree on it (checked after gen-env,
# which is where the names are known, so these are refused with no bundle left behind).
long_role=$(printf 'a%.0s' $(seq 1 64))
for bad in 'Idp' '1app' 'a-b' 'a b' 'a"b' "a'b" 'a;b' 'pg_admin' "$long_role"; do
  if run_entrypoint POSTGRES_MODE=bundled STUB_USER="$bad"; then fail "refused role: $bad"
  elif grep -q "is not a plain role name" "$work/out"; then pass "refused role: $bad"
  else fail "refused role for another reason: $bad ($(tail -1 "$work/out"))"; fi
done
run_entrypoint POSTGRES_MODE=bundled STUB_USER="$(printf 'a%.0s' $(seq 1 63))" && pass "a 63-character role is accepted" || fail "a 63-character role is accepted"
if run_entrypoint POSTGRES_MODE=bundled STUB_USER_edge=other_app; then fail "refused: services with different users"; else pass "refused: services with different users"; fi
grep -q "one Postgres user" "$work/out" && pass "the message says the users differ" || fail "the message says the users differ"

# ---- the rendered fragment is valid compose (only where docker is installed) -----------------------
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  run_entrypoint POSTGRES_MODE=bundled
  for f in auth central edge postgres; do : > "$work/out.d/$f.secrets.env"; done
  if (cd "$work/out.d" && docker compose -f compose.fragment.yml config -q > "$work/compose.out" 2>&1); then
    pass "docker compose accepts the bundled fragment"
  else
    fail "docker compose accepts the bundled fragment ($(tail -2 "$work/compose.out" | tr '\n' ' '))"
  fi
  run_entrypoint POSTGRES_HOST=db.example.com:5432
  for f in auth central edge; do : > "$work/out.d/$f.secrets.env"; done
  if (cd "$work/out.d" && docker compose -f compose.fragment.yml config -q > "$work/compose.out" 2>&1); then
    pass "docker compose accepts the external fragment"
  else
    fail "docker compose accepts the external fragment ($(tail -2 "$work/compose.out" | tr '\n' ' '))"
  fi
else
  echo "skip docker compose validation (docker is not installed)"
fi

rm -rf "${work:?}"
if [ "$failures" -ne 0 ]; then echo "$failures check(s) failed" >&2; exit 1; fi
echo "all entrypoint checks passed"
