import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles, iconActionStyles } from '../styles/components';
import type { OtpTemplateRecord, Locale, SubmissionLimits, RateLimit } from '../types';
import {
  fetchOtpTemplates,
  upsertOtpTemplate,
  deleteOtpTemplate,
  fetchLocales,
  fetchChallengeSettings,
  upsertChallengeSettings,
} from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';

const CODE_PLACEHOLDER = '{{code}}';

@customElement('versola-challenges-list')
export class VersolaChallengesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;

  @state() private templates: OtpTemplateRecord[] = [];
  @state() private availableLocales: Locale[] = [];
  @state() private isLoading = false;
  @state() private errorMessage = '';

  // View-mode: selected locale code per template id.
  @state() private viewLocale: Record<string, string> = {};

  // Edit-mode: expanded localization cards, keyed by locale code (collapsed by default).
  @state() private expandedLocales = new Set<string>();

  @state() private editingTemplateId: string | null = null; // null means not editing/adding
  @state() private editId = '';
  @state() private editLocalizations: Array<{ locale: string; template: string }> = [];
  @state() private saving = false;
  @state() private editError = '';

  @state() private phonePrefixes: string[] = [];
  @state() private editingSettings = false;
  @state() private editPrefixes: Array<{ value: string }> = [];
  @state() private isSavingSettings = false;
  @state() private settingsError = '';

  @state() private passwordRegex = '';
  @state() private editPasswordRegex = '';

  @state() private otpLength = 6;
  @state() private otpResendAfter = 60;
  @state() private editOtpLength = 6;
  @state() private editOtpResendAfter = 60;

  @state() private submissionLimits: SubmissionLimits = {
    otpRequest: [],
    otpSubmit: [],
    passwordSubmit: [],
    banDurationSeconds: 0,
  };
  @state() private editSubmissionLimits: SubmissionLimits = {
    otpRequest: [],
    otpSubmit: [],
    passwordSubmit: [],
    banDurationSeconds: 0,
  };

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
      this.phonePrefixes = challengeSettings.allowedPrefixes;
      this.passwordRegex = challengeSettings.passwordRegex ?? '';
      this.submissionLimits = challengeSettings.submissionLimits;
      this.otpLength = challengeSettings.otpLength;
      this.otpResendAfter = challengeSettings.otpResendAfter;
    } catch (e) {
      this.errorMessage = e instanceof Error ? e.message : 'Failed to load data';
    } finally {
      this.isLoading = false;
    }
  }

  private startAdd() {
    this.editingTemplateId = 'NEW';
    this.editId = '';
    this.editLocalizations = this.buildLocalizations({});
    this.expandedLocales = new Set(this.editLocalizations.map(l => l.locale));
    this.editError = '';
  }

  private startEdit(template: OtpTemplateRecord) {
    this.editingTemplateId = template.id;
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

    const localizations: Record<string, string> = {};
    for (const { locale, template } of this.editLocalizations) {
      const t = template.trim();
      if (!t) {
        this.editError = `Localization for ${this.localeName(locale)} (${locale}) is required`;
        return;
      }
      if (!t.includes(CODE_PLACEHOLDER)) {
        this.editError = `Localization for ${this.localeName(locale)} (${locale}) must include the ${CODE_PLACEHOLDER} placeholder`;
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
      await upsertOtpTemplate(id, this.tenantId, localizations);
      const updated: OtpTemplateRecord = { id, tenantId: this.tenantId, localizations };
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
    this.editPasswordRegex = this.passwordRegex;
    this.editSubmissionLimits = JSON.parse(JSON.stringify(this.submissionLimits));
    this.editOtpLength = this.otpLength;
    this.editOtpResendAfter = this.otpResendAfter;
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

  private addRateLimit(type: 'otpRequest' | 'otpSubmit' | 'passwordSubmit') {
    this.editSubmissionLimits[type] = [...this.editSubmissionLimits[type], { maxAttempts: 5, windowSeconds: 60 }];
    this.requestUpdate();
  }

  private removeRateLimit(type: 'otpRequest' | 'otpSubmit' | 'passwordSubmit', index: number) {
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
    const regex = this.editPasswordRegex.trim();
    if (regex) {
      try {
        new RegExp(regex);
      } catch {
        this.settingsError = 'Invalid password regular expression.';
        return;
      }
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
        regex || undefined,
      );
      this.phonePrefixes = prefixes;
      this.passwordRegex = regex;
      this.submissionLimits = JSON.parse(JSON.stringify(this.editSubmissionLimits));
      this.otpLength = this.editOtpLength;
      this.otpResendAfter = this.editOtpResendAfter;
      this.editingSettings = false;
    } catch (e) {
      this.settingsError = e instanceof Error ? e.message : 'Failed to save challenge settings';
    } finally {
      this.isSavingSettings = false;
    }
  }

  private renderEdit() {
    const isNew = this.editingTemplateId === 'NEW';
    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">${isNew ? 'Add OTP Template' : 'Edit OTP Template'}</h1>
          ${isNew ? nothing : html`<div class="entity-id-meta">${this.editId}</div>`}
        </div>
      </div>

      <div class="card">
        ${isNew ? html`
          <div class="form-group">
            <label for="template-id">Template ID *</label>
            <input id="template-id" type="text" class="compact-input" .value=${this.editId}
              @input=${(e: Event) => { this.editId = (e.target as HTMLInputElement).value; }}
              placeholder="e.g. login-otp" />
            <div class="hint">Unique identifier for this template (used by OAuth clients).</div>
          </div>
        ` : nothing}

        <label>Localizations</label>
        <div class="hint">All active localizations are required. Use ${CODE_PLACEHOLDER} as a placeholder for the verification code.</div>

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
                    placeholder="Your verification code is: {{code}}"></textarea>
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
          <div class="template-actions">
            <button class="icon-action" @click=${() => this.startEdit(template)} title="Edit">✎</button>
            <button class="icon-action danger" @click=${() => this.handleDelete(template.id)} title="Delete">✕</button>
          </div>
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
    return html`
      <section class="settings-section">
        <div class="section-header">
          <div>
            <h2 class="section-title">OTP Templates</h2>
            <div class="section-desc">One-time password message templates used by OAuth clients.</div>
          </div>
          <button class="btn btn-primary" @click=${() => this.startAdd()}>Add Template</button>
        </div>

        ${this.isLoading ? html`<div class="hint">Loading…</div>`
          : this.errorMessage ? html`<div class="error-msg">${this.errorMessage}</div>`
          : this.templates.length === 0
            ? html`<div class="card"><div class="hint">No OTP templates configured for this tenant.</div></div>`
            : this.templates.map(template => this.renderTemplateCard(template))}
      </section>
    `;
  }

  private renderChallengeSettings() {
    const { otpRequest, otpSubmit, passwordSubmit, banDurationSeconds } = this.submissionLimits;
    const hasLimits = otpRequest.length > 0 || otpSubmit.length > 0 || passwordSubmit.length > 0;

    return html`
      <section class="settings-section">
        <div class="section-header">
          <div>
            <h2 class="section-title">Challenge Settings</h2>
            <div class="section-desc">Global security settings for OTP and password submissions.</div>
          </div>
          <button class="btn btn-primary" @click=${() => this.startEditSettings()}>Edit</button>
        </div>

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

          <label style="margin-top: var(--spacing-lg);">Password Regex</label>
          ${this.passwordRegex
            ? html`<div class="template-text">${this.passwordRegex}</div>`
            : html`<div class="hint">No password regex configured. Any password is accepted.</div>`}
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
            </div>
          </div>
        ` : html`
          <div class="card">
            <div class="hint">No submission limits configured.</div>
          </div>
        `}
      </section>
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
          @input=${(e: Event) => { this.editOtpLength = parseInt((e.target as HTMLInputElement).value) || 1; this.requestUpdate(); }} />

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

        <label style="margin-top: var(--spacing-lg);">Password Regex</label>
        <div class="hint">Regular expression that submitted passwords must match. Leave empty to accept any password.</div>
        <input type="text" class="form-control compact-input" .value=${this.editPasswordRegex}
          @input=${(e: Event) => { this.editPasswordRegex = (e.target as HTMLInputElement).value; }}
          placeholder="^(?=.*[A-Za-z])(?=.*\\d).{8,}$" />

        <h3 style="margin-top: var(--spacing-xl); margin-bottom: var(--spacing-md);">Submission Limits</h3>

        <div class="form-group">
          <label>Ban Duration (seconds)</label>
          <div class="limit-row" style="margin-bottom: 0;">
            <input type="number" class="form-control compact-input limit-input" .value=${this.editSubmissionLimits.banDurationSeconds}
              @input=${(e: Event) => { this.editSubmissionLimits.banDurationSeconds = parseInt((e.target as HTMLInputElement).value) || 0; this.requestUpdate(); }} />
            <span class="limit-hint">${this.formatDuration(this.editSubmissionLimits.banDurationSeconds)}</span>
          </div>
          <div class="hint">How long a user is banned after exceeding the longest window.</div>
        </div>

        ${this.renderEditLimitList('OTP Request', 'otpRequest')}
        ${this.renderEditLimitList('OTP Submit', 'otpSubmit')}
        ${this.renderEditLimitList('Password Submit', 'passwordSubmit')}

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

  private renderEditLimitList(label: string, type: 'otpRequest' | 'otpSubmit' | 'passwordSubmit') {
    const limits = this.editSubmissionLimits[type];
    return html`
      <div class="form-group" style="margin-top: var(--spacing-lg);">
        <label>${label} Rate Limits</label>
        <div class="hint">Define multiple windows. Only the longest window triggers a ban; others are immediate rate limits.</div>
        ${limits.map((l, i) => html`
          <div class="limit-row">
            <span class="limit-label">Max Attempts</span>
            <input type="number" class="form-control compact-input limit-input" .value=${l.maxAttempts}
              @input=${(e: Event) => { l.maxAttempts = parseInt((e.target as HTMLInputElement).value) || 1; this.requestUpdate(); }} />
            <span class="limit-label">Window (sec)</span>
            <input type="number" class="form-control compact-input limit-input" .value=${l.windowSeconds}
              @input=${(e: Event) => { l.windowSeconds = parseInt((e.target as HTMLInputElement).value) || 1; this.requestUpdate(); }} />
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
        <h1 class="page-title">Challenges</h1>
      </div>

      ${this.renderOtpSettings()}
      ${this.renderChallengeSettings()}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-challenges-list': VersolaChallengesList;
  }
}
