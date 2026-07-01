import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { badgeStyles, buttonStyles, cardStyles, formStyles, iconActionStyles } from '../styles/components';
import type { Locale } from '../types';
import { fetchLocales, updateLocales, setDefaultLocale } from '../utils/central-api';
import './error-card';
import './loading-cards';

// ISO 639-1 language codes; display names are resolved at runtime via Intl.DisplayNames.
const LANGUAGE_CODES = [
  'aa', 'ab', 'af', 'ak', 'am', 'ar', 'as', 'ay', 'az', 'be', 'bg', 'bm', 'bn', 'bo', 'br',
  'bs', 'ca', 'ce', 'co', 'cs', 'cy', 'da', 'de', 'dv', 'dz', 'ee', 'el', 'en', 'eo', 'es',
  'et', 'eu', 'fa', 'ff', 'fi', 'fo', 'fr', 'fy', 'ga', 'gd', 'gl', 'gn', 'gu', 'gv', 'ha',
  'he', 'hi', 'hr', 'ht', 'hu', 'hy', 'ia', 'id', 'ig', 'is', 'it', 'iu', 'ja', 'jv', 'ka',
  'kk', 'kl', 'km', 'kn', 'ko', 'ku', 'kw', 'ky', 'la', 'lb', 'lg', 'ln', 'lo', 'lt', 'lu',
  'lv', 'mg', 'mi', 'mk', 'ml', 'mn', 'mr', 'ms', 'mt', 'my', 'nb', 'nd', 'ne', 'nl', 'nn',
  'no', 'ny', 'oc', 'om', 'or', 'pa', 'pl', 'ps', 'pt', 'qu', 'rm', 'rn', 'ro', 'ru', 'rw',
  'sd', 'se', 'sg', 'si', 'sk', 'sl', 'sn', 'so', 'sq', 'sr', 'ss', 'st', 'su', 'sv', 'sw',
  'ta', 'te', 'tg', 'th', 'ti', 'tk', 'tl', 'tn', 'to', 'tr', 'ts', 'tt', 'ug', 'uk', 'ur',
  'uz', 've', 'vi', 'wo', 'xh', 'yi', 'yo', 'zh', 'zu',
];

function capitalize(value: string): string {
  return value.length === 0 ? value : value[0].toUpperCase() + value.slice(1);
}

// Native (localized) display name of a language, e.g. 'fr' -> 'Français'.
function localeName(code: string): string {
  try {
    const native = new Intl.DisplayNames([code], { type: 'language' }).of(code);
    if (native && native !== code) return capitalize(native);
  } catch {
    // Intl may not know the code; fall back to the raw code below.
  }
  return code;
}

@customElement('versola-locales-list')
export class VersolaLocalesList extends LitElement {
  @state() private locales: Locale[] = [];
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private editing = false;
  @state() private draftLocales: Locale[] = [];
  @state() private newCode = '';
  @state() private newName = '';
  @state() private saving = false;
  @state() private editError = '';

  static styles = [
    theme,
    cardStyles,
    buttonStyles,
    formStyles,
    iconActionStyles,
    badgeStyles,
    css`
      :host { display: block; }
      .page-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: var(--spacing-xl);
      }
      .page-title {
        font-size: 1.625rem;
        font-weight: 700;
        letter-spacing: -0.025em;
        color: var(--text-primary);
        margin: 0;
      }
      .locale-list { display: flex; flex-direction: column; }
      .locale-row {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
        padding: 0.625rem 0;
        border-bottom: 1px solid var(--border-dark);
      }
      .locale-row:last-child { border-bottom: none; }
      .locale-code {
        font-family: var(--font-mono);
        font-weight: 600;
        color: var(--accent);
        min-width: 4rem;
      }
      .locale-name { color: var(--text-primary); flex: 1; }
      .add-row {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        gap: var(--spacing-md);
        align-items: end;
        margin-top: var(--spacing-lg);
        padding-top: var(--spacing-lg);
        border-top: 1px solid var(--border-dark);
      }
      .form-actions {
        display: flex;
        align-items: center;
        gap: 1rem;
        justify-content: flex-end;
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-dark);
      }
      .save-msg { font-size: 0.875rem; }
      .save-msg.error { color: var(--danger); }
      .hint { font-size: 0.75rem; color: var(--text-secondary); }
      .row-actions {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
        margin-left: auto;
      }
      .pill-btn {
        box-sizing: border-box;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 6.5rem;
        padding: 0.25rem 0.75rem;
        font-size: 0.75rem;
        font-weight: 600;
        font-family: inherit;
        border-radius: var(--radius-sm);
        border: 1px solid transparent;
        cursor: pointer;
        line-height: 1.2;
        text-align: center;
      }
      .pill-btn:disabled { opacity: 0.6; cursor: default; }
      .pill-btn.static { cursor: default; }
      .toggle-active.on {
        background: rgba(63, 185, 80, 0.15);
        color: var(--success);
        border-color: rgba(63, 185, 80, 0.3);
      }
      .toggle-active.off {
        background: rgba(210, 153, 34, 0.15);
        color: var(--warning);
        border-color: rgba(210, 153, 34, 0.3);
      }
      .pill-default {
        background: rgba(88, 166, 255, 0.15);
        color: var(--accent);
        border-color: rgba(88, 166, 255, 0.3);
        cursor: default;
      }
      .pill-set-default {
        background: transparent;
        color: var(--text-secondary);
        border-color: var(--border-dark);
      }
      .pill-set-default:hover:not(:disabled) {
        color: var(--accent);
        border-color: rgba(88, 166, 255, 0.3);
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    this.loadData();
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      this.locales = await fetchLocales();
    } catch (e) {
      this.errorMessage = e instanceof Error ? e.message : 'Failed to load locales';
    } finally {
      this.isLoading = false;
    }
  }


  private startEdit() {
    this.draftLocales = this.locales.map(l => ({ ...l }));
    this.newCode = '';
    this.newName = '';
    this.editError = '';
    this.editing = true;
  }

  private cancelEdit() {
    this.editing = false;
    this.editError = '';
  }

  private addDraft() {
    const code = this.newCode.trim();
    const name = this.newName.trim();
    if (!code || !name) return;
    if (this.draftLocales.some(l => l.code === code)) {
      this.editError = `Locale "${code}" already exists.`;
      return;
    }
    this.draftLocales = [...this.draftLocales, { code, name, isDefault: false, active: false }]
      .sort((a, b) => a.code.localeCompare(b.code));
    this.newCode = '';
    this.newName = '';
    this.editError = '';
  }

  private removeDraft(code: string) {
    this.draftLocales = this.draftLocales.filter(l => l.code !== code);
  }

  private toggleDraftActive(code: string) {
    this.draftLocales = this.draftLocales.map(l =>
      l.code === code ? { ...l, active: !l.active } : l,
    );
  }

  private setDraftDefault(code: string) {
    this.draftLocales = this.draftLocales.map(l => ({ ...l, isDefault: l.code === code }));
  }

  private get availableLanguageOptions(): { code: string; name: string }[] {
    const used = new Set(this.draftLocales.map(l => l.code));
    return LANGUAGE_CODES
      .filter(code => !used.has(code))
      .map(code => ({ code, name: localeName(code) }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }

  private selectLanguage(code: string) {
    this.newCode = code;
    this.newName = code ? localeName(code) : '';
  }

  private async confirmEdit() {
    const draftCodes = new Set(this.draftLocales.map(l => l.code));
    // Send all draft locales (upsert): captures new locales + active-flag changes for existing ones.
    const add = this.draftLocales;
    const remove = this.locales.filter(l => !draftCodes.has(l.code)).map(l => l.code);
    this.saving = true;
    this.editError = '';
    try {
      await updateLocales(add, remove);
      const draftDefault = this.draftLocales.find(l => l.isDefault);
      const originalDefault = this.locales.find(l => l.isDefault);
      if (draftDefault && draftDefault.code !== originalDefault?.code) {
        await setDefaultLocale(draftDefault.code);
      }
      this.locales = this.draftLocales.map(l => ({ ...l }));
      this.editing = false;
    } catch (e) {
      this.editError = e instanceof Error ? e.message : 'Failed to save';
    } finally {
      this.saving = false;
    }
  }

  private renderEdit() {
    const options = this.availableLanguageOptions;
    const canAdd = !!this.newCode;
    return html`
      <div class="page-header">
        <h1 class="page-title">Edit Locales</h1>
      </div>
      <div class="card">
        ${this.draftLocales.length === 0
          ? html`<div class="hint">No locales yet. Add one below.</div>`
          : html`
            <div class="locale-list">
              ${this.draftLocales.map(loc => html`
                <div class="locale-row">
                  <span class="locale-code">${loc.code}</span>
                  <span class="locale-name">${loc.name}</span>
                  <div class="row-actions">
                    ${loc.isDefault
                      ? html`<span class="pill-btn pill-default">Default</span>`
                      : loc.active
                        ? html`
                          <button class="pill-btn pill-set-default"
                            @click=${() => this.setDraftDefault(loc.code)}>
                            Set default
                          </button>`
                        : nothing}
                    <button class="pill-btn toggle-active ${loc.active ? 'on' : 'off'}"
                      @click=${() => this.toggleDraftActive(loc.code)}
                      title=${loc.active ? 'Click to deactivate' : 'Click to activate'}>
                      ${loc.active ? 'Active' : 'Inactive'}
                    </button>
                    <button class="icon-action danger" @click=${() => this.removeDraft(loc.code)}
                      title="Remove" aria-label=${`Remove ${loc.code}`}>✕</button>
                  </div>
                </div>
              `)}
            </div>`}
        <div class="add-row">
          <div class="form-group" style="margin:0">
            <label>Language</label>
            <select .value=${this.newCode}
              @change=${(e: Event) => this.selectLanguage((e.target as HTMLSelectElement).value)}>
              <option value="" ?selected=${!this.newCode}>Select a language…</option>
              ${options.map(opt => html`
                <option value=${opt.code} ?selected=${this.newCode === opt.code}>${opt.name} (${opt.code})</option>
              `)}
            </select>
          </div>
          <button class="btn btn-secondary" ?disabled=${!canAdd} @click=${() => this.addDraft()}>Add</button>
        </div>
        <div class="form-actions">
          <button class="btn btn-secondary" ?disabled=${this.saving} @click=${() => this.cancelEdit()}>Cancel</button>
          <button class="btn btn-primary" ?disabled=${this.saving} @click=${() => this.confirmEdit()}>
            ${this.saving ? 'Saving…' : 'Save'}
          </button>
          ${this.editError ? html`<span class="save-msg error">${this.editError}</span>` : nothing}
        </div>
      </div>
    `;
  }

  render() {
    if (this.editing) return html`${this.renderEdit()}`;

    return html`
      <div class="page-header">
        <h1 class="page-title">Locales</h1>
        <button class="btn btn-secondary" @click=${() => this.startEdit()}>Edit</button>
      </div>

      ${this.isLoading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
        : this.errorMessage ? html`
          <versola-error-card heading="Could not load locales" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
        `
        : html`
          <div class="card">
            ${this.locales.length === 0 ? html`<div class="hint">No locales configured.</div>` : html`
              <div class="locale-list">
                ${this.locales.map(loc => html`
                  <div class="locale-row">
                    <span class="locale-code">${loc.code}</span>
                    <span class="locale-name">${loc.name}</span>
                    <div class="row-actions">
                      ${loc.isDefault ? html`<span class="pill-btn pill-default">Default</span>` : nothing}
                      <span class="pill-btn static toggle-active ${loc.active ? 'on' : 'off'}">
                        ${loc.active ? 'Active' : 'Inactive'}
                      </span>
                    </div>
                  </div>
                `)}
              </div>
            `}
          </div>`}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-locales-list': VersolaLocalesList;
  }
}
