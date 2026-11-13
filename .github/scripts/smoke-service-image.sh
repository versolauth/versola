#!/usr/bin/env bash
#
# Runs a built service image (auth, central, edge) the way the deployments run it and checks what the
# unprivileged-user change promises, before the image is pushed:
#
#   * the process is uid/gid 10001, has no Linux capability and cannot gain one (`no-new-privileges`)
#   * the application tree is read-only to it, with the root filesystem mounted read-only
#   * /diagnostics, mounted as a NEW named volume (what compose does), is writable by it: the image creates the
#     directory owned by 10001, so a new volume takes that owner (docker/Dockerfile.*, deploy.md)
#   * the image's own JAVA_OPTS are valid and the JVM starts with them as that user on that filesystem: it opens
#     the GC log in /diagnostics, so a flag that is wrong, or a /diagnostics the user cannot write, fails here
#
# What it does NOT prove: that the service starts. That needs a configuration and a database, which an image build
# does not have; the staged-service e2e covers the application, and a start of the image itself is still a manual
# check (the crypto provider's native library, mapped from /tmp, is the part only a real start exercises).
#
# usage: smoke-service-image.sh <image> [extra-writable-dir ...]   (the edge image passes /var/lib/versola-edge)

set -euo pipefail

image="${1:?usage: smoke-service-image.sh <image> [extra-writable-dir ...]}"
shift
volume="smoke-diagnostics-$$-$RANDOM"
docker volume create "${volume}" >/dev/null
trap 'docker volume rm -f "${volume}" >/dev/null 2>&1 || true' EXIT

# The extra directories are mounted as new volumes as well, for the same reason /diagnostics is.
extra_args=()
extra_volumes=()
for dir in "$@"; do
  v="smoke-extra-$$-$RANDOM"
  docker volume create "${v}" >/dev/null
  extra_volumes+=("${v}")
  extra_args+=(-v "${v}:${dir}")
done
trap 'docker volume rm -f "${volume}" ${extra_volumes[@]+"${extra_volumes[@]}"} >/dev/null 2>&1 || true' EXIT

run() {
  docker run --rm --read-only --tmpfs /tmp:exec,mode=1777 -v "${volume}:/diagnostics" ${extra_args[@]+"${extra_args[@]}"} \
    --cap-drop ALL --security-opt no-new-privileges --entrypoint sh "${image}" -c "$1"
}

failures=()
check() { # check <what> <expected> <actual>
  if [ "$2" != "$3" ]; then failures+=("$1: expected '$2', got '$3'"); fi
}

report="$(run '
  echo "uid=$(id -u)"
  echo "gid=$(id -g)"
  echo "capeff=$(awk "/^CapEff:/ {print \$2}" /proc/self/status)"
  echo "nonewprivs=$(awk "/^NoNewPrivs:/ {print \$2}" /proc/self/status)"
  if touch /app/.smoke 2>/dev/null; then echo "app=writable"; else echo "app=read-only"; fi
  if touch /diagnostics/.smoke 2>/dev/null && rm /diagnostics/.smoke; then echo "diagnostics=writable"; else echo "diagnostics=not-writable"; fi
')"
echo "${report}"

value() { printf '%s\n' "${report}" | sed -n "s/^$1=//p"; }
check "user id" "10001" "$(value uid)"
check "group id" "10001" "$(value gid)"
check "effective capabilities" "0000000000000000" "$(value capeff)"
check "no-new-privileges" "1" "$(value nonewprivs)"
check "application tree" "read-only" "$(value app)"
check "/diagnostics on a new volume" "writable" "$(value diagnostics)"

for dir in "$@"; do
  got="$(run "if touch '${dir}/.smoke' 2>/dev/null && rm '${dir}/.smoke'; then echo writable; else echo not-writable; fi")"
  check "${dir} on a new volume" "writable" "${got}"
done

# The image's own flags, as the entrypoint passes them. -version exits at once, but opens the GC log first.
if jvm="$(run 'java $JAVA_OPTS -version 2>&1 && test -e /diagnostics/gc.log && echo gc-log-written' 2>&1)"; then
  echo "${jvm}" | tail -n 3
  case "${jvm}" in *gc-log-written*) ;; *) failures+=("the JVM started but wrote no GC log to /diagnostics");; esac
else
  echo "${jvm}"
  failures+=("the JVM did not start with the image's JAVA_OPTS as uid 10001 on a read-only root")
fi

if [ "${#failures[@]}" -gt 0 ]; then
  echo "smoke-service-image FAILED for ${image}:" >&2
  printf '  - %s\n' "${failures[@]}" >&2
  exit 1
fi
echo "smoke-service-image: ok (${image})"
