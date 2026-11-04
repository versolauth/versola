#!/usr/bin/env bash
#
# Starts the staged `auth` on a Postgres of your own, sends it requests that each produce one audit event, stops it with SIGTERM
# in the middle of the load, and prints how many responses the client got and how many rows of that event the table holds. It is
# for local, manual runs; CI does not run it.
#
# How to run it (from the repository root):
#   sbt auth-postgres-impl/stage
#   PGHOST=localhost PGPORT=5482 PGUSER=dev PGPASSWORD=... PGDATABASE=auth scripts/audit-shutdown-check.sh
#
# The request is `GET /authorize?client_id=...` without the other parameters: auth answers 400 and records one
# `authorize.request` event with the reason `invalid_request`, which is not coalesced with any other. The script counts those rows
# before the load and after the stop, and compares the difference with the number of responses it received (a response is any
# HTTP status; a refused connection is not one). It also prints the lines of the log that mention the audit queue on shutdown.
#
# Variables (all optional except the Postgres ones, which are the usual libpq ones; `psql` must be on the path):
#   STAGE_DIR     the staged app (default auth/implementations/postgres/target/universal/stage)
#   ENV_PATH      the config of auth (default auth/dev/env.conf)
#   PORT, DPORT, APORT, MPORT   the ports of auth (default 9303, 9304, 9307, 9308)
#   CLIENTS       parallel clients (default 8)
#   WARMUP        seconds of load before SIGTERM (default 3)
#   SLOW_MS       when above 0, every INSERT into audit_event sleeps this many milliseconds (a statement trigger, removed on exit)
#   LOG           where the log of auth is written (default /tmp/audit-shutdown-check.log)
set -euo pipefail

STAGE_DIR="${STAGE_DIR:-auth/implementations/postgres/target/universal/stage}"
ENV_PATH="${ENV_PATH:-auth/dev/env.conf}"
PORT="${PORT:-9303}"; DPORT="${DPORT:-9304}"; APORT="${APORT:-9307}"; MPORT="${MPORT:-9308}"
CLIENTS="${CLIENTS:-8}"
WARMUP="${WARMUP:-3}"
SLOW_MS="${SLOW_MS:-0}"
LOG="${LOG:-/tmp/audit-shutdown-check.log}"
WORK="$(mktemp -d)"

sql() { psql -X -q -t -A -v ON_ERROR_STOP=1 -c "$1"; }
rows() { sql "SELECT count(*) FROM audit_event WHERE kind = 'authorize.request' AND reason = 'invalid_request'"; }

cleanup() {
  if [[ "$SLOW_MS" -gt 0 ]]; then sql "DROP TRIGGER IF EXISTS audit_check_slow ON audit_event; DROP FUNCTION IF EXISTS audit_check_slow()" >/dev/null || true; fi
  [[ -n "${LOAD_PIDS:-}" ]] && kill $LOAD_PIDS 2>/dev/null || true
  [[ -n "${AUTH_PID:-}" ]] && kill -0 "$AUTH_PID" 2>/dev/null && kill "$AUTH_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

if [[ "$SLOW_MS" -gt 0 ]]; then
  sql "CREATE OR REPLACE FUNCTION audit_check_slow() RETURNS trigger AS \$\$ BEGIN PERFORM pg_sleep($SLOW_MS / 1000.0); RETURN NULL; END \$\$ LANGUAGE plpgsql;
       DROP TRIGGER IF EXISTS audit_check_slow ON audit_event;
       CREATE TRIGGER audit_check_slow AFTER INSERT ON audit_event FOR EACH STATEMENT EXECUTE FUNCTION audit_check_slow()" >/dev/null
fi

HTTP_CLIENT_POOL_SIZE=10 PORT="$PORT" DPORT="$DPORT" APORT="$APORT" MPORT="$MPORT" RUN_MIGRATIONS=true \
  nohup "$STAGE_DIR/bin/auth-postgres-impl" "-Denv.path=$ENV_PATH" > "$LOG" 2>&1 &
AUTH_PID=$!
for _ in $(seq 1 90); do
  curl -sf "http://localhost:$DPORT/readiness" >/dev/null 2>&1 && break
  sleep 2
done
curl -sf "http://localhost:$DPORT/readiness" >/dev/null || { echo "auth did not become ready; see $LOG"; exit 1; }

BEFORE="$(rows)"

LOAD_PIDS=""
for c in $(seq 1 "$CLIENTS"); do
  ( while true; do curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:$PORT/authorize?client_id=shutdown-check-$c-$RANDOM" >> "$WORK/codes.$c" 2>/dev/null || true; done ) &
  LOAD_PIDS="$LOAD_PIDS $!"
done
sleep "$WARMUP"

kill -TERM "$AUTH_PID"
STOPPED_AT="$(date +%s)"
SIGTERM_TIME="$(date '+%H:%M:%S')"
while kill -0 "$AUTH_PID" 2>/dev/null; do sleep 0.2; done
STOPPED_IN=$(( $(date +%s) - STOPPED_AT ))
kill $LOAD_PIDS 2>/dev/null || true
LOAD_PIDS=""
wait 2>/dev/null || true

# a refused connection is printed by curl as 000: not a response
RESPONSES="$(cat "$WORK"/codes.* | grep -vc '^000$' || true)"
AFTER="$(rows)"
DELTA=$(( AFTER - BEFORE ))

echo "slow_ms=$SLOW_MS clients=$CLIENTS warmup=${WARMUP}s"
echo "responses received:   $RESPONSES"
echo "rows written:         $DELTA   (before $BEFORE, after $AFTER)"
echo "auth exited after:    ${STOPPED_IN}s (SIGTERM at $SIGTERM_TIME)"
echo "--- log lines about the audit queue on shutdown:"
grep -E "audit queue was drained|not written before the service stopped" "$LOG" || echo "(none)"
if [[ "$DELTA" -ge "$RESPONSES" ]]; then echo "RESULT: rows >= responses"; else echo "RESULT: rows < responses by $(( RESPONSES - DELTA ))"; exit 2; fi
