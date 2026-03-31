import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';

@customElement('content-header')
export class ContentHeader extends LitElement {
  @property({ type: String }) title = '';
  @property({ type: String }) description = '';

  static styles = [
    theme,
    css`
      :host {
        display: block;
        margin-bottom: var(--spacing-xl);
      }

      .header-container {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        margin-bottom: var(--spacing-xl);
        gap: var(--spacing-lg);
      }

      .header-info {
        flex: 1;
      }

      .header-title {
        font-size: 2rem;
        font-weight: 700;
        color: var(--text-primary);
        margin: 0 0 0.5rem 0;
      }

      .header-description {
        font-size: 0.875rem;
        color: var(--text-secondary);
        margin: 0;
      }

      .header-actions {
        display: flex;
        gap: 0.5rem;
      }

      @media (max-width: 768px) {
        .header-container {
          flex-direction: column;
        }
      }
    `,
  ];

  render() {
    return html`
      <div class="header-container">
        <div class="header-info">
          <h1 class="header-title">${this.title}</h1>
          ${this.description ? html`
            <p class="header-description">${this.description}</p>
          ` : ''}
        </div>

        <div class="header-actions">
          <slot name="actions"></slot>
        </div>
      </div>
    `;
  }
}

