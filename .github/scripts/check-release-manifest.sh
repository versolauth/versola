#!/usr/bin/env bash
#
# Checks docker/versola-tools/release.json, the compatibility manifest versola-tools writes to
# /out/release.json (versolauth/versola#823), which `versola rollout` and `versola configure` read:
#
#   - it is JSON with schemaVersion 1, a version, an https `notes` link and the two lists
#     `rollingFrom` and `freshDatabaseFrom`;
#   - every list entry is an exact version (0.7.5, 0.8.0-rc1) or a prefix glob (0.7.*): no other
#     pattern syntax, no semver ranges, so the CLI's matcher stays one line;
#   - no version is claimed both rolling and fresh-database (that would be a release saying "you can
#     roll from 0.7.x" and "0.7.x has no way in" at once), and the release does not list itself;
#   - with an argument, `version` equals it. CI passes the release tag; run it by hand with the
#     tag you are about to publish (release.md): a published tag cannot be reused, so a manifest
#     that does not match must be found before Publish, not by the tag build.
#
# Usage: check-release-manifest.sh [<tag>] [<manifest>]      needs bash and jq
set -euo pipefail
cd "$(dirname "$0")/../.."

# jq.exe (Git Bash on Windows) ends its lines with CRLF; strip it so the comparisons below work everywhere.
jq() { command jq "$@" | tr -d '\r'; }

tag=${1:-}
file=${2:-docker/versola-tools/release.json}
status=0
fail() { echo "FAIL: $*" >&2; status=1; }

[[ -f "$file" ]] || { echo "FAIL: $file does not exist" >&2; exit 1; }
jq -e . "$file" >/dev/null 2>&1 || { echo "FAIL: $file is not valid JSON" >&2; exit 1; }

[[ "$(jq -r '.schemaVersion' "$file")" == 1 ]] || fail "schemaVersion must be 1"
version=$(jq -r '.version | strings' "$file")
[[ -n "$version" ]] || fail "version must be a non-empty string"
[[ "$(jq -r '.notes | strings' "$file")" == https://* ]] || fail "notes must be an https link"
# The entrypoint rewrites the "version" line by line: it has to stay on a line of its own.
grep -qE '^  "version": "[^"]*",$' "$file" || fail 'the "version" entry must be one line: `  "version": "x.y.z",`'

exact='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'
glob='^[0-9]+(\.[0-9]+)*\.\*$'

lists=()
for list in rollingFrom freshDatabaseFrom; do
  if ! jq -e --arg l "$list" '(.[$l] | type) == "array" and all(.[$l][]; type == "string")' "$file" >/dev/null; then
    fail "$list must be an array of strings"
    continue
  fi
  lists+=("$list")
  while IFS= read -r p; do
    [[ "$p" =~ $exact || "$p" =~ $glob ]] || fail "$list: '$p' is neither an exact version nor a prefix glob like 0.7.*"
  done < <(jq -r --arg l "$list" '.[$l][]' "$file")
done

# Two entries overlap when some version matches both: equal, or one is a glob whose prefix starts the other.
overlap() {
  local a=$1 b=$2 pa pb
  if [[ "$a" == *'*' ]]; then pa=${a%\*}; else pa=; fi
  if [[ "$b" == *'*' ]]; then pb=${b%\*}; else pb=; fi
  if [[ -n "$pa" && -n "$pb" ]]; then [[ "$a" == "$pb"* || "$b" == "$pa"* ]]
  elif [[ -n "$pa" ]]; then [[ "$b" == "$pa"* ]]
  elif [[ -n "$pb" ]]; then [[ "$a" == "$pb"* ]]
  else [[ "$a" == "$b" ]]
  fi
}

if [[ ${#lists[@]} -eq 2 ]]; then
  while IFS= read -r r; do
    while IFS= read -r f; do
      if overlap "$r" "$f"; then fail "rollingFrom '$r' and freshDatabaseFrom '$f' name the same version"; fi
    done < <(jq -r '.freshDatabaseFrom[]' "$file")
  done < <(jq -r '.rollingFrom[]' "$file")
  for list in "${lists[@]}"; do
    while IFS= read -r p; do
      if [[ -n "$version" ]] && overlap "$p" "$version"; then fail "$list '$p' matches this release's own version $version"; fi
    done < <(jq -r --arg l "$list" '.[$l][]' "$file")
  done
fi

if [[ -n "$tag" && "$(jq -r ".notes | strings" "$file")" != */"$tag" ]]; then
  fail "notes must end in /$tag (the release page of this version), not a copy of the previous release link"
fi
if [[ -n "$tag" && "$version" != "$tag" ]]; then
  fail "version is '$version' but the release is '$tag': update docker/versola-tools/release.json (what can roll to $tag, what needs a fresh database) before publishing"
fi

[[ $status -eq 0 ]] && echo "ok: $file${tag:+ (version $tag)}"
exit $status
