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
# Two probes, `PROBE=`:
#   authorize  (default) `GET /authorize` of a registered client: one `authorize.request` event with the outcome `success` for every
#              request, with the client `PROBE_CLIENT` (default `shutdown-probe`). The client has to exist, with a redirect URI, a
#              scope and no secret, and the audit log of its tenant has to be on (the default). In a local stack it can be made
#              from the console's bootstrap client, with central and auth stopped, then started again:
#                INSERT INTO oauth_clients SELECT ... FROM oauth_clients WHERE id = 'central-admin' with id = 'shutdown-probe',
#                secret = NULL, scope = '{openid}', require_pushed_authorization_requests = false.
#   coalesced  `GET /authorize?client_id=...` without the other parameters: auth answers 400 and the refusal is counted per client in
#              memory and written as rows with `details.count`; the script sums the counts.
# The script counts the rows of the probe before the load, after auth has exited and `LATER` seconds after that, and compares the
# difference with the number of responses it received (a response is any HTTP status; a refused connection is not one). It also prints
# the exit status of auth and the lines of its log that mention the audit queue or the stop.
#
# Variables (all optional except the Postgres ones, which are the usual libpq ones; `psql` must be on the path):
#   STAGE_DIR     the staged app (default auth/implementations/postgres/target/universal/stage)
#   ENV_PATH      the config of auth (default auth/dev/env.conf)
#   PORT, DPORT, APORT, MPORT   the ports of auth (default 9303, 9304, 9307, 9308)
#   CLIENTS       parallel clients (default 8)
#   WARMUP        seconds of load before SIGTERM (default 3)
#   SLOW_MS       when above 0, every INSERT into auth_audit_event sleeps this many milliseconds (a statement trigger, removed on exit)
#   PROBE, PROBE_CLIENT   see above
#   LATER         seconds after the exit before the table is counted again (default 15)
#   LOG           where the log of auth is written (default /tmp/audit-shutdown-check.log)
set -euo pipefail

STAGE_DIR="${STAGE_DIR:-auth/implementations/postgres/target/universal/stage}"
ENV_PATH="${ENV_PATH:-auth/dev/env.conf}"
PORT="${PORT:-9303}"; DPORT="${DPORT:-9304}"; APORT="${APORT:-9307}"; MPORT="${MPORT:-9308}"
CLIENTS="${CLIENTS:-8}"
WARMUP="${WARMUP:-3}"
SLOW_MS="${SLOW_MS:-0}"
LOG="${LOG:-/tmp/audit-shutdown-check.log}"
PROBE="${PROBE:-authorize}"
PROBE_CLIENT="${PROBE_CLIENT:-shutdown-probe}"
LATER="${LATER:-15}"
CHALLENGE="E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
WORK="$(mktemp -d)"

sql() { psql -X -q -t -A -v ON_ERROR_STOP=1 -c "$1"; }
rows() {
  if [[ "$PROBE" == "coalesced" ]]; then
    sql "SELECT coalesce(sum(coalesce((details->>'count')::bigint, 1)), 0) FROM auth_audit_event WHERE kind = 'authorize.request' AND reason = 'invalid_request'"
  else
    sql "SELECT count(*) FROM auth_audit_event WHERE kind = 'authorize.request' AND outcome = 'success' AND client_id = '$PROBE_CLIENT'"
  fi
}
url() {
  if [[ "$PROBE" == "coalesced" ]]; then echo "http://localhost:$PORT/authorize?client_id=shutdown-check-$1-$RANDOM"
  else echo "http://localhost:$PORT/authorize?client_id=$PROBE_CLIENT&response_type=code&redirect_uri=http://localhost:3000&scope=openid&code_challenge=$CHALLENGE&code_challenge_method=S256&state=$1-$RANDOM"; fi
}

cleanup() {
  if [[ "$SLOW_MS" -gt 0 ]]; then sql "DROP TRIGGER IF EXISTS audit_check_slow ON auth_audit_event; DROP FUNCTION IF EXISTS audit_check_slow()" >/dev/null || true; fi
  [[ -n "${LOAD_PIDS:-}" ]] && kill $LOAD_PIDS 2>/dev/null || true
  [[ -n "${AUTH_PID:-}" ]] && kill -0 "$AUTH_PID" 2>/dev/null && kill "$AUTH_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

if [[ "$SLOW_MS" -gt 0 ]]; then
  sql "CREATE OR REPLACE FUNCTION audit_check_slow() RETURNS trigger AS \$\$ BEGIN PERFORM pg_sleep($SLOW_MS / 1000.0); RETURN NULL; END \$\$ LANGUAGE plpgsql;
       DROP TRIGGER IF EXISTS audit_check_slow ON auth_audit_event;
       CREATE TRIGGER audit_check_slow AFTER INSERT ON auth_audit_event FOR EACH STATEMENT EXECUTE FUNCTION audit_check_slow()" >/dev/null
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
  ( while true; do curl -s -o /dev/null -w '%{http_code}\n' "$(url "$c")" >> "$WORK/codes.$c" 2>/dev/null || true; done ) &
  LOAD_PIDS="$LOAD_PIDS $!"
done
sleep "$WARMUP"

kill -TERM "$AUTH_PID"
STOPPED_AT="$(date +%s)"
SIGTERM_TIME="$(date '+%H:%M:%S')"
set +e
wait "$AUTH_PID"
EXIT_STATUS=$?
set -e
AUTH_PID=""
STOPPED_IN=$(( $(date +%s) - STOPPED_AT ))
kill $LOAD_PIDS 2>/dev/null || true
LOAD_PIDS=""
wait 2>/dev/null || true

# a refused connection is printed by curl as 000: not a response
RESPONSES="$(cat "$WORK"/codes.* | grep -vc '^000$' || true)"
AFTER="$(rows)"
DELTA=$(( AFTER - BEFORE ))
sleep "$LATER"
AFTER_LATER="$(rows)"
DELTA_LATER=$(( AFTER_LATER - BEFORE ))

echo "probe=$PROBE slow_ms=$SLOW_MS clients=$CLIENTS warmup=${WARMUP}s"
echo "responses received:   $RESPONSES"
echo "rows after the exit:   $DELTA   (before $BEFORE, after $AFTER)"
echo "rows ${LATER}s later:      $DELTA_LATER"
echo "auth exited after:    ${STOPPED_IN}s with status $EXIT_STATUS (SIGTERM at $SIGTERM_TIME)"
echo "--- log lines about the audit queue and the stop:"
grep -iE '"message":"[^"]*(audit|shutdown|timed out|interrupt)' "$LOG" | tail -n 12 | cut -c1-300 || echo "(none)"
if [[ "$DELTA_LATER" -ge "$RESPONSES" ]]; then echo "RESULT: rows >= responses"; else echo "RESULT: rows < responses by $(( RESPONSES - DELTA_LATER ))"; exit 2; fi
