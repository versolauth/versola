import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles, iconActionStyles } from '../styles/components';
import type { OtpTemplateRecord, Locale } from '../types';
import { fetchOtpTemplates, upsertOtpTemplate, deleteOtpTemplate, fetchLocales } from '../utils/central-api';

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
      const [templates, locales] = await Promise.all([
        fetchOtpTemplates(this.tenantId),
        fetchLocales(),
      ]);
      this.templates = templates;
      this.availableLocales = locales;
    } catch (e) {
      this.errorMessage = e instanceof Error ? e.message : 'Failed to load data';
    } finally {
      this.isLoading = false;
    }
  }

  private startAdd() {
    this.editingTemplateId = 'NEW';
    this.editId = '';
    const initial = this.availableLocales[0]?.code || 'en';
    this.editLocalizations = [{ locale: initial, template: '' }];
    this.expandedLocales = new Set([initial]);
    this.editError = '';
  }

  private startEdit(template: OtpTemplateRecord) {
    this.editingTemplateId = template.id;
    this.editId = template.id;
    this.editLocalizations = Object.entries(template.localizations).map(([locale, tmpl]) => ({ locale, template: tmpl }));
    this.expandedLocales = new Set();
    if (this.editLocalizations.length === 0) {
      const initial = this.availableLocales[0]?.code || 'en';
      this.editLocalizations = [{ locale: initial, template: '' }];
      this.expandedLocales = new Set([initial]);
    }
    this.editError = '';
  }

  private cancelEdit() {
    this.editingTemplateId = null;
    this.editError = '';
  }

  private addLocalization() {
    const used = new Set(this.editLocalizations.map(l => l.locale));
    const next = this.availableLocales.find(l => !used.has(l.code))?.code || 'en';
    this.editLocalizations = [...this.editLocalizations, { locale: next, template: '' }];
    this.expandedLocales.add(next);
    this.requestUpdate();
  }

  private removeLocalization(index: number) {
    this.expandedLocales.delete(this.editLocalizations[index].locale);
    this.editLocalizations = this.editLocalizations.filter((_, i) => i !== index);
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
      if (!t) continue;
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
      await this.loadData();
      this.editingTemplateId = null;
    } catch (e) {
      this.editError = e instanceof Error ? e.message : 'Failed to save template';
    } finally {
      this.saving = false;
    }
  }

  private async handleDelete(id: string) {
    if (!this.tenantId || !confirm(`Are you sure you want to delete template "${id}"?`)) return;
    try {
      await deleteOtpTemplate(id, this.tenantId);
      await this.loadData();
    } catch (e) {
      this.errorMessage = e instanceof Error ? e.message : 'Failed to delete template';
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
        <div class="hint">Use {{code}} as a placeholder for the verification code.</div>

        ${this.editLocalizations.map((loc, i) => {
          const expanded = this.expandedLocales.has(loc.locale);
          return html`
            <div class="edit-loc-card">
              <div class="edit-loc-head" @click=${() => this.toggleLocExpand(loc.locale)}>
                <span class="edit-loc-title">${loc.locale} (${this.localeName(loc.locale)})</span>
                <div class="edit-loc-head-actions" @click=${(e: Event) => e.stopPropagation()}>
                  <button class="icon-action danger" ?disabled=${this.editLocalizations.length <= 1}
                    @click=${() => this.removeLocalization(i)} title="Remove">✕</button>
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

        <button class="btn btn-secondary"
          ?disabled=${this.editLocalizations.length >= this.availableLocales.length}
          @click=${() => this.addLocalization()}>+ Add Localization</button>

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
            <h2 class="section-title">OTP Settings</h2>
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

  render() {
    if (!this.tenantId) {
      return html`<div class="hint">Please select a tenant to manage challenges.</div>`;
    }

    if (this.editingTemplateId) return this.renderEdit();

    return html`
      <div class="page-header">
        <h1 class="page-title">Challenges</h1>
      </div>

      ${this.renderOtpSettings()}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-challenges-list': VersolaChallengesList;
  }
}
