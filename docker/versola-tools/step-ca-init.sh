#!/bin/sh
# Creates the CA on a fresh `versola-step-ca-*` volume, in place of the step-ca image's own
# entrypoint, which the compose fragments bypass (`entrypoint:` there) for one reason: after its
# `step ca init` the image echoes the password of the provisioner it creates to stdout, whatever
# the password's source (DOCKER_STEPCA_INIT_PASSWORD_FILE included), so it lands in `docker logs
# versola-step-ca` and in whatever diagnostics those are pasted into (versolauth/versola#890).
#
# Otherwise the same CA as the image's init makes, from the same DOCKER_STEPCA_INIT_* variables:
# a root, the `client` intermediate, a JWK provisioner and ca.json, the keys encrypted with a random
# password kept in secrets/password, which step-ca, step-ca-intermediates.sh and mtls-init.sh read.
# Two differences: the password files are written under umask 077 (the image leaves the CA's
# password world-readable inside the volume; the rest -- certs/, config/ -- keeps the image's
# modes), and the provisioner's password is random, used once and deleted unseen -- nothing uses
# that provisioner (central signs through its own `central` one), so nobody needs it; the compose
# fragments remove it once central's is in, on CAs this script made and on those the image made,
# whose printed password would otherwise still open it. Nothing secret is ever printed or put on a
# command line: the passwords only go through files.
#
# Idempotent: a volume that has ca.json is left alone, whoever made it (the image, before this
# script, or this script), so existing CAs keep their keys and password. A start cut off before
# `step ca init` wrote anything runs it again with the password already written. One cut off
# inside it (keys written, no ca.json yet) fails the next start, as the image's init does: `step ca
# init` asks before overwriting and has no terminal to ask on -- refusing beats replacing key
# material this script cannot prove unused. STEP_HOME is overridable only so the shell tests can
# run this against a throwaway CA.
set -eu

STEP_HOME="${STEP_HOME:-/home/step}"

if [ -f "$STEP_HOME/config/ca.json" ]; then
  exit 0
fi

: "${DOCKER_STEPCA_INIT_NAME:?}" "${DOCKER_STEPCA_INIT_DNS_NAMES:?}"
: "${DOCKER_STEPCA_INIT_PROVISIONER_NAME:?}" "${DOCKER_STEPCA_INIT_ADDRESS:?}"

# 40 alphanumerics, as the image generates them. `tr` ends on SIGPIPE once `head` has enough;
# plain sh has no pipefail, so that is not an error.
generate_password() {
  tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40
  echo
}

password=$STEP_HOME/secrets/password
provisioner_password=$STEP_HOME/secrets/.provisioner_password
trap 'rm -f "$provisioner_password"' EXIT

# In a subshell, so the umask covers the two password files (and secrets/, which `step ca init`
# would create 0700 anyway) and nothing else. The CA's password is written beside and moved: a
# crash cannot leave an empty or partial file that the next start would take as the password.
mkdir -p "$STEP_HOME"
(
  umask 077
  mkdir -p "$STEP_HOME/secrets"
  if [ ! -s "$password" ]; then
    generate_password >"$password.new"
    mv "$password.new" "$password"
  fi
  generate_password >"$provisioner_password"
)

# Its output is paths, names and the root's fingerprint: nothing secret.
STEPPATH=$STEP_HOME step ca init \
  --name "$DOCKER_STEPCA_INIT_NAME" \
  --dns "$DOCKER_STEPCA_INIT_DNS_NAMES" \
  --provisioner "$DOCKER_STEPCA_INIT_PROVISIONER_NAME" \
  --address "$DOCKER_STEPCA_INIT_ADDRESS" \
  --password-file "$password" \
  --provisioner-password-file "$provisioner_password"

echo "step-ca: created the CA"
