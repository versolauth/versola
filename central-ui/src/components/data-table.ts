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
 * The row menu is the WAI-ARIA menu button pattern: click, Enter, Space or the arrows open it with
 * an item focused; the arrows move with wrap, Home and End jump; Escape closes and returns focus to
 * the trigger; Tab and a click elsewhere close; `aria-expanded` on the trigger says which.
 *
 * Below 720px the table becomes a stack of cards: the header row moves off screen (still read by
 * screen readers), each row is a grid with the name on the left and the menu on the right, the
 * other cells stacked under them with their column label above each value.
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
  /** The row whose record is open next to the table (the side panel); drawn selected. */
  @property({ type: String }) selectedKey: string | null = null;
  /** A click anywhere on a row (outside its menu) fires `row-select` with the row; the row's
   *  `.row-link` button stays the keyboard way in. */
  @property({ type: Boolean }) selectable = false;
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

      tr.selectable {
        cursor: pointer;
      }

      tbody tr[aria-current='true'] td,
      tbody tr[aria-current='true']:hover td {
        background: var(--accent-subtle);
      }

      tbody tr[aria-current='true'] td:first-child {
        box-shadow: inset 3px 0 0 var(--accent);
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

      .row-flags {
        margin-top: 0.25rem;
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

      /* ── card mode ──
         The table keeps its semantics: every element carries its ARIA table role explicitly (see
         render), so changing display here does not strip them (Safari/VoiceOver drop implicit
         roles on a display: block table), and the header row stays in the accessibility tree off
         screen. Each cell shows its column label through data-label. */
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
          gap: 0.5rem var(--spacing-md);
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

        tbody tr[aria-current='true'],
        tbody tr[aria-current='true']:hover td {
          background: var(--accent-subtle);
        }

        tbody tr[aria-current='true'] td:first-child {
          box-shadow: none;
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

        td[data-label]:not(:first-child)::before {
          content: attr(data-label);
          display: block;
          margin-bottom: 0.125rem;
          font-size: var(--text-xs);
          color: var(--text-secondary);
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
    document.addEventListener('click', this.handleOutsideClick);
  }

  disconnectedCallback() {
    document.removeEventListener('click', this.handleOutsideClick);
    super.disconnectedCallback();
  }

  private readonly handleOutsideClick = (event: Event) => {
    if (this.openMenuKey && !event.composedPath().includes(this)) this.openMenuKey = null;
  };

  private menuItemElements(): HTMLElement[] {
    return Array.from(this.shadowRoot?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
  }

  private focusMenuItem(index: number) {
    const all = this.menuItemElements();
    all[(index + all.length) % all.length]?.focus();
  }

  /** Opens a row's menu with its first item focused: the WAI-ARIA menu button pattern, like the
    * theme switcher's listbox. `last` focuses the last item (ArrowUp on the trigger). */
  private async openMenu(key: string, last = false) {
    this.openMenuKey = key;
    await this.updateComplete;
    this.focusMenuItem(last ? -1 : 0);
  }

  private closeMenu(returnFocus: boolean) {
    const key = this.openMenuKey;
    this.openMenuKey = null;
    if (returnFocus && key !== null) this.shadowRoot?.querySelector<HTMLElement>(`[data-menu-for="${CSS.escape(key)}"]`)?.focus();
  }

  /** Moves focus to a row's `.row-link`: where the side panel returns focus on Escape. */
  focusRow(key: string) {
    this.shadowRoot?.querySelector<HTMLElement>(`tr[data-key="${CSS.escape(key)}"] .row-link`)?.focus();
  }

  private handleRowClick(event: Event, row: T) {
    if (!this.selectable) return;
    // Selecting text in a row is not opening it.
    if (window.getSelection()?.toString()) return;
    // The menu button and its items act on their own; a click there is not a row selection.
    const path = event.composedPath();
    if (path.some(node => node instanceof HTMLElement && node.classList.contains('cell-actions'))) return;
    this.dispatchEvent(new CustomEvent('row-select', { detail: { row }, bubbles: true, composed: true }));
  }

  private handleTriggerClick(key: string) {
    if (this.openMenuKey === key) this.closeMenu(true);
    else void this.openMenu(key);
  }

  private handleTriggerKey(event: KeyboardEvent, key: string) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      void this.openMenu(key, event.key === 'ArrowUp');
    } else if (event.key === 'Escape' && this.openMenuKey === key) {
      event.preventDefault();
      this.closeMenu(true);
    }
  }

  private handleMenuKey(event: KeyboardEvent, index: number) {
    switch (event.key) {
      case 'ArrowDown': this.focusMenuItem(index + 1); break;
      case 'ArrowUp': this.focusMenuItem(index - 1); break;
      case 'Home': this.focusMenuItem(0); break;
      case 'End': this.focusMenuItem(-1); break;
      case 'Escape': this.closeMenu(true); break;
      case 'Tab': this.closeMenu(false); return;
      default: return;
    }
    event.preventDefault();
  }

  private renderMenu(row: T, key: string): TemplateResult | typeof nothing {
    if (!this.menuItems) return nothing;
    const items = this.menuItems(row);
    const open = this.openMenuKey === key;
    const label = this.menuLabel(row);
    return html`
      <td class="cell-actions" role="cell">
        <button type="button" class="icon-button menu-button" data-menu-for=${key}
          aria-label=${label} aria-haspopup="menu" aria-expanded=${open}
          @click=${() => this.handleTriggerClick(key)}
          @keydown=${(e: KeyboardEvent) => this.handleTriggerKey(e, key)}>
          ${statusIcon('dots', 'icon')}
        </button>
        ${open ? html`
          <div class="menu" role="menu" aria-label=${label}>
            ${items.map((item, index) => html`
              <button type="button" role="menuitem" tabindex="-1" class=${item.danger ? 'danger' : ''}
                aria-label=${item.ariaLabel ?? nothing}
                @keydown=${(e: KeyboardEvent) => this.handleMenuKey(e, index)}
                @click=${() => { this.closeMenu(false); item.onSelect(); }}>${item.label}</button>
            `)}
          </div>` : ''}
      </td>
    `;
  }

  render() {
    return html`
      <div class="card table-card">
        <table role="table">
          <thead role="rowgroup">
            <tr role="row">
              ${this.columns.map(column => html`
                <th scope="col" role="columnheader">${column.srOnly ? html`<span class="sr-only">${column.label}</span>` : column.label}</th>
              `)}
              ${this.menuItems ? html`<th scope="col" role="columnheader"><span class="sr-only">Actions</span></th>` : ''}
            </tr>
          </thead>
          <tbody role="rowgroup">
            ${this.rows.map(row => {
              const key = this.rowKey(row);
              return html`
                <tr role="row" data-key=${key} class=${this.selectable ? 'selectable' : nothing}
                  aria-current=${this.selectedKey !== null && this.selectedKey === key ? 'true' : nothing}
                  @click=${(e: Event) => this.handleRowClick(e, row)}>
                  ${this.columns.map(column => html`
                    <td role="cell" class=${column.class ?? nothing} data-label=${column.srOnly ? nothing : column.label}>${column.cell(row)}</td>`)}
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
