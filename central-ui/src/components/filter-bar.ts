import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';

export type FilterOption = { id: string; label: string; count?: number };

/**
 * A row of filter chips above a list (`All 12 | Web app 7 | ...`): one option is on at a time and
 * each shows how many rows it would leave. Fires `change` with `{ id }`. Below 720px the chips
 * fill the width and wrap.
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
        border: 1px solid var(--border-input);
        border-radius: var(--radius-md);
        overflow: hidden;
        background: var(--surface);
        max-width: 100%;
      }

      .filter-option {
        min-height: 2.25rem;
        padding: 0 0.875rem;
        border: 0;
        border-right: 1px solid var(--border-input);
        background: none;
        color: var(--text-secondary);
        font: inherit;
        font-size: 0.8125rem;
        cursor: pointer;
      }

      .filter-option:last-child {
        border-right: 0;
      }

      .filter-option:hover {
        color: var(--text-primary);
      }

      .filter-option:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: -2px;
      }

      .filter-option.on {
        background: var(--accent-subtle);
        color: var(--accent-text);
        font-weight: 600;
      }

      .filter-count {
        margin-left: 0.375rem;
        color: var(--text-secondary);
        font-weight: 400;
      }

      @media (max-width: 720px) {
        :host,
        .filter {
          display: flex;
          width: 100%;
        }

        .filter-option {
          flex: 1 1 auto;
          border-bottom: 1px solid var(--border-input);
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
