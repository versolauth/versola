import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';

@customElement('versola-error-card')
export class VersolaErrorCard extends LitElement {
  @property({ type: String }) message = '';
  @property({ type: String }) heading = 'Could not load data';

  static styles = [
    theme,
    css`
      :host { display: block; }
      .card { padding: var(--spacing-lg); border: 1px solid var(--border-dark); border-radius: var(--radius-lg); background: var(--bg-dark-card); }
      .empty-state { text-align: center; padding: 3rem; color: var(--text-secondary); }
      .empty-state-icon { font-size: 3rem; margin-bottom: 1rem; }
      .btn { display: inline-flex; align-items: center; gap: 0.5rem; padding: 0.5rem 1rem; border-radius: var(--radius-md); border: none; cursor: pointer; font-size: 0.875rem; font-weight: 500; transition: opacity 0.15s; }
      .btn:disabled { opacity: 0.5; cursor: not-allowed; }
      .btn-primary { background: var(--accent); color: #fff; }
      .btn-primary:not(:disabled):hover { opacity: 0.85; }
    `,
  ];

  render() {
    return html`
      <div class="card">
        <div class="empty-state">
          <div class="empty-state-icon">⚠️</div>
          <h3>${this.heading}</h3>
          <p>${this.message}</p>
          <button class="btn btn-primary" @click=${() => this.dispatchEvent(new CustomEvent('retry'))} style="margin-top: 1rem;">
            Retry
          </button>
        </div>
      </div>
    `;
  }
}

declare global { interface HTMLElementTagNameMap { 'versola-error-card': VersolaErrorCard; } }
