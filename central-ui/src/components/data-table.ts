import { LitElement, html, css, nothing, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { cardStyles, chipStyles, iconButtonStyles } from '../styles/components';
import { statusIcon } from './status-icon';

export type DataTableColumn<T> = {
  id: string;
  label: string;
  /** The header is read by screen readers only (the actions column). */
  srOnly?: boolean;
  /** Extra class on the cell, for a screen's own column widths. */
  class?: string;
  cell: (row: T) => unknown;
};

export type RowMenuItem = {
  label: string;
  onSelect: () => void;
  danger?: boolean;
  /** When the visible label is not specific enough on its own (`Delete client alpha-web`). */
  ariaLabel?: string;
};

/**
 * The list table of a section: a card with column headers and one row per record. The first
 * column is the record's name (a `.row-link` that opens it); the last, when `menuItems` is set,
 * is a `...` button with a menu (open, copy ID, delete) — nothing in a list edits.
 *
 * Below 720px the table becomes a stack of cards: headers are hidden, each row is a grid with the
 * name on the left and the menu on the right, the other cells stacked under them.
 *
 * Cells are rendered here, so the styles a cell can rely on are the ones in this file
 * (`.row-link`, `.row-title`, `.row-id`, `.muted`, `.type-label`, `.icon`, chips).
 */
@customElement('versola-data-table')
export class VersolaDataTable<T = unknown> extends LitElement {
  @property({ attribute: false }) columns: DataTableColumn<T>[] = [];
  @property({ attribute: false }) rows: T[] = [];
  @property({ attribute: false }) rowKey: (row: T) => string = row => String(row);
  @property({ attribute: false }) menuItems: ((row: T) => RowMenuItem[]) | null = null;
  /** The accessible name of a row's menu button, e.g. `Actions for alpha-web`. */
  @property({ attribute: false }) menuLabel: (row: T) => string = row => `Actions for ${this.rowKey(row)}`;
  @state() private openMenuKey: string | null = null;

  static styles = [
    theme,
    cardStyles,
    chipStyles,
    iconButtonStyles,
    css`
      :host {
        display: block;
      }

      .table-card {
        padding: 0;
        overflow: visible;
      }

      table {
        width: 100%;
        border-collapse: collapse;
      }

      th {
        text-align: left;
        font-size: var(--text-xs);
        font-weight: 500;
        color: var(--text-secondary);
        padding: 0.75rem var(--spacing-md);
        border-bottom: 1px solid var(--border-default);
      }

      td {
        padding: 0.875rem var(--spacing-md);
        border-bottom: 1px solid var(--border-subtle);
        vertical-align: middle;
        font-size: var(--text-base);
        color: var(--text-primary);
      }

      tbody tr:last-child td {
        border-bottom: 0;
      }

      tbody tr:hover td {
        background: var(--surface-inset);
      }

      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0 0 0 0);
        white-space: nowrap;
      }

      /* ── cell idioms ── */
      .row-link {
        display: flex;
        flex-direction: column;
        gap: 0.125rem;
        background: none;
        border: 0;
        padding: 0;
        font: inherit;
        text-align: left;
        cursor: pointer;
        color: inherit;
        min-height: 1.5rem;
      }

      .row-link:hover .row-title {
        color: var(--accent);
        text-decoration: underline;
      }

      .row-link:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
        border-radius: var(--radius-sm);
      }

      .row-title {
        font-weight: 600;
        font-size: var(--text-md);
      }

      .row-id {
        font-family: var(--font-mono);
        font-size: var(--text-base);
        color: var(--accent);
        word-break: break-all;
      }

      .muted {
        color: var(--text-secondary);
      }

      .type-label {
        display: inline-flex;
        gap: 0.5rem;
        align-items: center;
      }

      .type-label .icon {
        color: var(--text-secondary);
      }

      .icon {
        flex: none;
        vertical-align: -3px;
      }

      /* ── row menu ── */
      .cell-actions {
        width: 3rem;
        position: relative;
        text-align: right;
      }

      .menu-button {
        color: var(--text-primary);
        border: 1px solid var(--border-input);
        min-width: 2rem;
        min-height: 2rem;
        border-radius: var(--radius-md);
      }

      .menu {
        position: absolute;
        right: var(--spacing-md);
        top: 2.75rem;
        z-index: var(--z-dropdown);
        min-width: 11rem;
        display: flex;
        flex-direction: column;
        padding: 0.25rem;
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        background: var(--surface-raised);
        box-shadow: var(--shadow-lg);
        text-align: left;
      }

      .menu button {
        min-height: 2rem;
        padding: 0 0.75rem;
        border: 0;
        border-radius: var(--radius-sm);
        background: none;
        color: var(--text-primary);
        font: inherit;
        font-size: var(--text-base);
        text-align: left;
        cursor: pointer;
      }

      .menu button:hover {
        background: var(--surface-inset);
      }

      .menu button:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: -2px;
      }

      .menu button.danger {
        color: var(--danger);
      }

      /* ── card mode ── */
      @media (max-width: 720px) {
        thead {
          position: absolute;
          left: -9999px;
        }

        table,
        tbody,
        tr,
        td {
          display: block;
        }

        tr {
          display: grid;
          grid-template-columns: minmax(0, 1fr) auto;
          gap: 0.375rem var(--spacing-md);
          padding: var(--spacing-md);
          border-bottom: 1px solid var(--border-subtle);
        }

        tbody tr:last-child {
          border-bottom: 0;
        }

        td {
          padding: 0;
          border: 0;
        }

        tbody tr:hover td {
          background: none;
        }

        td:first-child {
          grid-column: 1;
        }

        .cell-actions {
          grid-row: 1;
          grid-column: 2;
          width: auto;
        }

        td:not(:first-child):not(.cell-actions) {
          grid-column: 1 / -1;
        }

        .menu {
          right: 0;
          top: 2.25rem;
        }
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    document.addEventListener('click', this.closeMenu);
    document.addEventListener('keydown', this.handleKeyDown);
  }

  disconnectedCallback() {
    document.removeEventListener('click', this.closeMenu);
    document.removeEventListener('keydown', this.handleKeyDown);
    super.disconnectedCallback();
  }

  private readonly closeMenu = () => {
    if (this.openMenuKey) this.openMenuKey = null;
  };

  private readonly handleKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && this.openMenuKey) {
      const key = this.openMenuKey;
      this.openMenuKey = null;
      this.shadowRoot?.querySelector<HTMLElement>(`[data-menu-for="${CSS.escape(key)}"]`)?.focus();
    }
  };

  private toggleMenu(event: Event, key: string) {
    event.stopPropagation();
    this.openMenuKey = this.openMenuKey === key ? null : key;
  }

  private renderMenu(row: T, key: string): TemplateResult | typeof nothing {
    if (!this.menuItems) return nothing;
    const items = this.menuItems(row);
    return html`
      <td class="cell-actions" @click=${(e: Event) => e.stopPropagation()}>
        <button type="button" class="icon-button menu-button" data-menu-for=${key}
          aria-label=${this.menuLabel(row)} aria-haspopup="menu" aria-expanded=${this.openMenuKey === key}
          @click=${(e: Event) => this.toggleMenu(e, key)}>
          ${statusIcon('dots', 'icon')}
        </button>
        ${this.openMenuKey === key ? html`
          <div class="menu" role="menu">
            ${items.map(item => html`
              <button type="button" role="menuitem" class=${item.danger ? 'danger' : ''}
                aria-label=${item.ariaLabel ?? nothing}
                @click=${() => { this.openMenuKey = null; item.onSelect(); }}>${item.label}</button>
            `)}
          </div>` : ''}
      </td>
    `;
  }

  render() {
    return html`
      <div class="card table-card">
        <table>
          <thead>
            <tr>
              ${this.columns.map(column => html`
                <th scope="col">${column.srOnly ? html`<span class="sr-only">${column.label}</span>` : column.label}</th>
              `)}
              ${this.menuItems ? html`<th scope="col"><span class="sr-only">Actions</span></th>` : ''}
            </tr>
          </thead>
          <tbody>
            ${this.rows.map(row => {
              const key = this.rowKey(row);
              return html`
                <tr>
                  ${this.columns.map(column => html`<td class=${column.class ?? nothing}>${column.cell(row)}</td>`)}
                  ${this.renderMenu(row, key)}
                </tr>
              `;
            })}
          </tbody>
        </table>
      </div>
    `;
  }
}
