import { LitElement, html, css, nothing, svg } from 'lit';
import { customElement, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { getStoredPreference, setThemePreference, THEME_PREFERENCES, type ThemePreference } from '../utils/theme';

const LABELS: Record<ThemePreference, string> = { auto: 'Auto', light: 'Light', dark: 'Dark' };

/**
 * The visitor's Auto / Light / Dark choice, as an icon button that opens a listbox: the same
 * control the login forms have (forms/shared/scheme-toggle.tsx), so the two surfaces behave
 * alike. The icon shows the current choice (a half-filled circle for Auto, a sun, a moon); the
 * button's accessible name says it in words.
 *
 * The listbox is the ARIA "select-only combobox" pattern without the text field: Enter, Space
 * or the arrows open it with the current item focused; the arrows, Home and End move; Enter or
 * Space picks; Escape closes and returns focus to the button; Tab or a click elsewhere closes.
 */
@customElement('versola-theme-switcher')
export class VersolaThemeSwitcher extends LitElement {
  @state() private preference: ThemePreference = getStoredPreference();
  @state() private open = false;
  @query('.trigger') private trigger?: HTMLButtonElement;

  static styles = [
    theme,
    css`
      :host {
        display: inline-block;
        position: relative;
      }

      .trigger {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 2.25rem;
        height: 2.25rem;
        padding: 0;
        border: 1px solid var(--border-input);
        border-radius: var(--radius-md);
        background: var(--surface);
        color: var(--text-secondary);
        cursor: pointer;
        transition: border-color var(--transition-fast), color var(--transition-fast);
      }

      .trigger:hover,
      .trigger[aria-expanded='true'] {
        border-color: var(--accent);
        color: var(--accent);
      }

      .trigger:focus-visible,
      .option:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      .icon {
        width: 1.125rem;
        height: 1.125rem;
      }

      /* Opens upwards: the switcher sits in the sidebar footer, at the bottom of the viewport. */
      .options {
        position: absolute;
        right: 0;
        bottom: calc(100% + 0.25rem);
        z-index: 20;
        min-width: 8rem;
        margin: 0;
        padding: 0.25rem;
        list-style: none;
        background: var(--surface-raised);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        box-shadow: var(--shadow-lg);
      }

      .option {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        min-height: 2rem;
        padding: 0 0.625rem;
        border-radius: var(--radius-sm);
        color: var(--text-primary);
        font-size: 0.875rem;
        cursor: pointer;
      }

      .option:hover {
        background: var(--surface-inset);
      }

      .option[aria-selected='true'] {
        background: var(--accent-subtle);
        color: var(--accent-text);
        font-weight: 600;
      }

      .option .icon {
        width: 1rem;
        height: 1rem;
        color: var(--text-secondary);
      }

      .option[aria-selected='true'] .icon {
        color: inherit;
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
    if (this.open && !event.composedPath().includes(this)) this.open = false;
  };

  private options(): HTMLElement[] {
    return Array.from(this.shadowRoot?.querySelectorAll<HTMLElement>('[role="option"]') ?? []);
  }

  private focusOption(index: number) {
    const all = this.options();
    all[(index + all.length) % all.length]?.focus();
  }

  private async show(index = THEME_PREFERENCES.indexOf(this.preference)) {
    this.open = true;
    await this.updateComplete;
    this.focusOption(Math.max(0, index));
  }

  private close(returnFocus: boolean) {
    this.open = false;
    if (returnFocus) this.trigger?.focus();
  }

  private pick(value: ThemePreference) {
    this.preference = value;
    setThemePreference(value);
    this.close(true);
  }

  private handleTriggerKey(event: KeyboardEvent) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      void this.show();
    } else if (event.key === 'Escape' && this.open) {
      event.preventDefault();
      this.close(false);
    }
  }

  private handleOptionKey(event: KeyboardEvent, index: number, value: ThemePreference) {
    switch (event.key) {
      case 'ArrowDown': this.focusOption(index + 1); break;
      case 'ArrowUp': this.focusOption(index - 1); break;
      case 'Home': this.focusOption(0); break;
      case 'End': this.focusOption(-1); break;
      case 'Enter': case ' ': this.pick(value); break;
      case 'Escape': this.close(true); break;
      case 'Tab': this.close(false); return;
      default: return;
    }
    event.preventDefault();
  }

  /** The glyph of a choice: a sun, a moon, or a half-filled circle for "follow the device". */
  private icon(choice: ThemePreference) {
    const body = choice === 'light'
      ? svg`<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>`
      : choice === 'dark'
        ? svg`<path d="M20 14.5A8.5 8.5 0 1 1 9.5 4a6.7 6.7 0 0 0 10.5 10.5z"/>`
        : svg`<circle cx="12" cy="12" r="9"/><path d="M12 3a9 9 0 0 1 0 18z" fill="currentColor"/>`;
    return html`<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
      stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
  }

  render() {
    const name = `Colour scheme: ${LABELS[this.preference]}`;
    return html`
      <button type="button" class="trigger" id="theme-trigger" aria-label=${name} title=${name}
        aria-haspopup="listbox" aria-expanded=${this.open} aria-controls=${this.open ? 'theme-options' : nothing}
        @click=${() => (this.open ? this.close(false) : void this.show())}
        @keydown=${this.handleTriggerKey}>
        ${this.icon(this.preference)}
      </button>
      ${this.open ? html`
        <ul class="options" id="theme-options" role="listbox" aria-labelledby="theme-trigger">
          ${THEME_PREFERENCES.map((value, index) => html`
            <li role="option" tabindex="-1" class="option" aria-selected=${value === this.preference}
              @click=${() => this.pick(value)}
              @keydown=${(event: KeyboardEvent) => this.handleOptionKey(event, index, value)}>
              ${this.icon(value)}${LABELS[value]}
            </li>
          `)}
        </ul>
      ` : ''}
    `;
  }
}
