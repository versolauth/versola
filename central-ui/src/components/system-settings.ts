import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles } from '../styles/components';
import type { Locale, SystemSettingsRecord } from '../types';
import { fetchLocales, fetchSystemSettings, setDefaultLocale, updateLocales, upsertSystemSettings, type ApiError } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { statusIcon } from './status-icon';
import type { DataTableColumn, RowMenuItem } from './data-table';
import './content-header';
import './data-table';
import './error-card';
import './loading-cards';
import './record-card';
import './record-header';

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

const PICK_ANOTHER_DEFAULT = 'Pick another default first';

function capitalize(value: string): string {
  return value.length === 0 ? value : value[0].toUpperCase() + value.slice(1);
}

/** The language's own name, `fr` → `Français`; the code when Intl does not know it. */
function languageName(code: string): string {
  try {
    const native = new Intl.DisplayNames([code], { type: 'language' }).of(code);
    if (native && native !== code) return capitalize(native);
  } catch {
    // Intl may not know the code; the raw code is shown instead.
  }
  return code;
}

/** What central answered when a locale could not be activated: the keys without content. */
function activationFailure(error: unknown): { locale: string; missing: string[] } | null {
  const details = (error as ApiError | undefined)?.details as { locale?: unknown; missing?: unknown } | undefined;
  if (details && typeof details.locale === 'string' && Array.isArray(details.missing)) {
    return { locale: details.locale, missing: details.missing.filter((key): key is string => typeof key === 'string') };
  }
  return null;
}

/**
 * The settings shared by every tenant: the password policy as a record card with its own Edit
 * (a page), and the locales the login forms, scopes and templates can be shown in, as a card
 * with a table whose row menu saves at once (Set as default, Activate, Deactivate, Remove) and
 * a small Add locale form.
 */
@customElement('versola-system-settings')
export class VersolaSystemSettings extends LitElement {
  @property({ type: Boolean }) canManage = false;
  @property({ type: Boolean }) canReadLocales = false;
  @property({ type: Boolean }) canManageLocales = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private settings: SystemSettingsRecord | null = null;
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private locales: Locale[] | null = null;
  @state() private localesError = '';
  @state() private localeActionError = '';
  @state() private activationFailure: { locale: string; missing: string[] } | null = null;
  @state() private addOpen = false;
  @state() private addCode = '';
  @state() private addActive = false;
  @state() private addError = '';
  @state() private isAdding = false;

  @state() private draftRegex = '';
  @state() private draftHistory = 5;
  @state() private draftDifferent = 3;
  @state() private fieldErrors: Record<string, string> = {};
  @state() private formError = '';
  @state() private isSaving = false;
  private draftKey = '';
  private draftBaseline = '';

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    iconButtonStyles,
    recordStyles,
    css`
      :host {
        display: block;
      }

      .muted {
        color: var(--text-secondary);
      }

      .mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
      }

      .editor-card {
        max-width: 54rem;
      }

      .form-actions {
        display: flex;
        justify-content: flex-end;
        gap: 0.75rem;
        margin-top: var(--spacing-lg);
        padding-top: var(--spacing-lg);
        border-top: 1px solid var(--border-default);
      }

      .locales-body {
        padding: var(--spacing-md) var(--spacing-lg) var(--spacing-lg);
        border-top: 1px solid var(--border-subtle);
      }

      .locales-body .banner {
        margin-bottom: 0;
      }

      .missing-keys {
        margin-top: 0.375rem;
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
      }

      .add-form {
        display: grid;
        gap: var(--spacing-md);
        max-width: 28rem;
      }

      .add-form .form-group {
        margin: 0;
      }

      .check-row {
        display: flex;
        gap: 0.5rem;
        align-items: flex-start;
        margin: 0;
        font-size: var(--text-base);
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
        color: var(--text-primary);
        cursor: pointer;
      }

      .check-row input {
        width: 1rem;
        height: 1rem;
        margin: 0.2rem 0 0;
        flex: none;
        accent-color: var(--accent);
      }

      .check-row input:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      .add-actions {
        display: flex;
        gap: 0.5rem;
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('canReadLocales') && this.canReadLocales && this.locales === null) void this.loadLocales();
    if (changed.has('route') || changed.has('settings')) {
      const editing = this.editingPassword;
      const draftKey = editing ? `password:${this.settings !== null}` : '';
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (editing) this.resetDraft();
      }
    }
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      this.settings = await fetchSystemSettings();
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load system settings';
    } finally {
      this.isLoading = false;
    }
  }

  private async loadLocales() {
    this.localesError = '';
    try {
      this.locales = await fetchLocales();
    } catch (error) {
      this.locales = null;
      this.localesError = error instanceof Error ? error.message : 'Failed to load locales';
    }
  }

  // ── password policy ──

  private get editingPassword(): boolean {
    return this.canManage && this.route.edit === 'password';
  }

  private get draftSnapshot(): string {
    return JSON.stringify([this.draftRegex, this.draftHistory, this.draftDifferent]);
  }

  private resetDraft() {
    this.draftRegex = this.settings?.passwordRegex ?? '';
    this.draftHistory = this.settings?.passwordHistorySize ?? 5;
    this.draftDifferent = this.settings?.passwordNumDifferent ?? 3;
    this.fieldErrors = {};
    this.formError = '';
    this.isSaving = false;
    this.draftBaseline = this.draftSnapshot;
  }

  private async cancelEditor() {
    if (this.draftSnapshot !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null });
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    const errors: Record<string, string> = {};
    const regex = this.draftRegex.trim();
    if (!regex) errors.regex = 'The password regex is required.';
    else {
      try { new RegExp(regex); } catch { errors.regex = 'Not a valid regular expression.'; }
    }
    if (!Number.isInteger(this.draftHistory) || this.draftHistory < 0) errors.history = 'A whole number, 0 or more.';
    if (!Number.isInteger(this.draftDifferent) || this.draftDifferent < 0) errors.different = 'A whole number, 0 or more.';
    this.fieldErrors = errors;
    if (Object.keys(errors).length) return;
    const next: SystemSettingsRecord = { passwordRegex: regex, passwordHistorySize: this.draftHistory, passwordNumDifferent: this.draftDifferent };
    this.isSaving = true;
    this.formError = '';
    try {
      await upsertSystemSettings(next);
      this.settings = next;
      this.draftBaseline = this.draftSnapshot;
      dispatchRecordRoute(this, { edit: null });
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save the password policy';
    } finally {
      this.isSaving = false;
    }
  }

  // ── locales ──

  private async runLocaleAction(action: () => Promise<void>) {
    this.localeActionError = '';
    this.activationFailure = null;
    try {
      await action();
      await this.loadLocales();
    } catch (error) {
      const failure = activationFailure(error);
      if (failure) this.activationFailure = failure;
      else this.localeActionError = error instanceof Error ? error.message : 'Failed to update the locale';
    }
  }

  private setActive(locale: Locale, active: boolean) {
    return this.runLocaleAction(() => updateLocales([{ ...locale, active }], []));
  }

  private setDefault(locale: Locale) {
    return this.runLocaleAction(() => setDefaultLocale(locale.code));
  }

  private async removeLocale(locale: Locale) {
    const confirmed = await confirmDestructiveAction({
      title: 'Remove locale',
      messagePrefix: 'Remove locale ',
      messageSubject: locale.code,
      messageSuffix: '? Forms stop offering it; its content stays.',
      confirmLabel: 'Remove',
    });
    if (!confirmed) return;
    await this.runLocaleAction(() => updateLocales([], [locale.code]));
  }

  private get addableLanguages(): Array<{ code: string; name: string }> {
    const used = new Set((this.locales ?? []).map(locale => locale.code));
    return LANGUAGE_CODES.filter(code => !used.has(code)).map(code => ({ code, name: languageName(code) })).sort((a, b) => a.name.localeCompare(b.name));
  }

  private openAdd() {
    this.addOpen = true;
    this.addCode = '';
    this.addActive = false;
    this.addError = '';
  }

  private async handleAdd(event: Event) {
    event.preventDefault();
    if (this.isAdding) return;
    if (!this.addCode) {
      this.addError = 'Pick a language.';
      return;
    }
    const locale: Locale = { code: this.addCode, name: languageName(this.addCode), isDefault: false, active: this.addActive };
    this.isAdding = true;
    this.addError = '';
    this.activationFailure = null;
    try {
      await updateLocales([locale], []);
      await this.loadLocales();
      this.addOpen = false;
    } catch (error) {
      const failure = activationFailure(error);
      if (failure) {
        this.activationFailure = failure;
        this.addError = `${locale.code} cannot be activated yet: content is missing for ${failure.missing.length} ${failure.missing.length === 1 ? 'key' : 'keys'}. Add it inactive, or add the content first.`;
      } else this.addError = error instanceof Error ? error.message : 'Failed to add the locale';
    } finally {
      this.isAdding = false;
    }
  }

  private renderLocalesTable(locales: Locale[]) {
    const columns: DataTableColumn<Locale>[] = [
      { id: 'code', label: 'Code', cell: locale => html`<span class="row-id">${locale.code}</span>` },
      { id: 'language', label: 'Language', cell: locale => locale.name },
      { id: 'status', label: 'Status', cell: locale => locale.active ? html`<span class="chip ok">Active</span>` : html`<span class="chip">Inactive</span>` },
      { id: 'default', label: 'Default', cell: locale => locale.isDefault ? html`<span class="chip">Default</span>` : html`<span class="muted">—</span>` },
    ];
    const menuItems = (locale: Locale): RowMenuItem[] => [
      ...(locale.active && !locale.isDefault ? [{ label: 'Set as default', onSelect: () => void this.setDefault(locale) }] : []),
      locale.active
        ? { label: 'Deactivate', ariaLabel: `Deactivate ${locale.code}`, disabledReason: locale.isDefault ? PICK_ANOTHER_DEFAULT : '', onSelect: () => void this.setActive(locale, false) }
        : { label: 'Activate', ariaLabel: `Activate ${locale.code}`, onSelect: () => void this.setActive(locale, true) },
      { label: 'Remove locale', danger: true, ariaLabel: `Remove locale ${locale.code}`, disabledReason: locale.isDefault ? PICK_ANOTHER_DEFAULT : '', onSelect: () => void this.removeLocale(locale) },
    ];
    return html`
      <versola-data-table flush
        .columns=${columns}
        .rows=${locales}
        .rowKey=${(locale: Locale) => locale.code}
        .menuItems=${this.canManageLocales ? menuItems : null}
        .menuLabel=${(locale: Locale) => `Actions for ${locale.code}`}
      ></versola-data-table>
    `;
  }

  private renderAddForm() {
    const options = this.addableLanguages;
    return html`
      <form class="add-form" novalidate @submit=${(e: Event) => this.handleAdd(e)} aria-label="Add locale">
        <div class="form-group">
          <label for="add-locale">Language *</label>
          <select id="add-locale" class="compact-input ${this.addError && !this.addCode ? 'input-error' : ''}" .value=${this.addCode}
            aria-invalid=${this.addError && !this.addCode ? 'true' : nothing} aria-describedby="add-locale-hint"
            @change=${(e: Event) => { this.addCode = (e.target as HTMLSelectElement).value; this.addError = ''; }}>
            <option value="" ?selected=${!this.addCode}>Select a language…</option>
            ${options.map(option => html`<option value=${option.code} ?selected=${this.addCode === option.code}>${option.name} (${option.code})</option>`)}
          </select>
          <div class="hint" id="add-locale-hint">${this.addCode ? html`Code <span class="mono">${this.addCode}</span> · ${languageName(this.addCode)}` : 'The code and name follow the language.'}</div>
        </div>
        <label class="check-row">
          <input type="checkbox" .checked=${this.addActive} @change=${(e: Event) => this.addActive = (e.target as HTMLInputElement).checked} />
          <span>Activate now<br><span class="hint" style="margin: 0">Validated: every form, scope and OTP template must have content for it.</span></span>
        </label>
        ${this.addError ? html`<div class="error-message" role="alert">${this.addError}</div>` : ''}
        <div class="add-actions">
          <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.isAdding} @click=${() => { this.addOpen = false; this.addError = ''; }}>Cancel</button>
          <button type="submit" class="btn btn-primary btn-sm" ?disabled=${this.isAdding}>${this.isAdding ? 'Adding…' : 'Add locale'}</button>
        </div>
      </form>
    `;
  }

  private renderLocalesCard() {
    const failure = this.activationFailure;
    return html`
      <section class="card record-card" id="locales" aria-label="Locales">
        <div class="record-card-head">
          <div>
            <h2>Locales</h2>
            <div class="record-card-sub">Languages the login forms, scopes and OTP templates can be shown in. Activation is validated: every form, scope and template must have content for it.</div>
          </div>
          ${this.canManageLocales && !this.addOpen && this.locales ? html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => this.openAdd()}>+ Add locale</button>` : ''}
        </div>
        ${this.localesError ? html`
          <div class="record-row"><div class="record-label"></div><div class="record-value"><span class="muted">Could not load locales: ${this.localesError}</span> <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.loadLocales()}>Retry</button></div></div>`
        : this.locales === null ? html`<div class="record-row"><div class="record-label"></div><div class="record-value muted">Loading…</div></div>`
        : this.locales.length === 0 ? html`<div class="record-row"><div class="record-label"></div><div class="record-value muted">No locales yet. Add one so the forms have a language to show.</div></div>`
        : this.renderLocalesTable(this.locales)}
        ${failure || this.localeActionError || this.addOpen ? html`
          <div class="locales-body">
            ${failure ? html`
              <div class="banner warn" role="alert">
                <div class="banner-text">
                  <div class="banner-title">${statusIcon('alert')}Cannot activate ${failure.locale}: content is missing for ${failure.missing.length} ${failure.missing.length === 1 ? 'key' : 'keys'}</div>
                  <div class="missing-keys">${failure.missing.join(' · ')}</div>
                </div>
              </div>` : ''}
            ${this.localeActionError ? html`<div class="editor-error" role="alert">${this.localeActionError}</div>` : ''}
            ${this.addOpen ? this.renderAddForm() : ''}
          </div>` : ''}
      </section>
    `;
  }

  // ── render ──

  private row(label: string, value: unknown) {
    return html`<versola-record-row .label=${label} .value=${value}></versola-record-row>`;
  }

  private renderPasswordCard(settings: SystemSettingsRecord) {
    return html`
      <versola-record-card id="password-policy" heading="Password policy" subheading="Applies to every user; passwords are stored per user, not per tenant." ?editable=${this.canManage} .body=${html`
        ${this.row('Regex', html`<span class="mono">${settings.passwordRegex || '—'}</span>`)}
        ${this.row('History size', `${settings.passwordHistorySize} previous ${settings.passwordHistorySize === 1 ? 'password' : 'passwords'}`)}
        ${this.row('Different passwords required', String(settings.passwordNumDifferent))}
      `} @edit=${() => dispatchRecordRoute(this, { id: null, edit: 'password', create: false, layout: null })}></versola-record-card>
    `;
  }

  private field(id: string, label: string, control: unknown, hint: string) {
    const error = this.fieldErrors[id];
    return html`
      <div class="form-group">
        <label for=${id}>${label}</label>
        ${control}
        ${error ? html`<div class="error-message" id=${`${id}-error`} role="alert">${error}</div>` : html`<div class="hint" id=${`${id}-hint`}>${hint}</div>`}
      </div>
    `;
  }

  private numberInput(id: string, value: number, onInput: (value: number) => void) {
    const invalid = !!this.fieldErrors[id];
    return html`
      <input type="number" id=${id} class="compact-input ${invalid ? 'input-error' : ''}" min="0" step="1" style="max-width: 8rem" .value=${String(value)}
        aria-invalid=${invalid ? 'true' : nothing} aria-describedby=${invalid ? `${id}-error` : `${id}-hint`}
        @input=${(e: Event) => onInput(Number((e.target as HTMLInputElement).value))} />
    `;
  }

  private renderPasswordEditor() {
    const regexInvalid = !!this.fieldErrors.regex;
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'System settings', onSelect: () => void this.cancelEditor() }, { label: 'Password policy' }]}
        .heading=${'Edit password policy'}
        .meta=${html`<span class="muted">Applies to every user of every tenant on the next password change.</span>`}
      ></versola-record-header>
      <form class="card editor-card" novalidate @submit=${(e: Event) => this.handleSubmit(e)}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        <div class="editor-box">
          <h3>Password policy</h3>
          <p class="editor-sub">What a new password must look like and how far back it is compared.</p>
          ${this.field('regex', 'Password regex *', html`
            <input type="text" id="regex" class="compact-input mono ${regexInvalid ? 'input-error' : ''}" .value=${this.draftRegex} placeholder="^(?=.*[A-Za-z])(?=.*\\d).{8,}$"
              aria-invalid=${regexInvalid ? 'true' : nothing} aria-describedby=${regexInvalid ? 'regex-error' : 'regex-hint'}
              @input=${(e: Event) => this.draftRegex = (e.target as HTMLInputElement).value} />`, 'A new password must match this pattern.')}
          ${this.field('history', 'Password history size', this.numberInput('history', this.draftHistory, value => this.draftHistory = value), 'Previous passwords a new one is compared against, including the current one.')}
          ${this.field('different', 'Different passwords required', this.numberInput('different', this.draftDifferent, value => this.draftDifferent = value), 'How many distinct passwords before an old one can be reused.')}
        </div>
        <div class="form-actions">
          <button type="button" class="btn btn-secondary" ?disabled=${this.isSaving} @click=${() => void this.cancelEditor()}>Cancel</button>
          <button type="submit" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : 'Save password policy'}</button>
        </div>
      </form>
    `;
  }

  render() {
    if (this.editingPassword && this.settings) return this.renderPasswordEditor();
    return html`
      <versola-record-header .heading=${'System settings'} .meta=${html`<span class="chip">Global</span><span class="muted">Settings shared by every tenant: passwords and the languages the login forms offer.</span>`}></versola-record-header>
      ${this.isLoading ? html`<versola-loading-cards .count=${2}></versola-loading-cards>`
      : this.errorMessage ? html`<versola-error-card heading="Could not load system settings" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>`
      : this.settings ? this.renderPasswordCard(this.settings) : ''}
      ${this.canReadLocales && !this.isLoading ? this.renderLocalesCard() : ''}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-system-settings': VersolaSystemSettings;
  }
}
