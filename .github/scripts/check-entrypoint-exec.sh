#!/usr/bin/env bash
#
# Asserts that the ENTRYPOINT of the auth, central and edge images hands PID 1 to the JVM with
# `exec` (versolauth/versola#622, fix in #454).
#
# Why this exists: the entrypoint is `/bin/sh -c "..."`. Without `exec` the shell stays PID 1 and
# swallows SIGTERM, so `docker stop` waits out the whole grace period and kills the JVM with
# exit code 137 -- no graceful shutdown, no drained connections. Nothing else notices: the image
# still builds and the service still starts.

set -euo pipefail
cd "$(dirname "$0")/../.."

status=0
for service in auth central edge; do
  file="docker/Dockerfile.$service"
  entrypoint=$(grep -E '^ENTRYPOINT ' "$file" || true)
  if [[ -z "$entrypoint" ]]; then
    echo "FAIL: $file has no ENTRYPOINT line"
    status=1
  elif ! grep -qE '"-c", "exec /app/bin/' <<<"$entrypoint"; then
    echo "FAIL: $file ENTRYPOINT must start the application with exec (so the JVM is PID 1 and gets SIGTERM):"
    echo "  $entrypoint"
    status=1
  else
    echo "ok: $file"
  fi
done
exit $status
