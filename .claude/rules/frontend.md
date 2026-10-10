---
paths: ["central-ui/**"]
---

# Frontend (`central-ui`)

Two different things live here; do not mix their conventions.

| | Admin console | Login forms |
|---|---|---|
| Where | `central-ui/src/` | `central-ui/forms/<name>/` |
| Stack | Lit web components, TypeScript, Vite | Solid.js (`<name>.tsx`, `<name>.css`, `<name>.i18n.json`) |
| Shipped as | static assets served by nginx | bundles copied into `central`'s resources |

## Commands (run from `central-ui/`)

```bash
npm ci                    # install exactly the lockfile; npm only (yarn/pnpm lockfiles are ignored)
npm run dev               # admin console on :3000
npm run type-check        # tsc --noEmit
npm run test:unit         # Vitest: src/**/*.test.ts
npm run test:ui           # Playwright: tests/*.spec.ts (starts its own dev server)
npm run build:forms       # forms -> ../central/src/main/resources/forms/
```

## Forms

- `central/src/main/resources/forms/` is **generated and gitignored**; edit `central-ui/forms/`,
  never the output. `central` fails at runtime without the built forms (`deploy.md` 9.2), and CI and
  the Docker build run `build:forms` before staging.
- `scripts/build-forms.mjs` lists the forms by hand. A new form directory must be added to that list
  or it is silently not built.
- The login forms handle credentials, OTPs and passkeys: no secrets or user input in `console.*`, no
  secrets in URLs, and the CSRF value from the conversation step is sent back unchanged.
- User-facing text goes in the form's `.i18n.json`, not into the component.

## Admin console

- Follow `central-ui/UI_STYLE_GUIDE.md` (shell, page structure, cards, the single primary action per
  page, theme tokens) and reuse `src/styles/theme.ts`, `src/styles/components.ts` and existing
  components before adding styles or a new component. Read the guide's representative screens
  (`clients-list.ts`, `client-form.ts`) before building a new list or form screen.
- Colours are tokens defined per theme in `index.html`; a new colour is a new token with a value in
  both the `light` and `dark` themes, never a literal in a component. Keep the guide current when
  you change the design system.
- API access goes through `src/utils/central-api.ts`. A backend change to a `central` request or
  response updates the client and the OpenAPI spec together (`openapi-specs.md`).
- The admin UI reaches `central` only through `edge` in production; do not hardcode service URLs.

## Show the screen before you change it

A UI change is judged by looking at it. For any change that alters what a screen looks like:

1. **Before editing**, render the current state of each affected screen and show it to the
   operator (the user). Then make the change, render it again, and show the result next to the
   baseline. A brand-new screen has no baseline; show the result.
   **For a new screen or a redesign, stop after showing it and wait for the operator's go-ahead**
   before building the rest. For an ordinary edit to an existing screen, show the baseline and carry
   on.
2. **How to render the admin console:** Playwright, which is already a dev dependency. It starts the
   dev server in `PLAYWRIGHT=true` mode and the API is mocked (`tests/mocks.ts`), so no backend is
   needed. Write a throwaway spec that calls `loadAdminApp(page, { path, state })`, sets the viewport,
   and `page.screenshot({ path: 'test-results/ui-review/<name>.png' })`; run it with
   `npx playwright test <file>`, and delete the spec before committing. `test-results/` is gitignored.
   `tests/docs-screenshots.spec.ts` is a working example (including full-page shots).
3. **Login forms** have no mock harness in the repo that I know of; render them against the running
   stack (`develop.md`) or say that you could not.
4. **Show it, do not describe it:** open the PNG yourself to inspect it, and give the operator a
   markdown link to the file (it is inside the session's folder). If you cannot produce a screenshot,
   say so and do not claim the UI works.
5. Take the screenshots at the widths and themes below, not only at one default.

## UI quality criteria

Check each of these on the screenshots and, where listed, with a script. A defect found is fixed
before the change is shown as done. Numbers are from WCAG 2.2 unless noted.

**Layout (look at the picture)**
- Nothing overlaps unintentionally: buttons, inputs, labels, badges, toasts, dropdowns, modals.
- Nothing is clipped: text, focus rings and shadows are not cut by `overflow: hidden`; truncation is
  deliberate (ellipsis, full text reachable by tooltip or expansion).
- No horizontal page scrollbar at any checked width; at most one scroll container per axis.
- Alignment and spacing come from the shared styles (`src/styles/theme.ts`, `components.ts`), colours
  from the theme tokens in `index.html`, not magic numbers;
  elements in a row or column line up.
- Layering is right: dropdowns, modals and toasts sit above sticky/fixed bars, and a sticky header
  does not cover the focused element (SC 2.4.11).

**Widths and themes**
- Check 1440 and 1024 for the console, and 375 and 320 for login forms (they must be usable at 320;
  SC 1.4.10 reflow: no two-way scrolling). A data table may scroll inside its own container.
- The console has a `light` (default) and a `dark` theme (`data-theme`); check both.

**States, not just the happy path**
- Empty, loading (no layout jump), error, disabled, hover, focus, validation (inline, per field),
  success, a very long unbroken string, a one-character value, and many items.
- A pending submit disables the control or ignores a second click: one click or Enter sends one
  request.
- Leave room for longer translations; no fixed-width buttons.

**Accessibility minimums**
- Text contrast at least 4.5:1 (3:1 for large text, 18pt or 14pt bold); control borders, icons and
  focus indicators at least 3:1 (SC 1.4.3, 1.4.11).
- Interactive targets at least 24x24 CSS px (SC 2.5.8); aim for 44px on touch-first login forms.
- Focus is always visible (SC 2.4.7), never `outline: none` without a replacement; tab order follows
  the visual order; a dialog traps focus, closes on Esc and returns focus.
- Every input has a programmatic label, not only a placeholder; login fields carry the right
  `autocomplete` token (SC 1.3.5); errors are tied to their field (`aria-invalid`,
  `aria-describedby`) and announced; icon-only buttons have an accessible name. Prefer native
  elements over ARIA.
- Motion respects `prefers-reduced-motion`.

**Checks you can run (Playwright)**
- Horizontal overflow: compare `documentElement.scrollWidth` with `clientWidth` after
  `page.setViewportSize`. Lit uses shadow DOM, so `querySelectorAll` does not reach inside components;
  walk `shadowRoot`s or use Playwright locators.
- Overlap and target size: collect the bounding boxes of visible interactive elements (again through
  shadow roots) and flag pairs that intersect and targets under 24x24.
- Accessibility: `tests/accessibility.spec.ts` runs `@axe-core/playwright` (tags `wcag2a`,
  `wcag2aa`, `wcag21a`, `wcag21aa`) over the console's list screens in both themes; run it with
  `npx playwright test accessibility`. Add a view to its list when you add a screen. It covers only
  part of WCAG, so it supplements looking at the screenshots and does not replace it.
- Layout shift: a `PerformanceObserver` on `layout-shift` entries; keep the total at or below 0.1
  (web.dev CLS).

## Tests

- Logic (validators, theme, presets) gets a Vitest test next to it (`*.test.ts`).
- Screens get a Playwright spec in `tests/`, using `loadAdminApp` from `tests/fixtures.ts`
  (mock data, `PLAYWRIGHT=true`).
- `tests/docs-screenshots.spec.ts` produces the screenshots for the public docs
  (`versola-website`); it is not an assertion suite. If you change a screen it covers, say that the
  documentation images are now stale.

## Dependencies

`package-lock.json` is committed and `npm ci` is what CI runs. Majors arrive from Dependabot as
separate PRs; `npm audit` is part of `security.yml`. Adding a package needs approval (CLAUDE.md).
