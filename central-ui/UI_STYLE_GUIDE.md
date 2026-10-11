# Central UI Style Guide

## Purpose

Use this guide for future UI changes in `central-ui` so new screens and tweaks stay consistent with the existing admin interface.

Primary source files:

- `src/styles/theme.ts`
- `src/styles/components.ts`
- `src/components/content-header.ts`
- representative screens: `clients-list.ts`, `client-form.ts`, `permissions-list.ts`, `scope-form.ts`, `role-form.ts`

## Design principles

- Two themes, `light` (the default) and `dark`, switched by `data-theme` on `<html>`; every screen must work in both
- Never hardcode a colour: use the theme tokens
- Clean, compact, utility-focused layout
- One primary action per screen region
- Prefer inline editing flows over modal stacks
- Keep page structure predictable across Clients, Scopes, Permissions, Resources, Roles, and Tenants

## Global shell structure

The app uses a 2-column shell:

- **Left sidebar**: fixed, `250px` wide, themed background, right border
- **Main content**: `margin-left: 250px`, `padding: 2rem`, `max-width: 1400px`
- On small screens, main content drops to `margin-left: 0` and `padding: 1rem`

### Sidebar structure

- Brand block at top with logo + gradient wordmark
- Tenant selector below brand
- Navigation section below tenant selector
- Active nav item uses accent tint background and accent text

## Page structure

### List/index pages

Use this structure:

1. `content-header`
2. optional status / banner / error block
3. optional search bar
4. empty state **or** result list

Rules:

- Top-right action is reserved for the page-level primary action like `+ Create Client`
- Search should sit below the header, usually capped around `28rem`
- Empty states should be centered inside a card
- If a create/edit form is open, prefer replacing the list page content with the form instead of showing both together

### Create/edit pages

Use this structure:

1. simple form header with page title only
2. single main card containing the form body
3. bottom action row with `Cancel` + primary submit button

Rules:

- Do **not** use a redundant top-right `Close` button if the bottom `Cancel` button already closes the form
- Form screens should feel like a page state, not a modal
- Keep the main action row at the bottom, separated by a top border

## Surfaces and containers

### Cards

- Background: `--bg-dark-card`
- Border: `1px solid --border-dark`
- Radius: `--radius-lg` (`12px`)
- Standard padding: `--spacing-xl` (`2rem`)

Use cards for:

- forms
- empty states
- banners
- collapsible record blocks
- nested detail sections

### Nested detail sections

For grouped details inside expanded cards:

- slightly darker inner surface using translucent black overlay
- `--radius-md`
- internal padding around `--spacing-md`
- label above value, label in small uppercase/secondary style

## Color palette

### Tokens, not values

The colours are CSS custom properties defined once per theme in `index.html`
(`:root[data-theme='dark']` and `:root[data-theme='light']`); components inherit them through the
shadow DOM. Read the values there, and use only the tokens in components:

- Page background `--bg-dark`, card background `--bg-dark-card`. The `dark` in these names is
  historical: in the light theme they hold the light surface colours.
- Accent `--accent`, `--accent-gradient`, and `--accent-tint` for tinted backgrounds
- Text `--text-primary`, `--text-secondary`
- Borders `--border-dark`, `--border-light`
- Semantic `--success`, `--warning`, `--danger` (+ `--danger-tint`, `--danger-strong`), `--info`
- Overlay and inset surfaces `--surface-overlay`, `--surface-inset`; switches `--toggle-track-off`

A new colour is a new token with a value in **both** themes, not a literal in a component.

### Usage rules

- Use accent color for focus, active state, selected state, and important links/labels
- Use danger only for destructive actions/errors
- Keep most surfaces neutral; use color sparingly for hierarchy
- Prefer tinted backgrounds over fully solid fills except for primary buttons and destructive actions

## Typography

- Primary font: `Inter`
- Monospace font: `JetBrains Mono`

### Type hierarchy

- Page/form titles: `2rem`, bold
- Card titles: `1.25rem`, bold
- Main body/input text: `0.875rem`
- Small labels / metadata: `0.75rem` to `0.8125rem`

### Label style

Default form labels are:

- uppercase
- `0.875rem`
- medium weight
- secondary text color
- slightly increased letter spacing

Use monospace for technical values like:

- client IDs
- endpoint paths
- method badges
- machine-readable identifiers when helpful

## Spacing, radius, and motion

### Spacing scale

- `xs`: `0.25rem`
- `sm`: `0.5rem`
- `md`: `1rem`
- `lg`: `1.5rem`
- `xl`: `2rem`

### Radius scale

- `sm`: `4px`
- `md`: `8px`
- `lg`: `12px`

### Motion

- Use subtle hover/focus transitions only
- Standard transitions are `0.15s` to `0.25s`
- Common hover treatment: accent border, accent text, or slight lift/scale

## Buttons and actions

### Primary buttons

- Use gradient fill
- Reserve for create/save/confirm actions
- Usually only one primary button per action area

### Secondary buttons

- Card-like background with border
- Use for cancel, add-inline, or non-destructive secondary actions

### Icon actions

- Minimal, borderless, text/icon only
- Default color: secondary text
- Hover: accent
- Destructive hover: danger

## Forms

### Base form pattern

- Wrap fields in a `.form-grid`
- Each field group uses `.form-group`
- Inputs/selects use the card background, a border, and an accent focus ring
- Show helper text below fields when guidance is useful
- Show inline error text below fields in danger color

### Width rules

Current compact field sizing in client-style forms:

- standard compact field max width: `22.8rem`
- inline add-button rows: field width + button width + gap
- `TTL` is the exception and may use a tighter custom width

Use the same visible width for related fields that should align vertically.

### Form action row

- Bottom-aligned
- Right-justified
- `Cancel` on the right group before primary action
- Optional special secondary action may sit on the far left if needed

## Lists, cards, and expandable rows

- Use stacked cards for entity lists where detail expansion matters
- Hover should emphasize border, not heavily change layout
- Expanded content should appear below the row header with a top border
- Use internal grid layouts for grouped details (`repeat(auto-fit, minmax(300px, 1fr))` is a good default)

## Tags, chips, and selection groups

- Tags use accent-tinted background, border, medium radius
- Tag remove actions use icon-only destructive affordance
- Checkbox groups are grid-based with bordered items
- Selected/unselected state should remain obvious without loud color fills

## Empty, loading, and error states

- Prefer dedicated card-based states
- Empty states should be centered and simple
- Loading copy should explain what is loading if it blocks a form
- Errors should be visible but not visually overwhelming
- Retry actions belong inside the error state card

## Consistency rules for future changes

- Reuse shared tokens from `theme.ts` and shared styles from `components.ts`
- Prefer existing patterns over inventing a new layout for a single screen
- New list pages should use `content-header`
- New create/edit pages should use title-only form header + bottom actions
- Avoid duplicate close actions
- When entering edit/create mode, hide unrelated list/search/actions unless side-by-side editing is explicitly needed
- Keep field widths aligned within a form whenever fields are conceptually grouped
- Use accessible, user-visible labels and button text

## Quick checklist

Before merging a UI change, verify:

- Does it use existing spacing, radius, and color tokens?
- Does the page follow the standard list or form structure?
- Is there only one obvious primary action per area?
- Are close/cancel actions non-redundant?
- Are search, list, and form states shown in a clean, non-conflicting way?
- Do related inputs align visually?
- Does hover/focus use accent styling consistently?