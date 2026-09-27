#!/usr/bin/env bash
#
# Asserts that auth survives being started before central (versolauth/versola#378).
#
# Nothing orders the two: a Helm install schedules every service at once, and
# `docker compose up` starts them together. auth syncs its configuration from
# central while building its own dependencies, so on a cold start it reliably
# loses that race. What it must do then is wait -- alive, not ready -- and
# serve once central appears, without ever exiting.
#
# Three separate things have to hold for that, and only the last one is what
# the issue reported:
#
#   1. /liveness answers while auth is still waiting. The diagnostics server
#      is started before `dependencies.build` for exactly this reason: until
#      it is listening, a liveness probe cannot tell a service waiting for
#      central from a dead one, and the kubelet kills the pod a few failures
#      in -- whatever the retry budget says.
#   2. /readiness stays false for that whole time. Readiness means the caches
#      hold data, not that the process is up; loosening it to fix (1) would
#      put traffic on an auth that cannot serve it.
#   3. the process is still there afterwards, and becomes ready once central
#      comes up, having never exited (RESTARTS stays 0).
#
# Against the pre-fix build this fails at (1) -- nothing is listening at all --
# and again at (3), where auth had already exited with code 1 after ~22s.
#
# Expects a staged auth/central (sbt "*-postgres-impl/stage"), the generated
# dev configs (scripts/gen-env.scala, target `local`), a reachable Postgres,
# and no central already running on the port auth is configured to call.

set -euo pipefail
cd "$(dirname "$0")/../.."

AUTH_BIN=${AUTH_BIN:-auth/implementations/postgres/target/universal/stage/bin/auth-postgres-impl}
CENTRAL_BIN=${CENTRAL_BIN:-central/implementations/postgres/target/universal/stage/bin/central-postgres-impl}
AUTH_CONF=${AUTH_CONF:-auth/dev/env.conf}
CENTRAL_CONF=${CENTRAL_CONF:-central/dev/env.conf}

# Deliberately not the ports the e2e steps use, so this can run before them
# without leaving anything of its own behind on a port they need.
AUTH_PORT=${AUTH_PORT:-9013}
AUTH_DPORT=${AUTH_DPORT:-9014}
AUTH_APORT=${AUTH_APORT:-9015}
# central, though, has to answer where auth's generated config already points.
CENTRAL_PORT=${CENTRAL_PORT:-9001}
CENTRAL_DPORT=${CENTRAL_DPORT:-9002}

# Long enough to be past both deadlines the old build died on: its own ~22s
# cache-retry budget, and the ~40s a default liveness probe takes to give up.
SURVIVE_SECONDS=${SURVIVE_SECONDS:-45}
LIVENESS_DEADLINE=${LIVENESS_DEADLINE:-30}
READY_DEADLINE=${READY_DEADLINE:-120}

AUTH_LOG=/tmp/startup-ordering-auth.log
CENTRAL_LOG=/tmp/startup-ordering-central.log

auth_pid=""
central_pid=""
errors=0
checked=0

# Returns only once both are really gone. central here binds the same port the e2e steps that
# follow start their own central on, and a SIGTERM it is still acting on would hand those steps
# an address already in use.
cleanup() {
  local pid
  for pid in "$central_pid" "$auth_pid"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  done
  for pid in "$central_pid" "$auth_pid"; do
    [ -n "$pid" ] || continue
    for _ in $(seq 1 20); do process_alive "$pid" || break; sleep 1; done
    if process_alive "$pid"; then kill -9 "$pid" 2>/dev/null || true; fi
  done
}
trap cleanup EXIT

fail() {
  echo "    $1" >&2
  errors=$((errors + 1))
}

check() {
  local name="$1" before="$errors"
  checked=$((checked + 1))
  "$2"
  if [ "$errors" -eq "$before" ]; then echo "PASS  ${name}"; else echo "FAIL  ${name}"; fi
}

# `kill -0` is not enough on its own: nothing here reaps its children, so a service that has
# exited stays visible as a zombie and answers it. Against the pre-fix build that turned "auth
# died waiting for central" -- the whole point of this check -- into a confusing complaint about
# /readiness instead.
process_alive() {
  local pid="$1" state
  kill -0 "$pid" 2>/dev/null || return 1
  state="$(sed -n 's/^State:[[:space:]]*\([A-Z]\).*/\1/p' "/proc/$pid/status" 2>/dev/null)"
  [ -n "$state" ] && [ "$state" != "Z" ]
}

# curl already prints 000 for a connection it could not make, and exits non-zero saying so;
# the fallback is only for the case where it prints nothing at all.
status_of() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$1" 2>/dev/null || true)"
  echo "${code:-000}"
}

# The whole premise is that central is not answering yet. Running this against
# a live central would pass every check below without testing anything.
central_url="$(sed -n 's/.*url *= *"\(http[^"]*\)".*/\1/p' "$AUTH_CONF" | head -1)"
if [ -z "$central_url" ]; then
  echo "could not read central's URL out of $AUTH_CONF" >&2
  exit 1
fi
if [ "$(status_of "$central_url/readiness")" != "000" ]; then
  echo "something is already answering at $central_url; start this check with central down" >&2
  exit 1
fi

echo "Starting auth with central down ($central_url)..."
PORT=$AUTH_PORT DPORT=$AUTH_DPORT APORT=$AUTH_APORT RUN_MIGRATIONS=true \
  "$AUTH_BIN" -Denv.path="$AUTH_CONF" > "$AUTH_LOG" 2>&1 &
auth_pid=$!
started_at=$(date +%s)

liveness_answers_while_waiting() {
  for _ in $(seq 1 "$LIVENESS_DEADLINE"); do
    [ "$(status_of "http://localhost:$AUTH_DPORT/liveness")" = "200" ] && return 0
    process_alive "$auth_pid" || { fail "auth exited before /liveness ever answered"; tail -30 "$AUTH_LOG" >&2; return; }
    sleep 1
  done
  fail "/liveness did not answer within ${LIVENESS_DEADLINE}s while auth waited for central"
  tail -30 "$AUTH_LOG" >&2
}

readiness_is_false_while_waiting() {
  local code
  code="$(status_of "http://localhost:$AUTH_DPORT/readiness")"
  [ "$code" = "503" ] || fail "/readiness answered $code while the caches were still empty; expected 503"
}

the_wait_is_logged() {
  grep -q "Couldn't initialize cache" "$AUTH_LOG" \
    || fail "auth never logged a failed cache load; it may not have reached the sync at all"
  grep -q "Still waiting for the source of cache" "$AUTH_LOG" \
    || fail "auth logged no further attempts; the initial load is not being retried"
}

auth_is_still_running() {
  local elapsed
  elapsed=$(( $(date +%s) - started_at ))
  [ "$elapsed" -lt "$SURVIVE_SECONDS" ] && sleep $(( SURVIVE_SECONDS - elapsed ))
  process_alive "$auth_pid" \
    || { fail "auth exited while waiting for central (this is #378)"; tail -30 "$AUTH_LOG" >&2; return; }
  case "$(status_of "http://localhost:$AUTH_DPORT/readiness")" in
    503) ;;
    000) fail "auth is running but nothing answers on its diagnostics port ${SURVIVE_SECONDS}s in" ;;
    *) fail "auth reports ready after ${SURVIVE_SECONDS}s with central still down" ;;
  esac
}

becomes_ready_once_central_appears() {
  PORT=$CENTRAL_PORT DPORT=$CENTRAL_DPORT RUN_MIGRATIONS=true \
    "$CENTRAL_BIN" -Denv.path="$CENTRAL_CONF" > "$CENTRAL_LOG" 2>&1 &
  central_pid=$!

  for _ in $(seq 1 "$READY_DEADLINE"); do
    if [ "$(status_of "http://localhost:$AUTH_DPORT/readiness")" = "200" ]; then
      # The point of the whole exercise: one process, from before central
      # existed to serving traffic, with no exit in between.
      process_alive "$auth_pid" || fail "auth became ready but is no longer the process we started"
      return 0
    fi
    process_alive "$auth_pid" || { fail "auth exited before becoming ready"; tail -30 "$AUTH_LOG" >&2; return; }
    process_alive "$central_pid" || { fail "central exited during the check"; tail -30 "$CENTRAL_LOG" >&2; return; }
    sleep 1
  done
  fail "auth did not become ready within ${READY_DEADLINE}s of central coming up"
  tail -30 "$AUTH_LOG" >&2
}

check "/liveness answers while auth waits for central" liveness_answers_while_waiting
check "/readiness stays false while the caches are empty" readiness_is_false_while_waiting
check "auth is still running ${SURVIVE_SECONDS}s in, not restarted" auth_is_still_running
# After the wait above, not before it: the first attempt fails within a second of start, but
# the line that proves this is a *wait* and not one slow attempt is only written by the second.
check "the wait is visible in the log" the_wait_is_logged
check "auth becomes ready once central appears" becomes_ready_once_central_appears

echo
if [ "$errors" -ne 0 ]; then
  echo "FAIL: ${errors} of ${checked} startup-ordering checks failed" >&2
  exit 1
fi
echo "OK: all ${checked} startup-ordering checks passed."
