#!/usr/bin/env bash
# PreToolUse (Edit|Write): paths that must not be edited, or only with the operator's say-so.
# Deterministic counterpart of CLAUDE.md "Ask Before" and the rules in .claude/rules/.
# Needs jq. Prints a permissionDecision JSON; no output means "no opinion".
set -uo pipefail

command -v jq >/dev/null || { echo "guard-paths: jq not found, path guard skipped" >&2; exit 1; }

input=$(cat)
path=$(jq -r '.tool_input.file_path // empty' <<<"$input")
[ -n "$path" ] || exit 0

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
rel="${path#"$root"/}"

decide() { # decision, reason
  jq -n --arg d "$1" --arg r "$2" \
    '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:$d,permissionDecisionReason:$r}}'
  exit 0
}

case "$rel" in
  central/src/main/resources/forms/*)
    decide deny "Generated output (gitignored). Edit central-ui/forms/ and run 'npm run build:forms' (see .claude/rules/frontend.md)." ;;
  */implementations/postgres/migrations/V*.sql)
    # A migration already on main may have been applied somewhere: Flyway checksums it, so editing it
    # breaks every database that ran it (deploy.md 9.1). New files are fine.
    if git -C "$root" cat-file -e "origin/main:$rel" 2>/dev/null; then
      decide ask "$rel exists on origin/main, so it may already be applied. Released migrations are immutable: add a new migration instead (.claude/rules/database-migrations.md)."
    fi ;;
  .github/workflows/*|.github/CODEOWNERS|SECURITY.md|project/Dependencies.scala)
    decide ask "$rel is on the CLAUDE.md 'Ask Before' list (CI, ownership, security policy, dependencies)." ;;
esac
exit 0
