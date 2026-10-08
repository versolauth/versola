#!/usr/bin/env bash
# Starts a TLS-enabled Postgres on localhost:${POSTGRES_TLS_PORT:-5433} for PostgresTlsConnectionSpec
# and leaves its CA at ${POSTGRES_TLS_CA:-target/postgres-tls/ca.crt} (relative to the repo root).
# The server certificate is valid for the DNS name `localhost` only, so connecting to 127.0.0.1
# fails hostname verification -- the spec relies on that.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
here="$root/util/implementations/postgres/tls-fixture"
ca="${POSTGRES_TLS_CA:-$root/target/postgres-tls/ca.crt}"
port="${POSTGRES_TLS_PORT:-5433}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

openssl req -x509 -newkey rsa:2048 -nodes -days 3 -subj "/CN=versola-test-ca" \
  -keyout "$work/ca.key" -out "$work/ca.crt" 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" \
  -keyout "$work/server.key" -out "$work/server.csr" 2>/dev/null
openssl x509 -req -in "$work/server.csr" -CA "$work/ca.crt" -CAkey "$work/ca.key" -CAcreateserial \
  -days 3 -extfile <(printf 'subjectAltName=DNS:localhost') -out "$work/server.crt" 2>/dev/null

mkdir -p "$(dirname "$ca")"
cp "$work/ca.crt" "$ca"
cp "$here/Dockerfile" "$work/Dockerfile"
docker build -q -t versola-postgres-tls "$work" >/dev/null

docker rm -f versola-postgres-tls >/dev/null 2>&1 || true
docker run -d --name versola-postgres-tls -p "$port:5432" \
  -e POSTGRES_DB=auth -e POSTGRES_USER=dev -e POSTGRES_PASSWORD=1234 \
  versola-postgres-tls >/dev/null

for _ in $(seq 1 30); do
  if docker exec versola-postgres-tls pg_isready -U dev -d auth >/dev/null 2>&1; then
    echo "postgres with TLS is up on localhost:$port, CA at $ca"
    exit 0
  fi
  sleep 1
done
echo "postgres with TLS did not become ready" >&2
docker logs versola-postgres-tls >&2
exit 1
