import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { iconButtonStyles } from '../styles/components';
import { statusIcon } from './status-icon';

export type TypeaheadOption = { id: string; label: string };

/**
 * A typeahead select for a list filter with too many values for a row of pills (the permissions'
 * resources): a text field with a listbox of the options that match what was typed, the ARIA 1.2
 * combobox with list autocomplete. The field shows the chosen option's label; focusing it or
 * pressing the down arrow opens the list; typing filters it; the arrows move, Home and End jump,
 * Enter picks, Escape closes (the first press) and Tab leaves. The clear button, shown while an
 * option is chosen, empties the choice. Fires `change` with `{ id }` (`''` for cleared).
 */
@customElement('versola-typeahead-select')
export class VersolaTypeaheadSelect extends LitElement {
  /** The accessible name of the field and the listbox. */
  @property({ type: String }) label = 'Select';
  @property({ type: String }) placeholder = '';
  @property({ attribute: false }) options: TypeaheadOption[] = [];
  /** The chosen option's id; `''` is none. */
  @property({ type: String }) value = '';
  @property({ type: String }) emptyText = 'Nothing matches';
  @state() private open = false;
  /** What was typed since the field was focused; `null` shows the chosen label instead. */
  @state() private typed: string | null = null;
  @state() private activeIndex = -1;
  @query('input') private input?: HTMLInputElement;
  private suppressOpen = false;
  private static nextId = 0;
  private readonly uid = `typeahead-${VersolaTypeaheadSelect.nextId++}`;

  static styles = [
    theme,
    iconButtonStyles,
    css`
      :host {
        display: inline-block;
        position: relative;
        width: 16rem;
        max-width: 100%;
      }

      .field {
        position: relative;
      }

      /* The console's field, as formStyles draws it, with the chevron or the clear button at the
         right inset. */
      input {
        width: 100%;
        box-sizing: border-box;
        padding: 0.6875rem 2.75rem 0.6875rem 1rem;
        background: var(--field);
        border: 1.5px solid var(--border-input);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-family: var(--font-family);
        font-size: var(--text-base);
        transition: border-color var(--transition-fast), background-color var(--transition-fast);
      }

      input::placeholder {
        color: var(--text-muted);
        opacity: 1;
      }

      input:hover {
        background-color: var(--field-hover);
      }

      input:focus {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
        background-color: var(--surface);
        border-color: var(--text-primary);
      }

      .chevron {
        position: absolute;
        top: 50%;
        right: 1rem;
        width: 0.625rem;
        height: 0.375rem;
        margin-top: -0.1875rem;
        background: var(--select-chevron) center / contain no-repeat;
        pointer-events: none;
      }

      .clear {
        position: absolute;
        top: 50%;
        right: 0.375rem;
        transform: translateY(-50%);
        min-width: 1.75rem;
        min-height: 1.75rem;
      }

      .options {
        position: absolute;
        top: calc(100% + 0.375rem);
        left: 0;
        z-index: var(--z-dropdown);
        box-sizing: border-box;
        min-width: 100%;
        max-width: 24rem;
        max-height: 16rem;
        margin: 0;
        padding: 0.375rem;
        overflow-y: auto;
        list-style: none;
        background: var(--surface-raised);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-inner);
        box-shadow: var(--shadow-card);
      }

      .option {
        display: flex;
        align-items: center;
        min-height: 2rem;
        padding: 0.25rem 0.75rem;
        border-radius: var(--radius-sm);
        color: var(--text-primary);
        font-size: var(--text-base);
        cursor: pointer;
      }

      .option .name {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .option.active,
      .option:hover {
        background: var(--surface-inset);
      }

      .option[aria-selected='true'] {
        background: var(--accent-subtle);
        color: var(--accent-text);
      }

      .empty {
        padding: 0.5rem 0.75rem;
        color: var(--text-secondary);
        font-size: var(--text-sm);
      }
    `,
  ];

  private get chosen(): TypeaheadOption | undefined {
    return this.options.find(option => option.id === this.value);
  }

  private get filtered(): TypeaheadOption[] {
    const query = (this.typed ?? '').trim().toLowerCase();
    return query ? this.options.filter(option => option.label.toLowerCase().includes(query)) : this.options;
  }

  private openList() {
    if (this.open) return;
    this.open = true;
    const chosenIndex = this.filtered.findIndex(option => option.id === this.value);
    this.activeIndex = chosenIndex >= 0 ? chosenIndex : (this.filtered.length ? 0 : -1);
  }

  private closeList() {
    this.open = false;
    this.typed = null;
    this.activeIndex = -1;
  }

  private pick(option: TypeaheadOption) {
    this.value = option.id;
    this.closeList();
    this.dispatchEvent(new CustomEvent('change', { detail: { id: option.id }, bubbles: true, composed: true }));
  }

  private clear() {
    this.value = '';
    this.closeList();
    this.dispatchEvent(new CustomEvent('change', { detail: { id: '' }, bubbles: true, composed: true }));
    this.suppressOpen = true;
    this.input?.focus();
    this.suppressOpen = false;
  }

  private handleInput(event: Event) {
    this.typed = (event.target as HTMLInputElement).value;
    this.open = true;
    this.activeIndex = this.filtered.length ? 0 : -1;
  }

  private move(delta: number) {
    const size = this.filtered.length;
    if (size === 0) return;
    this.activeIndex = (this.activeIndex + delta + size) % size;
    void this.updateComplete.then(() => this.shadowRoot?.querySelector('.option.active')?.scrollIntoView({ block: 'nearest' }));
  }

  private handleKeyDown(event: KeyboardEvent) {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        if (!this.open) this.openList(); else this.move(1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        if (!this.open) this.openList(); else this.move(-1);
        break;
      case 'Home':
        if (this.open) { event.preventDefault(); this.activeIndex = this.filtered.length ? 0 : -1; }
        break;
      case 'End':
        if (this.open) { event.preventDefault(); this.activeIndex = this.filtered.length - 1; }
        break;
      case 'Enter':
        if (this.open) {
          event.preventDefault();
          const option = this.filtered[this.activeIndex];
          if (option) this.pick(option);
        }
        break;
      case 'Escape':
        // The first Escape only closes the list; the side panel next to it must not also close.
        if (this.open) { event.preventDefault(); this.closeList(); }
        break;
      case 'Tab':
        this.closeList();
        break;
    }
  }

  private handleFocusOut(event: FocusEvent) {
    const next = event.relatedTarget as Node | null;
    if (next && this.shadowRoot?.contains(next)) return;
    this.closeList();
  }

  render() {
    const matches = this.filtered;
    const listId = `${this.uid}-list`;
    const active = this.open && matches[this.activeIndex] ? `${this.uid}-opt-${this.activeIndex}` : nothing;
    return html`
      <div class="field" @focusout=${this.handleFocusOut}>
        <input type="text" role="combobox" aria-label=${this.label} aria-autocomplete="list" aria-haspopup="listbox"
          aria-expanded=${this.open} aria-controls=${listId} aria-activedescendant=${active}
          autocomplete="off" spellcheck="false" placeholder=${this.placeholder}
          .value=${this.typed ?? this.chosen?.label ?? ''}
          @focus=${() => { if (!this.suppressOpen) this.openList(); }} @click=${() => this.openList()}
          @input=${this.handleInput} @keydown=${this.handleKeyDown} />
        ${this.value ? html`
          <button type="button" class="icon-button clear" aria-label=${`Clear ${this.label.toLowerCase()}`} title=${`Clear ${this.label.toLowerCase()}`}
            @click=${this.clear}>${statusIcon('x', 'icon', 14)}</button>` : html`<span class="chevron" aria-hidden="true"></span>`}
        ${this.open ? html`
          <ul class="options" id=${listId} role="listbox" aria-label=${this.label}>
            ${matches.length ? matches.map((option, index) => html`
              <li class=${`option ${index === this.activeIndex ? 'active' : ''}`} role="option" id=${`${this.uid}-opt-${index}`}
                aria-selected=${option.id === this.value}
                @mousedown=${(e: Event) => e.preventDefault()}
                @click=${() => this.pick(option)}>
                <span class="name" title=${option.label}>${option.label}</span>
              </li>`) : html`<li class="empty" role="presentation">${this.emptyText}</li>`}
          </ul>` : ''}
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-typeahead-select': VersolaTypeaheadSelect;
  }
}
