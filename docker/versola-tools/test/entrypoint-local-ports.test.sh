#!/bin/sh
# Check the emitted docker-local fragment, not just its template: only diagnostics are
# published for the services, and every publication is loopback; then let Compose normalize
# the file and confirm the same. This is not a full Versola bootstrap (CLI#78 covers that).
set -eu

here=$(cd "$(dirname "$0")" && pwd)
tools=$(cd "$here/.." && pwd)
work=$(mktemp -d)
cleanup() { rm -rf "${work:?}"; }
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

mkdir -p "$work/app/central-ui" "$work/bin"
for f in entrypoint.sh compose.fragment.vps.yml.template compose.fragment.yml.template openbao.hcl.vps.template openbao.hcl.template \
  nginx.conf.template proxy_params.conf.template mtls-init.sh step-ca-intermediates.sh postgres-init-schemas.sh release.json; do
  cp "$tools/$f" "$work/app/$f"
done
cat > "$work/bin/java" <<'STUB'
#!/bin/sh
dir=.local/env/$(cat)
mkdir -p "$dir"
for f in auth.conf central.conf edge.conf auth.generated-secrets.env central.generated-secrets.env edge.generated-secrets.env \
  utils.private-key.jwk secrets.schema.json; do : > "$dir/$f"; done
STUB
chmod +x "$work/bin/java"
(cd "$work/app" && env PATH="$work/bin:$PATH" TARGET=docker-local VERSION=9.9.9 OUT_DIR="$work/out" sh ./entrypoint.sh > /dev/null)
frag=$work/out/compose.fragment.yml

# Extract only list entries beneath ports:, so comments, expose: and volume mounts cannot pass.
publications() {
  awk '
    /^  [a-z][a-z-]*:/ { service=$1; sub(/:$/, "", service); ports=0 }
    /^    ports:/ { ports=1; next }
    /^    [a-z]/ { ports=0 }
    ports && /^      - / { binding=$0; sub(/^      - "/, "", binding); sub(/"[[:space:]]*$/, "", binding); print service, binding }
  ' "$1" | sort
}
want=$(printf '%s\n' 'auth 127.0.0.1:8081:8081' 'central 127.0.0.1:8091:8091' \
  'edge 127.0.0.1:8096:8096' 'openbao 127.0.0.1:8200:8200')
got=$(publications "$frag")
[ "$got" = "$want" ] || { printf 'unexpected host publications:\n%s\n' "$got" >&2; exit 1; }
echo 'ok   only diagnostics and OpenBao are published, all on loopback'

# A regression to the old wildcard binding must be detected for each service separately.
for port in 8081 8091 8096; do
  sed "s/127.0.0.1:$port:$port/$port:$port/" "$frag" > "$work/wildcard.yml"
  [ "$(publications "$work/wildcard.yml")" != "$want" ] || { echo "missed wildcard publication on $port" >&2; exit 1; }
done
echo 'ok   wildcard regressions are rejected for all three services'

# `docker compose config` only parses and normalizes the file, so it needs the Compose plugin but no
# daemon. Skip, rather than fail, where the tools are absent (CI has them).
if ! docker compose version >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
  echo 'skip Compose normalization: docker compose and jq are required'
  exit 0
fi
for service in auth central edge; do : > "$work/out/$service.secrets.env"; done
(cd "$work/out" && docker compose -f compose.fragment.yml config --format json) > "$work/compose.json"
jq -e '[.services[].ports[]?] | length == 4 and all(.[]; .host_ip == "127.0.0.1")' "$work/compose.json" >/dev/null
echo 'ok   Compose resolves all four publications to loopback'
echo 'all local port checks passed'
