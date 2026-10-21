#!/bin/sh
# Issues and renews the certificates of the vps bundle's mutual-TLS surface into $MTLS_DIR
# (the `versola-mtls-vps` volume, /app/mtls in the services). Runs as the compose `mtls-init`
# service, in the step-ca image, so it only has `step`, busybox and no openssl. See develop.md
# ("Certificate storage and rotation") for how the pieces fit; this file is the contract that
# `versola` (the CLI) drives through MTLS_INIT_PHASE.
#
# Leaves, by the intermediate that signs them (step-ca-intermediates.sh made all three):
#   server          auth's listener             server intermediate  90 days  pinned by edge
#   central-server  central's listener          server intermediate  90 days  pinned by edge
#   edge-client     identity of the local edge  edge intermediate     30 days  not pinned
# `client` (intermediate_ca.crt) is the CA step-ca itself signs with, for central's provisioner.
#
# Trust files, each holding exactly one intermediate and never the root, because a trust anchor
# that is the root accepts every intermediate and the split would mean nothing:
#   client-ca.crt, edge-ca.crt, server-ca.crt, and auth-trust.crt = client + edge (auth's
#   handshake takes client certificates and edge identities; the application layer then insists
#   that an urn:versola:edge:* name chains to edge-ca.crt).
# `ca.crt` (root + client intermediate) is still written for whatever pinned it before the split.
#
# Pinned leaves have two pin files, <name>.crt and <name>.next.crt. edge trusts both, so a leaf can
# be replaced without a window in which edge pins the wrong one: the replacement is staged as
# .next first, edge learns it by a restart, and only then does the leaf change.
#
# MTLS_INIT_PHASE (default all):
#   all       issue what is missing or due, in place (what a first start and `versola up` do)
#   prepare   stage the replacement of each due pinned leaf as <name>.next.{crt,key}; the current
#             pair is untouched. Safe to repeat: an already staged replacement is kept
#   commit    move each staged replacement into place as the current pair
#   identity  issue or renew edge-client only, in place (nothing pins it)
#   report    change nothing; print one line per certificate (a bundle has one per member):
#             `mtls-init: cert <file> notBefore=<RFC3339 UTC> notAfter=<RFC3339 UTC> subject=<CN or URI>`
#             (the URI name for an edge identity, otherwise the CN; subject is last and may hold spaces)
# Output lines other tools parse: `mtls-init: issued|staged|committed <name>`,
# `mtls-init: changed` (something was written that a service has to be restarted for) and
# the report lines. Key material is never printed.
#
# Central signs edge identities (enrollment and renewal) with the edge intermediate, so central, and
# only central, gets its certificate and key: $CENTRAL_CA_DIR (the `versola-central-ca-vps` volume,
# /app/ca in central alone) holds edge-ca.crt and edge-ca.key (unencrypted PKCS#8, mode 0600). The key
# never goes into $MTLS_DIR, which auth and edge mount too.
#
# MTLS_DOMAIN / CENTRAL_MTLS_DOMAIN: public names added to the SAN of auth's and central's leaf
# (opt-in, `versola configure vps --mtls-domain/--central-mtls-domain`). A leaf that lacks the
# configured name is due, so setting one takes effect at the next prepare/all. STEP_HOME and
# MTLS_DIR are overridable only for the shell test.
set -eu

STEP_HOME="${STEP_HOME:-/home/step}"
MTLS_DIR="${MTLS_DIR:-/mtls}"
CENTRAL_CA_DIR="${CENTRAL_CA_DIR:-/central-ca}"
PHASE="${MTLS_INIT_PHASE:-all}"
MTLS_DOMAIN="${MTLS_DOMAIN:-}"
CENTRAL_MTLS_DOMAIN="${CENTRAL_MTLS_DOMAIN:-}"

case "$PHASE" in
  all|prepare|commit|identity|report) ;;
  *) echo "mtls-init: unknown MTLS_INIT_PHASE '$PHASE' (expected all, prepare, commit, identity or report)" >&2; exit 1 ;;
esac
# versola-tools validated these as DNS names already; they go to `step` as plain arguments, but a
# service started by hand should not be able to smuggle anything else in either.
for d in "$MTLS_DOMAIN" "$CENTRAL_MTLS_DOMAIN"; do
  case "$d" in
    *[!a-z0-9.-]*) echo "mtls-init: refusing public name '$d' (lowercase letters, digits, '.' and '-' only)" >&2; exit 1 ;;
  esac
done

# --- what each leaf is ----------------------------------------------------------------------------

ca_crt() {
  if [ "$1" = client ]; then echo "$STEP_HOME/certs/intermediate_ca.crt"; else echo "$STEP_HOME/certs/$1_ca.crt"; fi
}
ca_key() {
  if [ "$1" = client ]; then echo "$STEP_HOME/secrets/intermediate_ca_key"; else echo "$STEP_HOME/secrets/$1_ca_key"; fi
}
leaf_ca() {
  case "$1" in
    server|central-server) echo server ;;
    edge-client) echo edge ;;
  esac
}
leaf_domain() {
  case "$1" in
    server) echo "$MTLS_DOMAIN" ;;
    central-server) echo "$CENTRAL_MTLS_DOMAIN" ;;
  esac
}

# mint <name> <prefix>: a new key and certificate for the leaf, as <prefix>.crt and <prefix>.key.
mint() {
  name=$1 prefix=$2
  case "$name" in
    server)
      cn=localhost hours=2160h; set -- --san auth --san localhost --san 127.0.0.1 ;;
    central-server)
      cn=central hours=2160h; set -- --san central --san localhost --san 127.0.0.1 ;;
    edge-client)
      # The URI name is what central checks against the edge the token names (`authorizeEdge`).
      cn=edge-default hours=720h; set -- --san urn:versola:edge:edge-default ;;
  esac
  domain=$(leaf_domain "$name")
  if [ -n "$domain" ]; then set -- "$@" --san "$domain"; fi
  ca=$(leaf_ca "$name")
  step certificate create "$cn" "$prefix.crt" "$prefix.pem" \
    --ca "$(ca_crt "$ca")" --ca-key "$(ca_key "$ca")" --ca-password-file "$STEP_HOME/secrets/password" \
    "$@" --kty RSA --size 2048 --not-after "$hours" --insecure --no-password --force
  # Netty reads PKCS#8 only; step writes PKCS#1.
  step crypto key format --pkcs8 --pem --insecure --no-password "$prefix.pem" > "$prefix.key"
  rm -f "${prefix:?}.pem"
  chmod 644 "$prefix.crt"
  chmod 600 "$prefix.key"
}

# due <name>: succeeds, saying why, when the leaf has to be issued again.
due() {
  name=$1 crt=$MTLS_DIR/$1.crt
  if [ ! -f "$crt" ] || [ ! -f "$MTLS_DIR/$name.key" ]; then
    echo "mtls-init: $name is missing"; return 0
  fi
  ca=$(leaf_ca "$name")
  # A leaf of the single-intermediate layout chains to the client intermediate, which the
  # listeners no longer trust for it; an expired one does not verify either.
  if ! step certificate verify "$crt" --roots "$(ca_crt "$ca")" >/dev/null 2>&1; then
    echo "mtls-init: $name does not verify against the $ca intermediate (issued by another CA, or expired)"; return 0
  fi
  domain=$(leaf_domain "$name")
  if [ -n "$domain" ] && ! step certificate inspect "$crt" --format json | grep -qF "\"$domain\""; then
    echo "mtls-init: $name does not carry the public name $domain"; return 0
  fi
  # `step certificate needs-renewal`: exit 0 = due, 1 = not due, anything else is an error.
  rc=0
  step certificate needs-renewal "$crt" --expires-in 33% >/dev/null 2>&1 || rc=$?
  if [ "$rc" -eq 1 ]; then echo "mtls-init: $name is valid, leaving it"; return 1; fi
  if [ "$rc" -ne 0 ]; then echo "mtls-init: cannot read $crt (step exit $rc)" >&2; exit 1; fi
  echo "mtls-init: $name is due for renewal"; return 0
}

# --- moving files around --------------------------------------------------------------------------
# Everything is written beside its final name and moved into place, so a failure midway leaves the
# previous file. The key moves before the certificate, and a staged key is removed before the pin
# copy is refreshed, for the reasons next to each use.

is_pinned() { [ "$1" = server ] || [ "$1" = central-server ]; }
# The marker of a staged replacement is its key, which is written last.
has_stage() { [ -f "$MTLS_DIR/$1.next.key" ]; }
drop_stage() { rm -f "${MTLS_DIR:?}/${1:?}.next.key"; }

# <name>.next.crt as a copy of the current certificate: what edge pins as "next" when nothing is staged.
sync_pin() {
  name=$1
  if [ -f "$MTLS_DIR/$name.next.crt" ] && cmp -s "$MTLS_DIR/$name.crt" "$MTLS_DIR/$name.next.crt"; then return 0; fi
  cp "$MTLS_DIR/$name.crt" "$MTLS_DIR/.$name.next.new"
  chmod 644 "$MTLS_DIR/.$name.next.new"
  mv "$MTLS_DIR/.$name.next.new" "$MTLS_DIR/$name.next.crt"
}

changed=false

# issue_in_place <name>: what `all` does for every leaf and `identity` for edge-client.
issue_in_place() {
  name=$1
  if due "$name"; then
    mint "$name" "$MTLS_DIR/.$name.new"
    mv "$MTLS_DIR/.$name.new.key" "$MTLS_DIR/$name.key"
    mv "$MTLS_DIR/.$name.new.crt" "$MTLS_DIR/$name.crt"
    # A replacement staged earlier is superseded. Its key goes first: were this cut off after the
    # pin copy, a later `commit` would pair the new pin with the old staged key.
    if is_pinned "$name"; then drop_stage "$name"; fi
    changed=true
    echo "mtls-init: issued $name"
  fi
  if is_pinned "$name" && ! has_stage "$name"; then sync_pin "$name"; fi
}

# prepare_stage <name>: stage the replacement of a pinned leaf next to the current pair.
prepare_stage() {
  name=$1
  if has_stage "$name"; then
    # A staged replacement is kept, not re-issued: a renewal that was cut off continues to `commit`.
    changed=true
    echo "mtls-init: staged $name"
    return 0
  fi
  if ! due "$name"; then
    sync_pin "$name"
    return 0
  fi
  if [ ! -f "$MTLS_DIR/$name.crt" ] || [ ! -f "$MTLS_DIR/$name.key" ]; then
    # Nothing to protect yet: there is no current pair for an edge to have pinned.
    issue_in_place "$name"
    return 0
  fi
  mint "$name" "$MTLS_DIR/.$name.stage"
  mv "$MTLS_DIR/.$name.stage.crt" "$MTLS_DIR/$name.next.crt"
  mv "$MTLS_DIR/.$name.stage.key" "$MTLS_DIR/$name.next.key"
  changed=true
  echo "mtls-init: staged $name"
}

commit_stage() {
  name=$1
  has_stage "$name" || return 0
  if [ ! -f "$MTLS_DIR/$name.next.crt" ]; then
    echo "mtls-init: $name has a staged key but no staged certificate" >&2; exit 1
  fi
  cp "$MTLS_DIR/$name.next.key" "$MTLS_DIR/.$name.commit.key"
  cp "$MTLS_DIR/$name.next.crt" "$MTLS_DIR/.$name.commit.crt"
  chmod 600 "$MTLS_DIR/.$name.commit.key"
  chmod 644 "$MTLS_DIR/.$name.commit.crt"
  mv "$MTLS_DIR/.$name.commit.key" "$MTLS_DIR/$name.key"
  mv "$MTLS_DIR/.$name.commit.crt" "$MTLS_DIR/$name.crt"
  # <name>.next.crt now equals the new current certificate, which is what "nothing staged" looks
  # like. Removing the marker last makes a cut-off commit repeatable.
  drop_stage "$name"
  changed=true
  echo "mtls-init: committed $name"
}

# write_trust <file> <certificate files...>
write_trust() {
  dest=$1; shift
  cat "$@" > "$MTLS_DIR/.$dest.new"
  chmod 644 "$MTLS_DIR/.$dest.new"
  mv "$MTLS_DIR/.$dest.new" "$MTLS_DIR/$dest"
}

# The edge intermediate's certificate and key for central. The key file of step-ca is encrypted;
# central reads PKCS#8 PEM without a password. Written beside and moved, the key first, only when
# it differs from what is there (the intermediate never changes, so this is a one-off).
export_edge_ca() {
  [ -d "$CENTRAL_CA_DIR" ] || return 0
  tmp=$CENTRAL_CA_DIR/.edge-ca.key.new
  (umask 077; step crypto key format --pkcs8 --pem --insecure --no-password \
    --password-file "$STEP_HOME/secrets/password" --force "$(ca_key edge)" > "$tmp")
  chmod 600 "$tmp"
  if [ -f "$CENTRAL_CA_DIR/edge-ca.key" ] && cmp -s "$tmp" "$CENTRAL_CA_DIR/edge-ca.key"; then
    rm -f "$tmp"
  else
    mv "$tmp" "$CENTRAL_CA_DIR/edge-ca.key"
  fi
  cp "$(ca_crt edge)" "$CENTRAL_CA_DIR/.edge-ca.crt.new"
  chmod 644 "$CENTRAL_CA_DIR/.edge-ca.crt.new"
  mv "$CENTRAL_CA_DIR/.edge-ca.crt.new" "$CENTRAL_CA_DIR/edge-ca.crt"
}

# --- report ---------------------------------------------------------------------------------------

# report_file <name>: one line per certificate in the file (a bundle holds several).
report_file() {
  file=$MTLS_DIR/$1
  [ -f "$file" ] || return 0
  tmp=$(mktemp -d)
  awk -v dir="$tmp" '/-----BEGIN CERTIFICATE-----/ { n++ } n > 0 { print > (dir "/c" n ".pem") }' "$file"
  i=1
  while [ -f "$tmp/c$i.pem" ]; do
    json=$(step certificate inspect "$tmp/c$i.pem" --format json)
    validity=$(printf '%s\n' "$json" | sed -n '/^  "validity": {/,/^  },/p')
    not_before=$(printf '%s\n' "$validity" | sed -n 's/.*"start": "\(.*\)".*/\1/p')
    not_after=$(printf '%s\n' "$validity" | sed -n 's/.*"end": "\(.*\)".*/\1/p')
    # The identity of an edge is its URI name; everything else is known by its CN.
    subject=$(printf '%s\n' "$json" | sed -n '/"uniform_resource_identifiers": \[/{n;s/^ *"\(.*[^,]\)",*$/\1/p;q;}')
    if [ -z "$subject" ]; then
      subject=$(printf '%s\n' "$json" | sed -n '/^  "subject": {/,/^  },/p' | sed -n '/"common_name": \[/{n;s/^ *"\(.*[^,]\)",*$/\1/p;q;}')
    fi
    echo "mtls-init: cert $1 notBefore=$not_before notAfter=$not_after subject=$subject"
    i=$((i + 1))
  done
  rm -rf "${tmp:?}"
}

# --- run ------------------------------------------------------------------------------------------

if [ "$PHASE" = report ]; then
  for f in ca.crt client-ca.crt edge-ca.crt server-ca.crt auth-trust.crt \
    server.crt server.next.crt central-server.crt central-server.next.crt edge-client.crt; do
    report_file "$f"
  done
  exit 0
fi

for name in client server edge; do
  if [ ! -f "$(ca_crt "$name")" ] || [ ! -f "$(ca_key "$name")" ]; then
    echo "mtls-init: the $name intermediate is missing from $STEP_HOME (step-ca creates it at start)" >&2; exit 1
  fi
done
mkdir -p "$MTLS_DIR"

case "$PHASE" in
  all)
    issue_in_place server
    issue_in_place central-server
    issue_in_place edge-client
    ;;
  identity)
    issue_in_place edge-client
    ;;
  prepare)
    prepare_stage server
    prepare_stage central-server
    ;;
  commit)
    commit_stage server
    commit_stage central-server
    ;;
esac

export_edge_ca
write_trust client-ca.crt "$(ca_crt client)"
write_trust edge-ca.crt "$(ca_crt edge)"
write_trust server-ca.crt "$(ca_crt server)"
write_trust auth-trust.crt "$(ca_crt client)" "$(ca_crt edge)"
write_trust ca.crt "$STEP_HOME/certs/root_ca.crt" "$(ca_crt client)"
if [ "$changed" = true ]; then echo "mtls-init: changed"; fi
