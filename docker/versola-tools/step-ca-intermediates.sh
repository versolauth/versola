#!/bin/sh
# Creates the `server` and `edge` intermediates beside the one the step-ca image's own init made
# (the `client` one: intermediate_ca.crt, the CA step-ca signs with and central's provisioner
# issues client certificates from). All three hang off the same root, so one root_ca.crt still
# anchors everything, while each listener can trust exactly the intermediates it needs: a trust
# anchor that is the root would accept every one of them and defeat the split. See
# compose.fragment.vps.yml.template and develop.md ("Certificate storage and rotation").
#
# Idempotent: an intermediate that exists is left alone, because re-creating it would orphan every
# leaf it signed. STEP_HOME is overridable only so the shell test can run this against a throwaway
# CA.
set -eu

STEP_HOME="${STEP_HOME:-/home/step}"

# About 9 years: inside the root's ten, and long enough that nothing rotates them in practice.
create() {
  name=$1 cn=$2
  if [ -f "$STEP_HOME/certs/${name}_ca.crt" ] && [ -f "$STEP_HOME/secrets/${name}_ca_key" ]; then
    return 0
  fi
  # Written beside the final names and moved into place, key first: a crash midway leaves nothing
  # that looks complete, so the next start creates it again.
  step certificate create "$cn" "$STEP_HOME/certs/.${name}_ca.new.crt" "$STEP_HOME/secrets/.${name}_ca.new.key" \
    --profile intermediate-ca \
    --ca "$STEP_HOME/certs/root_ca.crt" --ca-key "$STEP_HOME/secrets/root_ca_key" \
    --ca-password-file "$STEP_HOME/secrets/password" --password-file "$STEP_HOME/secrets/password" \
    --not-after 78840h --force
  chmod 600 "$STEP_HOME/secrets/.${name}_ca.new.key"
  mv "$STEP_HOME/secrets/.${name}_ca.new.key" "$STEP_HOME/secrets/${name}_ca_key"
  mv "$STEP_HOME/certs/.${name}_ca.new.crt" "$STEP_HOME/certs/${name}_ca.crt"
  echo "step-ca: created the $name intermediate"
}

create server "Versola Server CA"
create edge "Versola Edge CA"
