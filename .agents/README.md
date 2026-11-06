# Agent setup

Open this repository itself as the Codex project (or start Codex in it), rather than the
parent folder containing other checkouts. Root `AGENTS.md` supplies the shared policy; Claude
Code reads the same file through `CLAUDE.md`. No personal Codex config changes are required.

Detailed rules remain in `.claude/rules/` and are routed explicitly by `AGENTS.md`. Claude hook
registration stays in `.claude/settings.json`; Codex follows the documented guard commands and
permissions in `AGENTS.md`. No hooks or permission settings are silently installed for Codex.

Update the shared policy once. When adding a scoped rule, update the rule index in `AGENTS.md`
and retain the `paths` frontmatter for Claude. Keep local credentials and machine-specific MCP
configuration out of committed agent files. ScalaSemantic guidance applies when that MCP is
available; this setup does not provision an MCP server.

References: [Codex project instructions](https://developers.openai.com/codex/guides/agents-md).
