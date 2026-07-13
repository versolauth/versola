import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles, iconActionStyles } from '../styles/components';
import type { OtpTemplateRecord, Locale, SubmissionLimits, RateLimit, PasskeySettings } from '../types';
import {
  fetchOtpTemplates,
  upsertOtpTemplate,
  deleteOtpTemplate,
  fetchLocales,
  fetchChallengeSettings,
  upsertChallengeSettings,
} from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import './error-card';
import './loading-cards';

const CODE_PLACEHOLDER = '{{code}}';
const PASSWORD_PLACEHOLDER = '{{password}}';

@customElement('versola-challenges-list')
export class VersolaChallengesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;

  @state() private templates: OtpTemplateRecord[] = [];
  @state() private availableLocales: Locale[] = [];
  @state() private isLoading = false;
  @state() private errorMessage = '';

  // View-mode: selected locale code per template id.
  @state() private viewLocale: Record<string, string> = {};

  // Edit-mode: expanded localization cards, keyed by locale code (collapsed by default).
  @state() private expandedLocales = new Set<string>();

  @state() private editingTemplateId: string | null = null; // null means not editing/adding
  @state() private editingPurpose: 'otp' | 'password' = 'otp';
  @state() private editId = '';
  @state() private editLocalizations: Array<{ locale: string; template: string }> = [];
  @state() private saving = false;
  @state() private editError = '';

  @state() private phonePrefixes: string[] = [];
  @state() private hasChallengeSettings = false;
  @state() private editingSettings = false;
  @state() private editPrefixes: Array<{ value: string }> = [];
  @state() private isSavingSettings = false;
  @state() private settingsError = '';

  @state() private otpLength = 6;
  @state() private otpResendAfter = 60;
  @state() private editOtpLength = 6;
  @state() private editOtpResendAfter = 60;

  @state() private authConversationTtlSeconds = 900;
  @state() private sessionTtlSeconds = 86400;
  @state() private sessionIdleTtlSeconds: number | null = null;
  @state() private editAuthConversationTtlSeconds = 900;
  @state() private editSessionTtlSeconds = 86400;
  @state() private editSessionIdleTtlSeconds: number | null = null;

  @state() private ipHeader = 'X-Real-IP';
  @state() private editIpHeader = 'X-Real-IP';

  @state() private submissionLimits: SubmissionLimits = {
    otpRequest: [],
    otpSubmit: [],
    passwordSubmit: [],
    passkeyAssertion: [],
    banDurationSeconds: 0,
  };
  @state() private editSubmissionLimits: SubmissionLimits = {
    otpRequest: [],
    otpSubmit: [],
    passwordSubmit: [],
    passkeyAssertion: [],
    banDurationSeconds: 0,
  };

  @state() private passkeySettings: PasskeySettings | null = null;
  @state() private editPasskeyRpId = '';
  @state() private editPasskeyRpName = '';
  @state() private editPasskeyOrigins: Array<{ value: string }> = [];
  @state() private editPasskeyUserVerification = 'preferred';

  static styles = [
    theme,
    cardStyles,
    buttonStyles,
    formStyles,
    iconActionStyles,
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
      .form-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: var(--spacing-xl);
      }
      .form-title {
        font-size: 2rem;
        font-weight: 700;
        color: var(--text-primary);
        margin: 0;
      }
      .settings-section {
        margin-bottom: var(--spacing-xl);
      }
      .section-header {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: var(--spacing-md);
        margin-bottom: var(--spacing-lg);
      }
      .section-title {
        font-size: 1.25rem;
        font-weight: 700;
        color: var(--text-primary);
        margin: 0;
      }
      .section-desc {
        font-size: 0.875rem;
        color: var(--text-secondary);
        margin-top: var(--spacing-xs);
      }
      .template-card {
        margin-bottom: var(--spacing-lg);
      }
      .template-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: var(--spacing-md);
      }
      .template-id {
        font-family: var(--font-mono);
        font-weight: 600;
        font-size: 1.0625rem;
        color: var(--accent);
      }
      .template-actions {
        display: flex;
        gap: var(--spacing-sm);
      }
      .locale-bar {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        margin-bottom: var(--spacing-sm);
      }
      .locale-bar-label {
        font-size: 0.8125rem;
        color: var(--text-secondary);
      }
      .locale-select {
        max-width: 220px;
      }
      .template-text {
        font-size: 0.9375rem;
        color: var(--text-primary);
        line-height: 1.6;
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-md);
        white-space: pre-wrap;
      }
      .edit-loc-card {
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-md);
        margin-bottom: var(--spacing-md);
      }
      .edit-loc-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
        cursor: pointer;
      }
      .edit-loc-title {
        font-family: var(--font-mono);
        font-weight: 600;
        color: var(--accent);
      }
      .edit-loc-head-actions {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
      }
      .chevron {
        color: var(--text-secondary);
        font-size: 0.75rem;
      }
      .edit-loc-body {
        display: grid;
        gap: var(--spacing-md);
        margin-top: var(--spacing-md);
      }
      .edit-loc-textarea {
        box-sizing: border-box;
        min-height: 90px;
        resize: vertical;
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
      .error-msg { font-size: 0.875rem; color: var(--danger); margin-top: var(--spacing-sm); }
      .hint { font-size: 0.75rem; color: var(--text-secondary); margin-bottom: var(--spacing-md); }
      .prefix-tags {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        margin-top: var(--spacing-sm);
      }
      .prefix-tag {
        font-family: var(--font-mono);
        font-weight: 600;
        font-size: 0.9375rem;
        color: var(--accent);
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-xs) var(--spacing-md);
      }
      .limit-row {
        display: flex;
        gap: var(--spacing-md);
        align-items: center;
        margin-bottom: var(--spacing-sm);
      }
      .limit-input {
        width: 100px;
      }
      .limit-label {
        font-size: 0.8125rem;
        color: var(--text-secondary);
        width: 120px;
      }
      .limit-hint {
        font-size: 0.75rem;
        color: var(--text-secondary);
        min-width: 50px;
      }
      .limits-card-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: var(--spacing-lg);
      }
      .limits-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
        gap: var(--spacing-lg);
      }
      .limit-group-title {
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--text-secondary);
        margin-bottom: var(--spacing-sm);
      }
      .limit-chip {
        display: block;
        font-size: 0.875rem;
        color: var(--text-primary);
        font-family: var(--font-mono);
        margin-bottom: var(--spacing-xs);
      }
      .ban-badge {
        display: inline-flex;
        align-items: center;
        font-size: 0.8125rem;
        font-weight: 600;
        color: var(--accent);
        background: rgba(88, 166, 255, 0.1);
        border: 1px solid rgba(88, 166, 255, 0.2);
        border-radius: var(--radius-md);
        padding: var(--spacing-xs) var(--spacing-md);
      }
      .info-table {
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        overflow: hidden;
        margin-top: var(--spacing-md);
      }
      .prop-row {
        display: grid;
        grid-template-columns: 11rem 1fr;
        gap: var(--spacing-lg);
        align-items: start;
        padding: 0.875rem var(--spacing-lg);
        border-bottom: 1px solid var(--border-dark);
        font-size: 0.875rem;
      }
      .prop-row:last-child {
        border-bottom: none;
      }
      .prop-label {
        color: var(--text-secondary);
        font-size: 0.75rem;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        font-weight: 600;
        white-space: nowrap;
        padding-top: 0.125rem;
      }
      .prop-value {
        color: var(--text-primary);
        word-break: break-word;
        overflow-wrap: anywhere;
        min-width: 0;
        line-height: 1.5;
      }
      .prop-value.muted {
        color: var(--text-secondary);
        font-style: italic;
      }
      .empty-state {
        text-align: center;
        padding: 3rem;
        color: var(--text-secondary);
      }
      .empty-state-icon {
        font-size: 3rem;
        margin-bottom: 1rem;
      }
    `,
  ];

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId')) {
      this.loadData();
    }
  }

  private async loadData() {
    if (!this.tenantId) return;
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [templates, locales, challengeSettings] = await Promise.all([
        fetchOtpTemplates(this.tenantId),
        fetchLocales(),
        fetchChallengeSettings(this.tenantId),
      ]);
      this.templates = templates;
      this.availableLocales = locales;
      this.hasChallengeSettings = challengeSettings !== null;
      if (challengeSettings) {
        this.phonePrefixes = challengeSettings.allowedPrefixes;
        this.submissionLimits = challengeSettings.submissionLimits;
        this.otpLength = challengeSettings.otpLength;
        this.otpResendAfter = challengeSettings.otpResendAfter;
        this.passkeySettings = challengeSettings.passkeySettings ?? null;
        this.authConversationTtlSeconds = challengeSettings.authConversationTtlSeconds;
        this.sessionTtlSeconds = challengeSettings.sessionTtlSeconds;
        this.sessionIdleTtlSeconds = challengeSettings.sessionIdleTtlSeconds ?? null;
        this.ipHeader = challengeSettings.ipHeader || 'X-Real-IP';
      } else {
        this.phonePrefixes = [];
        this.submissionLimits = { otpRequest: [], otpSubmit: [], passwordSubmit: [], passkeyAssertion: [], banDurationSeconds: 0 };
        this.otpLength = 6;
        this.otpResendAfter = 60;
        this.passkeySettings = null;
        this.authConversationTtlSeconds = 900;
        this.sessionTtlSeconds = 86400;
        this.sessionIdleTtlSeconds = null;
        this.ipHeader = 'X-Real-IP';
      }
    } catch (e) {
      this.errorMessage = e instanceof Error ? e.message : 'Failed to load data';
    } finally {
      this.isLoading = false;
    }
  }

  private startAdd() {
    this.editingTemplateId = 'NEW';
    this.editingPurpose = 'otp';
    this.editId = '';
    this.editLocalizations = this.buildLocalizations({});
    this.expandedLocales = new Set(this.editLocalizations.map(l => l.locale));
    this.editError = '';
  }

  private startEdit(template: OtpTemplateRecord) {
    this.editingTemplateId = template.id;
    this.editingPurpose = template.purpose === 'password' ? 'password' : 'otp';
    this.editId = template.id;
    this.editLocalizations = this.buildLocalizations(template.localizations);
    this.expandedLocales = new Set(this.editLocalizations.filter(l => !l.template).map(l => l.locale));
    this.editError = '';
  }

  private cancelEdit() {
    this.editingTemplateId = null;
    this.editError = '';
  }

  // Every active locale must have a localization. Preserve any localizations
  // for locales that are no longer active so editing does not silently drop them.
  private buildLocalizations(existing: Record<string, string>): Array<{ locale: string; template: string }> {
    const active = this.availableLocales.map(l => ({ locale: l.code, template: existing[l.code] ?? '' }));
    const extra = Object.entries(existing)
      .filter(([code]) => !this.availableLocales.some(l => l.code === code))
      .map(([locale, template]) => ({ locale, template }));
    return [...active, ...extra];
  }

  private toggleLocExpand(code: string) {
    if (this.expandedLocales.has(code)) this.expandedLocales.delete(code);
    else this.expandedLocales.add(code);
    this.requestUpdate();
  }

  private selectedViewLocale(template: OtpTemplateRecord): string {
    const codes = Object.keys(template.localizations);
    const selected = this.viewLocale[template.id];
    return selected && codes.includes(selected) ? selected : codes[0] ?? '';
  }

  private localeName(code: string): string {
    return this.availableLocales.find(l => l.code === code)?.name ?? code;
  }

  private formatDuration(seconds: number): string {
    if (seconds === 0) return '—';
    if (seconds % 3600 === 0) return `${seconds / 3600} hr`;
    if (seconds % 60 === 0) return `${seconds / 60} min`;
    return `${seconds}s`;
  }

  private async saveTemplate() {
    if (!this.tenantId) return;
    const id = this.editId.trim();
    if (!id) {
      this.editError = 'Template ID is required';
      return;
    }

    const placeholder = this.editingPurpose === 'password' ? PASSWORD_PLACEHOLDER : CODE_PLACEHOLDER;
    const localizations: Record<string, string> = {};
    for (const { locale, template } of this.editLocalizations) {
      const t = template.trim();
      if (!t) {
        this.editError = `Localization for ${this.localeName(locale)} (${locale}) is required`;
        return;
      }
      if (!t.includes(placeholder)) {
        this.editError = `Localization for ${this.localeName(locale)} (${locale}) must include the ${placeholder} placeholder`;
        return;
      }
      localizations[locale] = t;
    }

    if (Object.keys(localizations).length === 0) {
      this.editError = 'At least one localization is required';
      return;
    }

    this.saving = true;
    this.editError = '';
    try {
      await upsertOtpTemplate(id, this.tenantId, localizations, this.editingPurpose);
      const updated: OtpTemplateRecord = { id, tenantId: this.tenantId, localizations, purpose: this.editingPurpose };
      const existing = this.templates.some(t => t.id === id);
      this.templates = existing
        ? this.templates.map(t => (t.id === id ? updated : t))
        : [...this.templates, updated];
      this.editingTemplateId = null;
    } catch (e) {
      this.editError = e instanceof Error ? e.message : 'Failed to save template';
    } finally {
      this.saving = false;
    }
  }

  private async handleDelete(id: string) {
    if (!this.tenantId) return;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete template',
      messagePrefix: 'Delete template ',
      messageSubject: id,
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    try {
      await deleteOtpTemplate(id, this.tenantId);
      this.templates = this.templates.filter(t => t.id !== id);
    } catch (e) {
      this.errorMessage = e instanceof Error ? e.message : 'Failed to delete template';
    }
  }

  private startEditSettings() {
    this.editingSettings = true;
    this.editPrefixes = this.phonePrefixes.map(value => ({ value }));
    this.editSubmissionLimits = JSON.parse(JSON.stringify(this.submissionLimits));
    this.editOtpLength = this.otpLength;
    this.editOtpResendAfter = this.otpResendAfter;
    this.editAuthConversationTtlSeconds = this.authConversationTtlSeconds;
    this.editSessionTtlSeconds = this.sessionTtlSeconds;
    this.editSessionIdleTtlSeconds = this.sessionIdleTtlSeconds;
    this.editIpHeader = this.ipHeader;
    this.editPasskeyRpId = this.passkeySettings?.rpId ?? '';
    this.editPasskeyRpName = this.passkeySettings?.rpName ?? '';
    this.editPasskeyOrigins = (this.passkeySettings?.origins ?? []).map(value => ({ value }));
    this.editPasskeyUserVerification = this.passkeySettings?.userVerification ?? 'preferred';
    this.settingsError = '';
  }

  private cancelEditSettings() {
    this.editingSettings = false;
    this.settingsError = '';
  }

  private addPrefix() {
    this.editPrefixes = [...this.editPrefixes, { value: '' }];
  }

  private removePrefix(index: number) {
    this.editPrefixes = this.editPrefixes.filter((_, i) => i !== index);
  }

  private addPasskeyOrigin() {
    this.editPasskeyOrigins = [...this.editPasskeyOrigins, { value: '' }];
  }

  private removePasskeyOrigin(index: number) {
    this.editPasskeyOrigins = this.editPasskeyOrigins.filter((_, i) => i !== index);
  }

  private addRateLimit(type: 'otpRequest' | 'otpSubmit' | 'passwordSubmit' | 'passkeyAssertion') {
    this.editSubmissionLimits[type] = [...this.editSubmissionLimits[type], { maxAttempts: 5, windowSeconds: 60 }];
    this.requestUpdate();
  }

  private removeRateLimit(type: 'otpRequest' | 'otpSubmit' | 'passwordSubmit' | 'passkeyAssertion', index: number) {
    this.editSubmissionLimits[type] = this.editSubmissionLimits[type].filter((_, i) => i !== index);
    this.requestUpdate();
  }

  private async saveSettings() {
    if (!this.tenantId) return;
    const prefixes = this.editPrefixes.map(p => p.value.trim()).filter(p => p.length > 0);
    if (prefixes.some(p => !/^\+\d+$/.test(p))) {
      this.settingsError = 'Each prefix must start with + followed by digits (e.g. +77).';
      return;
    }
    const rpId = this.editPasskeyRpId.trim();
    const rpName = this.editPasskeyRpName.trim();
    const origins = this.editPasskeyOrigins.map(o => o.value.trim()).filter(o => o.length > 0);
    if (!rpId) {
      this.settingsError = 'Passkey Relying Party ID is required.';
      return;
    }
    if (!rpName) {
      this.settingsError = 'Passkey Relying Party name is required.';
      return;
    }
    if (origins.length === 0) {
      this.settingsError = 'At least one passkey origin is required.';
      return;
    }
    const passkeySettings: PasskeySettings = { rpId, rpName, origins, userVerification: this.editPasskeyUserVerification };

    const ipHeader = this.editIpHeader.trim();
    if (!ipHeader) {
      this.settingsError = 'Client IP header is required.';
      return;
    }

    this.isSavingSettings = true;
    this.settingsError = '';
    try {
      await upsertChallengeSettings(
        this.tenantId,
        prefixes,
        this.editSubmissionLimits,
        this.editOtpLength,
        this.editOtpResendAfter,
        passkeySettings,
        this.editAuthConversationTtlSeconds,
        this.editSessionTtlSeconds,
        this.editSessionIdleTtlSeconds,
        ipHeader,
      );
      this.phonePrefixes = prefixes;
      this.submissionLimits = JSON.parse(JSON.stringify(this.editSubmissionLimits));
      this.otpLength = this.editOtpLength;
      this.otpResendAfter = this.editOtpResendAfter;
      this.authConversationTtlSeconds = this.editAuthConversationTtlSeconds;
      this.sessionTtlSeconds = this.editSessionTtlSeconds;
      this.sessionIdleTtlSeconds = this.editSessionIdleTtlSeconds;
      this.ipHeader = ipHeader;
      this.passkeySettings = { ...passkeySettings, origins: [...passkeySettings.origins] };
      this.hasChallengeSettings = true;
      this.editingSettings = false;
    } catch (e) {
      this.settingsError = e instanceof Error ? e.message : 'Failed to save challenge settings';
    } finally {
      this.isSavingSettings = false;
    }
  }

  private renderEdit() {
    const isNew = this.editingTemplateId === 'NEW';
    const isPassword = this.editingPurpose === 'password';
    // password template always uses the fixed 'password' id — treat as edit even on first creation
    const isNewOtp = isNew && !isPassword;
    const placeholder = isPassword ? PASSWORD_PLACEHOLDER : CODE_PLACEHOLDER;
    const typeLabel = isPassword ? 'Password Template' : 'OTP Template';
    const textareaPlaceholder = isPassword
      ? 'Your temporary password is {{password}}. It expires in {{expiresHours}} hours.'
      : 'Your verification code is: {{code}}';

    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">${isNewOtp ? `Add ${typeLabel}` : `Edit ${typeLabel}`}</h1>
          ${isNewOtp ? nothing : html`<div class="entity-id-meta">${this.editId}</div>`}
        </div>
      </div>

      <div class="card">
        ${isNew && !isPassword ? html`
          <div class="form-group">
            <label for="template-id">Template ID *</label>
            <input id="template-id" type="text" class="compact-input" .value=${this.editId}
              @input=${(e: Event) => { this.editId = (e.target as HTMLInputElement).value; }}
              placeholder="e.g. login-otp" />
            <div class="hint">Unique identifier for this template (used by OAuth clients).</div>
          </div>
        ` : nothing}

        <label>Localizations</label>
        <div class="hint">All active localizations are required. Use <code>${placeholder}</code> as a placeholder for the ${isPassword ? 'temporary password' : 'verification code'}.</div>

        ${this.editLocalizations.map((loc) => {
          const expanded = this.expandedLocales.has(loc.locale);
          return html`
            <div class="edit-loc-card">
              <div class="edit-loc-head" @click=${() => this.toggleLocExpand(loc.locale)}>
                <span class="edit-loc-title">${loc.locale} (${this.localeName(loc.locale)})</span>
                <div class="edit-loc-head-actions" @click=${(e: Event) => e.stopPropagation()}>
                  <span class="chevron">${expanded ? '▲' : '▼'}</span>
                </div>
              </div>
              ${expanded ? html`
                <div class="edit-loc-body">
                  <textarea class="edit-loc-textarea" .value=${loc.template}
                    @input=${(e: Event) => { loc.template = (e.target as HTMLTextAreaElement).value; }}
                    placeholder=${textareaPlaceholder}></textarea>
                </div>
              ` : nothing}
            </div>
          `;
        })}
        <div class="form-actions">
          <button class="btn btn-secondary" ?disabled=${this.saving} @click=${() => this.cancelEdit()}>Cancel</button>
          <button class="btn btn-primary" ?disabled=${this.saving} @click=${() => this.saveTemplate()}>
            ${this.saving ? 'Saving…' : 'Save'}
          </button>
        </div>
        ${this.editError ? html`<div class="error-msg">${this.editError}</div>` : nothing}
      </div>
    `;
  }

  private renderTemplateCard(template: OtpTemplateRecord) {
    const codes = Object.keys(template.localizations);
    const selected = this.selectedViewLocale(template);
    return html`
      <div class="card template-card">
        <div class="template-header">
          <span class="template-id">${template.id}</span>
          ${this.canManage ? html`
          <div class="template-actions">
            <button class="icon-action" @click=${() => this.startEdit(template)} title="Edit">✎</button>
            <button class="icon-action danger" @click=${() => this.handleDelete(template.id)} title="Delete">✕</button>
          </div>` : ''}
        </div>
        <div class="locale-bar">
          <span class="locale-bar-label">Language</span>
          <select class="form-control locale-select" .value=${selected}
            @change=${(e: Event) => { this.viewLocale = { ...this.viewLocale, [template.id]: (e.target as HTMLSelectElement).value }; }}>
            ${codes.map(code => html`
              <option value=${code} ?selected=${code === selected}>${code} (${this.localeName(code)})</option>
            `)}
          </select>
        </div>
        <div class="template-text">${template.localizations[selected] ?? ''}</div>
      </div>
    `;
  }

  private renderOtpSettings() {
    const otpTemplates = this.templates.filter(t => t.purpose !== 'password');
    return html`
      <section class="settings-section">
        <div class="section-header">
          <div>
            <h2 class="section-title">OTP Templates</h2>
            <div class="section-desc">One-time verification code templates used by OAuth clients. Must include the <code>${CODE_PLACEHOLDER}</code> placeholder.</div>
          </div>
          ${this.canManage ? html`<button class="btn btn-primary" @click=${() => this.startAdd()}>Add Template</button>` : ''}
        </div>

        ${otpTemplates.length === 0
          ? html`
            <div class="card">
              <div class="empty-state">
                <h3>No OTP templates yet</h3>
                <p>Add your first OTP template to get started.</p>
                ${this.canManage ? html`<button class="btn btn-primary" @click=${() => this.startAdd()} style="margin-top: 1rem;">+ Add Template</button>` : ''}
              </div>
            </div>`
          : otpTemplates.map(template => this.renderTemplateCard(template))}
      </section>
    `;
  }

  private startEditPasswordTemplate(template?: OtpTemplateRecord) {
    this.editingTemplateId = 'password';
    this.editingPurpose = 'password';
    this.editId = 'password';
    this.editLocalizations = this.buildLocalizations(template?.localizations ?? {});
    this.expandedLocales = new Set(this.editLocalizations.map(l => l.locale));
    this.editError = '';
  }

  private renderPasswordTemplates() {
    const passwordTemplate = this.templates.find(t => t.purpose === 'password');
    return html`
      <section class="settings-section">
        <div class="section-header">
          <div>
            <h2 class="section-title">Password Templates</h2>
            <div class="section-desc">Message sent when a temporary password is issued. Must include the <code>${PASSWORD_PLACEHOLDER}</code> placeholder.</div>
          </div>
          ${this.canManage ? html`
            <button class="btn btn-primary" @click=${() => this.startEditPasswordTemplate(passwordTemplate)}>
              ${passwordTemplate ? 'Edit' : 'Create'}
            </button>
          ` : nothing}
        </div>

        ${!passwordTemplate
          ? html`
            <div class="card">
              <div class="empty-state">
                <h3>No password template</h3>
                <p>Create a template or restart the server to seed the default one.</p>
                ${this.canManage ? html`<button class="btn btn-primary" @click=${() => this.startEditPasswordTemplate()} style="margin-top: 1rem;">+ Create Template</button>` : ''}
              </div>
            </div>`
          : this.renderPasswordTemplateCard(passwordTemplate)}
      </section>
    `;
  }

  private renderPasswordTemplateCard(template: OtpTemplateRecord) {
    const codes = Object.keys(template.localizations);
    const selected = this.selectedViewLocale(template);
    return html`
      <div class="card template-card">
        <div class="locale-bar">
          <span class="locale-bar-label">Language</span>
          <select class="form-control locale-select" .value=${selected}
            @change=${(e: Event) => { this.viewLocale = { ...this.viewLocale, [template.id]: (e.target as HTMLSelectElement).value }; }}>
            ${codes.map(code => html`
              <option value=${code} ?selected=${code === selected}>${code} (${this.localeName(code)})</option>
            `)}
          </select>
        </div>
        <div class="template-text">${template.localizations[selected] ?? ''}</div>
      </div>
    `;
  }

  private renderChallengeSettings() {
    return html`
      <section class="settings-section">
        <div class="section-header">
          <div>
            <h2 class="section-title">Challenge Settings</h2>
            <div class="section-desc">Global security settings for OTP and password submissions.</div>
          </div>
          ${this.hasChallengeSettings && this.canManage
            ? html`<button class="btn btn-primary" @click=${() => this.startEditSettings()}>Edit</button>`
            : nothing}
        </div>

        ${!this.hasChallengeSettings
          ? html`
            <div class="card">
              <div class="empty-state">
                <h3>No challenge settings yet</h3>
                <p>Configure OTP, password and passkey security for this tenant.</p>
                ${this.canManage ? html`<button class="btn btn-primary" @click=${() => this.startEditSettings()} style="margin-top: 1rem;">+ Add Challenge Settings</button>` : ''}
              </div>
            </div>`
          : this.renderChallengeSettingsContent()}
      </section>
    `;
  }

  private renderChallengeSettingsContent() {
    const { otpRequest, otpSubmit, passwordSubmit, passkeyAssertion, banDurationSeconds } = this.submissionLimits;
    const hasLimits = otpRequest.length > 0 || otpSubmit.length > 0 || passwordSubmit.length > 0 || passkeyAssertion.length > 0;

    return html`
      <div>
        <div class="card" style="margin-bottom: var(--spacing-lg);">
          <label>OTP Code Length</label>
          <div class="template-text">${this.otpLength} digits</div>

          <label style="margin-top: var(--spacing-lg);">OTP Resend After</label>
          <div class="template-text">${this.formatDuration(this.otpResendAfter)}</div>
        </div>

        <div class="card" style="margin-bottom: var(--spacing-lg);">
          <label>Allowed Phones</label>
          ${this.phonePrefixes.length === 0
            ? html`<div class="hint">No prefixes configured. Any phone number is accepted.</div>`
            : html`
              <div class="prefix-tags">
                ${this.phonePrefixes.map(prefix => html`<span class="prefix-tag">${prefix}</span>`)}
              </div>
            `}

        </div>

        <div class="card" style="margin-bottom: var(--spacing-lg);">
          <label>Auth Conversation TTL</label>
          <div class="template-text">${this.formatDuration(this.authConversationTtlSeconds)}</div>

          <label style="margin-top: var(--spacing-lg);">SSO Session TTL</label>
          <div class="template-text">${this.formatDuration(this.sessionTtlSeconds)}</div>

          <label style="margin-top: var(--spacing-lg);">SSO Session Idle Timeout</label>
          <div class="template-text">${this.sessionIdleTtlSeconds != null ? this.formatDuration(this.sessionIdleTtlSeconds) : 'Disabled'}</div>

          <label style="margin-top: var(--spacing-lg);">Client IP Header</label>
          <div class="template-text">${this.ipHeader}</div>
        </div>

        <div class="card" style="margin-bottom: var(--spacing-lg);">
          <label>Passkey (WebAuthn)</label>
          ${this.passkeySettings
            ? html`
              <div class="info-table">
                <div class="prop-row">
                  <span class="prop-label">Relying Party ID</span>
                  <span class="prop-value">${this.passkeySettings.rpId}</span>
                </div>
                <div class="prop-row">
                  <span class="prop-label">Relying Party Name</span>
                  <span class="prop-value">${this.passkeySettings.rpName}</span>
                </div>
                <div class="prop-row">
                  <span class="prop-label">User Verification</span>
                  <span class="prop-value">${this.passkeySettings.userVerification}</span>
                </div>
                <div class="prop-row">
                  <span class="prop-label">Allowed Origins</span>
                  ${this.passkeySettings.origins.length === 0
                    ? html`<span class="prop-value muted">None</span>`
                    : html`
                      <div class="prefix-tags" style="margin-top: 0;">
                        ${this.passkeySettings.origins.map(origin => html`<span class="prefix-tag">${origin}</span>`)}
                      </div>
                    `}
                </div>
              </div>
            `
            : html`<div class="hint">Passkeys are not configured for this tenant.</div>`}
        </div>

        ${hasLimits || banDurationSeconds > 0 ? html`
          <div class="card">
            <div class="limits-card-header">
              <label style="margin-bottom: 0;">Submission Limits</label>
              ${banDurationSeconds > 0 ? html`
                <span class="ban-badge">Ban: ${this.formatDuration(banDurationSeconds)}</span>
              ` : nothing}
            </div>
            <div class="limits-grid">
              ${this.renderLimitGroup('OTP Request', otpRequest)}
              ${this.renderLimitGroup('OTP Submit', otpSubmit)}
              ${this.renderLimitGroup('Password Submit', passwordSubmit)}
              ${this.renderLimitGroup('Passkey Assertion', passkeyAssertion)}
            </div>
          </div>
        ` : html`
          <div class="card">
            <div class="hint">No submission limits configured.</div>
          </div>
        `}
      </div>
    `;
  }

  private renderLimitGroup(label: string, limits: RateLimit[]) {
    if (limits.length === 0) return nothing;
    return html`
      <div>
        <div class="limit-group-title">${label}</div>
        ${limits.map(l => html`
          <span class="limit-chip">${l.maxAttempts} per ${this.formatDuration(l.windowSeconds)}</span>
        `)}
      </div>
    `;
  }

  private renderChallengeEdit() {
    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">Edit Challenge Settings</h1>
        </div>
      </div>

      <div class="card">
        <label>OTP Code Length</label>
        <div class="hint">Number of digits in generated one-time passwords.</div>
        <input type="number" class="form-control compact-input limit-input" .value=${this.editOtpLength}
          @input=${(e: Event) => { this.editOtpLength = Math.max(1, parseInt((e.target as HTMLInputElement).value) || 1); this.requestUpdate(); }} />

        <label style="margin-top: var(--spacing-lg);">OTP Resend After (seconds)</label>
        <div class="hint">How long the user must wait before requesting a new code.</div>
        <div class="limit-row" style="margin-bottom: 0;">
          <input type="number" class="form-control compact-input limit-input" .value=${this.editOtpResendAfter}
            @input=${(e: Event) => { this.editOtpResendAfter = parseInt((e.target as HTMLInputElement).value) || 0; this.requestUpdate(); }} />
          <span class="limit-hint">${this.formatDuration(this.editOtpResendAfter)}</span>
        </div>

        <label style="margin-top: var(--spacing-lg);">Allowed Phones</label>
        <div class="hint">Each prefix must start with + followed by digits (e.g. +77). Leave empty to accept any phone number.</div>

        ${this.editPrefixes.length === 0
          ? html`<div class="hint">No prefixes configured.</div>`
          : this.editPrefixes.map((entry, i) => html`
            <div class="locale-bar">
              <input type="text" class="form-control compact-input locale-select" .value=${entry.value}
                @input=${(e: Event) => { entry.value = (e.target as HTMLInputElement).value; }}
                placeholder="+77" />
              <button class="icon-action danger" @click=${() => this.removePrefix(i)} title="Remove">✕</button>
            </div>
          `)}

        <button class="btn btn-secondary" @click=${() => this.addPrefix()}>+ Add Prefix</button>

        <h3 style="margin-top: var(--spacing-xl); margin-bottom: var(--spacing-md);">Sessions</h3>

        <label>Auth Conversation TTL (seconds)</label>
        <div class="hint">How long an in-progress authentication conversation stays valid.</div>
        <div class="limit-row" style="margin-bottom: 0;">
          <input type="number" class="form-control compact-input limit-input" .value=${this.editAuthConversationTtlSeconds}
            @input=${(e: Event) => { this.editAuthConversationTtlSeconds = Math.max(1, parseInt((e.target as HTMLInputElement).value) || 1); this.requestUpdate(); }} />
          <span class="limit-hint">${this.formatDuration(this.editAuthConversationTtlSeconds)}</span>
        </div>

        <label style="margin-top: var(--spacing-lg);">SSO Session TTL (seconds)</label>
        <div class="hint">How long the SSO session cookie remains valid after authentication.</div>
        <div class="limit-row" style="margin-bottom: 0;">
          <input type="number" class="form-control compact-input limit-input" .value=${this.editSessionTtlSeconds}
            @input=${(e: Event) => { this.editSessionTtlSeconds = Math.max(1, parseInt((e.target as HTMLInputElement).value) || 1); this.requestUpdate(); }} />
          <span class="limit-hint">${this.formatDuration(this.editSessionTtlSeconds)}</span>
        </div>

        <label style="margin-top: var(--spacing-lg);">SSO Session Idle Timeout (seconds)</label>
        <div class="hint">Online sessions (no offline_access) expire after this period of inactivity, sliding forward on each silent re-authentication. Leave empty to disable.</div>
        <div class="limit-row" style="margin-bottom: 0;">
          <input type="number" class="form-control compact-input limit-input" .value=${this.editSessionIdleTtlSeconds ?? ''}
            @input=${(e: Event) => { const v = parseInt((e.target as HTMLInputElement).value); this.editSessionIdleTtlSeconds = Number.isFinite(v) && v > 0 ? v : null; this.requestUpdate(); }} />
          <span class="limit-hint">${this.editSessionIdleTtlSeconds != null ? this.formatDuration(this.editSessionIdleTtlSeconds) : 'Disabled'}</span>
        </div>

        ${this.renderIpHeaderEdit()}

        <h3 style="margin-top: var(--spacing-xl); margin-bottom: var(--spacing-md);">Passkey (WebAuthn)</h3>

        <label>Relying Party ID</label>
        <div class="hint">The domain the passkeys are scoped to (e.g. example.com).</div>
        <input type="text" class="form-control compact-input" .value=${this.editPasskeyRpId}
          @input=${(e: Event) => { this.editPasskeyRpId = (e.target as HTMLInputElement).value; }}
          placeholder="example.com" />

        <label style="margin-top: var(--spacing-lg);">Relying Party Name</label>
        <div class="hint">Human-readable name shown to users during passkey prompts.</div>
        <input type="text" class="form-control compact-input" .value=${this.editPasskeyRpName}
          @input=${(e: Event) => { this.editPasskeyRpName = (e.target as HTMLInputElement).value; }}
          placeholder="Example Inc." />

        <label style="margin-top: var(--spacing-lg);">User Verification</label>
        <div class="hint">Whether the authenticator must verify the user (PIN, biometrics).</div>
        <select class="form-control compact-input" .value=${this.editPasskeyUserVerification}
          @change=${(e: Event) => { this.editPasskeyUserVerification = (e.target as HTMLSelectElement).value; }}>
          <option value="required">required</option>
          <option value="preferred">preferred</option>
          <option value="discouraged">discouraged</option>
        </select>

        <label style="margin-top: var(--spacing-lg);">Allowed Origins</label>
        <div class="hint">Full origins permitted to use these passkeys (e.g. https://example.com).</div>
        ${this.editPasskeyOrigins.length === 0
          ? html`<div class="hint">No origins configured.</div>`
          : this.editPasskeyOrigins.map((entry, i) => html`
            <div class="locale-bar">
              <input type="text" class="form-control compact-input locale-select" .value=${entry.value}
                @input=${(e: Event) => { entry.value = (e.target as HTMLInputElement).value; }}
                placeholder="https://example.com" />
              <button class="icon-action danger" @click=${() => this.removePasskeyOrigin(i)} title="Remove">✕</button>
            </div>
          `)}
        <button class="btn btn-secondary" @click=${() => this.addPasskeyOrigin()}>+ Add Origin</button>

        ${this.renderIpHeaderEdit()}

        <h3 style="margin-top: var(--spacing-xl); margin-bottom: var(--spacing-md);">Submission Limits</h3>

        <div class="form-group">
          <label>Ban Duration (seconds)</label>
          <div class="limit-row" style="margin-bottom: 0;">
            <input type="number" class="form-control compact-input limit-input" .value=${this.editSubmissionLimits.banDurationSeconds}
              @input=${(e: Event) => { this.editSubmissionLimits.banDurationSeconds = Math.max(0, parseInt((e.target as HTMLInputElement).value) || 0); this.requestUpdate(); }} />
            <span class="limit-hint">${this.formatDuration(this.editSubmissionLimits.banDurationSeconds)}</span>
          </div>
          <div class="hint">How long a user is banned after exceeding the longest window.</div>
        </div>

        ${this.renderEditLimitList('OTP Request', 'otpRequest')}
        ${this.renderEditLimitList('OTP Submit', 'otpSubmit')}
        ${this.renderEditLimitList('Password Submit', 'passwordSubmit')}
        ${this.renderEditLimitList('Passkey Assertion', 'passkeyAssertion')}

        <div class="form-actions">
          <button class="btn btn-secondary" ?disabled=${this.isSavingSettings} @click=${() => this.cancelEditSettings()}>Cancel</button>
          <button class="btn btn-primary" ?disabled=${this.isSavingSettings} @click=${() => this.saveSettings()}>
            ${this.isSavingSettings ? 'Saving…' : 'Save'}
          </button>
        </div>
        ${this.settingsError ? html`<div class="error-msg">${this.settingsError}</div>` : nothing}
      </div>
    `;
  }

  private renderIpHeaderEdit() {
    const knownHeaders = ['X-Real-IP', 'X-Forwarded-For'];
    const isCustom = !knownHeaders.includes(this.editIpHeader);
    return html`
      <label style="margin-top: var(--spacing-lg);">Client IP Header</label>
      <div class="hint">HTTP header your reverse proxy sets with the real client IP, used for IP-based throttling.</div>
      <select class="form-control compact-input" .value=${isCustom ? 'custom' : this.editIpHeader}
        @change=${(e: Event) => {
          const value = (e.target as HTMLSelectElement).value;
          this.editIpHeader = value === 'custom' ? '' : value;
        }}>
        <option value="X-Real-IP" ?selected=${this.editIpHeader === 'X-Real-IP'}>X-Real-IP (nginx)</option>
        <option value="X-Forwarded-For" ?selected=${this.editIpHeader === 'X-Forwarded-For'}>X-Forwarded-For (other proxies)</option>
        <option value="custom" ?selected=${isCustom}>Custom…</option>
      </select>
      ${isCustom ? html`
        <input type="text" class="form-control compact-input" style="margin-top: var(--spacing-sm);"
          .value=${this.editIpHeader}
          @input=${(e: Event) => { this.editIpHeader = (e.target as HTMLInputElement).value; }}
          placeholder="X-Client-IP" />
      ` : nothing}
    `;
  }

  private renderEditLimitList(label: string, type: 'otpRequest' | 'otpSubmit' | 'passwordSubmit' | 'passkeyAssertion') {
    const limits = this.editSubmissionLimits[type];
    return html`
      <div class="form-group" style="margin-top: var(--spacing-lg);">
        <label>${label} Rate Limits</label>
        <div class="hint">Define multiple windows. Only the longest window triggers a ban; others are immediate rate limits.</div>
        ${limits.map((l, i) => html`
          <div class="limit-row">
            <span class="limit-label">Max Attempts</span>
            <input type="number" class="form-control compact-input limit-input" .value=${l.maxAttempts}
              @input=${(e: Event) => { l.maxAttempts = Math.max(1, parseInt((e.target as HTMLInputElement).value) || 1); this.requestUpdate(); }} />
            <span class="limit-label">Window (sec)</span>
            <input type="number" class="form-control compact-input limit-input" .value=${l.windowSeconds}
              @input=${(e: Event) => { l.windowSeconds = Math.max(1, parseInt((e.target as HTMLInputElement).value) || 1); this.requestUpdate(); }} />
            <span class="limit-hint">${this.formatDuration(l.windowSeconds)}</span>
            <button class="icon-action danger" @click=${() => this.removeRateLimit(type, i)} title="Remove">✕</button>
          </div>
        `)}
        <button class="btn btn-secondary btn-sm" @click=${() => this.addRateLimit(type)}>+ Add Window</button>
      </div>
    `;
  }

  render() {
    if (!this.tenantId) {
      return html`<div class="hint">Please select a tenant to manage challenges.</div>`;
    }

    if (this.editingTemplateId) return this.renderEdit();
    if (this.editingSettings) return this.renderChallengeEdit();

    return html`
      <div class="page-header">
        <h1 class="page-title">Challenges & Security</h1>
      </div>

      ${this.isLoading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
        : this.errorMessage ? html`
          <versola-error-card heading="Could not load challenges & security" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
        `
        : html`
          ${this.renderOtpSettings()}
          ${this.renderPasswordTemplates()}
          ${this.renderChallengeSettings()}
        `}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-challenges-list': VersolaChallengesList;
  }
}
