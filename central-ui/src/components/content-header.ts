import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import './nav-toggle';

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
        min-width: 0; /* let a long title wrap instead of stretching the row */
      }

      /* The toggle is display:none above the breakpoint, so on desktop this row
         collapses to just the title and the gap contributes nothing. */
      .title-row {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
        min-width: 0;
      }

      .header-title {
        font-size: 2rem;
        font-weight: 700;
        color: var(--text-primary);
        margin: 0 0 0.5rem 0;
        min-width: 0;
        overflow-wrap: anywhere;
      }

      .header-description {
        font-size: 0.875rem;
        color: var(--text-secondary);
        margin: 0;
      }

      .header-actions {
        display: flex;
        gap: 0.5rem;
        flex-wrap: wrap;
        justify-content: flex-end;
      }

      @media (max-width: 768px) {
        .header-container {
          flex-direction: column;
        }

        /* Cancels the h1's bottom margin so the button reads as vertically
           centred against the title rather than sitting low. */
        versola-nav-toggle {
          margin-bottom: 0.5rem;
        }

        /* 2rem alongside a 2.75rem button leaves too little room for longer
           titles ("Challenges & Security") on a ~390px screen. */
        .header-title {
          font-size: 1.5rem;
        }
      }
    `,
  ];

  render() {
    return html`
      <div class="header-container">
        <div class="header-info">
          <div class="title-row">
            <versola-nav-toggle></versola-nav-toggle>
            <h1 class="header-title">${this.title}</h1>
          </div>
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
