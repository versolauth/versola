#!/bin/sh
# release.json (versolauth/versola#823): entrypoint.sh writes the compatibility manifest to /out on both
# targets with the version the image was built as, replaces a stale copy and refuses a VERSION that
# would break the JSON; and check-release-manifest.sh accepts the checked-in manifest and rejects
# what it must. The entrypoint runs with a stub `java`, so no JVM and no docker. Plain POSIX sh
# (the check script part needs bash and jq and is skipped without them):
#
#   sh docker/versola-tools/test/entrypoint-release-manifest.test.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
tools=$(cd "$here/.." && pwd)
repo=$(cd "$tools/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0

pass() { echo "ok   $1"; }
fail() { echo "FAIL $1" >&2; failures=$((failures + 1)); }

mkdir -p "$work/app/central-ui" "$work/bin"
for f in entrypoint.sh compose.fragment.vps.yml.template compose.fragment.yml.template openbao.hcl.vps.template openbao.hcl.template \
  nginx.conf.template proxy_params.conf.template mtls-init.sh step-ca-intermediates.sh release.json; do
  cp "$tools/$f" "$work/app/$f"
done
cat > "$work/bin/java" <<STUB
#!/bin/sh
dir=.local/env/\$(cat)
mkdir -p "\$dir"
for f in auth.conf central.conf edge.conf auth.generated-secrets.env central.generated-secrets.env edge.generated-secrets.env \
  utils.private-key.jwk secrets.schema.json; do : > "\$dir/\$f"; done
STUB
chmod +x "$work/bin/java"

# run_entrypoint <target> <version>: exit status of one run, output in $work/out, bundle in $work/out.d
run_entrypoint() {
  target=$1; version=$2
  rm -rf "$work/app/.local"
  (cd "$work/app" && env PATH="$work/bin:$PATH" OUT_DIR="$work/out.d" VERSION="$version" TARGET="$target" \
    AUTH_URL=https://auth.example.com POSTGRES_HOST=127.0.0.1:5432 sh ./entrypoint.sh > "$work/out" 2>&1)
}

for target in docker-local vps; do
  rm -rf "$work/out.d"; mkdir -p "$work/out.d"
  echo stale > "$work/out.d/release.json"
  if run_entrypoint "$target" 9.9.9 && [ -f "$work/out.d/release.json" ]; then
    grep -qF '"version": "9.9.9",' "$work/out.d/release.json" && pass "$target: release.json says the built version" \
      || fail "$target: release.json says the built version"
    ! grep -q stale "$work/out.d/release.json" && pass "$target: a stale release.json is replaced" || fail "$target: a stale release.json is replaced"
    # Everything but the version line is the checked-in file, byte for byte.
    if [ "$(grep -v '"version"' "$work/out.d/release.json")" = "$(grep -v '"version"' "$tools/release.json")" ]; then
      pass "$target: the lists and notes are the checked-in ones"
    else fail "$target: the lists and notes are the checked-in ones"; fi
  else
    cat "$work/out" >&2; fail "$target: the run writes release.json"
  fi
done

# A source file whose last line has no newline (an editor stripped it) still comes out complete.
printf '%s' "$(cat "$tools/release.json")" > "$work/app/release.json"
if run_entrypoint vps 9.9.9 && [ "$(tail -c 2 "$work/out.d/release.json" | tr -d '\n')" = "}" ] && grep -q '"notes"' "$work/out.d/release.json"; then
  pass "a last line without a newline is kept"
else fail "a last line without a newline is kept"; fi
cp "$tools/release.json" "$work/app/release.json"

if run_entrypoint vps 'test-jlink.1+b' && grep -qF '"version": "test-jlink.1+b",' "$work/out.d/release.json"; then
  pass "a test tag is accepted"
else fail "a test tag is accepted"; fi

for bad in 'a/b' '1.0"x' '1.0\x' 'a b' '$(id)' '1.0;x'; do
  rm -rf "$work/out.d"
  if run_entrypoint vps "$bad"; then fail "refused VERSION: $bad"
  elif [ -e "$work/out.d/release.json" ]; then fail "no release.json for VERSION: $bad"
  else pass "refused VERSION: $bad"; fi
done

# The check script.
if command -v jq >/dev/null 2>&1 && command -v bash >/dev/null 2>&1; then
  check() { (cd "$repo" && bash .github/scripts/check-release-manifest.sh "$@" > "$work/check.out" 2>&1); }
  expect_ok() { label=$1; shift; if check "$@"; then pass "$label"; else cat "$work/check.out" >&2; fail "$label"; fi; }
  expect_bad() { label=$1; shift; if check "$@"; then fail "$label"; else pass "$label"; fi; }
  manifest() { # manifest <rollingFrom> <freshDatabaseFrom> [version]
    cat > "$work/m.json" <<JSON
{
  "schemaVersion": 1,
  "version": "${3:-1.2.3}",
  "rollingFrom": [$1],
  "freshDatabaseFrom": [$2],
  "notes": "https://example.com/tag/1.2.3"
}
JSON
  }
  expect_ok "the checked-in manifest passes"
  manifest '"1.2.0", "1.2.1"' '"1.0.*"'; expect_ok "a well-formed manifest passes" "" "$work/m.json"
  manifest '"1.2.0"' '"1.0.*"'; expect_ok "the tag equals the version" 1.2.3 "$work/m.json"
  expect_bad "a tag that differs from the version" 1.2.4 "$work/m.json"
  manifest '"1.2.0"' '' 1.2.4; expect_bad "notes copied from another release" 1.2.4 "$work/m.json"
  sed -i 's|/tag/1.2.3"|/tag/1.2.4"|' "$work/m.json"; expect_ok "notes ending in the tag" 1.2.4 "$work/m.json"
  manifest '"1.2.*"' '"1.2.1"'; expect_bad "a glob and an exact version that overlap" "" "$work/m.json"
  manifest '"1.2.*"' '"1.*"'; expect_bad "two globs that overlap" "" "$work/m.json"
  manifest '"1.2.1"' '"1.2.1"'; expect_bad "the same exact version in both lists" "" "$work/m.json"
  manifest '"1.2.1", "1.2.3"' ''; expect_bad "the release rolling from itself" "" "$work/m.json"
  manifest '"^1.2.0"' ''; expect_bad "a semver range" "" "$work/m.json"
  manifest '"1.2.x"' ''; expect_bad "a glob that is not a prefix glob" "" "$work/m.json"
  manifest '"1.2.0"' ''; sed -i 's/"schemaVersion": 1/"schemaVersion": 2/' "$work/m.json"; expect_bad "an unknown schemaVersion" "" "$work/m.json"
  manifest '"1.2.0"' ''; sed -i 's|  "version": "1.2.3",|  "version":\n "1.2.3",|' "$work/m.json"; expect_bad "version not on one line" "" "$work/m.json"
  echo '{' > "$work/m.json"; expect_bad "invalid JSON" "" "$work/m.json"
  expect_bad "a missing file" "" "$work/missing.json"
else
  echo "skip the check-release-manifest.sh cases: bash and jq are needed"
fi

if [ "$failures" -ne 0 ]; then echo "$failures failed" >&2; exit 1; fi
echo "all passed"
