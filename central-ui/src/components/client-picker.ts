import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { formStyles } from '../styles/components';
import type { OAuthClient } from '../types';
import { getLocalizedDescription } from '../utils/helpers';

/**
 * The many-to-many picker of clients (D7: the permission picker's shape over clients): a filter
 * field from four clients, one bordered group headed by `heading` with a `Select all` / `Clear all`
 * for the clients in view, and a checkbox per client with its name. Client ids the tenant no longer
 * has (an audience that outlived its client) stay listed so unticking them is possible. Fires
 * `change` with `{ value: string[] }`, the ids in the order they were picked.
 */
@customElement('versola-client-picker')
export class VersolaClientPicker extends LitElement {
  @property({ attribute: false }) clients: OAuthClient[] = [];
  @property({ attribute: false }) value: string[] = [];
  /** Names the group in the accessible tree: `Audience`. */
  @property({ type: String }) label = 'Clients';
  /** The group's header: `Clients in example-shop`. */
  @property({ type: String }) heading = 'Clients';
  @state() private query = '';

  static styles = [
    theme,
    formStyles,
    css`
      :host {
        display: block;
      }

      .search {
        max-width: 22.8rem;
        margin-bottom: var(--spacing-md);
      }

      .group {
        border: 1px solid var(--border-subtle);
        border-radius: var(--radius-md);
        background: var(--surface-inset);
      }

      .group-head {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: var(--spacing-md);
        padding: 0.5rem 0.75rem;
        border-bottom: 1px solid var(--border-subtle);
        font-size: var(--text-xs);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: var(--text-secondary);
        overflow-wrap: anywhere;
      }

      .group-head button {
        background: none;
        border: 0;
        padding: 0.25rem 0;
        min-height: 1.5rem;
        font: inherit;
        font-size: var(--text-xs);
        font-weight: 700;
        letter-spacing: 0.04em;
        text-transform: none;
        color: var(--accent);
        cursor: pointer;
        white-space: nowrap;
      }

      .group-head button:hover {
        text-decoration: underline;
      }

      .group-head button:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
        border-radius: var(--radius-sm);
      }

      .option {
        display: flex;
        gap: 0.625rem;
        align-items: flex-start;
        padding: 0.5rem 0.75rem;
        cursor: pointer;
      }

      .option + .option {
        border-top: 1px solid var(--border-subtle);
      }

      .option:hover {
        background: var(--surface);
      }

      .option input {
        margin: 0.2rem 0 0;
        width: 1rem;
        height: 1rem;
        flex: none;
        accent-color: var(--accent);
        cursor: pointer;
      }

      .option input:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      .option-text {
        flex: 1;
        min-width: 0;
        font-size: var(--text-base);
        color: var(--text-primary);
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
        margin: 0;
      }

      .option-id {
        font-family: var(--font-mono);
        overflow-wrap: anywhere;
      }

      .option-desc {
        color: var(--text-secondary);
        font-size: var(--text-sm);
      }

      .empty {
        font-size: var(--text-sm);
        color: var(--text-secondary);
        padding: 0.5rem 0;
      }
    `,
  ];

  private get options(): Array<{ id: string; name: string; missing: boolean }> {
    const known = new Map(this.clients.map(client => [client.id, client]));
    const ids = [...new Set([...this.clients.map(client => client.id), ...this.value])].sort((a, b) => a.localeCompare(b));
    const query = this.query.trim().toLowerCase();
    return ids
      .map(id => {
        const client = known.get(id);
        return { id, name: client ? getLocalizedDescription(client.clientName) : '', missing: !client };
      })
      .filter(option => !query || option.id.toLowerCase().includes(query) || option.name.toLowerCase().includes(query));
  }

  private emit(value: string[]) {
    this.value = value;
    this.dispatchEvent(new CustomEvent('change', { detail: { value }, bubbles: true, composed: true }));
  }

  private toggle(id: string) {
    this.emit(this.value.includes(id) ? this.value.filter(selected => selected !== id) : [...this.value, id]);
  }

  private toggleAll(ids: string[]) {
    const all = ids.every(id => this.value.includes(id));
    this.emit(all ? this.value.filter(id => !ids.includes(id)) : [...this.value, ...ids.filter(id => !this.value.includes(id))]);
  }

  render() {
    const options = this.options;
    const ids = options.map(option => option.id);
    const all = ids.length > 0 && ids.every(id => this.value.includes(id));
    return html`
      <div role="group" aria-label=${this.label}>
        ${this.clients.length > 3 ? html`
          <input class="search" type="search" aria-label="Filter clients" placeholder="Filter clients"
            .value=${this.query} @input=${(e: Event) => this.query = (e.target as HTMLInputElement).value} />` : ''}
        ${this.clients.length === 0 && this.value.length === 0 ? html`<div class="empty">No clients in this tenant yet.</div>`
        : options.length === 0 ? html`<div class="empty">No clients match.</div>`
        : html`
          <div class="group">
            <div class="group-head">
              <span>${this.heading}</span>
              <button type="button" @click=${() => this.toggleAll(ids)}>${all ? 'Clear all' : 'Select all'}</button>
            </div>
            ${options.map(option => html`
              <label class="option">
                <input type="checkbox" .checked=${this.value.includes(option.id)} @change=${() => this.toggle(option.id)} />
                <span class="option-text">
                  <span class="option-id">${option.id}</span>
                  ${option.name ? html`<span class="option-desc"> · ${option.name}</span>` : ''}
                  ${option.missing ? html`<span class="option-desc"> · no longer exists in this tenant</span>` : ''}
                </span>
              </label>
            `)}
          </div>`}
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-client-picker': VersolaClientPicker;
  }
}
