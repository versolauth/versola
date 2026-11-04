#!/bin/sh
# release.json (versolauth/versola#823): entrypoint.sh copies the compatibility manifest to /out on both
# targets as it is, whatever VERSION the image was built as, and replaces a stale copy; and
# check-release-manifest.sh accepts the checked-in manifest and rejects what it must. The entrypoint runs
# with a stub `java`, so no JVM and no docker. Plain POSIX sh (the check script part needs bash and jq
# and is skipped without them):
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
  nginx.conf.template proxy_params.conf.template mtls-init.sh step-ca-intermediates.sh postgres-init-schemas.sh release.json; do
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
    cmp -s "$tools/release.json" "$work/out.d/release.json" && pass "$target: release.json is the checked-in file, the stale copy is replaced" \
      || fail "$target: release.json is the checked-in file, the stale copy is replaced"
  else
    cat "$work/out" >&2; fail "$target: the run writes release.json"
  fi
done

# A test build's VERSION is no release and has no entry: the file is the same, so the CLI finds no entry and asks.
if run_entrypoint vps test-jlink.1 && cmp -s "$tools/release.json" "$work/out.d/release.json"; then
  pass "a test build ships the same file"
else fail "a test build ships the same file"; fi

# A source file whose last line has no newline (an editor stripped it) still comes out complete.
printf '%s' "$(cat "$tools/release.json")" > "$work/app/release.json"
if run_entrypoint vps 9.9.9 && cmp -s "$work/app/release.json" "$work/out.d/release.json"; then
  pass "a last line without a newline is kept"
else fail "a last line without a newline is kept"; fi
cp "$tools/release.json" "$work/app/release.json"

# The check script.
if command -v jq >/dev/null 2>&1 && command -v bash >/dev/null 2>&1; then
  check() { (cd "$repo" && bash .github/scripts/check-release-manifest.sh "$@" > "$work/check.out" 2>&1); }
  expect_ok() { label=$1; shift; if check "$@"; then pass "$label"; else cat "$work/check.out" >&2; fail "$label"; fi; }
  expect_bad() { label=$1; shift; if check "$@"; then fail "$label"; else pass "$label"; fi; }
  m="$work/m.json"
  # entry <version> <rollingFrom> <freshDatabaseFrom> [notes]: one entry, the JSON array items are given as they are written
  entry() {
    printf '{"version": "%s", "rollingFrom": [%s], "freshDatabaseFrom": [%s], "notes": "%s"}' "$1" "$2" "$3" "${4:-https://example.com/tag/$1}"
  }
  # manifest <entry>...: a manifest with these entries, in this order
  manifest() {
    out=
    for e in "$@"; do out="${out:+$out,}$e"; done
    printf '{"schemaVersion": 1, "releases": [%s]}\n' "$out" > "$m"
  }

  expect_ok "the checked-in manifest passes"
  expect_ok "the checked-in manifest passes for its newest release" "$(jq -r '.releases[0].version' "$repo/docker/versola-tools/release.json" | tr -d '\r')"
  manifest "$(entry 1.2.3 '"1.2.0"' '"1.0.*"')" "$(entry 1.2.0 '' '"1.0.*"')"; expect_ok "two entries, newest first" "" "$m"
  expect_ok "the tag is the newest entry" 1.2.3 "$m"
  expect_bad "a tag that is not the newest entry" 1.2.4 "$m"
  expect_bad "a tag that is an older entry" 1.2.0 "$m"
  manifest "$(entry 1.2.4 '' '' https://example.com/tag/1.2.3)"; expect_bad "notes copied from another release" 1.2.4 "$m"
  manifest "$(entry 1.2.3 '' '')" "$(entry 1.2.3 '' '')"; expect_bad "the same version twice" "" "$m"
  manifest "$(entry 1.2.0 '' '')" "$(entry 1.2.3 '' '')"; expect_bad "oldest first" "" "$m"
  manifest "$(entry 0.7.10 '' '')" "$(entry 0.7.6 '' '')"; expect_ok "0.7.10 is above 0.7.6" "" "$m"
  manifest "$(entry 0.7.6 '' '')" "$(entry 0.7.10 '' '')"; expect_bad "0.7.6 is not above 0.7.10" "" "$m"
  manifest "$(entry 1.0.0 '' '')" "$(entry 1.0.0-rc1 '' '')"; expect_ok "a release is above its pre-release" "" "$m"
  manifest "$(entry 1.0.0-rc1 '' '')" "$(entry 1.0.0 '' '')"; expect_bad "a pre-release is not above its release" "" "$m"
  manifest "$(entry 1.2.3 '"1.2.*"' '"1.0.*"')"; expect_ok "rollingFrom may match the release's own line" "" "$m"
  manifest "$(entry 1.2.3 '"1.2.*"' '"1.2.1"')"; expect_bad "a glob and an exact version that overlap" "" "$m"
  manifest "$(entry 2.0.0 '"1.2.*"' '"1.*"')"; expect_bad "two globs that overlap" "" "$m"
  grep -q "name the same version" "$work/check.out" && pass "the overlap is the reason" || fail "the overlap is the reason"
  manifest "$(entry 1.2.3 '"1.2.1"' '"1.2.1"')"; expect_bad "the same exact version in both lists" "" "$m"
  manifest "$(entry 1.2.3 '' '"1.2.*"')"; expect_bad "freshDatabaseFrom matching the release itself" "" "$m"
  manifest "$(entry 1.2.3 '"^1.2.0"' '')"; expect_bad "a semver range" "" "$m"
  manifest "$(entry 1.2.3 '"1.2.x"' '')"; expect_bad "a glob that is not a prefix glob" "" "$m"
  manifest "$(entry 1.2.x '' '')"; expect_bad "a version that is not exact" "" "$m"
  manifest "$(entry 1.2.3 '' '' http://example.com)"; expect_bad "notes that are not https" "" "$m"
  printf '{"schemaVersion": 1, "releases": []}\n' > "$m"; expect_bad "no releases" "" "$m"
  printf '{"schemaVersion": 2, "releases": [%s]}\n' "$(entry 1.2.3 '' '')" > "$m"; expect_bad "an unknown schemaVersion" "" "$m"
  printf '{"schemaVersion": 1, "releases": [{"version": "1.2.3", "rollingFrom": [], "notes": "https://example.com/tag/1.2.3"}]}\n' > "$m"
  expect_bad "an entry without freshDatabaseFrom" "" "$m"
  echo '{' > "$m"; expect_bad "invalid JSON" "" "$m"
  expect_bad "a missing file" "" "$work/missing.json"
  manifest "$(entry 1.2.3 '' '')"; if (cd "$work" && bash "$repo/.github/scripts/check-release-manifest.sh" 1.2.3 m.json > "$work/check.out" 2>&1); then pass "a relative manifest path is the caller's"; else cat "$work/check.out" >&2; fail "a relative manifest path is the caller's"; fi
else
  echo "skip the check-release-manifest.sh cases: bash and jq are needed"
fi

if [ "$failures" -ne 0 ]; then echo "$failures failed" >&2; exit 1; fi
echo "all passed"
