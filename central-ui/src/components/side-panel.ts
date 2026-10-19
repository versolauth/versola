import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, chipStyles, iconButtonStyles } from '../styles/components';
import { copyToClipboard } from '../utils/helpers';
import { statusIcon } from './status-icon';

export type SidePanelMode = 'view' | 'edit' | 'create';

/**
 * The detail of a row in a list of small records (scopes, roles, permissions, tenants, edges):
 * a panel fixed to the right edge that holds the record's groups, each with its own Edit, the
 * Fixed group and the danger card, at 560px (`wide`: 720px, for a picker). The list stays
 * visible and selectable next to it.
 *
 * Three modes. `view` is a non-modal `role="dialog"`: the kind label, the title, the ID with a
 * copy button and the chips in `meta`, `Open page` and Close. `edit` turns the same panel into
 * the editor of one group (breadcrumb `Record › Edit <group>`) and `create` into the form of a
 * new record; both are modal, with a backdrop and a focus trap, and end in a footer (`slot="footer"`)
 * with Cancel and the one primary submit. The screen renders the body into the default slot, so
 * record cards keep the screen's `recordStyles` (wrap them in `.record-compact`).
 *
 * Escape and the close button fire `close`; the screen returns focus to the row it came from
 * (`versola-data-table.focusRow`). `open-page` asks for the full-page fallback, `back` is the
 * breadcrumb of the edit mode. Below 1024px the panel is the width of the viewport; below 720px
 * it is a full-screen sheet that replaces the list.
 */
@customElement('versola-side-panel')
export class VersolaSidePanel extends LitElement {
  /** The kind of record, shown above the title in view mode (`Scope`). */
  @property({ type: String }) kind = '';
  @property({ type: String }) heading = '';
  @property({ type: String }) recordId = '';
  /** What the ID is called in the copy button's name: `Copy scope ID`. */
  @property({ type: String }) idLabel = 'ID';
  @property({ attribute: false }) meta: unknown = null;
  @property({ type: String }) mode: SidePanelMode = 'view';
  @property({ type: Boolean, reflect: true }) wide = false;
  /** Edit mode: the record the group belongs to, the first crumb. */
  @property({ type: String }) parentLabel = '';
  /** One line under the title in edit and create mode. */
  @property({ type: String }) subtitle = '';
  @property({ type: Boolean }) showOpenPage = true;
  @state() private hasFooter = false;
  @query('.panel-title') private titleEl?: HTMLElement;

  static styles = [
    theme,
    buttonStyles,
    chipStyles,
    iconButtonStyles,
    css`
      :host {
        display: contents;
      }

      .backdrop {
        position: fixed;
        inset: 0;
        z-index: var(--z-backdrop);
        background: var(--backdrop);
      }

      .panel {
        position: fixed;
        top: 0;
        right: 0;
        bottom: 0;
        z-index: var(--z-drawer);
        display: flex;
        flex-direction: column;
        width: min(100vw, 35rem);
        background: var(--surface);
        /* The login sheet: rounded on its free edge, the sheet shadow, no line. */
        border-radius: var(--radius-lg) 0 0 var(--radius-lg);
        box-shadow: var(--shadow-sheet);
        color: var(--text-primary);
        overflow: hidden;
      }

      :host([wide]) .panel {
        width: min(100vw, 45rem);
      }

      .panel-head {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: var(--spacing-md);
        padding: var(--spacing-lg) var(--spacing-lg) var(--spacing-md);
        border-bottom: 1px solid var(--border-default);
      }

      .head-text {
        min-width: 0;
        flex: 1;
      }

      .kind,
      .crumbs {
        display: flex;
        gap: 0.375rem;
        align-items: center;
        flex-wrap: wrap;
        font-size: var(--text-sm);
        color: var(--text-secondary);
        margin-bottom: 0.25rem;
      }

      .crumb-link {
        background: none;
        border: 0;
        padding: 0;
        min-height: 1.5rem;
        font: inherit;
        font-size: var(--text-sm);
        color: var(--accent);
        cursor: pointer;
      }

      .crumb-link:hover {
        text-decoration: underline;
      }

      .crumb-link:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
        border-radius: var(--radius-sm);
      }

      .panel-title {
        margin: 0;
        font-size: var(--text-2xl);
        font-weight: 800;
        letter-spacing: -0.02em;
        overflow-wrap: anywhere;
      }

      .panel-title:focus {
        outline: none;
      }

      .meta {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
        align-items: center;
        margin-top: 0.5rem;
      }

      .record-id {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        color: var(--accent);
        word-break: break-all;
      }

      .subtitle {
        margin: 0.375rem 0 0;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .head-actions {
        display: flex;
        gap: 0.5rem;
        align-items: center;
        flex: none;
      }

      .panel-body {
        flex: 1;
        overflow: auto;
        padding: var(--spacing-md) var(--spacing-lg) var(--spacing-lg);
      }

      .panel-foot {
        display: flex;
        justify-content: flex-end;
        gap: 0.75rem;
        padding: var(--spacing-md) var(--spacing-lg);
        border-top: 1px solid var(--border-default);
        background: var(--surface);
      }

      .panel-foot.empty {
        display: none;
      }

      .sentinel {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0 0 0 0);
      }

      @media (max-width: 1023px) {
        /* left + right instead of 100vw: 100vw counts a classic scrollbar, which pushes the
           panel's left edge that many pixels off screen. */
        .panel,
        :host([wide]) .panel {
          left: 0;
          width: auto;
        }
      }

      @media (max-width: 720px) {
        .panel {
          border-radius: 0;
        }

        .panel-head {
          padding: var(--spacing-md);
        }

        .panel-body {
          padding: var(--spacing-md);
        }

        .panel-foot {
          flex-direction: column-reverse;
          padding: var(--spacing-md);
        }

        .panel-foot ::slotted(*) {
          display: flex;
          flex-direction: column-reverse;
          gap: 0.75rem;
        }
      }
    `,
  ];

  private get modal(): boolean {
    return this.mode !== 'view';
  }

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('keydown', this.handleKeyDown);
  }

  disconnectedCallback() {
    window.removeEventListener('keydown', this.handleKeyDown);
    document.documentElement.style.removeProperty('--panel-inset');
    super.disconnectedCallback();
  }

  firstUpdated() {
    this.focusTitle();
  }

  updated(changed: Map<string, unknown>) {
    // A mode change is a new dialog: a screen reader hears the title again. Picking another row
    // while the panel is open in view mode leaves focus on that row, so the list stays walkable.
    if (changed.has('mode') || (this.mode !== 'view' && changed.has('recordId') && changed.get('recordId') !== undefined)) this.focusTitle();
    this.syncInset();
  }

  /** At 1440px and up the panel takes its 560px from the page instead of covering the table:
    * `admin-app` pads `<main>` by `--panel-inset`. Narrower screens keep the overlay. */
  private static readonly INSET_QUERY = '(min-width: 1440px)';

  private syncInset() {
    const inset = this.mode === 'view' && window.matchMedia(VersolaSidePanel.INSET_QUERY).matches && !this.wide;
    if (inset) document.documentElement.style.setProperty('--panel-inset', '35rem');
    else document.documentElement.style.removeProperty('--panel-inset');
  }

  private focusTitle() {
    void this.updateComplete.then(() => this.titleEl?.focus({ preventScroll: true }));
  }

  /** Escape closes, unless something above the panel (the confirm dialog, a row menu, a popover)
    * already took it. */
  private readonly handleKeyDown = (event: KeyboardEvent) => {
    if (event.key !== 'Escape' || event.defaultPrevented) return;
    if (document.querySelector('[data-versola-confirm-dialog]')) return;
    event.preventDefault();
    this.close();
  };

  private close() {
    this.dispatchEvent(new CustomEvent('close', { bubbles: true, composed: true }));
  }

  /** The focus trap of the modal modes: a Tab past the last control lands on the first and the
    * other way round. The sentinels are focusable only while modal. */
  private trapToStart() {
    const footer = this.querySelector('[slot="footer"]');
    const buttons = footer ? Array.from(footer.querySelectorAll<HTMLElement>('button, a[href], input, select, textarea')) : [];
    (buttons[buttons.length - 1] ?? this.shadowRoot?.querySelector<HTMLElement>('.close-button'))?.focus();
  }

  private trapToEnd() {
    this.shadowRoot?.querySelector<HTMLElement>('.close-button')?.focus();
  }

  private handleFooterSlotChange(event: Event) {
    const slot = event.target as HTMLSlotElement;
    this.hasFooter = slot.assignedElements().length > 0;
  }

  render() {
    const modal = this.modal;
    return html`
      ${modal ? html`<div class="backdrop" @click=${this.close}></div>` : ''}
      <aside class="panel" role="dialog" aria-modal=${modal} aria-labelledby="panel-title">
        ${modal ? html`<div class="sentinel" tabindex="0" @focus=${this.trapToStart}></div>` : ''}
        <header class="panel-head">
          <div class="head-text">
            ${this.mode === 'edit' && this.parentLabel ? html`
              <nav class="crumbs" aria-label="Breadcrumb">
                <button type="button" class="crumb-link" @click=${() => this.dispatchEvent(new CustomEvent('back', { bubbles: true, composed: true }))}><span aria-hidden="true">‹ </span>${this.parentLabel}</button>
              </nav>` : this.mode === 'view' && this.kind ? html`<div class="kind">${this.kind}</div>` : ''}
            <h2 class="panel-title" id="panel-title" tabindex="-1">${this.heading}</h2>
            ${this.mode === 'view' ? html`
              <div class="meta">
                ${this.recordId ? html`
                  <span class="record-id">${this.recordId}</span>
                  <button type="button" class="icon-button" aria-label=${`Copy ${this.idLabel}`} title=${`Copy ${this.idLabel}`}
                    @click=${() => copyToClipboard(this.recordId)}>${statusIcon('copy', 'icon')}</button>
                ` : ''}
                ${this.meta}
              </div>` : this.subtitle ? html`<p class="subtitle">${this.subtitle}</p>`
              : this.recordId ? html`<div class="meta"><span class="record-id">${this.recordId}</span></div>` : ''}
          </div>
          <div class="head-actions">
            ${this.mode === 'view' && this.showOpenPage ? html`
              <button type="button" class="btn btn-secondary btn-sm"
                @click=${() => this.dispatchEvent(new CustomEvent('open-page', { bubbles: true, composed: true }))}>Open page</button>` : ''}
            <button type="button" class="icon-button close-button" aria-label=${modal ? 'Cancel and close' : 'Close panel'}
              title=${modal ? 'Cancel and close' : 'Close panel'} @click=${this.close}>${statusIcon('x', 'icon', 18)}</button>
          </div>
        </header>
        <div class="panel-body"><slot></slot></div>
        <footer class=${`panel-foot ${this.hasFooter ? '' : 'empty'}`}><slot name="footer" @slotchange=${this.handleFooterSlotChange}></slot></footer>
        ${modal ? html`<div class="sentinel" tabindex="0" @focus=${this.trapToEnd}></div>` : nothing}
      </aside>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-side-panel': VersolaSidePanel;
  }
}
