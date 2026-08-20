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
 * Dark is the original/default palette (unchanged). Light reuses the
 * marketing site's warm palette (versola-website: public/css/style.css) and
 * Petrol Blue accent, so the console and the site read as one product.
 */

export const theme = css`
  /* Versola Theme Variables */
  :host {
    /* Fonts - from Versola website */
    --font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
    --font-mono: 'JetBrains Mono', 'Fira Code', monospace;
    
    /* Spacing */
    --spacing-xs: 0.25rem;
    --spacing-sm: 0.5rem;
    --spacing-md: 1rem;
    --spacing-lg: 1.5rem;
    --spacing-xl: 2rem;
    
    /* Border radius */
    --radius-sm: 4px;
    --radius-md: 8px;
    --radius-lg: 12px;
    
    /* Shadows */
    --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.12);
    --shadow-md: 0 4px 6px rgba(0, 0, 0, 0.15);
    --shadow-lg: 0 10px 20px rgba(0, 0, 0, 0.2);
    
    /* Transitions */
    --transition-fast: 0.15s ease;
    --transition-base: 0.25s ease;
    --transition-slow: 0.4s ease;
  }
`;

export const resetStyles = css`
  * {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
  }
`;

