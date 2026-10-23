import { css } from 'lit';

/**
 * Theme color tokens (--bg-dark, --accent, etc.) are NOT defined here.
 *
 * `:root` never matches inside a shadow root (it's meant for the document
 * element, and a ShadowRoot is a DocumentFragment, not a document) — a Lit
 * `css` tagged template saying `:root { ... }`, attached to a component's
 * shadow DOM the way the rest of this file's tokens are, would match
 * nothing. Custom properties do inherit through shadow boundaries though, so
 * the tokens are defined exactly once as plain `:root[data-theme=...]` rules
 * in index.html (both to have them ready before first paint, alongside the
 * pre-paint script that sets `data-theme`, and to keep one canonical copy
 * rather than a string duplicated between here and there). Every component's
 * shadow root inherits whichever theme's values are current without
 * redeclaring them. See src/utils/theme.ts for the get/set/toggle API and
 * index.html for the actual color values.
 *
 * The palette (the brand tokens of the login forms and the website: warm
 * neutrals, the petrol accent, ink buttons) and what each token is for are
 * described in central-ui/design-system/README.md.
 */

export const theme = css`
  /* Versola Theme Variables */
  :host {
    /* Fonts: the system UI stack of the website and the login forms (no
       webfont for text); JetBrains Mono for code, as the site. */
    --font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    --font-mono: 'JetBrains Mono', 'Fira Code', monospace;
    
    /* Spacing */
    --spacing-xs: 0.25rem;
    --spacing-sm: 0.5rem;
    --spacing-md: 1rem;
    --spacing-lg: 1.5rem;
    --spacing-xl: 2rem;
    
    /* Shape: the site's rounded corners, one step tighter for the console's
       denser controls (the site uses 14 / 24). --radius-sm: a small inline
       thing (a key cap, an inline code chip); --radius-md: fields, buttons,
       nav items, menu items; --radius-inner: a box inside a card (a menu,
       a popover, an editor box, a banner); --radius-lg: cards and the side
       panel; --radius-pill: chips, tags, filter options, the search field. */
    --radius-sm: 8px;
    --radius-md: 12px;
    --radius-inner: 16px;
    --radius-lg: 20px;
    --radius-pill: 999px;

    /* Shadows (--shadow-sm/md/lg) are colour tokens: defined per theme in
       index.html next to the other colours, not here. */

    /* Transitions */
    --transition-fast: 0.15s ease;
    --transition-base: 0.25s ease;
    --transition-slow: 0.4s ease;

    /* The type scale (--text-xs .. --text-4xl) and the layers (--z-dropdown,
       --z-backdrop, --z-drawer, --z-modal, --z-toast) are in index.html too:
       theme-independent, but one copy that inherits everywhere. */
  }

  /* A document-level rule does not reach into a shadow root, so every
     component carries the reduced-motion switch itself. */
  @media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
      animation-duration: 0.01ms !important;
      animation-iteration-count: 1 !important;
      transition-duration: 0.01ms !important;
      scroll-behavior: auto !important;
    }
  }
`;

export const resetStyles = css`
  * {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
  }
`;

