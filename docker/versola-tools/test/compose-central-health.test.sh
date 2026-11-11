#!/bin/sh
# auth and edge wait for central to be healthy, not merely started (versolauth/versola#704).
#
# Two levels, plain POSIX sh:
#   1. both shipped compose templates (docker-local and vps) give central a healthcheck that asks its
#      diagnostics port for /readiness, and auth and edge depend on central with `service_healthy`;
#   2. with docker: the probe itself runs in the JRE image central ships on (no curl there: it is bash's
#      /dev/tcp), on a port other than the default so that following DPORT is what is proved -- it fails
#      while nothing listens and succeeds against a 200 on /readiness. Skipped, and
#      said so, without docker.
#
#   sh docker/versola-tools/test/compose-central-health.test.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
dir=$(cd "$here/.." && pwd)
failures=0

pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }

# One service: from "  <name>:" to the next service at the same indentation.
service() { awk -v n="  $2:" '$0 ~ "^"n"[ \r]*$" {p=1; print; next} p && /^  [a-z][a-z-]*:/{exit} p' "$1"; }

for t in compose.fragment.yml.template compose.fragment.vps.yml.template; do
  tpl="$dir/$t"
  central=$(service "$tpl" central)
  [ -n "$central" ] || { fail "$t has a central service"; continue; }
  printf '%s\n' "$central" | grep -q 'healthcheck:' && pass "$t: central has a healthcheck" || fail "$t: central has a healthcheck"
  printf '%s\n' "$central" | grep -qF '/dev/tcp/127.0.0.1/$${DPORT:-8091}' && printf '%s\n' "$central" | grep -q 'GET /readiness' \
    && pass "$t: the healthcheck asks the container's DPORT for /readiness" || fail "$t: the healthcheck asks the container DPORT for /readiness"
  for s in auth edge; do
    svc=$(service "$tpl" "$s")
    cond=$(printf '%s\n' "$svc" | awk '/^    depends_on:/{d=1; next} d && /^    [a-z]/{exit} d && /^      central:/{c=1; next} c{print; exit}' | tr -d '\r ')
    [ "$cond" = "condition:service_healthy" ] && pass "$t: $s waits for central to be healthy" \
      || fail "$t: $s waits for central to be healthy (got: ${cond:-no central dependency})"
  done
done

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  image=$(sed -n 's/^FROM \(eclipse-temurin:[^ ]*-jre@[^ ]*\).*/\1/p' "$dir/../Dockerfile.central" | tail -1)
  # The test line of the healthcheck, joined back into one command.
  probe=$(service "$dir/compose.fragment.yml.template" central | awk '/^        - >-/{p=1; next} p && /^      [a-z_]+:/{exit} p' | tr -d '\r' | sed 's/^ *//' | tr '\n' ' ' | sed 's/[$][$]/$/g')
  # `printf '...\r\n'` in the template is the text \r\n, which is what the shell's printf expands.
  if [ -z "$image" ] || [ -z "$probe" ]; then
    fail "the probe and the image can be read from the templates"
  else
    name="central-health-test-$$"
    cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
    trap cleanup EXIT
    # Nothing listening: the probe must fail.
    docker run --rm -e DPORT=28091 "$image" bash -c "$probe" >/dev/null 2>&1 && fail "the probe fails while nothing listens" || pass "the probe fails while nothing listens"
    # A server that answers 200 for /readiness (the JDK's file server over a directory holding that file).
    docker run -d --name "$name" -e DPORT=28091 "$image" sh -c 'mkdir /tmp/w && echo ok > /tmp/w/readiness && exec java -m jdk.httpserver -b 127.0.0.1 -p 28091 -d /tmp/w' >/dev/null
    i=0
    until docker exec "$name" bash -c "$probe" >/dev/null 2>&1; do
      i=$((i + 1)); [ "$i" -lt 30 ] || break
      sleep 1
    done
    [ "$i" -lt 30 ] && pass "the probe succeeds against a 200 on /readiness" || fail "the probe succeeds against a 200 on /readiness"
    # And a 404 must not pass.
    docker exec "$name" bash -c "$(printf '%s' "$probe" | sed 's#/readiness#/nothing#')" >/dev/null 2>&1 \
      && fail "the probe fails against a 404" || pass "the probe fails against a 404"
  fi
else
  echo "skip probe check: no docker daemon"
fi

if [ "$failures" -ne 0 ]; then echo "$failures check(s) failed" >&2; exit 1; fi
echo "all central health checks passed"
