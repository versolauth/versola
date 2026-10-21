#!/bin/sh
# Runs step-ca-intermediates.sh and mtls-init.sh against a throwaway CA, through every phase, and
# asserts the files, the output lines and the chains. Needs `step`, so on a machine without it the
# script re-runs itself in a smallstep/step-ca container (no volumes, no ports, removed on exit):
#
#   sh docker/versola-tools/test/mtls-init.test.sh
#
# Plain POSIX sh; the image is the one compose.fragment.vps.yml.template uses.
set -eu

here=$(cd "$(dirname "$0")" && pwd)

if ! command -v step >/dev/null 2>&1; then
  exec docker run --rm --name "versola-mtls-init-test-$$" --user root \
    -v "$here/..":/scripts:ro --entrypoint /bin/sh \
    smallstep/step-ca:0.28.4 /scripts/test/mtls-init.test.sh
fi

scripts=$(cd "$here/.." && pwd)
work=$(mktemp -d)
export STEP_HOME=/home/step MTLS_DIR="$work/mtls" CENTRAL_CA_DIR="$work/central-ca"
mkdir -p "$CENTRAL_CA_DIR"
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
contains() { grep -qF -- "$2" "$1"; }
lacks() { ! grep -qF -- "$2" "$1"; }
same() { cmp -s "$1" "$2"; }
differs() { ! cmp -s "$1" "$2"; }
count_certs() { grep -c 'BEGIN CERTIFICATE' "$1"; }
verifies() { step certificate verify "$1" --roots "$2"; }
json() { step certificate inspect "$1" --format json; }
# lifetime_is <crt> <seconds>: the validity length, give or take a minute of backdating
lifetime_is() {
  len=$(json "$1" | sed -n 's/.*"length": \([0-9]*\).*/\1/p' | head -1)
  [ -n "$len" ] && [ "$len" -ge $(($2 - 60)) ] && [ "$len" -le $(($2 + 60)) ]
}

# run_init <outfile> [VAR=value ...]: one mtls-init run; the output is kept for the assertions.
run_init() {
  out=$1; shift
  env "$@" sh "$scripts/mtls-init.sh" > "$out" 2>&1
}
init() { # init <phase> [VAR=value ...]
  phase=$1; shift
  run_init "$work/out.$phase" MTLS_INIT_PHASE="$phase" "$@"
}

# --- a CA as the image's own init leaves it, then the two extra intermediates ---------------------
DOCKER_STEPCA_INIT_NAME="Versola Client CA" DOCKER_STEPCA_INIT_DNS_NAMES=localhost,127.0.0.1 \
  DOCKER_STEPCA_INIT_PROVISIONER_NAME=versola DOCKER_STEPCA_INIT_ADDRESS=127.0.0.1:9000 \
  /bin/bash /entrypoint.sh true >/dev/null 2>&1
sh "$scripts/step-ca-intermediates.sh" >"$work/ca1.out" 2>&1
certs=$STEP_HOME/certs
client_ca=$certs/intermediate_ca.crt server_ca=$certs/server_ca.crt edge_ca=$certs/edge_ca.crt root=$certs/root_ca.crt

fp() { step certificate fingerprint "$1"; }
cn() { json "$1" | sed -n '/^  "subject": {/,/^  },/p' | sed -n '/"common_name": \[/{n;s/^ *"\(.*[^,]\)",*$/\1/p;q;}'; }
issuer_cn() { json "$1" | sed -n '/^  "issuer": {/,/^  },/p' | sed -n '/"common_name": \[/{n;s/^ *"\(.*[^,]\)",*$/\1/p;q;}'; }

assert "the root signs the client intermediate" verifies "$client_ca" "$root"
assert "the root signs the server intermediate" verifies "$server_ca" "$root"
assert "the root signs the edge intermediate" verifies "$edge_ca" "$root"
[ "$(cn "$client_ca")" != "$(cn "$server_ca")" ] && [ "$(cn "$server_ca")" != "$(cn "$edge_ca")" ] \
  && [ "$(cn "$client_ca")" != "$(cn "$edge_ca")" ] && pass "three distinct intermediates" || fail "three distinct intermediates"
refute "a leaf of one intermediate does not verify against another's chain (server ca vs edge ca)" verifies "$server_ca" "$edge_ca"
before=$(fp "$server_ca")$(fp "$edge_ca")
sh "$scripts/step-ca-intermediates.sh" >"$work/ca2.out" 2>&1
[ "$before" = "$(fp "$server_ca")$(fp "$edge_ca")" ] && pass "intermediates are created only once" || fail "intermediates are created only once"
assert "the intermediate keys are private" test "$(stat -c %a "$STEP_HOME/secrets/server_ca_key")" = 600

# --- phase all (first start), with public names ---------------------------------------------------
init all MTLS_DOMAIN=id-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
o=$work/out.all
for n in server central-server edge-client; do assert "all: issued $n" contains "$o" "mtls-init: issued $n"; done
assert "all: reports a change" contains "$o" "mtls-init: changed"
for f in server.crt server.key server.next.crt central-server.crt central-server.key central-server.next.crt edge-client.crt edge-client.key \
  client-ca.crt edge-ca.crt server-ca.crt auth-trust.crt ca.crt; do assert "all: wrote $f" test -f "$MTLS_DIR/$f"; done
# central signs edge identities with the edge intermediate: its certificate and key, in the volume
# only central mounts, never in the shared one
assert "all: central gets edge-ca.crt" same "$CENTRAL_CA_DIR/edge-ca.crt" "$edge_ca"
assert "all: central's edge key is PKCS#8, unencrypted" grep -q "BEGIN PRIVATE KEY" "$CENTRAL_CA_DIR/edge-ca.key"
assert "all: central's edge key is private" test "$(stat -c %a "$CENTRAL_CA_DIR/edge-ca.key")" = 600
refute "all: the edge key is not in the shared volume" test -f "$MTLS_DIR/edge-ca.key"
assert "all: the exported key signs a certificate that verifies against edge-ca.crt" sh -c "step certificate create urn:versola:edge:t $work/t.crt $work/t.key --ca $CENTRAL_CA_DIR/edge-ca.crt --ca-key $CENTRAL_CA_DIR/edge-ca.key --no-password --insecure --force && step certificate verify $work/t.crt --roots $MTLS_DIR/edge-ca.crt"
refute "all: edge-client has no pin copy" test -f "$MTLS_DIR/edge-client.next.crt"
assert "server verifies against server-ca.crt" verifies "$MTLS_DIR/server.crt" "$MTLS_DIR/server-ca.crt"
assert "central-server verifies against server-ca.crt" verifies "$MTLS_DIR/central-server.crt" "$MTLS_DIR/server-ca.crt"
assert "edge-client verifies against edge-ca.crt" verifies "$MTLS_DIR/edge-client.crt" "$MTLS_DIR/edge-ca.crt"
refute "edge-client does not verify against client-ca.crt" verifies "$MTLS_DIR/edge-client.crt" "$MTLS_DIR/client-ca.crt"
refute "edge-client does not verify against server-ca.crt" verifies "$MTLS_DIR/edge-client.crt" "$MTLS_DIR/server-ca.crt"
refute "server does not verify against edge-ca.crt" verifies "$MTLS_DIR/server.crt" "$MTLS_DIR/edge-ca.crt"
refute "server does not verify against client-ca.crt" verifies "$MTLS_DIR/server.crt" "$MTLS_DIR/client-ca.crt"
assert "edge-client verifies against auth-trust.crt" verifies "$MTLS_DIR/edge-client.crt" "$MTLS_DIR/auth-trust.crt"
[ "$(issuer_cn "$MTLS_DIR/server.crt")" = "Versola Server CA" ] && [ "$(issuer_cn "$MTLS_DIR/central-server.crt")" = "Versola Server CA" ] \
  && [ "$(issuer_cn "$MTLS_DIR/edge-client.crt")" = "Versola Edge CA" ] && pass "issuers: server and central by the server CA, edge by the edge CA" \
  || fail "issuers: server and central by the server CA, edge by the edge CA"
assert "client-ca.crt is the client intermediate" same "$MTLS_DIR/client-ca.crt" "$client_ca"
assert "edge-ca.crt is the edge intermediate" same "$MTLS_DIR/edge-ca.crt" "$edge_ca"
assert "server-ca.crt is the server intermediate" same "$MTLS_DIR/server-ca.crt" "$server_ca"
[ "$(count_certs "$MTLS_DIR/client-ca.crt")" = 1 ] && [ "$(count_certs "$MTLS_DIR/edge-ca.crt")" = 1 ] \
  && [ "$(count_certs "$MTLS_DIR/server-ca.crt")" = 1 ] && pass "each trust file holds one certificate" || fail "each trust file holds one certificate"
[ "$(count_certs "$MTLS_DIR/auth-trust.crt")" = 2 ] && pass "auth-trust.crt holds client and edge" || fail "auth-trust.crt holds client and edge"
assert "auth-trust.crt is client then edge" test "$(cat "$client_ca" "$edge_ca" | cksum)" = "$(cksum < "$MTLS_DIR/auth-trust.crt")"
for f in client-ca.crt edge-ca.crt server-ca.crt auth-trust.crt; do
  refute "$f does not contain the root" sh -c "grep -qF \"\$(sed -n 2p '$root')\" '$MTLS_DIR/$f'"
done
assert "ca.crt is root then client intermediate (as before)" test "$(cat "$root" "$client_ca" | cksum)" = "$(cksum < "$MTLS_DIR/ca.crt")"
assert "server.next.crt is a copy of server.crt" same "$MTLS_DIR/server.crt" "$MTLS_DIR/server.next.crt"
assert "central-server.next.crt is a copy of central-server.crt" same "$MTLS_DIR/central-server.crt" "$MTLS_DIR/central-server.next.crt"
assert "server carries auth's public name" sh -c "step certificate inspect '$MTLS_DIR/server.crt' --format json | grep -qF '\"id-mtls.example.com\"'"
refute "server does not carry central's public name" sh -c "step certificate inspect '$MTLS_DIR/server.crt' --format json | grep -qF 'central-mtls.example.com'"
assert "central-server carries central's public name" sh -c "step certificate inspect '$MTLS_DIR/central-server.crt' --format json | grep -qF '\"central-mtls.example.com\"'"
assert "server keeps the internal names" sh -c "step certificate inspect '$MTLS_DIR/server.crt' --format json | grep -qF '\"auth\"'"
assert "edge-client carries the edge URI" sh -c "step certificate inspect '$MTLS_DIR/edge-client.crt' --format json | grep -qF 'urn:versola:edge:edge-default'"
assert "server lives 90 days" lifetime_is "$MTLS_DIR/server.crt" 7776000
assert "central-server lives 90 days" lifetime_is "$MTLS_DIR/central-server.crt" 7776000
assert "edge-client lives 30 days" lifetime_is "$MTLS_DIR/edge-client.crt" 2592000
assert "keys are PKCS#8" sh -c "head -1 '$MTLS_DIR/server.key' | grep -qx -- '-----BEGIN PRIVATE KEY-----'"
assert "keys are 0600" test "$(stat -c %a "$MTLS_DIR/server.key")" = 600
refute "no output ever contains key material" grep -rq "PRIVATE KEY" "$work"/out.*

# --- a second all changes nothing -----------------------------------------------------------------
sum_before=$(cd "$MTLS_DIR" && cksum ./*.crt ./*.key)
init all MTLS_DOMAIN=id-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
refute "second all issues nothing" contains "$work/out.all" "mtls-init: issued"
refute "second all reports no change" contains "$work/out.all" "mtls-init: changed"
[ "$sum_before" = "$(cd "$MTLS_DIR" && cksum ./*.crt ./*.key)" ] && pass "second all leaves every file alone" || fail "second all leaves every file alone"

# --- migration: a leaf of the old single-intermediate layout --------------------------------------
old_leaf() { # old_leaf <name> <cn> <san...>: what the previous bundle issued, from the client intermediate
  name=$1 cn=$2; shift 2
  step certificate create "$cn" "$MTLS_DIR/$name.crt" "$work/$name.old.pem" --ca "$client_ca" --ca-key "$STEP_HOME/secrets/intermediate_ca_key" \
    --ca-password-file "$STEP_HOME/secrets/password" "$@" --kty RSA --size 2048 --not-after 17520h --insecure --no-password --force >/dev/null 2>&1
  step crypto key format --pkcs8 --pem --insecure --no-password "$work/$name.old.pem" > "$MTLS_DIR/$name.key"
}
old_leaf central-server central --san central --san localhost --san 127.0.0.1 --san central-mtls.example.com
refute "an old leaf does not verify against the server intermediate" verifies "$MTLS_DIR/central-server.crt" "$server_ca"
init all MTLS_DOMAIN=id-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
assert "migration: says why" contains "$work/out.all" "mtls-init: central-server does not verify against the server intermediate"
assert "migration: re-issues the old leaf" contains "$work/out.all" "mtls-init: issued central-server"
refute "migration: leaves valid leaves alone" contains "$work/out.all" "mtls-init: issued server"
assert "migration: the new leaf verifies" verifies "$MTLS_DIR/central-server.crt" "$MTLS_DIR/server-ca.crt"
assert "migration: the pin copy follows" same "$MTLS_DIR/central-server.crt" "$MTLS_DIR/central-server.next.crt"

# --- prepare: nothing due, then a leaf that needs a public name -----------------------------------
init prepare MTLS_DOMAIN=id-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
refute "prepare with nothing due stages nothing" contains "$work/out.prepare" "mtls-init: staged"
refute "prepare with nothing due reports no change" contains "$work/out.prepare" "mtls-init: changed"
refute "prepare leaves no staged key" test -f "$MTLS_DIR/server.next.key"
cp "$MTLS_DIR/server.crt" "$work/server.cur.crt"; cp "$MTLS_DIR/server.key" "$work/server.cur.key"
cp "$MTLS_DIR/central-server.crt" "$work/central.cur.crt"; cp "$MTLS_DIR/edge-client.crt" "$work/edge.cur.crt"
init prepare MTLS_DOMAIN=id2-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
o=$work/out.prepare
assert "prepare: stages server (public name changed)" contains "$o" "mtls-init: staged server"
assert "prepare: says why" contains "$o" "mtls-init: server does not carry the public name id2-mtls.example.com"
refute "prepare: does not stage central-server" contains "$o" "mtls-init: staged central-server"
assert "prepare: reports a change" contains "$o" "mtls-init: changed"
assert "prepare: current certificate untouched" same "$work/server.cur.crt" "$MTLS_DIR/server.crt"
assert "prepare: current key untouched" same "$work/server.cur.key" "$MTLS_DIR/server.key"
assert "prepare: staged key exists" test -f "$MTLS_DIR/server.next.key"
assert "prepare: staged pin differs from the current" differs "$MTLS_DIR/server.crt" "$MTLS_DIR/server.next.crt"
assert "prepare: the staged certificate verifies" verifies "$MTLS_DIR/server.next.crt" "$MTLS_DIR/server-ca.crt"
assert "prepare: the staged certificate carries the new name" sh -c "step certificate inspect '$MTLS_DIR/server.next.crt' --format json | grep -qF '\"id2-mtls.example.com\"'"
assert "prepare: the staged key is 0600" test "$(stat -c %a "$MTLS_DIR/server.next.key")" = 600
assert "prepare: central-server untouched" same "$work/central.cur.crt" "$MTLS_DIR/central-server.crt"
assert "prepare: edge-client untouched" same "$work/edge.cur.crt" "$MTLS_DIR/edge-client.crt"
cp "$MTLS_DIR/server.next.key" "$work/staged.key"; cp "$MTLS_DIR/server.next.crt" "$work/staged.crt"
init prepare MTLS_DOMAIN=id2-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
assert "prepare again: still staged, resumable" contains "$work/out.prepare" "mtls-init: staged server"
assert "prepare again: reports a change" contains "$work/out.prepare" "mtls-init: changed"
assert "prepare again: keeps the staged key" same "$work/staged.key" "$MTLS_DIR/server.next.key"
assert "prepare again: keeps the staged certificate" same "$work/staged.crt" "$MTLS_DIR/server.next.crt"
init all MTLS_DOMAIN=id-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
refute "all does not touch a staged replacement of a leaf that is not due" contains "$work/out.all" "mtls-init: issued server"
assert "all keeps the staged pin" same "$work/staged.crt" "$MTLS_DIR/server.next.crt"

# --- commit ---------------------------------------------------------------------------------------
init commit MTLS_DOMAIN=id2-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
o=$work/out.commit
assert "commit: committed server" contains "$o" "mtls-init: committed server"
refute "commit: central-server had nothing staged" contains "$o" "mtls-init: committed central-server"
assert "commit: reports a change" contains "$o" "mtls-init: changed"
assert "commit: the staged certificate is current" same "$work/staged.crt" "$MTLS_DIR/server.crt"
assert "commit: the staged key is current" same "$work/staged.key" "$MTLS_DIR/server.key"
refute "commit: nothing staged any more" test -f "$MTLS_DIR/server.next.key"
assert "commit: the pin copy equals the new current" same "$MTLS_DIR/server.crt" "$MTLS_DIR/server.next.crt"
assert "commit: central-server untouched" same "$work/central.cur.crt" "$MTLS_DIR/central-server.crt"
init commit
[ ! -s "$work/out.commit" ] && pass "commit with nothing staged prints nothing" || fail "commit with nothing staged prints nothing"
sum_before=$(cd "$MTLS_DIR" && cksum ./*.crt ./*.key)
init all MTLS_DOMAIN=id2-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
refute "all after commit issues nothing" contains "$work/out.all" "mtls-init: issued"
[ "$sum_before" = "$(cd "$MTLS_DIR" && cksum ./*.crt ./*.key)" ] && pass "all after commit changes no file" || fail "all after commit changes no file"

# --- a leaf that is simply running out (a third of its life or less) -------------------------------
short_leaf() { # short_leaf <name> <cn> <san...>: valid, from the right intermediate, but three hours old of four
  name=$1 cn=$2; shift 2
  step certificate create "$cn" "$MTLS_DIR/$name.crt" "$work/$name.short.pem" --ca "$server_ca" --ca-key "$STEP_HOME/secrets/server_ca_key" \
    --ca-password-file "$STEP_HOME/secrets/password" "$@" --kty RSA --size 2048 --not-before=-3h --not-after=1h --insecure --no-password --force >/dev/null 2>&1
  step crypto key format --pkcs8 --pem --insecure --no-password "$work/$name.short.pem" > "$MTLS_DIR/$name.key"
}
short_leaf central-server central --san central --san localhost --san 127.0.0.1 --san central-mtls.example.com
assert "the short leaf verifies (it is only due)" verifies "$MTLS_DIR/central-server.crt" "$MTLS_DIR/server-ca.crt"
cp "$MTLS_DIR/central-server.crt" "$work/central.cur.crt"
init prepare MTLS_DOMAIN=id2-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
assert "expiry: prepare stages the leaf that is running out" contains "$work/out.prepare" "mtls-init: central-server is due for renewal"
assert "expiry: staged" contains "$work/out.prepare" "mtls-init: staged central-server"
assert "expiry: current certificate untouched" same "$work/central.cur.crt" "$MTLS_DIR/central-server.crt"
init commit MTLS_DOMAIN=id2-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
assert "expiry: committed" contains "$work/out.commit" "mtls-init: committed central-server"
refute "expiry: the committed leaf is no longer due" contains "$work/out.commit" "due for renewal"
init all MTLS_DOMAIN=id2-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
refute "expiry: nothing is due afterwards" contains "$work/out.all" "mtls-init: issued"

# --- identity -------------------------------------------------------------------------------------
init identity MTLS_DOMAIN=id2-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
refute "identity: a valid edge-client is left alone" contains "$work/out.identity" "mtls-init: issued"
old_leaf edge-client edge-default --san urn:versola:edge:edge-default
cp "$MTLS_DIR/server.crt" "$work/server.cur.crt"
init identity MTLS_DOMAIN=id2-mtls.example.com CENTRAL_MTLS_DOMAIN=central-mtls.example.com
assert "identity: re-issues edge-client" contains "$work/out.identity" "mtls-init: issued edge-client"
assert "identity: says why" contains "$work/out.identity" "mtls-init: edge-client does not verify against the edge intermediate"
assert "identity: reports a change" contains "$work/out.identity" "mtls-init: changed"
refute "identity: does not issue the servers" contains "$work/out.identity" "mtls-init: issued server"
assert "identity: server untouched" same "$work/server.cur.crt" "$MTLS_DIR/server.crt"
assert "identity: the new edge-client verifies against edge-ca.crt" verifies "$MTLS_DIR/edge-client.crt" "$MTLS_DIR/edge-ca.crt"

# --- report ---------------------------------------------------------------------------------------
sum_before=$(cd "$MTLS_DIR" && find . -type f | sort | xargs cksum)
init report
o=$work/out.report
[ "$sum_before" = "$(cd "$MTLS_DIR" && find . -type f | sort | xargs cksum)" ] && pass "report changes nothing" || fail "report changes nothing"
for f in server.crt server.next.crt central-server.crt central-server.next.crt edge-client.crt client-ca.crt edge-ca.crt server-ca.crt auth-trust.crt ca.crt; do
  assert "report: a line for $f" grep -Eq "^mtls-init: cert $f notBefore=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z notAfter=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z subject=.+\$" "$o"
done
assert "report: a bundle lists each certificate" test "$(grep -c '^mtls-init: cert auth-trust.crt ' "$o")" = 2
assert "report: edge-client subject" grep -Eq "^mtls-init: cert edge-client.crt .* subject=urn:versola:edge:edge-default\$" "$o"
assert "report: server subject is its CN" grep -Eq "^mtls-init: cert server.crt .* subject=localhost\$" "$o"
assert "report: central-server subject is its CN" grep -Eq "^mtls-init: cert central-server.crt .* subject=central\$" "$o"
assert "report: a CA is known by its CN" grep -Eq "^mtls-init: cert server-ca.crt .* subject=Versola Server CA\$" "$o"
expected=$(json "$MTLS_DIR/server.crt" | sed -n 's/.*"end": "\(.*\)".*/\1/p')
assert "report: notAfter is the certificate's" grep -qF "notAfter=$expected subject=localhost" "$o"
started=$(json "$MTLS_DIR/server.crt" | sed -n 's/.*"start": "\(.*\)".*/\1/p')
assert "report: notBefore is the certificate's" grep -qF "mtls-init: cert server.crt notBefore=$started notAfter=$expected " "$o"
refute "report prints no key material" grep -q "PRIVATE KEY" "$o"
refute "report prints no key file" grep -Eq "\.key" "$o"
assert "report: only report lines" test "$(grep -vc '^mtls-init: cert ' "$o")" = 0

# --- refusals -------------------------------------------------------------------------------------
refute "an unknown phase is refused" run_init "$work/out.bad" MTLS_INIT_PHASE=nope
assert "an unknown phase says so" contains "$work/out.bad" "unknown MTLS_INIT_PHASE"
refute "a public name with a shell metacharacter is refused" run_init "$work/out.bad" 'MTLS_DOMAIN=a.example.com;touch$IFS/tmp/x'
refute "an uppercase public name is refused" run_init "$work/out.bad" MTLS_DOMAIN=ID.example.com

rm -rf "${work:?}"
if [ "$failures" -ne 0 ]; then echo "$failures check(s) failed" >&2; exit 1; fi
echo "all mtls-init checks passed"
