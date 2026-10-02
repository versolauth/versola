#!/usr/bin/env bash
#
# Asserts that edge serves /login/central-admin as soon as it is ready on a
# freshly bootstrapped stack.
#
# Before the fix this answered 404 (PresetNotFound) for up to a refresh
# interval after every fresh deploy: central's client cache is built before
# bootstrap seeds central-admin, the seed's change notification fires before
# central listens for it, and the presets edge syncs are filtered by that
# stale client cache -- so edge's first sync had no central-admin preset.
# Bootstrap now reloads those caches before central reports ready.
#
# Has to run right after edge reports ready and before anything forces a
# sync (the e2e suite does), with central's refresh interval lengthened for
# the job: then only that reload can make central-admin visible here, however
# slow the runner is.
#
# Expects the e2e job's central, auth (behind the TLS terminator) and edge,
# started on a fresh database.

set -euo pipefail

EDGE_URL=${EDGE_URL:-http://localhost:9005}

# GET /login/<preset> stores a pending login and redirects to auth: 303 is
# the only right answer. Retried only while nothing answers at all.
code=000
for _ in $(seq 1 10); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$EDGE_URL/login/central-admin" || true)
  [ "$code" != "000" ] && break
  sleep 1
done

if [ "$code" != "303" ]; then
  echo "::error::/login/central-admin answered $code right after a fresh start, want 303"
  echo "--- central.log (tail) ---"
  tail -n 50 /tmp/central.log || true
  echo "--- edge.log (tail) ---"
  tail -n 50 /tmp/edge.log || true
  exit 1
fi
echo "/login/central-admin answered 303 right after a fresh start"
