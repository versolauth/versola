# Security

Versola is an identity provider: a mistake here is a vulnerability, not a bug. Protocol specifics
are in `oauth-oidc-protocols.md`, log hygiene in `observability.md`; this file is the rest.

## Default stance

- Fail closed. When a check cannot be completed (a key is missing, a lookup fails, a header is
  absent), the request is refused, not allowed.
- Do not loosen a security check to make a test, a demo or a client work (CLAUDE.md "Ask Before").
  If a legitimate case needs an exception, say what it is and ask.
- A security-relevant branch has a negative test next to the positive one (`testing.md`).
- If you notice a vulnerability while doing something else, tell the user in the conversation. Do
  not put exploit details in a public issue, commit message or PR description before it is fixed;
  `SECURITY.md` describes private reporting.

## Secrets

- Never write a real secret into the repository: not in code, tests, docs, config, Dockerfiles,
  workflows or examples. Generated dev configs (`*/dev/`) and `.local/` are gitignored; keep it so.
  Test and local-dev values (`develop.md`'s local admin login, `gen-env.scala` output) are fine
  there and nowhere else.
- Production secrets live in OpenBao; configs carry only `${VAR}` placeholders that
  `versola-cli` resolves. Services never talk to OpenBao.
- A new secret is added in every place that enumerates secrets, or CI fails on drift:
  `SecretSchema.specs` in `scripts/gen-env.scala` (and its `secrets.schema.json`), the chart's
  `versola.requiredSecretVars`, and the service's config model as a `Secret`. The checks are
  `.github/scripts/check-secret-schema.sh` and `check-chart-secret-vars.sh`.
- Never log, return in an error, or put into a metric label a secret, token, password, OTP or key.
- Cryptography uses the libraries and helpers already in the project; no custom algorithms.

## Dependencies and supply chain

- Adding a dependency needs approval (CLAUDE.md). Check it is maintained and that its license is
  compatible with `LICENCE.md` before proposing it.
- Dependabot covers GitHub Actions, base images and npm; Scala dependencies are watched through the
  dependency graph. Fix SLAs by severity are in `SECURITY.md` (critical 7 days, high 30, medium 90).
  A finding is accepted rather than fixed only through `.trivyignore` (or the tool's own
  suppression) with the reason, an owner and a re-evaluation date; never suppress silently.
- Workflow actions are pinned to a full commit SHA with the version in a comment; base images are
  pinned by digest. Do not replace a pin with a floating tag.
- Scanners in `security.yml` (TruffleHog, dependency review, Trivy, npm audit, CodeQL, Semgrep) say
  in a comment next to each job whether it gates or only reports. Do not change that without being
  asked.

## Review

Changes under `auth/`, `central/`, `edge/`, `util/`, `docker/` and `.github/` need a code-owner
review (`.github/CODEOWNERS`). For a change to authentication, token, certificate or session logic,
offer to run the `security-review` skill before the PR is opened.
