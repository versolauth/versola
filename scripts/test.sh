#!/usr/bin/env bash
# Runs the unit tests against throwaway Postgres containers, so a local run needs no set-up and
# cannot collide with another run, another worktree or the stack on :5432.
#
#   scripts/test.sh                       # sbt test
#   scripts/test.sh "auth/testOnly versola.oauth.revoke.RevocationServiceSpec"
#
# Starts a plain Postgres (POSTGRES_HOST) and a Postgres serving TLS for PostgresTlsConnectionSpec
# (POSTGRES_TLS_HOST, POSTGRES_TLS_CA) on free ports, runs sbt with them, and removes both on exit.
# Needs docker, openssl and sbt. Not for e2e: that needs the staged stack (develop.md).
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"
run="versola-test-$$"

free_port() {
  python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

cleanup() { docker rm -f "$run-pg" "$run-tls" >/dev/null 2>&1 || true; }
trap cleanup EXIT

pg_port="$(free_port)"
tls_port="$(free_port)"

docker run -d --name "$run-pg" -p "127.0.0.1:$pg_port:5432" \
  -e POSTGRES_DB=auth -e POSTGRES_USER=dev -e POSTGRES_PASSWORD=1234 \
  postgres:18 postgres -c max_connections=200 >/dev/null

POSTGRES_TLS_PORT="$tls_port" POSTGRES_TLS_CONTAINER="$run-tls" \
  util/implementations/postgres/tls-fixture/start.sh

for _ in $(seq 1 30); do
  docker exec "$run-pg" pg_isready -U dev -d auth >/dev/null 2>&1 && break
  sleep 1
done

export POSTGRES_HOST="localhost:$pg_port"
export POSTGRES_TLS_HOST="localhost:$tls_port"
export POSTGRES_TLS_CA="$root/target/postgres-tls/ca.crt"

if [ "$#" -eq 0 ]; then
  sbt test
else
  sbt "$@"
fi
