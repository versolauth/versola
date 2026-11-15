#!/usr/bin/env bash
#
# Guards the Flyway migrations a pull request touches (#616, #705). Two rules, both about what an
# operator's `versola migrate` will meet on a database that already ran an older release:
#
#   1. IMMUTABLE. Every migration file in the latest stable release tag must be in the checked-out tree,
#      unchanged. An edited migration fails Flyway's checksum validation on every database that applied
#      it, and the only way forward is recreating the schema (deploy.md, "Recreating the database"); a
#      removed or renamed one fails the same validation as a missing migration. Compared against the
#      RELEASE TAG, not `main`: what has shipped may be applied anywhere, while a migration that is only on
#      `main` has reached no operator, and fixing it before the release is legitimate.
#   2. ORDERED. A migration a PR adds must have a version greater than every version already on the
#      base branch, and no two files of one directory may share a version. `versola migrate` runs Flyway
#      without `outOfOrder`, so a lower version that arrives after a higher one was applied is not applied:
#      Flyway reports it as "ignored" (resolved, never applied, below the latest applied version: "Detected resolved migration not applied") and the run fails. Two PRs that
#      picked the same "next" number are exactly this case: the second must be renumbered before it merges.
#
# Escape hatch for rule 1 only, for the rare legitimate case (a migration that provably never ran
# anywhere): ALLOW_MIGRATION_EDIT=true, which the workflow sets when the PR carries the
# `allow-migration-edit` label. Rule 2 has no switch: there is no reviewable case for a migration
# Flyway will refuse to apply.
#
# Usage (repo root, full history and tags fetched):
#   BASE_REF=origin/main HEAD_REF=HEAD bash .github/scripts/check-migrations-immutable.sh
# Optional: RELEASE_TAG=<tag> to compare against that tag instead of the latest stable one.
#
# Locally, to try it on a branch: `git fetch origin main --tags`, then the line above.
set -euo pipefail

BASE_REF="${BASE_REF:-origin/main}"
HEAD_REF="${HEAD_REF:-HEAD}"
ALLOW_EDIT="${ALLOW_MIGRATION_EDIT:-false}"

# Every Flyway migration set in the repository: `<service>/implementations/postgres/migrations/` and
# loadgen's own `loadgen/migrations/`. Matching by file name keeps a new service covered without
# editing this script.
PATHSPEC=':(glob)**/migrations/V*.sql'

git rev-parse --verify --quiet "$HEAD_REF^{commit}" >/dev/null || { echo "::error::HEAD_REF $HEAD_REF is not a commit"; exit 1; }
git rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null || { echo "::error::BASE_REF $BASE_REF is not a commit (fetch it)"; exit 1; }

fail=0

# --- Rule 1: released migrations are immutable ----------------------------------------------------------
# Stable tags only (no `-` in the name, `release.md`): a pre-release is not what operators upgrade from. Only tags
# reachable from the base branch count, so a hotfix tag cut elsewhere never becomes the baseline.
tag="${RELEASE_TAG:-$(git tag --merged "$BASE_REF" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)}"
if [ -z "$tag" ]; then
  # Fail closed: with no tag there is nothing to compare against, and a shallow clone looks the same.
  echo "::error::No release tag found. Fetch tags (actions/checkout with fetch-depth: 0) before running this check."
  exit 1
fi
echo "Rule 1: released migrations of $tag must be unchanged in $HEAD_REF"

# Fail closed if the pathspec stops matching anything: an empty tag would make the rule pass trivially.
tag_files="$(git -c core.quotepath=off ls-tree -r --name-only "refs/tags/$tag")"
grep -qE '(^|/)migrations/V[^/]*\.sql$' <<<"$tag_files" || { echo "::error::Release $tag has no migrations; the check would pass vacuously."; exit 1; }

# What THIS branch changed since it left the base branch (three dots = from the merge-base), M = edited,
# D = removed, T = type change (a symlink in its place); --no-renames reports a rename as D (old path) + A (new path). Judged by the branch's own
# changes, not by `tag..HEAD`: a branch cut before the latest tag lacks files the tag has, and after a
# maintainer let one edit through with the label, `main` differs from the tag until the next release;
# neither is this PR's doing. Of those, only a file the tag has and whose content now differs from the tag
# is a violation (an edit that restores the tag's content is not).
# Captured first: a failing `git diff` inside `< <(...)` would not stop the script and would read as "no changes".
branch_changes="$(git -c core.quotepath=off diff --no-renames --name-status --diff-filter=MDT "$BASE_REF...$HEAD_REF" -- "$PATHSPEC")"
violations=""
while IFS=$'\t' read -r status path; do
  [ -n "$path" ] || continue
  git cat-file -e "refs/tags/$tag:$path" 2>/dev/null || continue
  if ! git cat-file -e "$HEAD_REF:$path" 2>/dev/null || ! git --literal-pathspecs diff --quiet "refs/tags/$tag" "$HEAD_REF" -- "$path"; then
    violations+="$status	$path"$'\n'
  fi
done <<<"$branch_changes"
violations="${violations%$'\n'}"
if [ -n "$violations" ]; then
  if [ "$ALLOW_EDIT" = "true" ]; then
    echo "::warning::Released migration(s) differ from $tag, allowed by the allow-migration-edit label:"
    echo "$violations" | sed 's/^/  /'
  else
    {
      echo "::error title=Released migration modified::A migration that shipped in release $tag was edited or removed (M = edited, D = removed or renamed, T = replaced by a symlink):"
      echo "$violations" | sed 's/^/  /'
      echo
      echo "Flyway checksums every applied migration: on a database that already ran this file, 'versola migrate'"
      echo "fails validation until the schema history is repaired or the schema recreated (deploy.md, \"Recreating the database\"): both are operator work."
      echo "Put the change in a NEW migration file with the next higher version instead."
      echo "If this migration provably never ran anywhere, a maintainer can add the 'allow-migration-edit' label to the PR."
    } >&2
    fail=1
  fi
fi

# --- Rule 2: new migrations have a version above everything on the base branch ----------------------------
echo "Rule 2: migrations added since $BASE_REF must have a version above all of its versions"
# One `<dir>\t<version>\t<path>` line per migration (tab-separated: a path may hold spaces); the version is a
# number (leading zeros dropped). `core.quotepath=off` keeps non-ASCII names from being C-quoted.
# A migration file whose name is not `V<digits>__*.sql` is not understood by this rule (Flyway would still
# load `V1_1__x.sql` as version 1.1), so it is refused rather than skipped silently.
table() {
  local all parsed
  all="$(git -c core.quotepath=off ls-tree -r --name-only "$1" | grep -E '(^|/)migrations/V[^/]*\.sql$' || true)"
  parsed="$(printf '%s\n' "$all" | awk -F'\n' -v OFS='\t' '
    /(^|\/)migrations\/V[0-9]+__[^\/]*\.sql$/ {
      n = split($0, parts, "/"); file = parts[n]; dir = substr($0, 1, length($0) - length(file) - 1)
      match(file, /^V[0-9]+/); v = substr(file, 2, RLENGTH - 1) + 0
      print dir, v, $0 }')"
  if [ "$(printf '%s\n' "$all" | grep -c .)" != "$(printf '%s\n' "$parsed" | grep -c .)" ]; then
    echo "::error::A migration in $1 is not named V<number>__<name>.sql, which this check cannot order:" >&2
    diff <(printf '%s\n' "$all") <(printf '%s\n' "$parsed" | cut -f3) | grep '^<' >&2 || true
    exit 1
  fi
  printf '%s\n' "$parsed"
}
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
table "$BASE_REF" >"$tmp/base"
table "$HEAD_REF" >"$tmp/head"
# Fail closed: an empty base table would let every added version pass.
[ "$(grep -c . "$tmp/base")" -gt 0 ] || { echo "::error::No migrations found in $BASE_REF; the version-order rule would pass vacuously."; exit 1; }

# An added file is one in HEAD whose path is not on the base branch; it is judged against the base
# branch's highest version in its directory (0 for a directory the base branch does not have yet).
# A file renamed within the base branch's own set counts as added: rename an unreleased migration by
# giving it a new, higher version.
awk -F'\t' '
  NR == FNR { if ($2 > max[$1]) max[$1] = $2; seen[$3] = 1; next }
  !($3 in seen) { added++; if ($2 <= max[$1] + 0) printf "%s: version %d, but the base branch already has %d in %s (a file renamed from one on the base branch counts as new)\n", $3, $2, max[$1] + 0, $1 }
  END { print added + 0 > "'"$tmp"'/added" }
' "$tmp/base" "$tmp/head" >"$tmp/problems"
added="$(cat "$tmp/added")"
if [ -s "$tmp/problems" ]; then
  {
    echo "::error title=Migration version not above the base branch::a migration added by this branch is not newer than $BASE_REF's:"
    sed 's/^/- /' "$tmp/problems"   # "- ", not blanks: the runner trims blanks before looking for ::, and a name starting with :: must not parse as a workflow command
    echo
    echo "Rename it to a version above the base branch's highest (and again after a rebase onto a newer base)."
    echo "Flyway runs without outOfOrder: a lower version arriving after a higher one was applied is never applied"
    echo "and fails 'versola migrate' (deploy.md)."
  } >&2
  fail=1
fi

# Two files with one version in one directory are refused by Flyway at load time; name them here.
dups="$(awk -F'	' '{ c[$1 " V" $2]++ } END { for (k in c) if (c[k] > 1) print k }' "$tmp/head")"
if [ -n "$dups" ]; then
  echo "::error title=Duplicate migration version::Two migrations share a version: $(echo "$dups" | tr '\n' ';')" >&2
  fail=1
fi

if [ "$fail" -ne 0 ]; then exit 1; fi
if [ -n "$violations" ]; then
  echo "DONE with the allow-migration-edit label: $added migration(s) added since $BASE_REF; the released migration(s) listed above differ from $tag."
else
  echo "OK: $added migration(s) added since $BASE_REF, none of $tag's modified."
fi
