import { LitElement, html, css } from 'lit';
import { customElement } from 'lit/decorators.js';
import { theme } from '../styles/theme';

/** The mobile drawer's "open" button, rendered inline with a screen's title.
  *
  * Deliberately stateless: it only ever opens the drawer, so it needs no
  * knowledge of whether the drawer is currently open. Closing is handled from
  * inside the drawer (its own ✕), by the backdrop, and by Escape — all owned by
  * admin-app. That keeps this usable from any header on any screen without
  * threading drawer state down through a dozen components.
  *
  * Hidden above the 768px breakpoint, where the sidebar is always visible and
  * there is nothing to open.
  */
@customElement('versola-nav-toggle')
export class VersolaNavToggle extends LitElement {
  static styles = [
    theme,
    css`
      :host {
        display: none;
      }

      @media (max-width: 768px) {
        :host {
          display: block;
          flex: none;
        }
      }

      button {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 2.75rem;
        height: 2.75rem;
        padding: 0;
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        background: var(--bg-dark-card);
        color: var(--text-primary);
        font-family: var(--font-family);
        font-size: 1.25rem;
        line-height: 1;
        cursor: pointer;
        transition: all var(--transition-fast);
      }

      button:hover {
        border-color: var(--accent);
        color: var(--accent);
      }
    `,
  ];

  private open() {
    this.dispatchEvent(new CustomEvent('open-nav', {
      bubbles: true,
      composed: true,
    }));
  }

  render() {
    return html`
      <button type="button" @click=${this.open} aria-label="Open navigation menu">☰</button>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-nav-toggle': VersolaNavToggle;
  }
}
