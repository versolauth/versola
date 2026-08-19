import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { formStyles } from '../styles/components';
import { theme } from '../styles/theme';
import type { Locale } from '../types';

@customElement('versola-localized-text-editor')
export class VersolaLocalizedTextEditor extends LitElement {
  @property({ attribute: false }) value: Record<string, string> = {};
  @property({ attribute: false }) locales: Locale[] = [];
  @property({ type: String }) fieldId = 'localized-text';
  @property({ type: String }) label = 'Description';
  @property({ type: Boolean }) required = false;
  @property({ type: Boolean }) showRequiredIndicator = false;
  @property({ type: Boolean }) showLabel = true;
  @property({ type: Boolean }) selectorBelowInput = false;

  @state() private activeLocale = 'en';

  static styles = [theme, formStyles, css`
    :host { display: block; }
    .label-row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .locale-tabs {
      display: flex;
      flex-wrap: wrap;
      gap: 0.375rem;
      margin: 0.5rem 0;
    }
    .locale-tab {
      padding: 0.25rem 0.625rem;
      border: 1px solid var(--border-dark);
      border-radius: var(--radius-sm);
      background: transparent;
      color: var(--text-secondary);
      cursor: pointer;
      font-size: 0.75rem;
    }
    .locale-tab.active {
      border-color: var(--accent);
      background: rgba(88, 166, 255, 0.12);
      color: var(--accent);
    }
    .hint {
      margin-top: 0.375rem;
      color: var(--text-secondary);
      font-size: 0.75rem;
    }
  `];

  private get localeOptions(): Array<{ code: string; name: string }> {
    const configured = this.locales.map(locale => ({ code: locale.code, name: locale.name }));
    const known = new Map(configured.map(locale => [locale.code, locale]));
    known.set('en', known.get('en') ?? { code: 'en', name: 'English' });
    Object.keys(this.value).forEach(code => {
      if (!known.has(code)) known.set(code, { code, name: code });
    });
    return [...known.values()].sort((a, b) => a.code === 'en' ? -1 : b.code === 'en' ? 1 : a.code.localeCompare(b.code));
  }

  updated(changed: Map<string, unknown>) {
    if (!changed.has('value') && !changed.has('locales')) return;
    if (!this.localeOptions.some(locale => locale.code === this.activeLocale)) {
      this.activeLocale = this.localeOptions[0]?.code ?? 'en';
    }
  }

  private updateValue(event: Event) {
    const value = (event.target as HTMLInputElement).value;
    this.dispatchEvent(new CustomEvent('localized-change', {
      detail: { value: { ...this.value, [this.activeLocale]: value } },
      bubbles: true,
      composed: true,
    }));
  }

  render() {
    const active = this.localeOptions.find(locale => locale.code === this.activeLocale) ?? this.localeOptions[0];
    const activeCode = active?.code ?? 'en';
    const inputId = activeCode === 'en' ? this.fieldId : `${this.fieldId}-${activeCode}`;
    const accessibleLabel = `${this.label}${this.showRequiredIndicator ? ' *' : ''}`;
    const selector = html`
      <div class="locale-tabs" role="tablist" aria-label="Localization languages">
        ${this.localeOptions.map(locale => html`
          <button
            type="button"
            class=${`locale-tab ${locale.code === this.activeLocale ? 'active' : ''}`}
            role="tab"
            aria-selected=${locale.code === this.activeLocale ? 'true' : 'false'}
            @click=${() => this.activeLocale = locale.code}
          >${locale.code}</button>
        `)}
      </div>
    `;
    const input = html`
      <input
        id=${inputId}
        type="text"
        class="compact-input"
        aria-label=${accessibleLabel}
        .value=${this.value[this.activeLocale] ?? ''}
        @input=${this.updateValue}
        ?required=${this.required && this.activeLocale === 'en'}
        placeholder=${this.label}
      />
    `;
    return html`
      ${this.showLabel ? html`
        <div class="label-row">
          <label for=${inputId}>${accessibleLabel}</label>
          <slot name="info"></slot>
        </div>
      ` : ''}
      ${this.selectorBelowInput ? html`${input}${selector}` : html`${selector}${input}`}
    `;
  }
}