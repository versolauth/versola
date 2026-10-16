# Admin console design system

The rules, tokens, patterns and components of the admin console (`central-ui/src`). Read this
before changing a console screen; `.claude/rules/frontend.md` makes it mandatory. The login forms
(`central-ui/forms`) share the palette's neutrals and accent but are a separate stack with their
own rules.

Files next to this one:

- `specimen.html`: a static page that shows every token and the main patterns in both themes.
  Open it in a browser (no server needed); the switch in its sidebar footer flips the page theme.
  Its token block is a copy of `index.html`'s; keep it in step.
- `contrast.mjs`: the WCAG check over the tokens in `index.html`. `npm run design:contrast`
  fails when a required pair is below its threshold; `-- --md` prints the full table.

## Principles

1. **One product.** The console continues the login forms: the same neutrals ("Stone": warm
   near-white, stone grey, charcoal), the same petrol accent (`#155e75`, the `versola` theme
   preset), the same Inter/JetBrains Mono pair. No gradients.
2. **Tokens, never literals.** Every colour is a custom property defined once per theme in
   `index.html`. A component never writes a hex, `rgb()` or `white`. A new colour is a new token
   with a value in both themes, a line in the table below, and a green `design:contrast`.
3. **View first.** A record opens on a read-only page (breadcrumb, title, meta, one card per group
   with its own Edit, a Fixed card, the danger card last). A list never edits; it opens.
4. **One primary action per page.** The page header carries it; cards carry secondary buttons.
5. **Same words everywhere.** A setting looks the same where it is chosen (wizard), where it is
   read (record card) and where it is changed (group editor).
6. **Quiet by default.** Neutral surfaces, colour only for the accent, states and danger. Hover
   changes a border or a text colour, not the layout.
7. **Accessible by construction.** Text 4.5:1, controls and focus 3:1, targets 24px, visible focus,
   native elements, labels on everything, motion off under `prefers-reduced-motion`.

## Tokens

Defined in `index.html` under `:root[data-theme='light']` (also the bare `:root`) and
`:root[data-theme='dark']`. They inherit into every shadow root, so `src/styles/theme.ts` defines
only the non-colour tokens (fonts, spacing, radius, transitions).

Semantic sets follow one rule: `X` is the colour on a surface (text, icon, outline, solid fill for
danger), `X-subtle` is a fill (badge, banner, selected row), `X-text` is the text on `X-subtle`,
`X-border` is the outline of something filled with `X-subtle`.

| Token | Light | Dark | Use |
|---|---|---|---|
| `--bg-page` | `#faf9f7` | `#121617` | Page background, input fields, nested panels inside a card |
| `--surface` | `#ffffff` | `#1b2123` | Cards, tables, the secondary button |
| `--surface-inset` | `#f3f1ed` | `#232a2c` | A panel inside a card (claims, permissions, nested rows), hovered table row, disabled button; replaces every old `rgba(0,0,0,.2)` slab |
| `--surface-raised` | `#ffffff` | `#20272a` | Popovers, dropdowns, menus (with `--shadow-lg`) |
| `--sidebar-bg` | `#f3f1ed` | `#151a1b` | The sidebar |
| `--sidebar-hover-bg` | `#e9e6e0` | `#20272a` | A hovered nav item (`--surface-inset` would vanish: it equals `--sidebar-bg` in light). The active item is `--accent-subtle` / `--accent-text` with a 3px `--accent` bar on its left edge |
| `--code-bg` | `#f3f1ed` | `#121617` | Code blocks |
| `--selection-bg` | `#cfe3ea` | `#244a56` | `::selection` |
| `--backdrop` | `rgba(28,27,25,.5)` | `rgba(0,0,0,.6)` | Behind a dialog or the mobile drawer |
| `--border-subtle` | `#ebe8e2` | `#272e30` | Row dividers inside a card |
| `--border-default` | `#dbd7cf` | `#323a3d` | Card and table outlines, dividers between sections (decorative, no contrast requirement) |
| `--border-strong` | `#847e74` | `#616d70` | A boundary that must be seen (3:1): a bordered control that is not an input |
| `--border-input` | `#7c766b` | `#6d797c` | Inputs, selects, textareas, the secondary button, the filter bar, menu buttons (3:1) |
| `--text-primary` | `#1c1b19` | `#f0f1f0` | Body text, values, titles |
| `--text-secondary` | `#55534e` | `#acb4b4` | Labels, hints, table headers, nav items |
| `--text-muted` | `#6b6a66` | `#8f9a9a` | Placeholders, disabled text, the quietest text that must still be read (4.5:1 on every surface) |
| `--accent` | `#155e75` | `#62b9d0` | Links, IDs, the primary button fill, accent outlines, the brand wordmark |
| `--accent-hover` | `#104c5f` | `#7ec8db` | Primary button hover |
| `--on-accent` | `#ffffff` | `#0c1a1e` | Text and the toggle knob on `--accent` (dark: near-black on the light petrol) |
| `--accent-subtle` | `#e1eef2` | `#1a3940` | Accent fills: tags, the active filter, the active nav item, selected rows |
| `--accent-text` | `#114d61` | `#8fd0e2` | Text on `--accent-subtle` |
| `--accent-border` | `#74b2c4` | `#2c5f6c` | Outline of an accent-filled chip or tag, the ring of a selected (not focused) card. 2.3:1 on surfaces: never a focus indicator |
| `--focus-ring` | `#155e75` | `#62b9d0` | The one focus indicator: `outline: 2px solid` (offset 2px around a control, 1px around a field, -2px inside a nav or menu item). 3:1 on every surface, checked |
| `--success`, `-subtle`, `-text`, `-border` | `#2a7a35`, `#e6f3e2`, `#1f5f26`, `#8ac48e` | `#62c472`, `#1a3121`, `#8bd998`, `#336b3e` | Positive state: FAPI 2.0 chip outline, "created" banner, GET badge |
| `--warning`, `-subtle`, `-text`, `-border` | `#9a5200`, `#fbf0dc`, `#7a4100`, `#dca452` | `#e0a63a`, `#332b15`, `#efc45f`, `#7a5b1a` | Attention: secret rotation chip, drift panel, PATCH badge |
| `--danger`, `--on-danger` | `#c0341d`, `#ffffff` | `#ff8a73`, `#1c1b19` | Destructive: danger card outline and title, invalid field border, the solid danger button |
| `--danger-subtle`, `-text`, `-border` | `#fbeae6`, `#9c2915`, `#ec9a88` | `#3c201c`, `#ffab98`, `#80362c` | Danger fills and the outline danger button text |
| `--info`, `-subtle`, `-text`, `-border` | same as accent | same as accent | Informational banners and badges (POST badge) |
| `--toggle-track-off`, `--toggle-knob-off` | `#7c766b`, `#ffffff` | `#616d70`, `#f0f1f0` | A switch in the off position (on: `--accent` / `--on-accent`) |
| `--select-chevron` | image | image | The `<select>` and tenant-selector chevron, drawn in `--text-secondary` (an image cannot read a `var()`) |
| `--shadow-sm` / `-md` / `-lg` | `rgba(28,27,25,…)` | `rgba(0,0,0,…)` | Elevation: `-lg` for popovers and the drawer, `-md` for suggestion lists |
| `--ch-*`, `--cel-*` | GitHub Light-derived | GitHub Dark-derived | Syntax highlighting (`src/styles/code-highlight.ts`, `cel-highlight.ts`); every one passes 4.5:1 on `--code-bg` and `--bg-page` |

Non-colour tokens (`src/styles/theme.ts`): `--font-family`, `--font-mono`; `--spacing-xs` 0.25rem,
`-sm` 0.5rem, `-md` 1rem, `-lg` 1.5rem, `-xl` 2rem; `--radius-sm` 4px, `-md` 8px, `-lg` 12px;
`--transition-fast` 0.15s, `-base` 0.25s, `-slow` 0.4s.

Theme-independent scales, defined once in `index.html` (bare `:root`) and inherited everywhere:

| Token | Value | Use |
|---|---|---|
| `--text-xs` | 0.75rem (12px) | Chips, badges, table headers, section titles, hints. **The smallest size in the console**: nothing is set below 12px |
| `--text-sm` | 0.8125rem (13px) | Secondary text, the filter bar, a record card's subheading, IDs in meta |
| `--text-base` | 0.875rem (14px) | Body, inputs, buttons, nav items, table cells |
| `--text-md` | 0.9375rem (15px) | A row title in a list |
| `--text-lg` | 1rem (16px) | A card heading, `.btn-lg` |
| `--text-xl` | 1.125rem (18px) | An empty-state heading |
| `--text-2xl` | 1.25rem (20px) | `.card-title`, the brand wordmark |
| `--text-3xl` | 1.5rem (24px) | The page title below 720px |
| `--text-4xl` | 2rem (32px) | The page title |
| `--z-dropdown` | 20 | A menu, listbox, suggestion list or tooltip over its page |
| `--z-backdrop` / `--z-drawer` | 150 / 200 | The mobile drawer's backdrop and the drawer |
| `--z-modal` | 1000 | A dialog and its backdrop (`confirm-dialog.ts`, the users' password popups) |
| `--z-toast` | 1100 | A toast, above a dialog (reserved; nothing uses it yet) |

The shared styles (`components.ts`), the shell and the shared components use the scale; the
older screens still carry literal sizes and move to the scale as they are redesigned, except that
every size under 12px has already been replaced by `--text-xs`.

Motion: `index.html` and `theme.ts` both carry a `prefers-reduced-motion: reduce` block that
zeroes every animation and transition (the one in `theme.ts` is needed because a document rule
does not reach into a shadow root).

Deliberate literals, the only ones in `src/`: the theme-preset sketches in `theme-edit.ts` and
`theme-token-controls.ts` draw the *login forms'* page colours (`#fffdf9` sheet, `#1c1b19` ink,
the `color-mix` brand tints) so a preset looks the same whatever the console theme is; the brand
swatches in `theme-token-controls.ts` are the preset colours themselves. Nothing else.

### Contrast

`npm run design:contrast` checks every pair the console relies on (text on each surface, accent as
text and as a boundary, button text on fills, badge text on badge fills, syntax tokens on
`--code-bg`). The required pairs all pass in both themes (lowest: `--border-input` and
`--toggle-track-off` on `--surface` in dark, 3.05–3.6:1, chosen just above the line so fields do
not look heavy). The `*-border` outlines on `*-subtle` fills are 1.7–2.2:1 and are listed as
informative: the badge's text carries its meaning, the outline is decoration.

## Theme mechanism

- The stored choice is `localStorage['versola-theme']` = `auto` | `light` | `dark` (default
  `auto`; a `light`/`dark` stored before `auto` existed still works).
- `index.html`'s inline pre-paint script resolves `auto` through
  `matchMedia('(prefers-color-scheme: dark)')`, sets `data-theme` (the painted theme) and
  `data-theme-pref` (the choice) on `<html>` and swaps the favicon, synchronously, before the
  first paint. `color-scheme` is set per theme so native controls and scrollbars follow;
  `<meta name="color-scheme" content="light dark">` says both exist.
- `src/utils/theme.ts` is the API: `getStoredPreference()`, `getResolvedTheme()`,
  `setThemePreference(pref)`, `watchSystemTheme()` (called by `admin-app`; repaints on an OS change
  while the choice is `auto`), and the `versola-theme-change` event (detail: the painted
  `ThemeName`) for the few components that cannot follow by CSS alone (`versola-logo`).
- The switcher is `versola-theme-switcher` in the sidebar footer: an icon button (half circle
  Auto, sun Light, moon Dark; accessible name `Colour scheme: <choice>`) that opens a listbox.
  Keyboard: arrows open and move, Enter/Space pick, Escape closes and returns focus, Tab and an
  outside click close. It is the login forms' control (`forms/shared/scheme-toggle.tsx`) in Lit.
- Tests: `src/utils/theme.test.ts` (Vitest), `tests/theme-switcher.spec.ts` (keyboard,
  persistence, OS follow via `page.emulateMedia`), `tests/accessibility.spec.ts` (axe over every
  list screen with Auto on a dark and a light OS, Light and Dark).

## Shell

`versola-admin` lays out a flex row: `versola-navigation` (250px, sticky, `--sidebar-bg`) and
`<main class="main-content">` (padding 2rem, max-width 1400px). Below 768px the sidebar is an
off-canvas drawer opened by the `versola-nav-toggle` in each page header, with a `--backdrop`.

Sidebar, top to bottom: brand (logo + wordmark in `--accent`), tenant selector (looks like every
other `<select>`: same padding, chevron at the same inset), the nav (`Tenant Scoped`, `Global`;
items are buttons with `aria-current="page"` on the active one; hover is a `--sidebar-hover-bg`
fill, active is `--accent-subtle` with a 3px `--accent` bar on the left edge, the weight never
changes so the label's width never shifts; 44px tall in the drawer), and a footer that stays put:
the `Theme` row with the switcher, `My Security`, `Log out`. The brand, tenant and nav scroll as
one block with a thin, always-reserved scrollbar when they are taller than the viewport; with
every permission the whole list fits at 1440×900.

Page header: `content-header` with `title`, an optional `description` (one line under the title)
and the single primary action in its `actions` slot. A record page uses `versola-record-header`
instead (see below). Breadcrumbs exist only on record pages: `List › Record`, the list a link.

## Page patterns

**List.** `content-header` (title, `+ Create X` as the only primary action) → optional banner
(a created secret, an error) → toolbar (a `search` input, max-width 28rem, and a
`versola-filter-bar`) → `versola-data-table`, or `versola-empty-state` when there is nothing or
nothing matches, or `versola-loading-cards` while loading, or `versola-error-card` with Retry.
Rows open the record; the row menu holds Open, Copy ID and Delete. Below 720px the table is a
stack of cards. Reference: `clients-list.ts`.

**Record (view first).** `versola-record-header` (breadcrumb, title, meta row: ID with copy,
kind chip, status chips, created) → a status banner when the page can say something about the
whole record (`.banner`, `.banner.ok`) → one `versola-record-card` per group of settings, each
with its own Edit that opens only that group as a page with Cancel and `Save <group>`; settings
fixed at creation sit in a card with the `Fixed` lock, each row with its reason (`By type`) →
`versola-danger-card` last, named for what it deletes. Reference: `clients-list.ts`
(`renderClientView`).

**Wizard (create).** Stepped, with a step list on top, each step a stack of bordered panels
(title, one-line subtitle, body), a rule the type sets shown as a locked row (lock icon, text,
reason), and a review step in the page's own groups with `Change` back to the step that sets it.
Reference: `client-form.ts`.

**Group editor and simple forms.** A page state, not a modal: a title-only header, one card with
the fields (`formStyles`: uppercase labels, compact fields at 22.8rem, inline errors under the
field with `aria-invalid` and `aria-describedby`), a bottom action row separated by a top border
with Cancel and the one primary submit. No second Close at the top.

**Settings pages** (System Settings, Challenges & Security, Well Known). Read-only cards in the
record pattern: label/value rows, one Edit per group, never a read view shaped like disabled
inputs, never two primary buttons in one region.

## Component catalogue

| Component | Element | Use it for | Not for |
|---|---|---|---|
| `content-header` | `<content-header title description>` + `slot="actions"` | Every list and settings page: title, one-line description, the one primary action | Record pages (use the record header) |
| Record header | `<versola-record-header .crumbs .heading .recordId idLabel .meta>` | The top of a record page; copies the ID with `Copy <idLabel>`; `meta` holds chips | Pages with no ID |
| Record card | `<versola-record-card .heading .subheading ?editable ?editDisabled .fixedLabel .body @edit>` | A group of a record's settings, with Edit or a Fixed lock | Forms (use `.card` with `formStyles`) |
| Record row | `<versola-record-row .label .fixed .value>` | A label/value line in a record card; `fixed` is the reason it cannot change; no label for an action row | Tables |
| Danger card | `<versola-danger-card heading text actionLabel .actionAriaLabel @action>` | The last card of a record page: delete | Anything reversible |
| Data table | `<versola-data-table .columns .rows .rowKey .menuItems .menuLabel>` | A section's list; card mode under 720px (explicit table roles survive the `display` change, each cell shows its column label); row menu with Open / Copy ID / Delete as a WAI-ARIA menu button: opens with the first item focused, arrows wrap, Home/End, Escape returns focus to the trigger, Tab and an outside click close, `aria-expanded` on the trigger (`tests/data-table.spec.ts`) | Editing in place; nested detail |
| Filter bar | `<versola-filter-bar label .options .value @change>` | One-of filters with counts above a table | Multi-select (use checkboxes) |
| Empty state | `<versola-empty-state heading text>` + one action in its slot | Nothing to list; nothing matches (no action) | Errors (use the error card) |
| Error card | `<versola-error-card heading .message @retry>` | A failed load with Retry | Validation |
| Loading cards | `<versola-loading-cards .count>` | While a list loads, same height as the rows that will replace it | Buttons (disable them instead) |
| Status icon | `statusIcon(name)` from `status-icon.ts` | Stroke icons for state (lock, check, x, shield, alert, info, dots, copy) | Emoji, text glyphs (`✎`, `▼`) |
| Theme switcher | `<versola-theme-switcher>` | The sidebar footer only | Anywhere else |
| Tenant selector | `<tenant-selector>` | The sidebar only | |
| Chips | `chipStyles`: `.chips`, `.chip`, `.chip.ok`, `.chip.warn` | A kind or a state as a small outlined label; the text carries the meaning | Interactive tags |
| Badges | `badgeStyles`: `.badge-success`, `.badge-warning`; `methodBadgeStyles` for HTTP methods | Filled state labels | |
| Buttons | `buttonStyles`: `.btn-primary`, `.btn-secondary`, `.btn-danger`, `.btn-danger-secondary`, `.btn-sm`, `.btn-lg` | Primary = solid accent, one per page; secondary = outlined; danger = solid red, only on confirmation | Icon-only actions (use `iconButtonStyles` with `aria-label`) |
| Cards | `cardStyles`: `.card`, `.card-header`, `.card-title` | Forms, banners, nested detail | Lists of records (use the table) |
| Record styles | `recordStyles` | Include in the host of record cards / rows / danger card (they render into light DOM) | |

`versola-record-card`, `versola-record-row` and `versola-danger-card` render into **light DOM**
on purpose: the body of a card is the screen's own markup (a flow chain, a list of URIs) and keeps
the screen's styles, and a test reads the whole card through its region
(`getByRole('region', { name })`). The host includes `recordStyles`, `buttonStyles` and
`cardStyles`. `versola-data-table` and `versola-record-header` render cells and meta through
properties inside their shadow root, so the styles a cell can rely on are the ones they ship
(`.row-link`, `.row-title`, `.row-id`, `.muted`, `.type-label`, `.icon`, chips).

Naming: elements are `versola-<name>` (the older `content-header`, `tenant-selector` stay).
Shared styles are exported from `src/styles/components.ts`; a style used by two screens moves
there, a style used by one stays in the screen.

## States

Every screen shows, and a spec covers: empty (empty state), loading (loading cards, no layout
jump), error (error card with Retry), disabled (`.btn:disabled` is a colour state:
`--surface-inset` fill, `--text-muted` text, never opacity), hover (border or text colour),
focus (`--focus-ring` outline), validation (inline, per field, `--danger` border,
`aria-invalid`), success (banner `.banner.ok` or `--success-text` line), a very long unbroken
string (`overflow-wrap: anywhere` on values, `word-break: break-all` on IDs), a one-character
value, many items (the table scrolls inside its card, the page never scrolls sideways). A pending
submit disables its button; one click or Enter sends one request.

## Accessibility rules

WCAG 2.2 AA. Text 4.5:1 (3:1 for 18pt / 14pt bold); control boundaries, icons and focus
indicators 3:1 (`design:contrast` proves the tokens; `tests/accessibility.spec.ts` proves the
screens). Targets at least 24×24 CSS px (`iconButtonStyles` enforce it; nav items are 44px in the
mobile drawer). Focus always visible, and always the same thing: **every focus indicator is a
2px `outline` in `--focus-ring`** (`outline-offset` 2px around a control, 1px around a field,
-2px inside a nav or menu item). A field may also change its border to `--accent`, and a halo
(`box-shadow`) may be added on top of the outline, but never replaces it: `--accent-border` is
2.3:1 on the surfaces, below the 3:1 a focus indicator needs. `outline: none` appears only where
a wrapper draws the ring for the element inside it (`code-editor`, `cel-editor`) and on the
focus flash of `.main-content` after the drawer closes (`admin-app.ts`), which uses `--accent`
and falls back to an outline under `prefers-reduced-motion`. Native elements first: a nav item is
a `<button>`, a list is a `<table>`, a popover is a listbox or a menu with the ARIA pattern the
login forms use (see the row menu below). Every input
has a label; icon-only buttons have `aria-label` and `title`; errors are tied to their field.
Dialogs trap focus, close on Escape and return focus. Motion respects `prefers-reduced-motion`.

## How to render and verify a screen

1. Playwright renders the console with a mocked API (`tests/mocks.ts`), no backend. Write a
   throwaway spec in `tests/` that calls `loadAdminApp(page, { path, state })`, sets the viewport,
   sets the theme with `page.addInitScript(v => localStorage.setItem('versola-theme', v), 'dark')`
   (or `page.emulateMedia({ colorScheme })` for Auto) and `page.screenshot({ path })`. Run it with
   `npx playwright test <file> --output=/tmp/pw-out` so `test-results/` is not wiped. Delete the
   spec before committing.
2. Shoot 1440 and 1024 in both themes, and 375 for list screens (card mode). Open the PNGs and
   check them against `.claude/rules/frontend.md` (overlap, clipping, no sideways scroll,
   alignment, layering); fix before showing.
3. `npm run type-check`, `npm run test:unit`, `npm run design:contrast`, `npx playwright test`
   (one run at a time; it owns port 4173).
4. A screen the public docs show needs its screenshots retaken (`tests/docs-screenshots.spec.ts`,
   `tests/docs-screenshots-branding.spec.ts`) and a `versola-website` PR
   (`.claude/rules/documentation.md`).
