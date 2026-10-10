#!/usr/bin/env bash
# PostToolUse (Edit|Write): the CI rule "no raw .connect/.transact in src/main" (ci-cd.yml,
# "Enforce measured DB access"), run on the file just written so the mistake is seen immediately.
# Exit 2 sends stderr to Claude. Needs jq.
set -uo pipefail

command -v jq >/dev/null || { echo "check-scala-edit: jq not found, check skipped" >&2; exit 1; }

path=$(jq -r '.tool_input.file_path // empty')
case "$path" in
  */src/main/*.scala) ;;
  *) exit 0 ;;
esac
case "$path" in
  */util/implementations/postgres/src/main/scala/versola/util/postgres/BasicCodecs.scala) exit 0 ;;
esac
[ -f "$path" ] || exit 0

# Same pattern as CI: a .connect/.transact call that is not connectMeasured/transactMeasured.
hits=$(grep -nE '\.(connect|transact)[^a-zA-Z_]' "$path" | grep -vE '(connect|transact)Measured')
if [ -n "$hits" ]; then
  {
    echo "Raw .connect / .transact in production code; CI will fail the build. Use .connectMeasured(...) / .transactMeasured(...):"
    echo "$hits"
  } >&2
  exit 2
fi
exit 0
