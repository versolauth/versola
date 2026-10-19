import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../styles/components';
import { codeHighlightStyles } from '../styles/code-highlight';
import type { AuthorizationDetailType, Locale } from '../types';
import { createAuthorizationDetailType, deleteAuthorizationDetailType, fetchAuthorizationDetailTypes, fetchLocales, updateAuthorizationDetailType } from '../utils/central-api';
import { tokenize } from '../utils/code-highlight';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { copyToClipboard, getLocalizedDescription } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { validateAuthorizationDetailType } from '../utils/validators';
import { languagesCell, languagesChip, localizedValue, missingLocales } from './localized-value';
import { statusIcon } from './status-icon';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from './data-table';
import './code-editor';
import './content-header';
import './danger-card';
import './data-table';
import './empty-state';
import './error-card';
import './loading-cards';
import './localized-text-editor';
import './record-card';
import './record-header';
import './side-panel';

type DetailGroup = 'general' | 'schema';

const DEFAULT_SCHEMA = `{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "type": { "type": "string" },
    "locations": { "type": "array", "items": { "type": "string" } },
    "actions": { "type": "array", "items": { "type": "string" } }
  },
  "required": ["type"],
  "unevaluatedProperties": false
}`;

/** `3 properties · required: type, actions`, what the list says about a schema. */
export function schemaSummary(schema: Record<string, unknown>): string {
  const properties = schema.properties && typeof schema.properties === 'object' ? Object.keys(schema.properties as object) : [];
  const required = Array.isArray(schema.required) ? (schema.required as unknown[]).filter((value): value is string => typeof value === 'string') : [];
  const count = `${properties.length} ${properties.length === 1 ? 'property' : 'properties'}`;
  return required.length ? `${count} · required: ${required.join(', ')}` : count;
}

function schemaTextError(text: string): string {
  try {
    const parsed: unknown = JSON.parse(text);
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed) ? '' : 'The schema is a JSON object.';
  } catch (error) {
    return error instanceof Error ? `Invalid JSON: ${error.message}` : 'Invalid JSON.';
  }
}

/**
 * RFC 9396 authorization detail types: the tenant's table and, for the one selected, the side
 * panel with its description, its JSON Schema, the Fixed type and the danger card. Editing a
 * group and creating a type happen in the same (wide) panel.
 */
@customElement('versola-authorization-details-list')
export class VersolaAuthorizationDetailsList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private types: AuthorizationDetailType[] = [];
  @state() private locales: Locale[] = [];
  @state() private searchQuery = '';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private draftType = '';
  @state() private draftDescription: Record<string, string> = { en: '' };
  @state() private draftSchema = DEFAULT_SCHEMA;
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @query('versola-data-table') private table?: VersolaDataTable<AuthorizationDetailType>;
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
    codeHighlightStyles,
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

      .schema-json {
        margin: 0;
        padding: var(--spacing-md);
        background: var(--code-bg);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        line-height: 1.6;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        color: var(--text-primary);
      }

      .record-card > .schema-json {
        margin: var(--spacing-md) var(--spacing-lg) var(--spacing-lg);
      }

      .record-compact .record-card > .schema-json {
        margin: var(--spacing-md);
      }

      .record-page .record-card {
        margin-bottom: var(--spacing-md);
      }
    `,
  ];

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId')) void this.loadData();
    if (changed.has('route') || changed.has('types')) {
      const mode = this.editorMode;
      const draftKey = mode === 'view' ? '' : `${mode}:${this.route.id ?? ''}:${this.route.edit ?? ''}`;
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (mode !== 'view') this.resetDraft();
      }
    }
  }

  private async loadData() {
    if (!this.tenantId) {
      this.types = [];
      this.errorMessage = '';
      return;
    }
    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [types, locales] = await Promise.all([fetchAuthorizationDetailTypes(this.tenantId), fetchLocales().catch(() => [] as Locale[])]);
      if (requestId !== this.loadRequestId) return;
      this.types = [...types].sort((a, b) => a.type.localeCompare(b.type));
      this.locales = locales;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.types = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load authorization detail types';
    } finally {
      if (requestId === this.loadRequestId) this.isLoading = false;
    }
  }

  // ── routing ──

  private get openType(): AuthorizationDetailType | null {
    return this.route.id ? this.types.find(type => type.type === this.route.id) ?? null : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.editGroup && this.openType ? 'edit' : 'view';
  }

  private get editGroup(): DetailGroup | null {
    return this.route.edit === 'general' || this.route.edit === 'schema' ? this.route.edit : null;
  }

  private openView(type: AuthorizationDetailType) {
    dispatchRecordRoute(this, { id: type.type, edit: null, create: false });
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
    return JSON.stringify([this.draftType, this.draftDescription, this.draftSchema]);
  }

  private resetDraft() {
    const type = this.editorMode === 'edit' ? this.openType : null;
    this.draftType = type?.type ?? '';
    this.draftDescription = { ...(type?.description ?? { en: '' }) };
    this.draftSchema = type ? JSON.stringify(type.schema, null, 2) : DEFAULT_SCHEMA;
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
    this.draftBaseline = this.draftSnapshot;
  }

  private get draftTypeError(): string {
    const type = this.draftType.trim();
    if (!type) return this.submitted ? 'A type is required.' : '';
    if (!validateAuthorizationDetailType(type)) return 'Lowercase letters, digits and underscores, starting with a letter.';
    if (this.types.some(candidate => candidate.type === type)) return 'A type with this value already exists.';
    return '';
  }

  private get draftDescriptionError(): string {
    return this.submitted && !(this.draftDescription.en ?? '').trim() ? 'A description in English is required.' : '';
  }

  private get draftSchemaError(): string {
    return schemaTextError(this.draftSchema);
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (!this.tenantId || this.isSaving) return;
    this.submitted = true;
    const mode = this.editorMode;
    const group = this.editGroup;
    const typeInvalid = mode === 'create' && !!this.draftTypeError;
    const descriptionInvalid = (mode === 'create' || group === 'general') && !(this.draftDescription.en ?? '').trim();
    const schemaInvalid = (mode === 'create' || group === 'schema') && !!this.draftSchemaError;
    if (typeInvalid || descriptionInvalid || schemaInvalid) return;

    const existing = this.openType;
    const description = Object.fromEntries(Object.entries(this.draftDescription).filter(([, value]) => value.trim()));
    const next: AuthorizationDetailType = mode === 'create'
      ? { type: this.draftType.trim(), description, schema: JSON.parse(this.draftSchema) as Record<string, unknown> }
      : group === 'general' ? { ...existing!, description } : { ...existing!, schema: JSON.parse(this.draftSchema) as Record<string, unknown> };

    this.isSaving = true;
    this.formError = '';
    try {
      if (mode === 'create') {
        await createAuthorizationDetailType(this.tenantId, next);
        this.types = [...this.types, next].sort((a, b) => a.type.localeCompare(b.type));
        dispatchRecordRoute(this, { id: next.type, edit: null, create: false });
      } else {
        await updateAuthorizationDetailType(this.tenantId, next);
        this.types = this.types.map(type => type.type === next.type ? next : type);
        dispatchRecordRoute(this, { edit: null, create: false });
      }
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save authorization detail type';
    } finally {
      this.isSaving = false;
    }
  }

  private async handleDelete(type: AuthorizationDetailType) {
    if (!this.tenantId) return;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete type',
      messagePrefix: 'Delete authorization detail type ',
      messageSubject: type.type,
      messageSuffix: '? Clients can no longer request it in authorization_details.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    try {
      await deleteAuthorizationDetailType(this.tenantId, type.type);
      this.types = this.types.filter(candidate => candidate.type !== type.type);
      if (this.route.id === type.type) dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to delete authorization detail type';
    }
  }

  // ── list ──

  private get filteredTypes(): AuthorizationDetailType[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.types.filter(type => !query || type.type.toLowerCase().includes(query) || getLocalizedDescription(type.description).toLowerCase().includes(query));
  }

  private renderTable() {
    const columns: DataTableColumn<AuthorizationDetailType>[] = [
      { id: 'type', label: 'Type', cell: type => html`
        <button type="button" class="row-link" aria-label=${`Open type ${getLocalizedDescription(type.description) || type.type} ${type.type}`}>
          <span class="row-title">${getLocalizedDescription(type.description) || type.type}</span>
          <span class="row-id">${type.type}</span>
        </button>` },
      { id: 'schema', label: 'Schema', cell: type => schemaSummary(type.schema) },
      ...(this.locales.length ? [{ id: 'languages', label: 'Languages', cell: (type: AuthorizationDetailType) => languagesCell(type.description, this.locales) }] : []),
    ];
    const menuItems = (type: AuthorizationDetailType): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(type) },
      { label: 'Copy type', onSelect: () => void copyToClipboard(type.type) },
      ...(this.canManage ? [{ label: 'Delete type', danger: true, ariaLabel: `Delete type ${type.type}`, onSelect: () => void this.handleDelete(type) }] : []),
    ];
    return html`
      <versola-data-table selectable
        .columns=${columns}
        .rows=${this.filteredTypes}
        .rowKey=${(type: AuthorizationDetailType) => type.type}
        .selectedKey=${this.route.id}
        .menuItems=${menuItems}
        .menuLabel=${(type: AuthorizationDetailType) => `Actions for ${type.type}`}
        @row-select=${(e: CustomEvent<{ row: AuthorizationDetailType }>) => this.openView(e.detail.row)}
      ></versola-data-table>
    `;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private renderSchema(schema: Record<string, unknown>) {
    const json = JSON.stringify(schema, null, 2);
    return html`<pre class="schema-json" aria-label="JSON Schema">${tokenize(json, 'json').map(token => html`<span class=${`ch-tok ch-${token.kind}`}>${token.value}</span>`)}</pre>`;
  }

  private renderGroups(type: AuthorizationDetailType) {
    return html`
      <versola-record-card heading="General" ?editable=${this.canManage} .body=${html`
        ${this.row('Description', localizedValue(type.description, this.locales))}
      `} @edit=${() => dispatchRecordRoute(this, { edit: 'general', create: false })}></versola-record-card>

      <versola-record-card heading="JSON Schema" subheading=${`Detail objects of this type are validated against it. ${schemaSummary(type.schema)}.`} ?editable=${this.canManage}
        .body=${this.renderSchema(type.schema)}
        @edit=${() => dispatchRecordRoute(this, { edit: 'schema', create: false })}></versola-record-card>

      <versola-record-card heading="Fixed" fixedLabel="Fixed" .body=${html`
        ${this.row('Type', html`<span class="mono">${type.type}</span>`, 'Set at creation; clients send it as the type member')}
      `}></versola-record-card>

      ${this.canManage ? html`
        <versola-danger-card heading="Delete type" text="Clients can no longer request it in authorization_details. This can't be undone."
          actionLabel="Delete type" .actionAriaLabel=${`Delete type ${type.type}`}
          @action=${() => this.handleDelete(type)}></versola-danger-card>` : ''}
    `;
  }

  private renderBanner(type: AuthorizationDetailType) {
    const missing = missingLocales(type.description, this.locales);
    if (missing.length === 0) return '';
    return html`
      <div class="banner warn" role="status">
        <div class="banner-text">
          <div class="banner-title">${statusIcon('alert')}Missing translations for ${missing.join(', ')}</div>
          <div>Edit General to add them.</div>
        </div>
      </div>
    `;
  }

  private renderMeta(type: AuthorizationDetailType) {
    return html`<span class="chip">RFC 9396 type</span>${languagesChip(type.description, this.locales)}`;
  }

  private renderRecordPage(type: AuthorizationDetailType) {
    const title = getLocalizedDescription(type.description) || type.type;
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Authorization details', onSelect: () => this.closePanel() }, { label: title }]}
        .heading=${title}
        .recordId=${type.type} idLabel="type"
        .meta=${this.renderMeta(type)}
      ></versola-record-header>
      ${this.renderBanner(type)}
      <div class="record-page">${this.renderGroups(type)}</div>
    `;
  }

  // ── editor ──

  private renderDescriptionField() {
    const error = this.draftDescriptionError;
    return html`
      <div class="form-group">
        <label for="detail-description">Description *</label>
        <versola-localized-text-editor fieldId="detail-description" label="Description" .showLabel=${false} .required=${true}
          .selectorBelowInput=${true} .value=${this.draftDescription} .locales=${this.locales}
          @localized-change=${(e: CustomEvent<{ value: Record<string, string> }>) => this.draftDescription = e.detail.value}
        ></versola-localized-text-editor>
        ${error ? html`<div class="error-message" role="alert">${error}</div>` : html`<div class="hint">English is required; other active locales are optional.</div>`}
      </div>
    `;
  }

  private renderSchemaEditor() {
    const error = this.submitted || this.draftSchema !== DEFAULT_SCHEMA ? this.draftSchemaError : '';
    return html`
      <div class="editor-box">
        <h3>JSON Schema</h3>
        <p class="editor-sub">JSON Schema 2020-12. Detail objects of this type are rejected unless they validate against it; <code>unevaluatedProperties: false</code> rejects unknown members.</p>
        <div class="form-group">
          <label for="detail-schema">Schema *</label>
          <versola-code-editor id="detail-schema" label="Schema" language="json" .rows=${16} .value=${this.draftSchema} .invalid=${!!error}
            @code-input=${(e: CustomEvent<{ value: string }>) => this.draftSchema = e.detail.value}></versola-code-editor>
          ${error ? html`<div class="error-message" role="alert">${error}</div>` : ''}
          ${this.schemaServerError ? html`<div class="error-message" role="alert" aria-live="assertive" data-testid="schema-server-error">${this.formError}</div>` : ''}
        </div>
      </div>
    `;
  }

  /** What central says is wrong with the schema is read under the editor, not at the top of the form. */
  private get schemaServerError(): boolean {
    return this.formError.startsWith('Invalid JSON Schema');
  }

  private renderEditor() {
    const mode = this.editorMode;
    const group = this.editGroup;
    const typeError = this.draftTypeError;
    return html`
      <form id="detail-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError && !this.schemaServerError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${mode === 'create' ? html`
          <div class="editor-box">
            <h3>Basics</h3>
            <p class="editor-sub">The type is what clients send; the description is what the consent screen shows.</p>
            <div class="form-group">
              <label for="detail-type">Type *</label>
              <input type="text" id="detail-type" class="compact-input mono ${typeError ? 'input-error' : ''}" .value=${this.draftType} required
                aria-invalid=${typeError ? 'true' : nothing} aria-describedby="detail-type-hint" placeholder="e.g. payment_initiation"
                @input=${(e: Event) => this.draftType = (e.target as HTMLInputElement).value} />
              ${typeError ? html`<div class="error-message" id="detail-type-hint" role="alert">${typeError}</div>`
                : html`<div class="hint" id="detail-type-hint">Lowercase letters, digits and underscores, starting with a letter.</div>`}
            </div>
            ${this.renderDescriptionField()}
          </div>
          ${this.renderSchemaEditor()}
        ` : group === 'general' ? html`
          <div class="editor-box">
            <h3>General</h3>
            <p class="editor-sub">What the consent screen shows for this type.</p>
            ${this.renderDescriptionField()}
          </div>
        ` : this.renderSchemaEditor()}
      </form>
    `;
  }

  private renderPanel(type: AuthorizationDetailType | null) {
    const mode = this.editorMode;
    if (mode === 'view' && !type) return '';
    const title = type ? getLocalizedDescription(type.description) || type.type : '';
    const heading = mode === 'create' ? 'Create type' : mode === 'edit' ? `Edit ${this.editGroup === 'general' ? 'general' : 'JSON Schema'}` : title;
    const submitLabel = mode === 'create' ? 'Create type' : this.editGroup === 'general' ? 'Save general' : 'Save JSON Schema';
    return html`
      <versola-side-panel kind="Authorization detail type" .heading=${heading} .recordId=${type?.type ?? ''} idLabel="type" .mode=${mode} wide
        .parentLabel=${title} .subtitle=${mode === 'create' ? `In ${this.tenantId}. The type is fixed once created.` : ''}
        .meta=${type && mode === 'view' ? this.renderMeta(type) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()}
        @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">
          ${mode === 'view' ? html`${this.renderBanner(type!)}${this.renderGroups(type!)}` : this.renderEditor()}
        </div>
        ${mode !== 'view' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="detail-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : submitLabel}</button>
          </div>` : ''}
      </versola-side-panel>
    `;
  }

  render() {
    if (!this.tenantId) {
      return html`
        <content-header title="Authorization details"></content-header>
        <versola-empty-state heading="Select a tenant" text="Select a tenant to manage its authorization detail types."></versola-empty-state>
      `;
    }

    const type = this.openType;
    const panel = this.renderPanel(type);
    if (type && this.route.layout === 'page') {
      return html`${this.renderRecordPage(type)}${this.editorMode === 'view' ? '' : panel}`;
    }

    return html`
      <content-header title="Authorization details" description="The RFC 9396 types a client may request in authorization_details, each with the JSON Schema its objects are validated against.">
        ${this.types.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Type</button>` : ''}
      </content-header>

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load authorization detail types" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.types.length === 0 ? html`
        <versola-empty-state heading="No authorization detail types yet" text="Register a type before clients can use it in authorization_details">
          ${this.canManage ? html`<button class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Type</button>` : ''}
        </versola-empty-state>
      ` : html`
        <div class="list-toolbar">
          <input class="search-input" type="search" aria-label="Search types" placeholder="Type or description"
            .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
        </div>
        ${this.filteredTypes.length === 0 ? html`<versola-empty-state heading="No types match"></versola-empty-state>` : this.renderTable()}
      `}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-authorization-details-list': VersolaAuthorizationDetailsList;
  }
}
