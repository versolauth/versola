---
paths: ["central-ui/**", "auth/**", "central/**", "edge/**", "util/**", "**/open-api/*.yaml", "deploy.md", "develop.md", "k8s/**", "docker/**", "services.yml"]
---

# Documentation Rules

The product documentation is the separate `versolauth/versola-website` repository (GitHub), under
`src/content/docs/` (English) and `src/content/docs/ru/` (Russian twin of every page). It is not part
of this checkout; if it is not available locally, work on a clone of it. It documents the **released**
product, so a docs pull request is opened together with the code change and merged when the release
that contains the change goes out. Read `.claude/docs-style-guide.md` in that repository before you
write a page.

A change is not done until its docs impact is handled or explicitly ruled out. The PR description
carries a **Docs** line with one of: a link to the `versola-website` PR, or `none: <reason>` (for
example "internal refactor, no behaviour change"). A bare "none" is not accepted.

Do not open a follow-up issue for documentation, and do not leave the docs for later: the docs PR is
opened in the same session as the code PR, as a draft if it has to wait for the release, and its link
is the Docs line. If the website repository cannot be reached, say so and stop; an issue is not a
substitute for the PR.

## What triggers a docs change

| You change | Update in `versola-website` |
|---|---|
| Anything a user sees in the admin console (`central-ui`): a screen, a form field, a label, a button, a default, a validation message, navigation, a new or removed screen | The page that documents the screen (`entities/*`, `console/*`, `how-to/*`) **and its screenshots**, see "Screenshots" |
| Behaviour a standard defines: authorize, token, PAR, DPoP, mTLS, JAR/JARM, introspection, revocation, discovery metadata, client authentication, security profiles | The standard's checklist page in `rfc/`, see "Standards" |
| A new standard is implemented, or part of one is added or dropped | A new page in `rfc/` (copy the structure of a neighbour), plus the table in `.claude/rules/oauth-oidc-protocols.md` |
| Security profile rules (`standard`, `fapi2`) | `concepts/security-profiles` and the profile table in the affected `rfc/` pages |
| Sender-constrained tokens, token lifetimes, session or cookie behaviour | `concepts/sender-constrained-tokens`, and `concepts/compromises` if a trade-off is added or removed |
| `edge`: policies (CEL variables, effects), routes, native-app endpoints, configuration, certificates | `edge/*`, and `how-to/protect-an-api-with-edge`, `how-to/native-mobile-app` when their steps change |
| A config key, env var, CLI flag, default, secret, migration or deployment step | `operations/*`, `getting-started/*` when the first-run path changes, and `deploy.md` or `develop.md` here |
| A public endpoint, parameter, status or error code | The OpenAPI YAML here (`openapi-specs.md`). The site only links to those files, so also check `reference/openapi` if a spec is added, moved or renamed |
| A known limitation, trade-off or unsupported case appears or disappears | The affected page, and `concepts/compromises` for a deliberate trade-off |
| A release (version, key changes) | `about/changelog` (key changes only, link to the GitHub release); the install and Kubernetes pages if image tags or the chart version are named there |

No docs change is needed for: refactors with identical behaviour, tests, build and CI changes,
log and metric changes that no page names, and bug fixes that restore documented behaviour. If a
bug fix reveals that the page was wrong, fix the page.

## Screenshots (admin console)

The console is documented with screenshots; a stale one is a bug.

- Retake every screenshot whose screen your change alters, in the same docs PR. Walk the page, not
  only the field you touched: a renamed label or a moved button changes the whole frame.
- Files live in `public/img/docs/console/` and `public/img/docs/entities/` of `versola-website`.
  Each screenshot is a pair: dark `name.png` and light `name-light.png`, 1440x900. Keep the file
  names; pages and both language twins reference them. A new screen adds a new pair and a
  `Screenshot` entry in the EN page and its RU twin.
- Generate them with `npm run docs:screenshots` in `central-ui` (the manifest
  `tests/docs-screenshots.manifest.ts`, see `central-ui/design-system/README.md` "Docs screenshots")
  and copy them with `npm run docs:screenshots:sync -- <website-checkout>`; never take them by hand
  or from a shared or production environment. Use `example.com`-style data; no real hostnames,
  emails or secrets.
- If a screen is removed, delete its files and references (the site build checks links, not images,
  so grep for the file name).
- Alt text and captions describe what the screenshot proves; update them with the picture.

## Standards (RFC and OpenID)

Each page under `rfc/` is a checklist: one line per requirement, with its section, whether Versola
enforces it, and the test that proves it. It is the statement of what the product promises
(`oauth-oidc-protocols.md`).

- Behaviour changes with respect to a standard: update the matching lines in the same change. A
  newly enforced requirement gets a checked line that names its test. A requirement the code stops
  enforcing is unchecked, not deleted. A deliberate departure from the standard stays visible as a
  line saying so.
- Recount the page's `Checked claims: N of M` status line; it must match the list.
- Open the RFC section before you write a claim. A line you cannot trace to code or a test is left
  out. Never claim certification or conformance results: Versola is not certified.
- A behaviour change that no page covers because no page exists for that standard gets a new page,
  with frontmatter `section: standards`, a unique `order`, and its RU twin.
- Cite by the standard's text, not by how the code looks. If the code and the standard disagree,
  the page says so.

## Mechanics

- Every page exists in both languages with the same headings, code blocks and screenshots in the
  same order. A change that touches one language without the other is incomplete.
- Say limits in the page, not only in the standards checklist. Do not describe how any particular
  deployment runs; describe what the product supports.
- Do not document an undeclared or unreleased feature (for example anything behind a flag that is
  off by default and not in release notes) unless the maintainers ask for it.
- Run the site's build (`npm run build` in `versola-website`) before opening the docs PR: it checks
  frontmatter and renders every page. Check the changed pages in the dev server.
- Docs PRs follow that repository's rules; the Docs line in the code PR links to them.
