#!/bin/sh
# Check the emitted docker-local fragment, not just its template: only diagnostics are
# published for the services, and every publication is loopback. With a local Docker daemon,
# exercise those bindings with disposable HTTP listeners, keeping the container-side ports.
# This checks Docker networking, not a full Versola bootstrap (CLI#78 covers that).
set -eu

here=$(cd "$(dirname "$0")" && pwd)
tools=$(cd "$here/.." && pwd)
work=$(mktemp -d)
containers=
network=
d() { MSYS_NO_PATHCONV=1 docker "$@"; }
cleanup() {
  for c in $containers; do d rm -f "$c" >/dev/null 2>&1 || true; done
  [ -z "$network" ] || d network rm "$network" >/dev/null 2>&1 || true
  rm -rf "${work:?}"
}
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

# Do not touch a remote daemon or any existing Versola container. Ephemeral host ports avoid
# clashing with a developer's running stack; the host IP and container port come from the output.
docker_local() {
  [ -z "${DOCKER_HOST:-}" ] || return 1
  case "$(d context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" in
    unix://*|npipe://*) d info >/dev/null 2>&1 ;;
    *) return 1 ;;
  esac
}
if ! command -v docker >/dev/null 2>&1 || ! docker_local; then
  echo 'skip Docker networking: no local Docker daemon'
  exit 0
fi
command -v curl >/dev/null 2>&1 || { echo 'curl is required for Docker networking checks' >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo 'jq is required for Compose checks' >&2; exit 1; }
for service in auth central edge; do : > "$work/out/$service.secrets.env"; done
(cd "$work/out" && d compose -f compose.fragment.yml config --format json) > "$work/compose.json"
jq -e '[.services[].ports[]?] | length == 4 and all(.[]; .host_ip == "127.0.0.1")' "$work/compose.json" >/dev/null
echo 'ok   Compose resolves all four publications to loopback'
image=busybox:1.37@sha256:a7dbc319b1438115a207e11930dc4bb3e643dcb3ec0bdd694bb36cd8fad289ec
network=$(d network create "versola-local-ports-$$")
for service in auth central edge; do
  binding=$(printf '%s\n' "$got" | awk -v s="$service" '$1 == s {print $2}')
  host=${binding%%:*}
  port=${binding##*:}
  c=$(d run -d --network "$network" --network-alias "$service" -p "$host::$port" "$image" \
    sh -c 'mkdir -p /www; echo ready > /www/readiness; exec httpd -f -p "$1" -h /www' sh "$port")
  containers="$containers $c"
  addr=$(d port "$c" "$port/tcp" | tr -d '\r')
  case "$addr" in 127.0.0.1:*) ;; *) echo "unexpected Docker binding: $addr" >&2; exit 1 ;; esac
  # Wait for httpd, with a bound, before checking the host and peer-container paths.
  ready=
  for _ in $(seq 1 30); do
    if [ "$(curl --noproxy '*' -fsS --max-time 2 "http://$addr/readiness" 2>/dev/null || true)" = ready ]; then ready=1; break; fi
    sleep 1
  done
  [ -n "$ready" ] || { echo "$service: host readiness failed" >&2; exit 1; }
  [ "$(d run --rm --network "$network" "$image" wget -q -T 5 -O - "http://$service:$port/readiness")" = ready ] \
    || { echo "$service: peer-container readiness failed" >&2; exit 1; }
  echo "ok   $service: loopback HTTP works and the container listener is reachable from its peer"
done
echo 'all local port checks passed'
