#!/bin/sh
# Runs step-ca-init.sh against throwaway CAs and asserts that nothing secret reaches its output or
# step-ca's (versolauth/versola#890): on a fresh volume, on a restart, and on a volume the image's
# own init made before this script existed, which must be left exactly as it is -- and then that the
# fragments' lines removing the `versola` provisioner, whose password that init printed, work on it. Needs `step` and
# `step-ca`, so on a machine without them the script re-runs itself in a smallstep/step-ca
# container (no volumes, no ports, removed on exit):
#
#   sh docker/versola-tools/test/step-ca-init.test.sh
#
# Plain POSIX sh; the image is the one both compose fragments use.
set -eu

here=$(cd "$(dirname "$0")" && pwd)

if ! command -v step-ca >/dev/null 2>&1; then
  exec docker run --rm --name "versola-step-ca-init-test-$$" \
    -v "$here/..":/scripts:ro --entrypoint /bin/sh \
    smallstep/step-ca:0.28.4 /scripts/test/step-ca-init.test.sh
fi

scripts=$(cd "$here/.." && pwd)
work=$(mktemp -d)
# A directory of its own per CA, never the image's /home/step: the last case runs the image's
# entrypoint, which works on STEPPATH.
export STEP_HOME="$work/ca"
export DOCKER_STEPCA_INIT_NAME="Versola Client CA" DOCKER_STEPCA_INIT_DNS_NAMES=localhost,127.0.0.1 \
  DOCKER_STEPCA_INIT_PROVISIONER_NAME=versola DOCKER_STEPCA_INIT_ADDRESS=127.0.0.1:9000
failures=0

pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }
assert() { # assert <description> <command...>
  desc=$1; shift
  if "$@" >/dev/null 2>&1; then pass "$desc"; else fail "$desc"; fi
}
refute() {
  desc=$1; shift
  if "$@" >/dev/null 2>&1; then fail "$desc"; else pass "$desc"; fi
}
mode() { stat -c %a "$1"; }
# modes: every path under the CA with its mode, but the password, which is the one we tighten.
modes() { (cd "$STEP_HOME" && find . ! -path ./secrets/password | sort | while read -r f; do echo "$(mode "$f") $f"; done); }
fp() { step certificate fingerprint "$STEP_HOME/certs/root_ca.crt"; }
# leaks <file>: the output holds the CA's password, any 40-character alphanumeric word (the length
# both passwords are generated with; fingerprints are 64 hex characters, so they do not match) or
# the image's announcement. The provisioner's password is gone by then, so its shape stands for it.
leaks() {
  grep -qF -- "$(cat "$STEP_HOME/secrets/password")" "$1" && return 0
  tr -c 'A-Za-z0-9' '\n' <"$1" | grep -qxE '[A-Za-z0-9]{40}' && return 0
  grep -qi 'administrative password' "$1"
}

# run_ca <outfile>: start step-ca as the compose fragments do, wait until it is healthy, stop it.
# Proves the password file opens the keys, and gives the server's own output to `leaks`.
run_ca() {
  step-ca --password-file "$STEP_HOME/secrets/password" "$STEP_HOME/config/ca.json" >"$1" 2>&1 &
  pid=$!
  ok=1
  for _ in $(seq 1 50); do
    if step ca health --ca-url https://127.0.0.1:9000 --root "$STEP_HOME/certs/root_ca.crt" >/dev/null 2>&1; then ok=0; break; fi
    sleep 0.2
  done
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  return $ok
}

# --- a fresh volume ------------------------------------------------------------------------------
sh "$scripts/step-ca-init.sh" >"$work/init1.out" 2>&1 && pass "a fresh CA is created" || fail "a fresh CA is created"
assert "ca.json is written" test -f "$STEP_HOME/config/ca.json"
assert "the CA's password is not empty" test -s "$STEP_HOME/secrets/password"
assert "the CA's password is private" test "$(mode "$STEP_HOME/secrets/password")" = 600
modes >"$work/modes.script"
refute "the provisioner's password is not left behind" test -e "$STEP_HOME/secrets/.provisioner_password"
refute "no temporary password file is left behind" test -e "$STEP_HOME/secrets/password.new"
assert "it says it created the CA" grep -qx 'step-ca: created the CA' "$work/init1.out"
refute "the first start prints nothing secret" leaks "$work/init1.out"
assert "the CA starts with the password file" run_ca "$work/ca1.out"
refute "the CA prints nothing secret" leaks "$work/ca1.out"
assert "the provisioner exists" grep -qF '"name": "versola"' "$STEP_HOME/config/ca.json"

# --- a restart: nothing is touched ---------------------------------------------------------------
before="$(fp) $(cat "$STEP_HOME/secrets/password")"
sh "$scripts/step-ca-init.sh" >"$work/init2.out" 2>&1 && pass "a restart succeeds" || fail "a restart succeeds"
assert "a restart prints nothing" test ! -s "$work/init2.out"
[ "$before" = "$(fp) $(cat "$STEP_HOME/secrets/password")" ] && pass "a restart keeps the root and the password" \
  || fail "a restart keeps the root and the password"
assert "the CA starts again" run_ca "$work/ca2.out"
refute "the restarted CA prints nothing secret" leaks "$work/ca2.out"

# --- a start cut off before `step ca init`: the password already written is used -----------------
rm -rf "$STEP_HOME"
mkdir -p "$STEP_HOME/secrets"
(umask 077; echo "sentinel-$$-0123456789abcdefghijklmnopqrstuv" >"$STEP_HOME/secrets/password")
sh "$scripts/step-ca-init.sh" >"$work/init3.out" 2>&1 && pass "a start after a cut-off one succeeds" || fail "a start after a cut-off one succeeds"
assert "it keeps the password written before" grep -qx "sentinel-$$-0123456789abcdefghijklmnopqrstuv" "$STEP_HOME/secrets/password"
refute "the sentinel password is not printed" grep -qF "sentinel-$$" "$work/init3.out"
assert "the keys open with it" run_ca "$work/ca3.out"
refute "the CA does not print the sentinel" grep -qF "sentinel-$$" "$work/ca3.out"

# --- missing configuration fails before anything is written --------------------------------------
rm -rf "$STEP_HOME"
refute "an empty CA name is refused" env DOCKER_STEPCA_INIT_NAME= sh "$scripts/step-ca-init.sh"
refute "and nothing is written" test -e "$STEP_HOME"

# --- a volume the image's own init made (every deployment before this script) --------------------
# The image prints the provisioner's password: that is the bug, asserted here so this test notices
# if a step-ca upgrade changes it. Then step-ca-init.sh must leave that CA exactly as it is.
rm -rf "$STEP_HOME"
mkdir -p "$STEP_HOME"
STEPPATH=$STEP_HOME PWDPATH=$STEP_HOME/secrets/password /bin/bash /entrypoint.sh true >"$work/image.out" 2>&1
assert "the image's own init prints a password (why this script exists)" grep -qi 'administrative password' "$work/image.out"
# Without the announcement, so the 40-character check is what has to catch it: every `refute leaks`
# above is only worth something if `leaks` sees a real one.
grep -vi 'administrative password' "$work/image.out" >"$work/image.bare"
grep -i 'administrative password' "$work/image.out" | sed 's/.*: *//' >>"$work/image.bare"
assert "leaks catches the image's password by its shape alone" leaks "$work/image.bare"
before="$(fp) $(cat "$STEP_HOME/secrets/password") $(cksum <"$STEP_HOME/config/ca.json")"
sh "$scripts/step-ca-init.sh" >"$work/init4.out" 2>&1 && pass "an image-made CA is accepted" || fail "an image-made CA is accepted"
assert "and nothing is printed for it" test ! -s "$work/init4.out"
[ "$before" = "$(fp) $(cat "$STEP_HOME/secrets/password") $(cksum <"$STEP_HOME/config/ca.json")" ] \
  && pass "an image-made CA keeps its root, password and ca.json" || fail "an image-made CA keeps its root, password and ca.json"
assert "an image-made CA still starts" run_ca "$work/ca4.out"
# The CA this script makes has the same files with the same modes as the image's, the password's
# aside (the image leaves it 644, this script 0600).
modes | grep -v ' ./db/' >"$work/modes.image"
grep -v ' ./db/' "$work/modes.script" | diff "$work/modes.image" - >&2 && pass "the same files and modes as the image's init" \
  || fail "the same files and modes as the image's init"

# --- the compose fragments then drop the `versola` provisioner, whose password that image printed --
# The fragments' own lines, run against this CA once central's provisioner is in, as they run there:
# twice, since every start runs them.
for t in compose.fragment.yml.template compose.fragment.vps.yml.template; do
  sed -n '/^        if grep -qF .*"name": "versola"/,/^        fi$/p' "$scripts/$t" >"$work/drop.$t"
done
assert "both fragments drop the provisioner with the same lines" cmp -s "$work/drop.compose.fragment.yml.template"   "$work/drop.compose.fragment.vps.yml.template"
sed "s|/home/step|$STEP_HOME|g" "$work/drop.compose.fragment.yml.template" >"$work/drop.sh"
assert "the lines are there" grep -qF 'step ca provisioner remove versola' "$work/drop.sh"
step crypto jwk create "$STEP_HOME/certs/central.pub.json" "$STEP_HOME/secrets/central.json"   --kty EC --crv P-256 --use sig --no-password --insecure >/dev/null 2>&1
STEPPATH=$STEP_HOME step ca provisioner add central --type JWK --public-key="$STEP_HOME/certs/central.pub.json"   --ca-config="$STEP_HOME/config/ca.json" >/dev/null 2>&1
STEPPATH=$STEP_HOME sh -eu "$work/drop.sh" >"$work/drop1.out" 2>&1 && pass "the provisioner is dropped" || fail "the provisioner is dropped"
STEPPATH=$STEP_HOME sh -eu "$work/drop.sh" >"$work/drop2.out" 2>&1 && pass "a restart passes over it" || fail "a restart passes over it"
refute "versola is gone from ca.json" grep -qF '"name": "versola"' "$STEP_HOME/config/ca.json"
assert "central's stays" grep -qF '"name": "central"' "$STEP_HOME/config/ca.json"
refute "dropping it prints nothing secret" leaks "$work/drop1.out"
assert "the CA starts without it" run_ca "$work/ca5.out"

rm -rf "${work:?}"
if [ "$failures" -ne 0 ]; then echo "$failures check(s) failed" >&2; exit 1; fi
echo "all step-ca-init checks passed"
