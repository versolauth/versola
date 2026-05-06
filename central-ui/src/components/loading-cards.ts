import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';

@customElement('versola-loading-cards')
export class VersolaLoadingCards extends LitElement {
  @property({ type: Number }) count = 3;

  static styles = [
    theme,
    css`
      :host { display: block; }
      .stack { display: grid; gap: var(--spacing-md); }
      .card { padding: var(--spacing-lg); border: 1px solid var(--border-dark); border-radius: var(--radius-lg); background: var(--bg-dark-card); }
      .line { height: 0.875rem; border-radius: 999px; background: linear-gradient(90deg, rgba(255,255,255,0.06), rgba(88,166,255,0.18), rgba(255,255,255,0.06)); background-size: 200% 100%; animation: shimmer 1.4s infinite; }
      .line + .line { margin-top: 0.75rem; }
      .line.short { width: 32%; }
      .line.medium { width: 56%; }
      .line.full { width: 100%; }
      @keyframes shimmer { from { background-position: 200% 0; } to { background-position: -200% 0; } }
    `,
  ];

  render() {
    return html`
      <div class="stack" aria-label="Loading">
        ${Array.from({ length: this.count }, (_, index) => html`
          <div class="card" role="presentation">
            <div class="line ${index % 2 === 0 ? 'medium' : 'short'}"></div>
            <div class="line full"></div>
            <div class="line medium"></div>
          </div>
        `)}
      </div>
    `;
  }
}