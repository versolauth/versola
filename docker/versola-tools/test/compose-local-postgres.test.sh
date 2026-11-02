#!/bin/sh
# docker-local's Postgres keeps its data across `versola down` and `versola up` (versolauth/versola#891).
# `down` removes the container; a database that lives in the image's anonymous volume is gone with it,
# and the next `up` initialises an empty cluster ("Schema central doesn't exist" in central).
#
# Two levels, plain POSIX sh:
#   1. the shipped compose template mounts a managed volume at /var/lib/postgresql, sets a PGDATA
#      without a major version in it, and declares the volume (not `external`, so `down --volumes`
#      and `versola uninstall` delete it);
#   2. with docker: a database written through exactly that mount and PGDATA survives the container
#      being removed and a new one started on the same volume. Skipped, and said so, without docker.
#
#   sh docker/versola-tools/test/compose-local-postgres.test.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
tpl=$(cd "$here/.." && pwd)/compose.fragment.yml.template
failures=0

pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }

# The postgres service: from "  postgres:" to the next service at the same indentation.
svc=$(awk '/^  postgres:/{p=1; print; next} p && /^  [a-z][a-z-]*:/{exit} p' "$tpl")
[ -n "$svc" ] || { echo "no postgres service in $tpl" >&2; exit 1; }

mount=$(printf '%s\n' "$svc" | sed -n 's/^ *- \(versola-postgres-data-local:\/var\/lib\/postgresql\) *$/\1/p')
pgdata=$(printf '%s\n' "$svc" | sed -n 's/^ *PGDATA: *\(\/var\/lib\/postgresql\/[^ ]*\) *$/\1/p')

[ -n "$mount" ] && pass "the data volume is mounted at /var/lib/postgresql" || fail "the data volume is mounted at /var/lib/postgresql"
[ "$pgdata" = "/var/lib/postgresql/data" ] && pass "PGDATA has no major version in it" || fail "PGDATA has no major version in it (got: ${pgdata:-none})"

# The top-level declaration, and that it is a managed volume.
decl=$(awk '/^volumes:/{v=1; next} v && /^[^ #]/{exit} v' "$tpl" | awk '/^  versola-postgres-data-local:/{d=1; print; next} d && /^  [^ #]/{exit} d')
[ -n "$decl" ] && pass "the volume is declared" || fail "the volume is declared"
printf '%s\n' "$decl" | grep -q 'external' && fail "the volume is not external (down --volumes must delete it)" || pass "the volume is not external"

# Level 2: the database really survives a container being replaced.
if [ -z "$mount" ] || [ -z "$pgdata" ]; then
  echo "skip persistence check: the template has no data mount (reported above)"
elif ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "skip persistence check: no docker daemon"
else
  vol="versola-pg-local-test-$$"
  name="versola-pg-local-test-$$"
  image=$(printf '%s\n' "$svc" | sed -n 's/^ *image: *\(postgres:[^ ]*\) *$/\1/p')
  cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; docker volume rm -f "$vol" >/dev/null 2>&1 || true; }
  trap cleanup EXIT
  start() {
    docker run -d --name "$name" -e POSTGRES_PASSWORD=t -e POSTGRES_USER=versola -e POSTGRES_DB=auth \
      -e "PGDATA=$pgdata" -v "$vol:/var/lib/postgresql" "$image" >/dev/null
    i=0
    until docker exec "$name" pg_isready -U versola -d auth >/dev/null 2>&1; do
      i=$((i + 1)); [ "$i" -lt 60 ] || { echo "postgres did not become ready" >&2; docker logs "$name" >&2; return 1; }
      sleep 1
    done
    # The entrypoint's temporary server answers pg_isready before the real one is up: ask for a query.
    i=0
    until docker exec "$name" psql -U versola -d auth -Atc 'select 1' >/dev/null 2>&1; do
      i=$((i + 1)); [ "$i" -lt 30 ] || return 1
      sleep 1
    done
  }
  start && pass "postgres starts on the volume" || fail "postgres starts on the volume"
  docker exec "$name" psql -U versola -d auth -v ON_ERROR_STOP=1 -c 'create schema central; create table central.t(v int); insert into central.t values (42);' >/dev/null \
    && pass "data is written" || fail "data is written"
  docker rm -f "$name" >/dev/null   # what `versola down` does to the container
  start && pass "a new container starts on the same volume" || fail "a new container starts on the same volume"
  got=$(docker exec "$name" psql -U versola -d auth -Atc 'select v from central.t' 2>/dev/null || true)
  [ "$got" = "42" ] && pass "the data survived the container being replaced" || fail "the data survived the container being replaced (got: ${got:-nothing})"
fi

if [ "$failures" -ne 0 ]; then echo "$failures check(s) failed" >&2; exit 1; fi
echo "all local postgres checks passed"
