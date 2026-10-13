#!/usr/bin/env bash
# Runs the unit tests against throwaway Postgres containers, so a local run needs no set-up and
# cannot collide with another run, another worktree or the stack on :5432.
#
#   scripts/test.sh                       # sbt test
#   scripts/test.sh "auth/testOnly versola.oauth.revoke.RevocationServiceSpec"
#
# Starts one Postgres serving TLS (a server with ssl=on also accepts the plaintext connections most
# specs make) on a free port, points POSTGRES_HOST, POSTGRES_TLS_HOST and POSTGRES_TLS_CA at it,
# runs sbt, and removes it on exit.
# Needs docker, openssl and sbt. Not for e2e: that needs the staged stack (develop.md).
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"
run="versola-test-$$"

free_port() {
  python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

cleanup() { docker rm -f "$run-tls" >/dev/null 2>&1 || true; }
trap cleanup EXIT

tls_port="$(free_port)"

POSTGRES_TLS_PORT="$tls_port" POSTGRES_TLS_CONTAINER="$run-tls" \
  util/implementations/postgres/tls-fixture/start.sh

export POSTGRES_HOST="localhost:$tls_port"
export POSTGRES_TLS_HOST="localhost:$tls_port"
export POSTGRES_TLS_CA="$root/target/postgres-tls/ca.crt"

if [ "$#" -eq 0 ]; then
  sbt test
else
  sbt "$@"
fi
