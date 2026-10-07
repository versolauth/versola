#!/usr/bin/env bash
#
# Asserts what scripts/gen-env.scala promises about the `utils` client's key pair
# (versolauth/versola#459):
#   - a run generates a pair, and a second run does not reproduce it by itself;
#   - UTILS_PRIVATE_KEY_JWK hands an earlier run's key back, and the public half in
#     central.generated-secrets.env then matches it, so central.conf stays valid for a
#     deployment that already seeded `utils`;
#   - the private half is only in utils.private-key.jwk, never in a
#     *.generated-secrets.env (versola-cli loads those into the containers).
#
# This is the generator's own level: the key reaching a running central, and an assertion
# signed with it being accepted, is what e2e's provisioning specs cover with the pinned
# `local` key. The reuse path is not reachable from there.
#
# Requires: scala-cli. Usage: .github/scripts/check-utils-key-reuse.sh

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Each run in its own scratch directory: gen-env writes .local/env/<target>/ relative to it.
# `--server=false` keeps scala-cli from depending on a bloop server being healthy.
generate() {
  local dir="$1"; shift
  mkdir -p "$work/$dir"
  (
    cd "$work/$dir"
    env "$@" ENV_NAME=ci-check AUTH_URL=https://ci.invalid POSTGRES_HOST=ci.invalid:5432 \
      scala-cli run --server=false "$repo_root/scripts/gen-env.scala" <<< "vps" >/dev/null
  )
}

out() { echo "$work/$1/.local/env/vps/$2"; }
public_of() { grep '^UTILITY_CLIENT_PUBLIC_JWK=' "$(out "$1" central.generated-secrets.env)" | cut -d= -f2-; }

fail() { echo "FAIL: $*" >&2; exit 1; }

generate first
generate fresh
generate reused "UTILS_PRIVATE_KEY_JWK=$(cat "$(out first utils.private-key.jwk)")"

[ -s "$(out first utils.private-key.jwk)" ] || fail "no utils.private-key.jwk was written"
grep -q '"d"' "$(out first utils.private-key.jwk)" || fail "utils.private-key.jwk carries no private half"

[ "$(public_of first)" != "$(public_of fresh)" ] || fail "two runs produced the same pair without being handed one"
cmp -s "$(out first utils.private-key.jwk)" "$(out reused utils.private-key.jwk)" || fail "the handed-back private key was not kept"
[ "$(public_of first)" = "$(public_of reused)" ] || fail "the public half changed although the private key was handed back"

for f in auth central edge; do
  if grep -q '"d"' "$(out first "$f.generated-secrets.env")"; then
    fail "$f.generated-secrets.env holds a private key; versola-cli loads it into the container"
  fi
done

# A malformed value must stop the run rather than fall back to a new pair.
if generate broken "UTILS_PRIVATE_KEY_JWK={\"kty\":\"EC\"}" 2>/dev/null; then
  fail "a UTILS_PRIVATE_KEY_JWK that is not a private key was accepted"
fi

echo "OK: the utils key pair is stable when handed back and kept out of the generated-secrets files."
