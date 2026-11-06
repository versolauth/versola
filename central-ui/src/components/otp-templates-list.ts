import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../styles/components';
import type { Locale, OtpTemplateRecord } from '../types';
import { deleteOtpTemplate, fetchLocales, fetchOtpTemplates, upsertOtpTemplate } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { copyToClipboard } from '../utils/helpers';
import { localizationError, templatePlaceholders, type TemplateChannel, type TemplatePurpose } from '../utils/otp-template';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { languagesCell, languagesChip, missingLocales } from './localized-value';
import { statusIcon } from './status-icon';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from './data-table';
import './code-editor';
import './content-header';
import './danger-card';
import './data-table';
import './empty-state';
import './error-card';
import './filter-bar';
import './loading-cards';
import './record-card';
import './record-header';
import './side-panel';

type TemplateFilter = 'all' | 'otp' | 'password' | 'sms' | 'email' | 'missing';
const PURPOSES: TemplatePurpose[] = ['otp', 'password'];
const CHANNELS: TemplateChannel[] = ['sms', 'email'];
const PURPOSE_LABELS: Record<TemplatePurpose, string> = { otp: 'OTP', password: 'Password' };
const CHANNEL_LABELS: Record<TemplateChannel, string> = { sms: 'SMS', email: 'Email' };

/** The composite key of a template, and the `id` the URL carries. */
export function templateKey(template: Pick<OtpTemplateRecord, 'id' | 'purpose' | 'channel'>): string {
  return `${template.id}:${template.purpose}:${template.channel}`;
}

function placeholderExample(purpose: TemplatePurpose, channel: TemplateChannel): string {
  if (purpose === 'password') {
    return channel === 'email'
      ? '<html><body><p>Your temporary password is {{password}}.</p><p>It expires in {{expiresHours}} hours.</p></body></html>'
      : 'Your temporary password is {{password}}. It expires in {{expiresHours}} hours.';
  }
  return channel === 'email' ? '<html><body><p>Your verification code is {{code}}</p></body></html>' : 'Your verification code is: {{code}}';
}

/**
 * OTP and temporary-password templates: the tenant's table and, for the one selected, the side
 * panel with the message per locale (an email body in a sandboxed preview), the Fixed key, the
 * other variants of the same ID and the danger card. Editing the message and creating a template
 * happen in the same panel.
 */
@customElement('versola-otp-templates-list')
export class VersolaOtpTemplatesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private templates: OtpTemplateRecord[] = [];
  @state() private locales: Locale[] = [];
  @state() private searchQuery = '';
  @state() private filter: TemplateFilter = 'all';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private draftId = '';
  @state() private draftPurpose: TemplatePurpose = 'otp';
  @state() private draftChannel: TemplateChannel = 'sms';
  @state() private draftLocalizations: Record<string, string> = {};
  @state() private previewLocales = new Set<string>();
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @query('versola-data-table') private table?: VersolaDataTable<OtpTemplateRecord>;
  private loadRequestId = 0;
  private draftKey = '';
  private draftBaseline = '';

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    iconButtonStyles,
    recordStyles,
    css`
      :host {
        display: block;
      }

      .list-toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-md);
        align-items: center;
        margin-bottom: var(--spacing-lg);
      }

      .search-input {
        flex: 1 1 14rem;
        max-width: 28rem;
        min-width: 0;
      }

      .muted {
        color: var(--text-secondary);
      }

      .mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
      }

      .message-text {
        white-space: pre-wrap;
        overflow-wrap: anywhere;
      }

      .email-preview {
        display: block;
        width: 100%;
        min-height: 16rem;
        border: 1px solid var(--border-strong);
        border-radius: var(--radius-md);
        background: var(--surface);
      }

      .preview-caption {
        margin-bottom: 0.375rem;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .locale-head {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: var(--spacing-md);
        margin-bottom: 0.5rem;
      }

      .locale-head label {
        margin: 0;
      }

      .record-page .record-card {
        margin-bottom: var(--spacing-md);
      }

      /* An email body is a box, not a line: the locale label sits at its top. */
      .record-row:has(.email-preview) {
        align-items: start;
      }

      textarea.message-input {
        max-width: none;
        min-height: 6rem;
        font-family: var(--font-family);
      }
    `,
  ];

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId')) void this.loadData();
    if (changed.has('route') || changed.has('templates') || changed.has('locales')) {
      const mode = this.editorMode;
      const draftKey = mode === 'view' ? '' : `${mode}:${this.route.id ?? ''}:${this.route.edit ?? ''}:${this.locales.length}`;
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (mode !== 'view') this.resetDraft();
      }
    }
  }

  private async loadData() {
    if (!this.tenantId) {
      this.templates = [];
      this.errorMessage = '';
      return;
    }
    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [templates, locales] = await Promise.all([fetchOtpTemplates(this.tenantId), fetchLocales().catch(() => [] as Locale[])]);
      if (requestId !== this.loadRequestId) return;
      this.templates = this.sorted(templates);
      this.locales = locales;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.templates = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load templates';
    } finally {
      if (requestId === this.loadRequestId) this.isLoading = false;
    }
  }

  private sorted(templates: OtpTemplateRecord[]): OtpTemplateRecord[] {
    return [...templates].sort((a, b) => templateKey(a).localeCompare(templateKey(b)));
  }

  // ── routing ──

  private get openTemplate(): OtpTemplateRecord | null {
    return this.route.id ? this.templates.find(template => templateKey(template) === this.route.id) ?? null : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.route.edit === 'message' && this.openTemplate ? 'edit' : 'view';
  }

  private openView(template: OtpTemplateRecord) {
    dispatchRecordRoute(this, { id: templateKey(template), edit: null, create: false });
  }

  private closePanel() {
    const key = this.route.id;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private async cancelEditor() {
    if (this.draftSnapshot !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  private handleCreateClick() {
    dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null });
  }

  // ── draft ──

  private get draftSnapshot(): string {
    return JSON.stringify([this.draftId, this.draftPurpose, this.draftChannel, this.draftLocalizations]);
  }

  /** Every active locale gets a field; a stored localization of an inactive locale is kept. */
  private localizationsFor(existing: Record<string, string>): Record<string, string> {
    const result: Record<string, string> = {};
    for (const locale of this.locales.filter(locale => locale.active)) result[locale.code] = existing[locale.code] ?? '';
    for (const [code, text] of Object.entries(existing)) if (!(code in result)) result[code] = text;
    return result;
  }

  private resetDraft() {
    const template = this.editorMode === 'edit' ? this.openTemplate : null;
    this.draftId = template?.id ?? '';
    this.draftPurpose = template?.purpose === 'password' ? 'password' : 'otp';
    this.draftChannel = template?.channel ?? 'sms';
    this.draftLocalizations = this.localizationsFor(template?.localizations ?? {});
    this.previewLocales = new Set();
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
    this.draftBaseline = this.draftSnapshot;
  }

  private get draftIdError(): string {
    const id = this.draftId.trim();
    if (!id) return this.submitted ? 'A template ID is required.' : '';
    if (!/^[a-z][a-z0-9_-]*$/.test(id)) return 'Lowercase letters, digits, underscores and hyphens, starting with a letter.';
    if (this.templates.some(template => template.id === id && template.purpose === this.draftPurpose && template.channel === this.draftChannel)) {
      return `${this.draftPurpose === 'otp' ? 'An' : 'A'} ${PURPOSE_LABELS[this.draftPurpose]} template for ${CHANNEL_LABELS[this.draftChannel]} with this ID already exists.`;
    }
    return '';
  }

  private localizationErrorFor(code: string): string {
    return this.submitted || (this.draftLocalizations[code] ?? '').trim() ? localizationError(this.draftLocalizations[code] ?? '', this.draftPurpose, this.draftChannel) : '';
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (!this.tenantId || this.isSaving) return;
    this.submitted = true;
    const mode = this.editorMode;
    const codes = Object.keys(this.draftLocalizations);
    if ((mode === 'create' && this.draftIdError) || codes.some(code => localizationError(this.draftLocalizations[code] ?? '', this.draftPurpose, this.draftChannel))) return;
    if (codes.length === 0) {
      this.formError = 'At least one localization is required; activate a locale under System settings first.';
      return;
    }
    const existing = this.openTemplate;
    const localizations = Object.fromEntries(codes.map(code => [code, this.draftLocalizations[code].trim()]));
    const next: OtpTemplateRecord = mode === 'create'
      ? { id: this.draftId.trim(), tenantId: this.tenantId, purpose: this.draftPurpose, channel: this.draftChannel, localizations }
      : { ...existing!, localizations };
    this.isSaving = true;
    this.formError = '';
    try {
      await upsertOtpTemplate(next.id, this.tenantId, next.localizations, next.purpose, next.channel);
      const key = templateKey(next);
      this.templates = this.sorted([...this.templates.filter(template => templateKey(template) !== key), next]);
      dispatchRecordRoute(this, { id: key, edit: null, create: false });
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save template';
    } finally {
      this.isSaving = false;
    }
  }

  private async handleDelete(template: OtpTemplateRecord) {
    if (!this.tenantId) return;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete template',
      messagePrefix: 'Delete template ',
      messageSubject: `${template.id} (${PURPOSE_LABELS[template.purpose as TemplatePurpose] ?? template.purpose} · ${CHANNEL_LABELS[template.channel]})`,
      messageSuffix: `? Clients that name it have no ${template.purpose} message on ${template.channel} any more.`,
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    try {
      await deleteOtpTemplate(template.id, this.tenantId, template.purpose, template.channel);
      const key = templateKey(template);
      this.templates = this.templates.filter(candidate => templateKey(candidate) !== key);
      if (this.route.id === key) dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to delete template';
    }
  }

  // ── list ──

  private matchesFilter(template: OtpTemplateRecord, filter: TemplateFilter = this.filter): boolean {
    switch (filter) {
      case 'otp': return template.purpose === 'otp';
      case 'password': return template.purpose === 'password';
      case 'sms': return template.channel === 'sms';
      case 'email': return template.channel === 'email';
      case 'missing': return missingLocales(template.localizations, this.locales).length > 0;
      default: return true;
    }
  }

  private get filteredTemplates(): OtpTemplateRecord[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.templates.filter(template => this.matchesFilter(template) && (!query || template.id.toLowerCase().includes(query)));
  }

  private renderToolbar() {
    const options: Array<{ id: TemplateFilter; label: string }> = [
      { id: 'all', label: 'All' },
      { id: 'otp', label: 'OTP' },
      { id: 'password', label: 'Password' },
      { id: 'sms', label: 'SMS' },
      { id: 'email', label: 'Email' },
      ...(this.locales.length ? [{ id: 'missing' as const, label: 'Missing translations' }] : []),
    ];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search templates" placeholder="Template ID"
          .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
        <versola-filter-bar label="Filter templates"
          .options=${options}
          .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => this.filter = e.detail.id as TemplateFilter}></versola-filter-bar>
      </div>
    `;
  }

  private renderTable() {
    const columns: DataTableColumn<OtpTemplateRecord>[] = [
      { id: 'template', label: 'Template', cell: template => html`
        <button type="button" class="row-link" aria-label=${`Open template ${template.id} ${template.purpose} ${template.channel}`}>
          <span class="row-id">${template.id}</span>
        </button>` },
      { id: 'type', label: 'Type', cell: template => PURPOSE_LABELS[template.purpose as TemplatePurpose] ?? template.purpose },
      { id: 'channel', label: 'Channel', cell: template => CHANNEL_LABELS[template.channel] },
      ...(this.locales.length ? [{ id: 'languages', label: 'Languages', cell: (template: OtpTemplateRecord) => languagesCell(template.localizations, this.locales) }] : []),
    ];
    const menuItems = (template: OtpTemplateRecord): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(template) },
      { label: 'Copy template ID', onSelect: () => void copyToClipboard(template.id) },
      ...(this.canManage ? [{ label: 'Delete template', danger: true, ariaLabel: `Delete template ${templateKey(template)}`, onSelect: () => void this.handleDelete(template) }] : []),
    ];
    return html`
      <versola-data-table selectable
        .columns=${columns}
        .rows=${this.filteredTemplates}
        .rowKey=${templateKey}
        .selectedKey=${this.editorMode === 'view' ? this.route.id : null}
        .menuItems=${menuItems}
        .menuLabel=${(template: OtpTemplateRecord) => `Actions for ${templateKey(template)}`}
        @row-select=${(e: CustomEvent<{ row: OtpTemplateRecord }>) => this.openView(e.detail.row)}
      ></versola-data-table>
    `;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private localeName(code: string): string {
    const name = this.locales.find(locale => locale.code === code)?.name;
    return name ? `${code} · ${name}` : code;
  }

  private renderMessage(template: OtpTemplateRecord, code: string) {
    const text = template.localizations[code] ?? '';
    return template.channel === 'email'
      ? html`<div class="preview-caption">Preview as the recipient sees it</div><iframe class="email-preview" title=${`Preview for ${code}`} sandbox="" .srcdoc=${text}></iframe>`
      : html`<div class="message-text">${text}</div>`;
  }

  private renderGroups(template: OtpTemplateRecord) {
    const codes = Object.keys(template.localizations);
    const variants = this.templates.filter(candidate => candidate.id === template.id && templateKey(candidate) !== templateKey(template));
    return html`
      <versola-record-card heading="Message" subheading=${`Uses ${templatePlaceholders(template.purpose as TemplatePurpose).join(' and ')}; ${template.channel === 'email' ? 'HTML, shown as the recipient sees it.' : 'plain text.'}`}
        ?editable=${this.canManage}
        .body=${codes.length ? codes.map(code => this.row(this.localeName(code), this.renderMessage(template, code)))
          : html`<div class="record-row"><div class="record-label"></div><div class="record-value muted">No localizations.</div></div>`}
        @edit=${() => dispatchRecordRoute(this, { edit: 'message', create: false })}></versola-record-card>

      <versola-record-card heading="Fixed" fixedLabel="Fixed" .body=${html`
        ${this.row('Template ID', html`<span class="mono">${template.id}</span>`, 'Part of the template\'s key; clients name it')}
        ${this.row('Type', PURPOSE_LABELS[template.purpose as TemplatePurpose] ?? template.purpose, 'Part of the template\'s key')}
        ${this.row('Channel', CHANNEL_LABELS[template.channel], 'Part of the template\'s key')}
      `}></versola-record-card>

      ${variants.length ? html`
        <versola-record-card heading="Other variants" subheading="The same ID for another type or channel." .body=${html`
          ${this.row('Variants', html`<div class="tag-list">${variants.map(variant => html`
            <button type="button" class="tag" @click=${() => this.openView(variant)}>${PURPOSE_LABELS[variant.purpose as TemplatePurpose] ?? variant.purpose} · ${CHANNEL_LABELS[variant.channel]}</button>`)}</div>`)}
        `}></versola-record-card>` : ''}

      ${this.canManage ? html`
        <versola-danger-card heading="Delete template" text=${`Clients that name this template have no ${template.purpose} message on ${template.channel} any more.`}
          actionLabel="Delete template" .actionAriaLabel=${`Delete template ${templateKey(template)}`}
          @action=${() => this.handleDelete(template)}></versola-danger-card>` : ''}
    `;
  }

  private renderBanner(template: OtpTemplateRecord) {
    const missing = missingLocales(template.localizations, this.locales);
    if (missing.length === 0) return '';
    return html`
      <div class="banner warn" role="status">
        <div class="banner-text">
          <div class="banner-title">${statusIcon('alert')}Missing translations for ${missing.join(', ')}</div>
          <div>Edit Message to add them.</div>
        </div>
      </div>
    `;
  }

  private renderMeta(template: OtpTemplateRecord) {
    return html`
      <span class="chip">${PURPOSE_LABELS[template.purpose as TemplatePurpose] ?? template.purpose} · ${CHANNEL_LABELS[template.channel]}</span>
      ${languagesChip(template.localizations, this.locales)}
    `;
  }

  private renderRecordPage(template: OtpTemplateRecord) {
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'OTP templates', onSelect: () => this.closePanel() }, { label: template.id }]}
        .heading=${template.id}
        .recordId=${template.id} idLabel="template ID"
        .meta=${html`<span class="chip">Template</span>${this.renderMeta(template)}`}
      ></versola-record-header>
      ${this.renderBanner(template)}
      <div class="record-page">${this.renderGroups(template)}</div>
    `;
  }

  // ── editor ──

  private renderLocalizationEditor() {
    const codes = Object.keys(this.draftLocalizations);
    const placeholders = templatePlaceholders(this.draftPurpose);
    return html`
      <div class="editor-box">
        <h3>Message</h3>
        <p class="editor-sub">One text per active locale. ${placeholders.length === 1 ? `${placeholders[0]} is replaced` : `${placeholders.join(' and ')} are replaced`} when the message is sent. ${this.draftChannel === 'email' ? 'Email templates must contain valid HTML.' : 'SMS templates are plain text.'}</p>
        ${codes.length === 0 ? html`<div class="hint">No active locales. Activate one under System settings first.</div>` : codes.map(code => {
          const error = this.localizationErrorFor(code);
          const fieldId = `message-${code}`;
          const preview = this.previewLocales.has(code);
          return html`
            <div class="form-group">
              <div class="locale-head">
                <label for=${fieldId}>${this.localeName(code)}</label>
                ${this.draftChannel === 'email' ? html`
                  <button type="button" class="btn btn-secondary btn-sm" aria-pressed=${preview ? 'true' : 'false'} ?disabled=${!preview && !!localizationError(this.draftLocalizations[code] ?? '', this.draftPurpose, 'email')}
                    @click=${() => { const next = new Set(this.previewLocales); if (preview) next.delete(code); else next.add(code); this.previewLocales = next; }}>${preview ? 'Edit HTML' : 'Preview'}</button>` : ''}
              </div>
              ${this.draftChannel === 'email'
                ? preview
                  ? html`<div class="preview-caption">Preview as the recipient sees it</div><iframe class="email-preview" title=${`Preview for ${code}`} sandbox="" .srcdoc=${this.draftLocalizations[code]}></iframe>`
                  : html`<versola-code-editor id=${fieldId} label=${this.localeName(code)} language="html" .rows=${10} .value=${this.draftLocalizations[code]} .placeholder=${placeholderExample(this.draftPurpose, 'email')} .invalid=${!!error}
                      @code-input=${(e: CustomEvent<{ value: string }>) => this.draftLocalizations = { ...this.draftLocalizations, [code]: e.detail.value }}></versola-code-editor>`
                : html`<textarea id=${fieldId} class="message-input ${error ? 'input-error' : ''}" rows="4" .value=${this.draftLocalizations[code]} placeholder=${placeholderExample(this.draftPurpose, 'sms')}
                    aria-invalid=${error ? 'true' : nothing} aria-describedby=${error ? `${fieldId}-error` : nothing}
                    @input=${(e: Event) => this.draftLocalizations = { ...this.draftLocalizations, [code]: (e.target as HTMLTextAreaElement).value }}></textarea>`}
              ${error ? html`<div class="error-message" id=${`${fieldId}-error`} role="alert">${error}</div>` : ''}
            </div>`;
        })}
      </div>
    `;
  }

  private renderEditor() {
    const idError = this.draftIdError;
    return html`
      <form id="template-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${this.editorMode === 'create' ? html`
          <div class="editor-box">
            <h3>Basics</h3>
            <p class="editor-sub">ID, type and channel are the template's key; a client names the ID and auth picks the variant for the step.</p>
            <div class="form-group">
              <label for="template-id">Template ID *</label>
              <input type="text" id="template-id" class="compact-input mono ${idError ? 'input-error' : ''}" .value=${this.draftId} required
                aria-invalid=${idError ? 'true' : nothing} aria-describedby="template-id-hint" placeholder="e.g. login-otp"
                @input=${(e: Event) => this.draftId = (e.target as HTMLInputElement).value} />
              ${idError ? html`<div class="error-message" id="template-id-hint" role="alert">${idError}</div>`
                : html`<div class="hint" id="template-id-hint">Lowercase letters, digits, underscores and hyphens, starting with a letter.</div>`}
            </div>
            <div class="form-group">
              <label for="template-purpose">Type</label>
              <select id="template-purpose" class="compact-input" .value=${this.draftPurpose} @change=${(e: Event) => { this.draftPurpose = (e.target as HTMLSelectElement).value as TemplatePurpose; }}>
                ${PURPOSES.map(purpose => html`<option value=${purpose} ?selected=${this.draftPurpose === purpose}>${PURPOSE_LABELS[purpose]}</option>`)}
              </select>
              <div class="hint">OTP carries a sign-in code; Password carries a temporary password and how long it lasts.</div>
            </div>
            <div class="form-group">
              <label for="template-channel">Channel</label>
              <select id="template-channel" class="compact-input" .value=${this.draftChannel} @change=${(e: Event) => { this.draftChannel = (e.target as HTMLSelectElement).value as TemplateChannel; this.previewLocales = new Set(); }}>
                ${CHANNELS.map(channel => html`<option value=${channel} ?selected=${this.draftChannel === channel}>${CHANNEL_LABELS[channel]}</option>`)}
              </select>
              <div class="hint">${this.draftChannel === 'email' ? 'Email templates must contain valid HTML.' : 'SMS templates are plain text.'}</div>
            </div>
          </div>` : ''}
        ${this.renderLocalizationEditor()}
      </form>
    `;
  }

  private renderPanel(template: OtpTemplateRecord | null) {
    const mode = this.editorMode;
    if (mode === 'view' && !template) return '';
    const heading = mode === 'create' ? 'Create template' : mode === 'edit' ? 'Edit message' : template!.id;
    return html`
      <versola-side-panel kind="OTP template" .heading=${heading} .recordId=${template?.id ?? ''} idLabel="template ID" .mode=${mode} ?wide=${this.draftChannel === 'email' || template?.channel === 'email'}
        .parentLabel=${template?.id ?? ''} .subtitle=${mode === 'create' ? `In ${this.tenantId}. ID, type and channel are fixed once created.` : ''}
        .meta=${template && mode === 'view' ? this.renderMeta(template) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()}
        @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">
          ${mode === 'view' ? html`${this.renderBanner(template!)}${this.renderGroups(template!)}` : this.renderEditor()}
        </div>
        ${mode !== 'view' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="template-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : mode === 'create' ? 'Create template' : 'Save message'}</button>
          </div>` : ''}
      </versola-side-panel>
    `;
  }

  render() {
    if (!this.tenantId) {
      return html`
        <content-header title="OTP templates"></content-header>
        <versola-empty-state heading="Select a tenant" text="Select a tenant to manage its OTP templates."></versola-empty-state>
      `;
    }

    const template = this.openTemplate;
    const panel = this.renderPanel(template);
    if (template && this.route.layout === 'page') {
      return html`${this.renderRecordPage(template)}${this.editorMode === 'view' ? '' : panel}`;
    }

    return html`
      <content-header title="OTP templates" description="The text of the one-time codes and temporary passwords sent by SMS and email; a client names the template it uses.">
        ${this.templates.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Template</button>` : ''}
      </content-header>

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load OTP templates" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.templates.length === 0 ? html`
        <versola-empty-state heading="No OTP templates yet" text="Create your first template to get started">
          ${this.canManage ? html`<button class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Template</button>` : ''}
        </versola-empty-state>
      ` : html`
        ${this.renderToolbar()}
        ${this.filteredTemplates.length === 0 ? html`<versola-empty-state heading="No templates match"></versola-empty-state>` : this.renderTable()}
      `}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-otp-templates-list': VersolaOtpTemplatesList;
  }
}
