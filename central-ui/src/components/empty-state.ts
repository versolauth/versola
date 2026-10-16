import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { cardStyles } from '../styles/components';

/**
 * The card a list shows instead of its rows when there is nothing to list: a heading, a line of
 * text and, in the default slot, the one action that fills it (`+ Create Client`). Also the
 * "nothing matches" card of a filtered list, then without an action.
 */
@customElement('versola-empty-state')
export class VersolaEmptyState extends LitElement {
  @property({ type: String }) heading = '';
  @property({ type: String }) text = '';

  static styles = [
    theme,
    cardStyles,
    css`
      :host {
        display: block;
      }

      .empty-state {
        text-align: center;
        padding: 3rem;
        color: var(--text-secondary);
      }

      h3 {
        margin: 0;
        font-size: 1.125rem;
        font-weight: 600;
        color: var(--text-primary);
      }

      p {
        margin: 0.5rem 0 0;
        font-size: 0.875rem;
      }

      .actions {
        margin-top: 1rem;
      }

      .actions:not(:has(*)) {
        display: none;
      }
    `,
  ];

  render() {
    return html`
      <div class="card empty-state">
        <h3>${this.heading}</h3>
        ${this.text ? html`<p>${this.text}</p>` : ''}
        <div class="actions"><slot></slot></div>
      </div>
    `;
  }
}
