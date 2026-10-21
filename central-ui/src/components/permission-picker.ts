import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, formStyles } from '../styles/components';
import { getPermissionCategory, type Permission, type Resource } from '../types';
import { getLocalizedDescription } from '../utils/helpers';
import './permission-info';

type PermissionGroup = { category: string; permissions: Permission[] };

/**
 * The many-to-many picker of permissions (D7): a search field, then one bordered group per
 * category (the text before the first `:`, `other` for a bare id) with a `Select all` and a
 * checkbox per permission with its description and the endpoints popover. Fires `change` with
 * `{ value: Permission[] }`.
 */
@customElement('versola-permission-picker')
export class VersolaPermissionPicker extends LitElement {
  @property({ attribute: false }) permissions: Permission[] = [];
  @property({ attribute: false }) resources: Resource[] = [];
  @property({ attribute: false }) value: Permission[] = [];
  /** Names the group in the accessible tree: `Permissions`. */
  @property({ type: String }) label = 'Permissions';
  @state() private query = '';

  static styles = [
    theme,
    buttonStyles,
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
        margin-bottom: 0.625rem;
      }

      .group-head {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: var(--spacing-md);
        padding: 0.5rem 0.75rem;
        border-bottom: 1px solid var(--border-subtle);
        font-size: var(--text-xs);
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--text-secondary);
      }

      .group-head button {
        background: none;
        border: 0;
        padding: 0.25rem 0;
        min-height: 1.5rem;
        font: inherit;
        font-size: var(--text-xs);
        color: var(--accent);
        cursor: pointer;
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

  private get groups(): PermissionGroup[] {
    const query = this.query.trim().toLowerCase();
    const groups = new Map<string, Permission[]>();
    for (const permission of [...this.permissions].sort((a, b) => a.id.localeCompare(b.id))) {
      if (query && !permission.id.toLowerCase().includes(query) && !getLocalizedDescription(permission.description).toLowerCase().includes(query)) continue;
      const category = getPermissionCategory(permission.id);
      groups.set(category, [...(groups.get(category) ?? []), permission]);
    }
    return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([category, permissions]) => ({ category, permissions }));
  }

  private isSelected(permission: Permission): boolean {
    return this.value.some(selected => selected.id === permission.id);
  }

  private emit(value: Permission[]) {
    this.value = value;
    this.dispatchEvent(new CustomEvent('change', { detail: { value }, bubbles: true, composed: true }));
  }

  private toggle(permission: Permission) {
    this.emit(this.isSelected(permission) ? this.value.filter(selected => selected.id !== permission.id) : [...this.value, permission]);
  }

  private toggleGroup(group: PermissionGroup) {
    const allSelected = group.permissions.every(permission => this.isSelected(permission));
    const ids = new Set(group.permissions.map(permission => permission.id));
    this.emit(allSelected
      ? this.value.filter(selected => !ids.has(selected.id))
      : [...this.value, ...group.permissions.filter(permission => !this.isSelected(permission))]);
  }

  render() {
    const groups = this.groups;
    return html`
      <div role="group" aria-label=${this.label}>
        ${this.permissions.length > 3 ? html`
          <input class="search" type="search" aria-label="Filter permissions" placeholder="Filter permissions"
            .value=${this.query} @input=${(e: Event) => this.query = (e.target as HTMLInputElement).value} />` : ''}
        ${this.permissions.length === 0 ? html`<div class="empty">No permissions in this tenant yet.</div>`
        : groups.length === 0 ? html`<div class="empty">No permissions match.</div>`
        : groups.map(group => {
          const allSelected = group.permissions.every(permission => this.isSelected(permission));
          return html`
            <div class="group">
              <div class="group-head">
                <span>${group.category}</span>
                <button type="button" @click=${() => this.toggleGroup(group)}>${allSelected ? 'Clear all' : 'Select all'}</button>
              </div>
              ${group.permissions.map(permission => html`
                <label class="option">
                  <input type="checkbox" .checked=${this.isSelected(permission)} @change=${() => this.toggle(permission)} />
                  <span class="option-text">
                    <span class="option-id">${permission.id}</span>
                    ${getLocalizedDescription(permission.description) ? html`<span class="option-desc"> · ${getLocalizedDescription(permission.description)}</span>` : ''}
                  </span>
                  <versola-permission-info .permission=${permission} .resources=${this.resources}></versola-permission-info>
                </label>
              `)}
            </div>
          `;
        })}
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-permission-picker': VersolaPermissionPicker;
  }
}
