import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';

export type FilterOption = { id: string; label: string; count?: number };

/**
 * A row of filter pills above a list (`All 12  Web app 7  ...`): one option is on at a time (ink
 * filled) and each shows how many rows it would leave. Fires `change` with `{ id }`. Below 720px
 * the pills fill the width and wrap.
 */
@customElement('versola-filter-bar')
export class VersolaFilterBar extends LitElement {
  /** The accessible name of the group, e.g. "Filter by type". */
  @property({ type: String }) label = 'Filter';
  @property({ attribute: false }) options: FilterOption[] = [];
  @property({ type: String }) value = '';

  static styles = [
    theme,
    css`
      :host {
        display: inline-flex;
        max-width: 100%;
      }

      .filter {
        display: inline-flex;
        flex-wrap: wrap;
        gap: 0.375rem;
        max-width: 100%;
      }

      .filter-option {
        min-height: 2.25rem;
        padding: 0 0.875rem;
        border: 1.5px solid var(--border-input);
        border-radius: var(--radius-pill);
        background: transparent;
        color: var(--text-primary);
        font: inherit;
        font-size: var(--text-sm);
        font-weight: 600;
        cursor: pointer;
      }

      .filter-option:hover {
        border-color: var(--text-primary);
      }

      .filter-option:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      /* The active filter is ink-filled with the page colour as its text. */
      .filter-option.on {
        background: var(--text-primary);
        border-color: var(--text-primary);
        color: var(--bg-page);
      }

      .filter-count {
        margin-left: 0.375rem;
        color: var(--text-secondary);
        font-weight: 400;
      }

      .filter-option.on .filter-count {
        color: inherit;
        opacity: 0.75;
      }

      @media (max-width: 720px) {
        :host,
        .filter {
          display: flex;
          width: 100%;
        }

        .filter-option {
          flex: 1 1 auto;
        }
      }
    `,
  ];

  private select(id: string) {
    if (id === this.value) return;
    this.value = id;
    this.dispatchEvent(new CustomEvent('change', { detail: { id }, bubbles: true, composed: true }));
  }

  render() {
    return html`
      <div class="filter" role="group" aria-label=${this.label}>
        ${this.options.map(option => html`
          <button type="button" class="filter-option ${this.value === option.id ? 'on' : ''}"
            aria-pressed=${this.value === option.id}
            @click=${() => this.select(option.id)}>
            ${option.label}${option.count !== undefined ? html`<span class="filter-count">${option.count}</span>` : ''}
          </button>
        `)}
      </div>
    `;
  }
}
