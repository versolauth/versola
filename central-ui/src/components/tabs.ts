import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';

export type TabItem = { id: string; label: string };

/**
 * The tabs of a record page (the user record): a WAI-ARIA tablist above one tabpanel. The host decides which tab is
 * selected (`value`, kept in the URL) and renders only the selected tab's content into the default slot, so a tab
 * whose data is expensive is not fetched until it is opened. Activation is manual: the arrow keys, Home and End move
 * focus along the bar (roving tabindex) and Enter, Space or a click select, so passing over a tab loads nothing. Fires
 * `tab-change` with `{ id }`. Below 768px the bar scrolls sideways inside itself instead of wrapping.
 */
@customElement('versola-tabs')
export class VersolaTabs extends LitElement {
  /** The accessible name of the tab list, e.g. "User sections". */
  @property({ type: String }) label = 'Sections';
  @property({ attribute: false }) tabs: TabItem[] = [];
  @property({ type: String }) value = '';
  /** The tab that holds the roving tabindex while focus is in the bar. */
  @state() private focusId = '';

  static styles = [
    theme,
    css`
      :host {
        display: block;
      }

      .tablist {
        display: flex;
        gap: var(--spacing-xs);
        margin-bottom: var(--spacing-lg);
        border-bottom: 1px solid var(--border-default);
        overflow-x: auto;
        scrollbar-width: thin;
      }

      .tab {
        flex: none;
        min-height: 2.75rem;
        margin-bottom: -1px;
        padding: 0 var(--spacing-md);
        border: 0;
        border-bottom: 3px solid transparent;
        background: none;
        color: var(--text-secondary);
        font: inherit;
        font-size: var(--text-base);
        font-weight: 600;
        white-space: nowrap;
        cursor: pointer;
        transition: color var(--transition-fast);
      }

      .tab:hover {
        color: var(--text-primary);
      }

      .tab[aria-selected='true'] {
        color: var(--accent);
        border-bottom-color: var(--accent);
      }

      .tab:focus-visible,
      .panel:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: -2px;
        border-radius: var(--radius-sm);
      }

      .panel {
        outline-offset: 2px;
      }
    `,
  ];

  private get currentFocus(): string {
    return this.tabs.some(tab => tab.id === this.focusId) ? this.focusId : this.value;
  }

  private moveFocus(id: string) {
    this.focusId = id;
    void this.updateComplete.then(() => {
      const target = this.renderRoot.querySelector<HTMLElement>(`#tab-${id}`);
      target?.focus();
      target?.scrollIntoView({ inline: 'nearest', block: 'nearest' });
    });
  }

  private handleKeyDown(event: KeyboardEvent) {
    const ids = this.tabs.map(tab => tab.id);
    const index = ids.indexOf(this.currentFocus);
    let next: number | null = null;
    if (event.key === 'ArrowRight') next = (index + 1) % ids.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + ids.length) % ids.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = ids.length - 1;
    if (next === null) return;
    event.preventDefault();
    this.moveFocus(ids[next]);
  }

  private select(id: string) {
    this.focusId = id;
    if (id === this.value) return;
    this.dispatchEvent(new CustomEvent('tab-change', { detail: { id }, bubbles: true, composed: true }));
  }

  render() {
    const focusable = this.currentFocus;
    return html`
      <div class="tablist" role="tablist" aria-label=${this.label} @keydown=${this.handleKeyDown}
        @focusout=${(event: FocusEvent) => { if (!(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) this.focusId = ''; }}>
        ${this.tabs.map(tab => html`
          <button type="button" class="tab" role="tab" id=${`tab-${tab.id}`} aria-selected=${tab.id === this.value ? 'true' : 'false'}
            aria-controls="panel" tabindex=${tab.id === focusable ? '0' : '-1'} @click=${() => this.select(tab.id)}>${tab.label}</button>`)}
      </div>
      <div class="panel" role="tabpanel" id="panel" tabindex="0" aria-labelledby=${`tab-${this.value}`}><slot></slot></div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-tabs': VersolaTabs;
  }
}
