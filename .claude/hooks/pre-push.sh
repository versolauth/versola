#!/usr/bin/env bash
# PreToolUse (Bash, only for `git push ...`): what CLAUDE.md "Git" forbids or requires of a push.
#  1. never force-push;
#  2. never push to main/master (work happens on a feature branch);
#  3. Scala formatting: once the project-wide scalafmt reformat has landed, every push is checked with
#     `scalafmt --mode diff --diff-branch origin/main --test`. That check is OFF until the file
#     .claude/scalafmt-enforced exists (create it in the reformat change): scalafmt checks whole files,
#     so before the reformat it would fail on legacy code nobody touched.
# Needs jq.
set -uo pipefail

command -v jq >/dev/null || { echo "pre-push: jq not found, push guard skipped" >&2; exit 1; }

cmd=$(jq -r '.tool_input.command // empty')
root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"

deny() {
  jq -n --arg r "$1" '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$r}}'
  exit 0
}

# Only the part from `git push` on, up to a command separator.
push=$(sed -E 's/.*(git[[:space:]]+push)/\1/; s/(\&\&|\|\||;|\|).*//' <<<"$cmd")
case "$push" in "git push"*) ;; *) exit 0 ;; esac

# 1. force
if grep -qE '(^|[[:space:]])(-f|--force|--force-with-lease(=[^[:space:]]*)?|--force-if-includes)([[:space:]]|$)' <<<"$push" \
   || grep -qE '[[:space:]]\+[^[:space:]]+' <<<"$push"; then
  deny "Force-push is not allowed (CLAUDE.md Git). Ask the operator."
fi

# 2. target branch: positional args after `git push` are <remote> [<refspec>...]; no refspec means the current branch.
set -- $push
shift 2
positional=()
for a in "$@"; do case "$a" in -*) ;; *) positional+=("$a") ;; esac; done
current=$(git -C "$root" branch --show-current 2>/dev/null)
targets=()
if [ "${#positional[@]}" -le 1 ]; then
  targets+=("$current")
else
  for spec in "${positional[@]:1}"; do
    dst="${spec##*:}"; dst="${dst#refs/heads/}"
    [ "$dst" = "HEAD" ] && dst="$current"
    targets+=("$dst")
  done
fi
for t in "${targets[@]}"; do
  case "$t" in main|master) deny "Pushing to '$t' is not allowed (CLAUDE.md Git): push a feature branch and open a pull request." ;; esac
done

# 3. formatting (gated)
if [ -f "$root/.claude/scalafmt-enforced" ]; then
  if ! command -v scalafmt >/dev/null; then
    deny "scalafmt is not installed; it is required before a push (CLAUDE.md Git)."
  fi
  if ! out=$(cd "$root" && scalafmt --mode diff --diff-branch origin/main --test 2>&1); then
    deny "Unformatted Scala in this change. Run 'scalafmt --mode diff --diff-branch origin/main', review and commit the result, then push again."
  fi
fi
exit 0
