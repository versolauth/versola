import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
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
import { validateRedirectUri } from '../utils/validators';
import './content-header';
import './code-editor';
import './authorization-detail-types-list';
import './error-card';
import './loading-cards';
import './nav-toggle';
import { VersolaAuthorizationDetailTypesList } from './authorization-detail-types-list';

const CODE_PLACEHOLDER = '{{code}}';
const PASSWORD_PLACEHOLDER = '{{password}}';
const PASSWORD_EXPIRY_PLACEHOLDER = '{{expiresHours}}';
type TemplatePurpose = 'otp' | 'password';
type TemplateChannel = 'sms' | 'email';
type TemplateViewSelection = { purpose: TemplatePurpose; channel: TemplateChannel };

@customElement('versola-challenges-list')
export class VersolaChallengesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @query('versola-authorization-detail-types-list') private authorizationDetailTypesList?: VersolaAuthorizationDetailTypesList;

  @state() private templates: OtpTemplateRecord[] = [];
  @state() private availableLocales: Locale[] = [];
  @state() private isLoading = false;
  @state() private errorMessage = '';

  // View-mode: selected locale code and template variant per template id.
  @state() private viewLocale: Record<string, string> = {};
  @state() private viewSelection: Record<string, TemplateViewSelection> = {};

  // Edit-mode: expanded localization cards, keyed by locale code (collapsed by default).
  @state() private expandedLocales = new Set<string>();

  @state() private editingTemplateId: string | null = null; // null means not editing/adding
  @state() private editingPurpose: 'otp' | 'password' = 'otp';
  @state() private editingChannel: 'sms' | 'email' = 'sms';
  @state() private editId = '';
  @state() private editLocalizations: Array<{ locale: string; template: string }> = [];
  @state() private previewLocales = new Set<string>();
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
  @state() private userAgentTtlSeconds = 15552000;
  @state() private editAuthConversationTtlSeconds = 900;
  @state() private editSessionTtlSeconds = 86400;
  @state() private editSessionIdleTtlSeconds: number | null = null;
  @state() private editUserAgentTtlSeconds = 15552000;

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
  @state() private acrVocabulary: Record<string, string[]> = {};
  @state() private editAcrVocabulary: Array<{ acr: string; factors: string[] }> = [];

  @state() private postLogoutRedirectUris: string[] = [];
  @state() private editPostLogoutRedirectUris: Array<{ value: string }> = [];

  static styles = [
    theme,
    cardStyles,
    buttonStyles,
    formStyles,
    iconActionStyles,
    css`
      :host {
        display: block;
        --challenge-button-width: 12rem;
        --challenge-button-height: 2.75rem;
      }

      .btn {
        box-sizing: border-box;
        flex: 0 0 var(--challenge-button-width);
        width: var(--challenge-button-width);
        min-height: var(--challenge-button-height);
      }

      .section-header > .btn {
        width: var(--challenge-button-width) !important;
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
      .template-id-group {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
      }
      .template-channel-select {
        width: 100%;
        max-width: none;
      }
      .template-actions {
        display: flex;
        gap: var(--spacing-sm);
      }
      .locale-bar {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: var(--spacing-sm);
        margin-bottom: var(--spacing-sm);
        width: 100%;
      }
      .locale-bar-label {
        font-size: 0.8125rem;
        color: var(--text-secondary);
      }
      .locale-select {
        width: 100%;
        max-width: none;
      }
      .template-channel-control,
      .template-type-control,
      .template-language-control {
        display: grid;
        grid-template-columns: 7rem minmax(0, 1fr);
        align-items: center;
        gap: var(--spacing-sm);
        width: 100%;
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
      .edit-loc-toolbar {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
      }
      .preview-toggle {
        width: auto;
        padding: 0.5rem 0.75rem;
      }
      .html-validation-error {
        font-size: 0.75rem;
      }
      .html-validation-error { color: var(--danger); }
      .email-preview {
        display: block;
        width: 100%;
        min-height: 360px;
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        background: #fff;
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
        /* Phone prefixes and ACR names are short, but this class also renders
           passkey origins, which are full URLs. Without these the tag is an
           unbreakable box that pushes past the card's right edge on a phone. */
        max-width: 100%;
        min-width: 0;
        overflow-wrap: anywhere;
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
      /* The 11rem label column plus a 1.5rem gap leaves almost nothing for the
         value on a ~390px screen. Below this width the label sits above its
         value instead of beside it. */
      @media (max-width: 720px) {
        .prop-row {
          grid-template-columns: minmax(0, 1fr);
          gap: var(--spacing-xs);
          padding: 0.875rem var(--spacing-md);
        }
        .prop-label {
          white-space: normal;
        }
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
      this.viewSelection = {};
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
        this.userAgentTtlSeconds = challengeSettings.userAgentTtlSeconds;
        this.acrVocabulary = challengeSettings.acrVocabulary ?? {};
        this.ipHeader = challengeSettings.ipHeader || 'X-Real-IP';
        this.postLogoutRedirectUris = challengeSettings.postLogoutRedirectUris ?? [];
      } else {
        this.phonePrefixes = [];
        this.submissionLimits = { otpRequest: [], otpSubmit: [], passwordSubmit: [], passkeyAssertion: [], banDurationSeconds: 0 };
        this.otpLength = 6;
        this.otpResendAfter = 60;
        this.passkeySettings = null;
        this.authConversationTtlSeconds = 900;
        this.sessionTtlSeconds = 86400;
        this.sessionIdleTtlSeconds = null;
        this.userAgentTtlSeconds = 15552000;
        this.acrVocabulary = {};
        this.ipHeader = 'X-Real-IP';
        this.postLogoutRedirectUris = [];
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
    this.editingChannel = 'sms';
    this.editId = '';
    this.editLocalizations = this.buildLocalizations({});
    this.previewLocales = new Set();
    this.expandedLocales = new Set(this.editLocalizations.map(l => l.locale));
    this.editError = '';
  }

  private startEdit(template: OtpTemplateRecord) {
    this.editingTemplateId = this.templateKey(template);
    this.editingPurpose = template.purpose === 'password' ? 'password' : 'otp';
    this.editingChannel = template.channel ?? (this.editingPurpose === 'password' ? 'email' : 'sms');
    this.editId = template.id;
    this.editLocalizations = this.buildLocalizations(template.localizations);
    this.previewLocales = new Set();
    this.expandedLocales = new Set(this.editLocalizations.map(l => l.locale));
    this.editError = '';
  }

  private changeEditingChannel(channel: 'sms' | 'email') {
    if (channel === this.editingChannel) return;

    this.editingChannel = channel;
    const existing = this.templates.find(template =>
      template.id === this.editId
      && template.purpose === this.editingPurpose
      && template.channel === channel,
    );
    this.editLocalizations = this.buildLocalizations(existing?.localizations ?? {});
    this.previewLocales = new Set();
    this.expandedLocales = new Set(this.editLocalizations.map(l => l.locale));
    this.editError = '';
  }

  private changeEditingPurpose(purpose: 'otp' | 'password') {
    if (purpose === this.editingPurpose) return;

    this.editingPurpose = purpose;
    const channels = this.templates
      .filter(template => template.id === this.editId && template.purpose === purpose)
      .map(template => template.channel);
    this.editingChannel = channels.includes(this.editingChannel)
      ? this.editingChannel
      : channels.includes('sms') ? 'sms' : 'email';
    const existing = this.templates.find(template =>
      template.id === this.editId
      && template.purpose === purpose
      && template.channel === this.editingChannel,
    );
    this.editLocalizations = this.buildLocalizations(existing?.localizations ?? {});
    this.previewLocales = new Set();
    this.expandedLocales = new Set(this.editLocalizations.map(l => l.locale));
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

  private toggleLocalePreview(code: string) {
    const next = new Set(this.previewLocales);
    if (next.has(code)) next.delete(code);
    else next.add(code);
    this.previewLocales = next;
  }

  private updateLocalization(locale: string, template: string) {
    this.editLocalizations = this.editLocalizations.map(item =>
      item.locale === locale ? { ...item, template } : item,
    );
  }

  private selectedViewLocale(template: OtpTemplateRecord): string {
    const codes = Object.keys(template.localizations);
    const selected = this.viewLocale[this.templateGroupKey(template)];
    return selected && codes.includes(selected) ? selected : codes[0] ?? '';
  }

  private templateKey(template: OtpTemplateRecord): string {
    return `${template.purpose}:${template.id}:${template.channel}`;
  }

  private templateGroupKey(template: OtpTemplateRecord): string {
    return template.id;
  }

  private availableViewPurposes(template: OtpTemplateRecord): TemplatePurpose[] {
    const purposes = [...new Set(this.templates
      .filter(candidate => this.templateGroupKey(candidate) === this.templateGroupKey(template))
      .map(candidate => candidate.purpose as TemplatePurpose))];
    if (template.id === 'default') {
      if (!purposes.includes('otp')) purposes.push('otp');
      if (!purposes.includes('password')) purposes.push('password');
    }
    return purposes;
  }

  private availableViewChannels(template: OtpTemplateRecord, purpose: TemplatePurpose): TemplateChannel[] {
    const channels = [...new Set(this.templates
      .filter(candidate => this.templateGroupKey(candidate) === this.templateGroupKey(template) && candidate.purpose === purpose)
      .map(candidate => candidate.channel as TemplateChannel))];
    if (channels.length === 0) channels.push(purpose === 'password' ? 'email' : template.channel as TemplateChannel);
    return channels;
  }

  private selectedViewSelection(template: OtpTemplateRecord): TemplateViewSelection {
    const groupKey = this.templateGroupKey(template);
    const purposes = this.availableViewPurposes(template);
    const stored = this.viewSelection[groupKey];
    const purpose = stored && purposes.includes(stored.purpose)
      ? stored.purpose
      : purposes.includes('otp') ? 'otp' : template.purpose as TemplatePurpose;
    const channels = this.availableViewChannels(template, purpose);
    const channel = stored?.purpose === purpose && channels.includes(stored.channel)
      ? stored.channel
      : channels.includes('sms') ? 'sms' : channels[0];
    return { purpose, channel };
  }

  private selectViewPurpose(template: OtpTemplateRecord, purpose: TemplatePurpose) {
    const currentSelection = this.selectedViewSelection(template);
    const channels = this.availableViewChannels(template, purpose);
    const channel = channels.includes(currentSelection.channel)
      ? currentSelection.channel
      : channels.includes('sms') ? 'sms' : channels[0];
    this.viewSelection = {
      ...this.viewSelection,
      [this.templateGroupKey(template)]: { purpose, channel },
    };
  }

  private selectViewChannel(template: OtpTemplateRecord, channel: TemplateChannel) {
    const selection = this.selectedViewSelection(template);
    const channels = this.availableViewChannels(template, selection.purpose);
    if (!channels.includes(channel)) return;
    this.viewSelection = {
      ...this.viewSelection,
      [this.templateGroupKey(template)]: { ...selection, channel },
    };
  }

  private localeName(code: string): string {
    return this.availableLocales.find(l => l.code === code)?.name ?? code;
  }

  private formatDuration(seconds: number): string {
    if (seconds === 0) return '—';
    if (seconds % 86400 === 0 && seconds >= 86400) {
      const days = seconds / 86400;
      return `${days} day${days === 1 ? '' : 's'}`;
    }
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

    const placeholders = this.editingPurpose === 'password'
      ? [PASSWORD_PLACEHOLDER, PASSWORD_EXPIRY_PLACEHOLDER]
      : [CODE_PLACEHOLDER];
    const localizations: Record<string, string> = {};
    for (const { locale, template } of this.editLocalizations) {
      const t = template.trim();
      if (!t) {
        this.editError = `Localization for ${this.localeName(locale)} (${locale}) is required`;
        return;
      }
      for (const placeholder of placeholders) {
        if (!t.includes(placeholder)) {
          this.editError = `Localization for ${this.localeName(locale)} (${locale}) must include the ${placeholder} placeholder`;
          return;
        }
      }
      const htmlError = this.getEmailHtmlValidationError(t);
      if (this.editingChannel === 'email' && htmlError) {
        this.editError = `Localization for ${this.localeName(locale)} (${locale}) must contain valid HTML: ${htmlError}`;
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
      await upsertOtpTemplate(id, this.tenantId, localizations, this.editingPurpose, this.editingChannel);
      const updated: OtpTemplateRecord = {
        id,
        tenantId: this.tenantId,
        localizations,
        purpose: this.editingPurpose,
        channel: this.editingChannel,
      };
      const existing = this.templates.some(t => this.templateKey(t) === this.templateKey(updated));
      this.templates = existing
        ? this.templates.map(t => (this.templateKey(t) === this.templateKey(updated) ? updated : t))
        : [...this.templates, updated];
      this.editingTemplateId = null;
    } catch (e) {
      this.editError = e instanceof Error ? e.message : 'Failed to save template';
    } finally {
      this.saving = false;
    }
  }

  private getEmailHtmlValidationError(template: string): string {
    if (!template.trim().startsWith('<')) return 'HTML must start with an element';
    const document = new DOMParser().parseFromString(template, 'text/html');
    if (document.querySelector('parsererror')) return 'HTML could not be parsed';
    if (!document.body.querySelector('*')) return 'HTML must contain an element';
    if (document.querySelector('script')) return 'script elements are not allowed';
    if (document.querySelector('[onclick], [onload], [onerror], iframe, object, embed')) {
      return 'scripts and executable embedded content are not allowed';
    }
    const structureError = this.getHtmlStructureError(template);
    if (structureError) return structureError;
    return '';
  }

  private getHtmlStructureError(template: string): string {
    const voidElements = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
    const tags = /<!--[\s\S]*?-->|<!doctype\s+[^>]*>|<\/?([A-Za-z][A-Za-z0-9:-]*)(?:\s[^<>]*?)?\/?>/gi;
    const openElements: string[] = [];
    let cursor = 0;
    let match: RegExpExecArray | null;

    while ((match = tags.exec(template)) !== null) {
      if (template.slice(cursor, match.index).includes('<')) return 'HTML contains an invalid tag';
      cursor = tags.lastIndex;
      const token = match[0];
      const name = match[1]?.toLowerCase();
      if (!name || token.startsWith('<!--') || token.toLowerCase().startsWith('<!doctype')) continue;

      if (token.startsWith('</')) {
        if (openElements[openElements.length - 1] !== name) return `Unexpected closing tag </${name}>`;
        openElements.pop();
      } else {
        const attributeError = this.getHtmlAttributeError(token, name);
        if (attributeError) return attributeError;
        if (!token.endsWith('/>') && !voidElements.has(name)) openElements.push(name);
      }
    }

    if (template.slice(cursor).includes('<')) return 'HTML contains an invalid tag';
    if (openElements.length > 0) return `Unclosed tag <${openElements[openElements.length - 1]}>`;
    return '';
  }

  private getHtmlAttributeError(token: string, tagName: string): string {
    const booleanAttributes = new Set(['allowfullscreen', 'async', 'autofocus', 'autoplay', 'checked', 'controls', 'default', 'defer', 'disabled', 'formnovalidate', 'hidden', 'inert', 'ismap', 'itemscope', 'loop', 'multiple', 'muted', 'nomodule', 'novalidate', 'open', 'playsinline', 'readonly', 'required', 'reversed', 'selected']);
    const body = token.slice(1, token.endsWith('/>') ? -2 : -1).trim();
    const firstWhitespace = body.search(/\s/);
    if (firstWhitespace < 0) return '';

    const attributes = body.slice(firstWhitespace).trim();
    let index = 0;
    while (index < attributes.length) {
      while (/\s/.test(attributes[index] ?? '')) index += 1;
      if (index >= attributes.length) break;

      const nameStart = index;
      while (index < attributes.length && !/[\s=]/.test(attributes[index])) index += 1;
      const attributeName = attributes.slice(nameStart, index);
      if (!attributeName) return `Malformed attribute on <${tagName}>`;
      while (/\s/.test(attributes[index] ?? '')) index += 1;

      if (attributes[index] !== '=') {
        if (booleanAttributes.has(attributeName.toLowerCase())) continue;
        return `Attribute "${attributeName}" on <${tagName}> must have a value`;
      }

      index += 1;
      while (/\s/.test(attributes[index] ?? '')) index += 1;
      if (index >= attributes.length || attributes[index] === '=') {
        return `Attribute "${attributeName}" on <${tagName}> must have a value`;
      }

      const quote = attributes[index];
      if (quote === '"' || quote === "'") {
        index += 1;
        const valueEnd = attributes.indexOf(quote, index);
        if (valueEnd < 0) return `Attribute "${attributeName}" on <${tagName}> has an unterminated value`;
        index = valueEnd + 1;
      } else {
        while (index < attributes.length && !/\s/.test(attributes[index])) index += 1;
      }
    }
    return '';
  }

  private previewTemplate(template: string): string {
    return template;
  }

  private async handleDelete(template: OtpTemplateRecord) {
    if (!this.tenantId) return;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete template',
      messagePrefix: 'Delete template ',
      messageSubject: `${template.id} (${template.channel})`,
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    try {
      await deleteOtpTemplate(template.id, this.tenantId, template.purpose, template.channel);
      this.templates = this.templates.filter(t => this.templateKey(t) !== this.templateKey(template));
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
    this.editUserAgentTtlSeconds = this.userAgentTtlSeconds;
    this.editIpHeader = this.ipHeader;
    this.editPasskeyRpId = this.passkeySettings?.rpId ?? '';
    this.editPasskeyRpName = this.passkeySettings?.rpName ?? '';
    this.editPasskeyOrigins = (this.passkeySettings?.origins ?? []).map(value => ({ value }));
    this.editPasskeyUserVerification = this.passkeySettings?.userVerification ?? 'preferred';
    this.editAcrVocabulary = Object.entries(this.acrVocabulary)
      .map(([acr, factors]) => ({ acr, factors: [...factors] }));
    this.editPostLogoutRedirectUris = this.postLogoutRedirectUris.map(value => ({ value }));
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

  private addPostLogoutRedirectUri() {
    this.editPostLogoutRedirectUris = [...this.editPostLogoutRedirectUris, { value: '' }];
  }

  private removePostLogoutRedirectUri(index: number) {
    this.editPostLogoutRedirectUris = this.editPostLogoutRedirectUris.filter((_, i) => i !== index);
  }

  private addAcrRow() {
    this.editAcrVocabulary = [...this.editAcrVocabulary, { acr: '', factors: [] }];
  }

  private removeAcrRow(index: number) {
    this.editAcrVocabulary = this.editAcrVocabulary.filter((_, i) => i !== index);
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

    const postLogoutRedirectUris = this.editPostLogoutRedirectUris.map(p => p.value.trim()).filter(p => p.length > 0);
    for (const uri of postLogoutRedirectUris) {
      const validation = validateRedirectUri(uri);
      if (!validation.valid) {
        this.settingsError = `Invalid post-logout redirect URI "${uri}": ${validation.error}`;
        return;
      }
    }

    this.isSavingSettings = true;
    this.settingsError = '';
    const acrVocabulary = Object.fromEntries(
      this.editAcrVocabulary
        .filter(row => row.acr.trim() && row.factors.length > 0)
        .map(row => [row.acr.trim(), row.factors]),
    );
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
        this.editUserAgentTtlSeconds,
        ipHeader,
        acrVocabulary,
        postLogoutRedirectUris,
      );
      this.phonePrefixes = prefixes;
      this.submissionLimits = JSON.parse(JSON.stringify(this.editSubmissionLimits));
      this.otpLength = this.editOtpLength;
      this.otpResendAfter = this.editOtpResendAfter;
      this.authConversationTtlSeconds = this.editAuthConversationTtlSeconds;
      this.sessionTtlSeconds = this.editSessionTtlSeconds;
      this.sessionIdleTtlSeconds = this.editSessionIdleTtlSeconds;
      this.userAgentTtlSeconds = this.editUserAgentTtlSeconds;
      this.ipHeader = ipHeader;
      this.passkeySettings = { ...passkeySettings, origins: [...passkeySettings.origins] };
      this.acrVocabulary = acrVocabulary;
      this.postLogoutRedirectUris = postLogoutRedirectUris;
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
    const placeholders = isPassword
      ? `${PASSWORD_PLACEHOLDER} and ${PASSWORD_EXPIRY_PLACEHOLDER}`
      : CODE_PLACEHOLDER;
    const typeLabel = isPassword ? 'Password Template' : 'OTP Template';
    const textareaPlaceholder = isPassword
      ? '<html><body><p>Your temporary password is {{password}}.</p><p>It expires in {{expiresHours}} hours.</p></body></html>'
      : this.editingChannel === 'email'
        ? '<html><body><p>Your verification code is {{code}}</p></body></html>'
        : 'Your verification code is: {{code}}';

    return html`
      <div class="form-header">
        <div class="form-header-lead">
          <versola-nav-toggle></versola-nav-toggle>
          <div class="title-stack">
            <h1 class="form-title">${isNewOtp ? `Add ${typeLabel}` : `Edit ${typeLabel}`}</h1>
            ${isNewOtp ? nothing : html`<div class="entity-id-meta">${this.editId}</div>`}
          </div>
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

        <div class="form-group">
          <label for="template-purpose">Type</label>
          <select id="template-purpose" class="compact-input" .value=${this.editingPurpose}
            @change=${(e: Event) => this.changeEditingPurpose((e.target as HTMLSelectElement).value as 'otp' | 'password')}>
            <option value="otp">otp</option>
            <option value="password">password</option>
          </select>
        </div>

        <div class="form-group">
          <label for="template-channel">Channel</label>
          <select id="template-channel" class="compact-input" .value=${this.editingChannel}
            @change=${(e: Event) => this.changeEditingChannel((e.target as HTMLSelectElement).value as 'sms' | 'email')}>
            <option value="sms">sms</option>
            <option value="email">email</option>
          </select>
          <div class="hint">${this.editingChannel === 'email' ? 'Email templates must contain valid HTML.' : 'SMS templates are plain text.'}</div>
        </div>

        <label>Localizations</label>
        <div class="hint">All active localizations are required. Use <code>${placeholders}</code> as placeholders for the ${isPassword ? 'temporary password' : 'verification code'}.</div>

        ${this.editLocalizations.map((loc) => {
          const expanded = this.expandedLocales.has(loc.locale);
          const htmlError = this.editingChannel === 'email' ? this.getEmailHtmlValidationError(loc.template) : '';
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
                  ${this.editingChannel === 'email' ? html`
                    <div class="edit-loc-toolbar">
                      <button type="button" class="btn btn-secondary preview-toggle"
                        ?disabled=${Boolean(htmlError) && !this.previewLocales.has(loc.locale)}
                        @click=${() => this.toggleLocalePreview(loc.locale)}>
                        ${this.previewLocales.has(loc.locale) ? 'Edit HTML' : 'Preview'}
                      </button>
                      ${htmlError ? html`<span class="html-validation-error">${htmlError}</span>` : nothing}
                    </div>
                    ${this.previewLocales.has(loc.locale)
                      ? html`<iframe class="email-preview" title=${`Preview for ${loc.locale}`} sandbox="" .srcdoc=${this.previewTemplate(loc.template)}></iframe>`
                      : html`<versola-code-editor language="html" rows="20"
                          .value=${loc.template}
                          .placeholder=${textareaPlaceholder}
                          .invalid=${Boolean(htmlError)}
                          @code-input=${(e: CustomEvent<{ value: string }>) => this.updateLocalization(loc.locale, e.detail.value)}
                        ></versola-code-editor>`}
                  ` : html`
                    <textarea class="edit-loc-textarea" .value=${loc.template}
                      @input=${(e: Event) => this.updateLocalization(loc.locale, (e.target as HTMLTextAreaElement).value)}
                      placeholder=${textareaPlaceholder}></textarea>
                  `}
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

  private renderTemplateCard(template: OtpTemplateRecord, showActions = true) {
    const selection = this.selectedViewSelection(template);
    const selectedPurpose = selection.purpose;
    const selectedChannel = selection.channel;
    const selectedTemplate = this.templates.find(candidate =>
      this.templateGroupKey(candidate) === this.templateGroupKey(template)
      && candidate.purpose === selectedPurpose
      && candidate.channel === selectedChannel,
    );
    const displayTemplate: OtpTemplateRecord = selectedTemplate ?? {
      ...template,
      purpose: selectedPurpose,
      channel: selectedChannel,
      localizations: {},
    };
    const purposes = this.availableViewPurposes(template);
    const codes = Object.keys(displayTemplate.localizations);
    const selected = selectedTemplate ? this.selectedViewLocale(selectedTemplate) : '';
    const channels = this.availableViewChannels(template, selectedPurpose);
    const orderedChannels = (['sms', 'email'] as TemplateChannel[]).filter(channel => channels.includes(channel));
    return html`
      <div class="card template-card">
        <div class="template-header">
          <div class="template-id-group">
            <span class="template-id">${displayTemplate.id}</span>
          </div>
          ${this.canManage && showActions ? html`
          <div class="template-actions">
            <button class="icon-action" @click=${() => this.startEdit(displayTemplate)} title=${selectedTemplate ? 'Edit' : 'Create'}>✎</button>
            ${selectedTemplate ? html`<button class="icon-action danger" @click=${() => this.handleDelete(selectedTemplate)} title="Delete">✕</button>` : nothing}
          </div>` : ''}
        </div>
        <div class="locale-bar">
          ${purposes.length > 1 ? html`
            <div class="template-type-control">
              <span class="locale-bar-label">Type</span>
              <select class="form-control" aria-label="Type"
                .value=${selectedPurpose}
                @change=${(e: Event) => this.selectViewPurpose(template, (e.target as HTMLSelectElement).value as 'otp' | 'password')}>
                ${purposes.map(purpose => html`<option value=${purpose}>${purpose}</option>`)}
              </select>
            </div>
          ` : nothing}
          <div class="template-channel-control">
            <span class="locale-bar-label">Channel</span>
            <select class="form-control template-channel-select" aria-label="Channel"
              .value=${selectedChannel}
              @change=${(e: Event) => this.selectViewChannel(template, (e.target as HTMLSelectElement).value as 'sms' | 'email')}>
              ${orderedChannels.map(channel => html`<option value=${channel}>${channel}</option>`)}
            </select>
          </div>
          <div class="template-language-control">
            <span class="locale-bar-label">Language</span>
            <select class="form-control locale-select" .value=${selected}
              @change=${(e: Event) => { this.viewLocale = { ...this.viewLocale, [this.templateGroupKey(template)]: (e.target as HTMLSelectElement).value }; }}>
              ${codes.map(code => html`
                <option value=${code} ?selected=${code === selected}>${code} (${this.localeName(code)})</option>
              `)}
            </select>
          </div>
        </div>
        ${selectedTemplate && selectedTemplate.channel === 'email'
          ? html`<iframe class="email-preview" title=${`Preview for ${selectedTemplate.id} ${selectedChannel}`} sandbox=""
              .srcdoc=${this.previewTemplate(selectedTemplate.localizations[selected] ?? '')}></iframe>`
          : selectedTemplate
            ? html`<div class="template-text">${selectedTemplate.localizations[selected] ?? ''}</div>`
            : html`<div class="empty-state"><p>No ${selectedPurpose} template exists for this ID yet.</p></div>`}
      </div>
    `;
  }

  private renderOtpSettings() {
    return html`
      <section class="settings-section">
        <div class="section-header">
          <div>
            <h2 class="section-title">Templates</h2>
            <div class="section-desc">OTP and temporary-password templates used by OAuth clients. Choose the type and delivery channel for each template. SMS templates are plain text; email templates use HTML.</div>
          </div>
          ${this.canManage ? html`<button class="btn btn-primary" @click=${() => this.startAdd()}>Add Template</button>` : ''}
        </div>

        ${this.templates.length === 0
          ? html`
            <div class="card">
              <div class="empty-state">
                <h3>No templates yet</h3>
                <p>Add your first template to get started.</p>
                ${this.canManage ? html`<button class="btn btn-primary" @click=${() => this.startAdd()} style="margin-top: 1rem;">+ Add Template</button>` : ''}
              </div>
            </div>`
          : this.templates
            .filter((template, index, all) => all.findIndex(candidate =>
              this.templateGroupKey(candidate) === this.templateGroupKey(template),
            ) === index)
            .map(template => this.renderTemplateCard(template))}
      </section>
    `;
  }

  private renderAuthorizationDetailTypes() {
    return html`
      <section class="settings-section">
        <div class="section-header">
          <div>
            <h2 class="section-title">Authorization Details</h2>
            <div class="section-desc">Register the RFC 9396 authorization detail types and JSON Schemas that clients may request.</div>
          </div>
          ${this.canManage ? html`
            <button class="btn btn-primary" @click=${() => this.authorizationDetailTypesList?.startCreate()}>Create Type</button>
          ` : nothing}
        </div>
        <versola-authorization-detail-types-list
          .tenantId=${this.tenantId}
          .canManage=${this.canManage}
        ></versola-authorization-detail-types-list>
      </section>
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

          <label style="margin-top: var(--spacing-lg);">User Agent TTL</label>
          <div class="template-text">${this.formatDuration(this.userAgentTtlSeconds)}</div>

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

        <div class="card" style="margin-bottom: var(--spacing-lg);">
          <label>ACR Vocabulary</label>
          ${Object.keys(this.acrVocabulary).length === 0
            ? html`<div class="hint">No ACR vocabulary configured.</div>`
            : html`
              <div class="info-table">
                ${Object.entries(this.acrVocabulary).map(([acr, factors]) => html`
                  <div class="prop-row">
                    <span class="prop-label prop-value" style="font-family: var(--font-mono); text-transform: none;">${acr}</span>
                    <div class="prefix-tags" style="margin-top: 0;">
                      ${factors.map(f => html`<span class="prefix-tag">${f}</span>`)}
                    </div>
                  </div>
                `)}
              </div>
            `}
        </div>

        <div class="card" style="margin-bottom: var(--spacing-lg);">
          <label>Post-Logout Redirect URIs</label>
          <div class="hint">Exact-match URIs RPs may request as <code>post_logout_redirect_uri</code> after OIDC logout.</div>
          ${this.postLogoutRedirectUris.length === 0
            ? html`<div class="hint">No post-logout redirect URIs configured.</div>`
            : html`
              <div class="prefix-tags">
                ${this.postLogoutRedirectUris.map(uri => html`<span class="prefix-tag">${uri}</span>`)}
              </div>
            `}
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
        <div class="form-header-lead">
          <versola-nav-toggle></versola-nav-toggle>
          <div class="title-stack">
            <h1 class="form-title">Edit Challenge Settings</h1>
          </div>
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

        <label style="margin-top: var(--spacing-lg);">SSO Session TTL (days)</label>
        <div class="hint">How long the SSO session cookie remains valid after authentication.</div>
        <div class="limit-row" style="margin-bottom: 0;">
          <input type="number" step="0.5" class="form-control compact-input limit-input" .value=${this.editSessionTtlSeconds / 86400}
            @input=${(e: Event) => { const days = Math.max(1 / 86400, parseFloat((e.target as HTMLInputElement).value) || 0); this.editSessionTtlSeconds = Math.round(days * 86400); this.requestUpdate(); }} />
          <span class="limit-hint">${this.formatDuration(this.editSessionTtlSeconds)}</span>
        </div>

        <label style="margin-top: var(--spacing-lg);">SSO Session Idle Timeout (seconds)</label>
        <div class="hint">Online sessions (no offline_access) expire after this period of inactivity, sliding forward on each silent re-authentication. Leave empty to disable.</div>
        <div class="limit-row" style="margin-bottom: 0;">
          <input type="number" class="form-control compact-input limit-input" .value=${this.editSessionIdleTtlSeconds ?? ''}
            @input=${(e: Event) => { const v = parseInt((e.target as HTMLInputElement).value); this.editSessionIdleTtlSeconds = Number.isFinite(v) && v > 0 ? v : null; this.requestUpdate(); }} />
          <span class="limit-hint">${this.editSessionIdleTtlSeconds != null ? this.formatDuration(this.editSessionIdleTtlSeconds) : 'Disabled'}</span>
        </div>

        <label style="margin-top: var(--spacing-lg);">User Agent TTL (days)</label>
        <div class="hint">How long a recognized device (user agent) is remembered before it needs to be re-verified.</div>
        <div class="limit-row" style="margin-bottom: 0;">
          <input type="number" step="0.5" class="form-control compact-input limit-input" .value=${this.editUserAgentTtlSeconds / 86400}
            @input=${(e: Event) => { const days = Math.max(1 / 86400, parseFloat((e.target as HTMLInputElement).value) || 0); this.editUserAgentTtlSeconds = Math.round(days * 86400); this.requestUpdate(); }} />
          <span class="limit-hint">${this.formatDuration(this.editUserAgentTtlSeconds)}</span>
        </div>

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

        <h3 style="margin-top: var(--spacing-xl); margin-bottom: var(--spacing-md);">ACR Vocabulary</h3>
        <div class="hint">Map ACR names to the authentication factors they require. Clients can request a specific ACR via the <code>acr_values</code> parameter.</div>

        ${this.editAcrVocabulary.map((row, i) => html`
          <div class="edit-loc-card" style="margin-bottom: var(--spacing-sm);">
            <div class="locale-bar" style="align-items: flex-start; flex-wrap: wrap; gap: var(--spacing-md);">
              <input type="text" class="form-control compact-input locale-select" .value=${row.acr}
                placeholder="e.g. company_mfa"
                @input=${(e: Event) => { row.acr = (e.target as HTMLInputElement).value; this.requestUpdate(); }} />
              <div style="display: flex; gap: var(--spacing-lg); align-items: center; flex-wrap: wrap;">
                ${(['otp', 'password', 'passkey'] as const).map(factor => html`
                  <label style="display: flex; align-items: center; gap: var(--spacing-xs); font-size: 0.875rem; cursor: pointer;">
                    <input type="checkbox"
                      .checked=${row.factors.includes(factor)}
                      @change=${(e: Event) => {
                        const checked = (e.target as HTMLInputElement).checked;
                        row.factors = checked
                          ? [...row.factors, factor]
                          : row.factors.filter(f => f !== factor);
                        this.requestUpdate();
                      }} />
                    ${factor}
                  </label>
                `)}
              </div>
              <button class="icon-action danger" style="margin-left: auto;" @click=${() => this.removeAcrRow(i)} title="Remove">✕</button>
            </div>
          </div>
        `)}
        <button class="btn btn-secondary" @click=${() => this.addAcrRow()}>+ Add ACR</button>

        <h3 style="margin-top: var(--spacing-xl); margin-bottom: var(--spacing-md);">Logout</h3>
        <label>Post-Logout Redirect URIs</label>
        <div class="hint">RPs must request an exact match of one of these URIs as <code>post_logout_redirect_uri</code>.</div>

        ${this.editPostLogoutRedirectUris.length === 0
          ? html`<div class="hint">No post-logout redirect URIs configured.</div>`
          : this.editPostLogoutRedirectUris.map((entry, i) => html`
            <div class="locale-bar">
              <input type="text" class="form-control compact-input locale-select" .value=${entry.value}
                @input=${(e: Event) => { entry.value = (e.target as HTMLInputElement).value; }}
                placeholder="https://app.example.com/logged-out" />
              <button class="icon-action danger" @click=${() => this.removePostLogoutRedirectUri(i)} title="Remove">✕</button>
            </div>
          `)}

        <button class="btn btn-secondary" @click=${() => this.addPostLogoutRedirectUri()}>+ Add URI</button>

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
      <content-header title="Challenges & Security"></content-header>

      ${this.isLoading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
        : this.errorMessage ? html`
          <versola-error-card heading="Could not load challenges & security" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
        `
        : html`
          ${this.renderOtpSettings()}
          ${this.renderAuthorizationDetailTypes()}
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