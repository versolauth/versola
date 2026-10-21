import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { formStyles } from '../styles/components';
import type { Role } from '../types';
import { getLocalizedDescription } from '../utils/helpers';

/**
 * The many-to-many picker of roles (D7, the permission picker's shape over roles): a filter
 * field from four roles, one bordered group with a checkbox per role, its description and an
 * `Inactive` note. Role ids the tenant no longer has (a stale default) stay listed so unticking
 * them is possible. Fires `change` with `{ value: string[] }`, the ids in the order they were picked.
 */
@customElement('versola-role-picker')
export class VersolaRolePicker extends LitElement {
  @property({ attribute: false }) roles: Role[] = [];
  @property({ attribute: false }) value: string[] = [];
  /** Names the group in the accessible tree: `Default roles`. */
  @property({ type: String }) label = 'Roles';
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

  private get options(): Array<{ id: string; description: string; missing: boolean; inactive: boolean }> {
    const known = new Map(this.roles.map(role => [role.id, role]));
    const ids = [...new Set([...this.roles.map(role => role.id), ...this.value])].sort((a, b) => a.localeCompare(b));
    const query = this.query.trim().toLowerCase();
    return ids
      .map(id => {
        const role = known.get(id);
        return { id, description: role ? getLocalizedDescription(role.description) : '', missing: !role, inactive: role ? !role.active : false };
      })
      .filter(option => !query || option.id.toLowerCase().includes(query) || option.description.toLowerCase().includes(query));
  }

  private toggle(id: string) {
    const value = this.value.includes(id) ? this.value.filter(selected => selected !== id) : [...this.value, id];
    this.value = value;
    this.dispatchEvent(new CustomEvent('change', { detail: { value }, bubbles: true, composed: true }));
  }

  render() {
    const options = this.options;
    return html`
      <div role="group" aria-label=${this.label}>
        ${this.roles.length > 3 ? html`
          <input class="search" type="search" aria-label="Filter roles" placeholder="Filter roles"
            .value=${this.query} @input=${(e: Event) => this.query = (e.target as HTMLInputElement).value} />` : ''}
        ${this.roles.length === 0 && this.value.length === 0 ? html`<div class="empty">No roles in this tenant yet.</div>`
        : options.length === 0 ? html`<div class="empty">No roles match.</div>`
        : html`
          <div class="group">
            ${options.map(option => html`
              <label class="option">
                <input type="checkbox" .checked=${this.value.includes(option.id)} @change=${() => this.toggle(option.id)} />
                <span class="option-text">
                  <span class="option-id">${option.id}</span>
                  ${option.description ? html`<span class="option-desc"> · ${option.description}</span>` : ''}
                  ${option.missing ? html`<span class="option-desc"> · no longer exists in this tenant</span>` : option.inactive ? html`<span class="option-desc"> · inactive</span>` : ''}
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
    'versola-role-picker': VersolaRolePicker;
  }
}
