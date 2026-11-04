#!/usr/bin/env bash
#
# Checks docker/versola-tools/release.json, the compatibility manifest versola-tools writes to
# /out/release.json (versolauth/versola#823), which `versola rollout` and `versola configure` read.
# The file keeps one entry per release, newest first:
#
#   {"schemaVersion": 1, "releases": [{"version", "rollingFrom": [], "freshDatabaseFrom": [], "notes"}, ...]}
#
# Each entry says, about its own release: from which running versions it can be rolled to without
# stopping (`rollingFrom`) and from which there is no way in at all (`freshDatabaseFrom`). A version
# in neither list is "unknown", and the CLI then asks the operator. Checked here:
#
#   - schemaVersion is 1 and `releases` is a non-empty array of entries with a `version`, an https
#     `notes` link and the two lists;
#   - versions are unique and in strictly descending order (a pre-release sorts below its release),
#     so a copied entry, or one added at the bottom, is caught;
#   - every list item is an exact version (0.7.5, 0.8.0-rc1) or a prefix glob (0.7.*): no other
#     pattern syntax, no semver ranges, so the CLI's matcher stays one line;
#   - within an entry no version is claimed both rolling and fresh-database, and freshDatabaseFrom does
#     not match the entry's own version (rollingFrom may: `0.7.*` for a 0.7.x release is the natural
#     way to say "any earlier 0.7", and rolling from the version already running is a redeploy);
#   - with an argument (the release tag), the first entry is that tag and its `notes` link ends in it.
#     CI passes the release tag; run it by hand with the tag you are about to publish (release.md): a
#     published tag cannot be reused, so a manifest that does not match must be found before Publish.
#
# Not checked here: that entries of earlier releases were left alone (a diff against the base branch).
#
# Usage: check-release-manifest.sh [<tag>] [<manifest>]      needs bash and jq
set -euo pipefail
# A manifest given as a relative path is relative to where the caller stands, not to the repo root the script moves to next.
if [[ -n "${2:-}" && "$2" != /* ]]; then set -- "${1:-}" "$PWD/$2"; fi
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
if ! jq -e '(.releases | type) == "array" and (.releases | length) > 0 and all(.releases[]; type == "object")' "$file" >/dev/null; then
  echo "FAIL: releases must be a non-empty array of objects" >&2
  exit 1
fi

exact='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$'
glob='^[0-9]+(\.[0-9]+)*\.\*$'

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

# older_than A B: A is a lower version than B. Cores compare numerically; at equal cores a pre-release
# (0.8.0-rc1) is lower than its release (0.8.0), and two pre-releases compare as version strings.
older_than() {
  local a=$1 b=$2 ca cb pa pb
  ca=${a%%-*}; cb=${b%%-*}
  pa=; pb=
  [[ "$a" == *-* ]] && pa=${a#*-}
  [[ "$b" == *-* ]] && pb=${b#*-}
  if [[ "$ca" != "$cb" ]]; then
    [[ "$(printf '%s\n%s\n' "$ca" "$cb" | sort -V | head -n 1)" == "$ca" ]]
  elif [[ -n "$pa" && -z "$pb" ]]; then true
  elif [[ -z "$pa" || "$pa" == "$pb" ]]; then false
  elif [[ -z "$pb" ]]; then false
  else [[ "$(printf '%s\n%s\n' "$pa" "$pb" | sort -V | head -n 1)" == "$pa" ]]
  fi
}

count=$(jq -r '.releases | length' "$file")
prev=
for ((i = 0; i < count; i++)); do
  entry=".releases[$i]"
  version=$(jq -r "$entry.version | strings" "$file")
  label="releases[$i] ($version)"
  if [[ ! "$version" =~ $exact ]]; then
    fail "releases[$i]: version '$version' is not an exact version like 0.7.6"
    prev=; continue
  fi
  [[ "$(jq -r "$entry.notes | strings" "$file")" == https://* ]] || fail "$label: notes must be an https link"

  if [[ -n "$prev" ]]; then
    if [[ "$version" == "$prev" ]]; then fail "$label: version appears twice"
    elif ! older_than "$version" "$prev"; then fail "$label: versions must be in descending order, newest first ($prev is above it)"
    fi
  fi
  prev=$version

  lists=()
  for list in rollingFrom freshDatabaseFrom; do
    if ! jq -e --arg l "$list" "($entry[\$l] | type) == \"array\" and all($entry[\$l][]; type == \"string\")" "$file" >/dev/null; then
      fail "$label: $list must be an array of strings"
      continue
    fi
    lists+=("$list")
    while IFS= read -r p; do
      [[ "$p" =~ $exact || "$p" =~ $glob ]] || fail "$label: $list '$p' is neither an exact version nor a prefix glob like 0.7.*"
    done < <(jq -r --arg l "$list" "$entry[\$l][]" "$file")
  done

  if [[ ${#lists[@]} -eq 2 ]]; then
    while IFS= read -r r; do
      while IFS= read -r f; do
        if overlap "$r" "$f"; then fail "$label: rollingFrom '$r' and freshDatabaseFrom '$f' name the same version"; fi
      done < <(jq -r "$entry.freshDatabaseFrom[]" "$file")
    done < <(jq -r "$entry.rollingFrom[]" "$file")
    while IFS= read -r f; do
      if overlap "$f" "$version"; then fail "$label: freshDatabaseFrom '$f' matches this release's own version"; fi
    done < <(jq -r "$entry.freshDatabaseFrom[]" "$file")
  fi
done

if [[ -n "$tag" ]]; then
  first=$(jq -r '.releases[0].version | strings' "$file")
  if [[ "$first" != "$tag" ]]; then
    fail "the newest entry is '$first' but the release is '$tag': add an entry for $tag at the top of docker/versola-tools/release.json (what can roll to $tag, what needs a fresh database) before publishing"
  elif [[ "$(jq -r '.releases[0].notes | strings' "$file")" != */"$tag" ]]; then
    fail "the notes of the $tag entry must end in /$tag (the release page of this version), not a copy of the previous release link"
  fi
fi

[[ $status -eq 0 ]] && echo "ok: $file${tag:+ (release $tag)}"
exit $status
