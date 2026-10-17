import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';

/** The Versola mark: a monoline V holding a dot, drawn with the console's
  * tokens (`--text-primary` for the V, `--accent` for the dot) so it follows
  * the active theme through CSS alone. The favicons (public/favicon-*.svg)
  * are the same mark on a tile. */
@customElement('versola-logo')
export class VersolaLogo extends LitElement {
  @property({ type: Number }) size = 40;

  static styles = css`
    :host {
      display: inline-block;
    }

    svg {
      display: block;
    }
  `;

  render() {
    return html`
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="${this.size}" height="${this.size}" aria-hidden="true">
        <path d="M14 18 L32 46 L50 18" fill="none" stroke="var(--text-primary)" stroke-width="7"
              stroke-linecap="round" stroke-linejoin="round"/>
        <circle cx="32" cy="24.5" r="6" fill="var(--accent)"/>
      </svg>
    `;
  }
}
