import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../styles/components';
import type { Locale, OAuthClaim, OAuthClient, OAuthScope } from '../types';
import { createScope, deleteScope, fetchAllClients, fetchLocales, getScopes, updateScope } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { copyToClipboard, getLocalizedDescription } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { validateScopeId } from '../utils/validators';
import { languagesCell, languagesChip, localizedValue, missingLocales } from './localized-value';
import { statusIcon } from './status-icon';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from './data-table';
import './content-header';
import './danger-card';
import './data-table';
import './empty-state';
import './error-card';
import './filter-bar';
import './loading-cards';
import './localized-text-editor';
import './record-card';
import './record-header';
import './side-panel';

type ScopeFilter = 'all' | 'claims' | 'no-claims' | 'missing';
type ScopeGroup = 'general' | 'claims';

/** A claim being edited: its id is editable while the row is new. */
type ClaimDraft = { key: number; id: string; description: Record<string, string>; existing: boolean };

/**
 * OAuth scopes: the table of the tenant's scopes and, for the one selected, the side panel
 * with its description, its claims, the clients that request it, the Fixed group and the
 * danger card. Editing a group and creating a scope happen in the same panel.
 */
@customElement('versola-scopes-list')
export class VersolaScopesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private scopes: OAuthScope[] = [];
  @state() private locales: Locale[] = [];
  @state() private searchQuery = '';
  @state() private filter: ScopeFilter = 'all';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  /** The tenant's clients, loaded the first time a panel opens, for "Requested by". */
  @state() private clients: OAuthClient[] | null = null;
  @state() private clientsError = '';
  @state() private draftId = '';
  @state() private draftDescription: Record<string, string> = { en: '' };
  @state() private draftClaims: ClaimDraft[] = [];
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @query('versola-data-table') private table?: VersolaDataTable<OAuthScope>;
  private loadRequestId = 0;
  private claimKey = 0;
  private draftKey = '';

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

      .localized-value {
        display: inline-flex;
        flex-wrap: wrap;
        gap: 0.375rem;
        align-items: center;
      }

      .claim-rows {
        display: grid;
        gap: 0.5rem;
      }

      .claim-row {
        display: grid;
        grid-template-columns: minmax(0, 9rem) minmax(0, 1fr) auto;
        gap: 0.5rem;
        align-items: start;
      }

      .claim-row .compact-input {
        max-width: none;
      }

      .claim-row .icon-button {
        margin-top: 0.5rem;
      }

      .claim-row .icon-button:hover {
        color: var(--danger);
      }

      @media (max-width: 720px) {
        .claim-row {
          grid-template-columns: minmax(0, 1fr) auto;
        }

        .claim-row versola-localized-text-editor {
          grid-column: 1 / -1;
        }
      }

      .record-page .record-card {
        margin-bottom: var(--spacing-md);
      }

      .list-hint {
        margin: var(--spacing-md) 0 0;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }
    `,
  ];

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId')) {
      this.clients = null;
      this.clientsError = '';
      void this.loadData();
    }
    if (changed.has('route') || changed.has('scopes')) {
      // The draft belongs to one editor of one record; a new editor, or the record arriving
      // after a reload with `&edit=` in the URL, starts it over.
      const mode = this.editorMode;
      const draftKey = mode === 'view' ? '' : `${mode}:${this.route.id ?? ''}:${this.route.edit ?? ''}`;
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (mode !== 'view') this.resetDraft();
      }
      if (this.route.id && this.clients === null && !this.clientsError) void this.loadClients();
    }
  }

  private async loadData() {
    if (!this.tenantId) {
      this.scopes = [];
      this.errorMessage = '';
      return;
    }
    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [scopes, locales] = await Promise.all([getScopes(this.tenantId), fetchLocales().catch(() => [] as Locale[])]);
      if (requestId !== this.loadRequestId) return;
      this.scopes = [...scopes].sort((a, b) => a.id.localeCompare(b.id));
      this.locales = locales;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.scopes = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load scopes';
    } finally {
      if (requestId === this.loadRequestId) this.isLoading = false;
    }
  }

  private async loadClients() {
    if (!this.tenantId) return;
    const tenantId = this.tenantId;
    this.clientsError = '';
    try {
      const clients = await fetchAllClients(tenantId);
      if (this.tenantId === tenantId) this.clients = clients;
    } catch (error) {
      if (this.tenantId === tenantId) this.clientsError = error instanceof Error ? error.message : 'Failed to load clients';
    }
  }

  // ── routing ──

  private get openScope(): OAuthScope | null {
    return this.route.id ? this.scopes.find(scope => scope.id === this.route.id) ?? null : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.route.edit && this.openScope ? 'edit' : 'view';
  }

  private get editGroup(): ScopeGroup | null {
    return this.route.edit === 'general' || this.route.edit === 'claims' ? this.route.edit : null;
  }

  private openView(scope: OAuthScope) {
    dispatchRecordRoute(this, { id: scope.id, edit: null, create: false });
  }

  private closePanel() {
    const key = this.route.id;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private openEditor(group: ScopeGroup) {
    dispatchRecordRoute(this, { edit: group, create: false });
  }

  private cancelEditor() {
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  private handleCreateClick() {
    dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null });
  }

  // ── draft ──

  private newClaim(claim?: OAuthClaim): ClaimDraft {
    return { key: ++this.claimKey, id: claim?.id ?? '', description: { ...(claim?.description ?? { en: '' }) }, existing: !!claim };
  }

  private resetDraft() {
    const scope = this.editorMode === 'edit' ? this.openScope : null;
    this.draftId = scope?.id ?? '';
    this.draftDescription = { ...(scope?.description ?? { en: '' }) };
    this.draftClaims = (scope?.claims ?? []).map(claim => this.newClaim(claim));
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
  }

  private get draftIdError(): string {
    const id = this.draftId.trim();
    if (!id) return this.submitted ? 'A scope ID is required.' : '';
    if (!validateScopeId(id)) return 'Lowercase letters, digits and underscores, starting with a letter.';
    if (this.scopes.some(scope => scope.id === id)) return 'A scope with this ID already exists.';
    return '';
  }

  private get draftDescriptionError(): string {
    return this.submitted && !(this.draftDescription.en ?? '').trim() ? 'A description in English is required.' : '';
  }

  private claimError(claim: ClaimDraft): string {
    if (!this.submitted) return '';
    const id = claim.id.trim();
    if (!id) return 'A claim ID is required.';
    if (this.draftClaims.filter(other => other.id.trim() === id).length > 1) return 'Claim IDs are unique within the scope.';
    if (!(claim.description.en ?? '').trim()) return 'A description in English is required.';
    return '';
  }

  private updateClaim(key: number, patch: Partial<ClaimDraft>) {
    this.draftClaims = this.draftClaims.map(claim => claim.key === key ? { ...claim, ...patch } : claim);
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (!this.tenantId || this.isSaving) return;
    this.submitted = true;
    const mode = this.editorMode;
    const group = this.editGroup;
    const idInvalid = mode === 'create' && !!this.draftIdError;
    const descriptionInvalid = (mode === 'create' || group === 'general') && !(this.draftDescription.en ?? '').trim();
    const claimsInvalid = (mode === 'create' || group === 'claims') && this.draftClaims.some(claim => this.claimError(claim));
    if (idInvalid || descriptionInvalid || claimsInvalid) return;

    const existing = this.openScope;
    const scopeId = mode === 'create' ? this.draftId.trim() : existing!.id;
    const claims: OAuthClaim[] = this.draftClaims.map(claim => ({
      id: claim.id.trim(),
      scopeId,
      description: Object.fromEntries(Object.entries(claim.description).filter(([, value]) => value.trim())),
    }));
    const description = Object.fromEntries(Object.entries(this.draftDescription).filter(([, value]) => value.trim()));
    const next: OAuthScope = mode === 'create'
      ? { id: scopeId, description, claims }
      : group === 'general'
        ? { ...existing!, description }
        : { ...existing!, claims };

    this.isSaving = true;
    this.formError = '';
    try {
      if (mode === 'create') {
        await createScope(this.tenantId, next);
        this.scopes = [...this.scopes, next].sort((a, b) => a.id.localeCompare(b.id));
        dispatchRecordRoute(this, { id: next.id, edit: null, create: false });
      } else {
        await updateScope(this.tenantId, existing!, next);
        this.scopes = this.scopes.map(scope => scope.id === next.id ? next : scope);
        this.cancelEditor();
      }
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save scope';
    } finally {
      this.isSaving = false;
    }
  }

  private async handleDelete(scope: OAuthScope) {
    if (!this.tenantId) return;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete scope',
      messagePrefix: 'Delete scope ',
      messageSubject: scope.id,
      messageSuffix: '? Clients can no longer request it.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    try {
      await deleteScope(this.tenantId, scope.id);
      this.scopes = this.scopes.filter(candidate => candidate.id !== scope.id);
      if (this.route.id === scope.id) dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to delete scope';
    }
  }

  // ── list ──

  private matchesFilter(scope: OAuthScope, filter: ScopeFilter = this.filter): boolean {
    switch (filter) {
      case 'claims': return scope.claims.length > 0;
      case 'no-claims': return scope.claims.length === 0;
      case 'missing': return this.hasMissingTranslation(scope);
      default: return true;
    }
  }

  private hasMissingTranslation(scope: OAuthScope): boolean {
    return missingLocales(scope.description, this.locales).length > 0
      || scope.claims.some(claim => missingLocales(claim.description, this.locales).length > 0);
  }

  private get filteredScopes(): OAuthScope[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.scopes.filter(scope => this.matchesFilter(scope)
      && (!query || scope.id.toLowerCase().includes(query) || getLocalizedDescription(scope.description).toLowerCase().includes(query)));
  }

  private count(filter: ScopeFilter): number {
    return this.scopes.filter(scope => this.matchesFilter(scope, filter)).length;
  }

  private renderToolbar() {
    const options: Array<{ id: ScopeFilter; label: string }> = [
      { id: 'all', label: 'All' },
      { id: 'claims', label: 'With claims' },
      { id: 'no-claims', label: 'No claims' },
      ...(this.locales.length ? [{ id: 'missing' as const, label: 'Missing translations' }] : []),
    ];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search scopes" placeholder="Search scopes by ID or description"
          .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
        <versola-filter-bar label="Filter scopes"
          .options=${options.map(option => ({ ...option, count: this.count(option.id) }))}
          .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => this.filter = e.detail.id as ScopeFilter}></versola-filter-bar>
      </div>
    `;
  }

  private renderTable() {
    const columns: DataTableColumn<OAuthScope>[] = [
      { id: 'scope', label: 'Scope', cell: scope => html`
        <button type="button" class="row-link" aria-label=${`Open scope ${scope.id}`}>
          <span class="row-title">${getLocalizedDescription(scope.description) || scope.id}</span>
          <span class="row-id">${scope.id}</span>
        </button>` },
      { id: 'claims', label: 'Claims', cell: scope => scope.claims.length
        ? html`<span title=${scope.claims.map(claim => claim.id).join(', ')}>${scope.claims.length}</span>`
        : html`<span class="muted">0</span>` },
      ...(this.locales.length ? [{ id: 'languages', label: 'Languages', cell: (scope: OAuthScope) => languagesCell(scope.description, this.locales) }] : []),
    ];
    const menuItems = (scope: OAuthScope): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(scope) },
      { label: 'Copy scope ID', onSelect: () => void copyToClipboard(scope.id) },
      ...(this.canManage ? [{ label: 'Delete scope', danger: true, ariaLabel: `Delete scope ${scope.id}`, onSelect: () => void this.handleDelete(scope) }] : []),
    ];
    return html`
      <versola-data-table selectable
        .columns=${columns}
        .rows=${this.filteredScopes}
        .rowKey=${(scope: OAuthScope) => scope.id}
        .selectedKey=${this.route.id}
        .menuItems=${menuItems}
        .menuLabel=${(scope: OAuthScope) => `Actions for ${scope.id}`}
        @row-select=${(e: CustomEvent<{ row: OAuthScope }>) => this.openView(e.detail.row)}
      ></versola-data-table>
    `;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private renderGroups(scope: OAuthScope) {
    const requestedBy = this.clients?.filter(client => client.scope.includes(scope.id)) ?? null;
    return html`
      <versola-record-card heading="General" ?editable=${this.canManage} .body=${html`
        ${this.row('Description', localizedValue(scope.description, this.locales))}
      `} @edit=${() => this.openEditor('general')}></versola-record-card>

      <versola-record-card heading="Claims" subheading="Released into the ID token and /userinfo when the scope is granted." ?editable=${this.canManage}
        .body=${scope.claims.length ? scope.claims.map(claim => this.row(claim.id, localizedValue(claim.description, this.locales)))
          : html`<div class="record-row"><div class="record-value muted">No claims. The scope releases nothing on its own.</div></div>`}
        @edit=${() => this.openEditor('claims')}></versola-record-card>

      <versola-record-card heading="Requested by" subheading="Clients that list this scope. Change it from the client." .body=${html`
        ${this.row('Clients', this.clientsError
          ? html`<span class="muted">Could not load clients.</span> <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.loadClients()}>Retry</button>`
          : requestedBy === null ? html`<span class="muted">Loading…</span>`
          : requestedBy.length ? html`<div class="tag-list">${requestedBy.map(client => html`<span class="tag">${client.id}</span>`)}</div>`
          : html`<span class="muted">No client requests this scope.</span>`)}
      `}></versola-record-card>

      <versola-record-card heading="Fixed" fixedLabel="Fixed" .body=${html`
        ${this.row('Scope ID', html`<span class="mono">${scope.id}</span>`, 'Set at creation; clients request it by this value')}
      `}></versola-record-card>

      ${this.canManage ? html`
        <versola-danger-card heading="Delete scope" text="Clients can no longer request it. This can't be undone."
          actionLabel="Delete scope" .actionAriaLabel=${`Delete scope ${scope.id}`}
          @action=${() => this.handleDelete(scope)}></versola-danger-card>` : ''}
    `;
  }

  private renderBanner(scope: OAuthScope) {
    const missing = new Set<string>([
      ...missingLocales(scope.description, this.locales),
      ...scope.claims.flatMap(claim => missingLocales(claim.description, this.locales)),
    ]);
    if (missing.size === 0) return '';
    return html`
      <div class="banner warn" role="status">
        <div class="banner-text">
          <div class="banner-title">${statusIcon('alert')}Missing translations for ${[...missing].join(', ')}</div>
          <div>Edit General or Claims to add them.</div>
        </div>
      </div>
    `;
  }

  private renderMeta(scope: OAuthScope) {
    return html`
      <span class="chip">${scope.claims.length} ${scope.claims.length === 1 ? 'claim' : 'claims'}</span>
      ${languagesChip(scope.description, this.locales)}
    `;
  }

  private renderRecordPage(scope: OAuthScope) {
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Scopes', onSelect: () => this.closePanel() }, { label: getLocalizedDescription(scope.description) || scope.id }]}
        .heading=${getLocalizedDescription(scope.description) || scope.id}
        .recordId=${scope.id} idLabel="scope ID"
        .meta=${html`<span class="chip">Scope</span>${this.renderMeta(scope)}`}
      ></versola-record-header>
      ${this.renderBanner(scope)}
      <div class="record-page">${this.renderGroups(scope)}</div>
    `;
  }

  // ── editor ──

  private renderDescriptionField(label: string) {
    const error = this.draftDescriptionError;
    return html`
      <div class="form-group">
        <label for="scope-description">${label} *</label>
        <versola-localized-text-editor fieldId="scope-description" label="Description" .showLabel=${false} .required=${true}
          .selectorBelowInput=${true} .value=${this.draftDescription} .locales=${this.locales}
          @localized-change=${(e: CustomEvent<{ value: Record<string, string> }>) => this.draftDescription = e.detail.value}
        ></versola-localized-text-editor>
        ${error ? html`<div class="error-message" role="alert">${error}</div>` : html`<div class="hint">English is required; other active locales are optional.</div>`}
      </div>
    `;
  }

  private renderClaimsEditor() {
    return html`
      <div class="editor-box">
        <h3>Claims</h3>
        <p class="editor-sub">Claim IDs are unique within the scope. The English description is required; other active locales are optional.</p>
        <div class="claim-rows">
          ${this.draftClaims.map(claim => {
            const error = this.claimError(claim);
            return html`
              <div class="claim-row">
                <div>
                  <input type="text" class="compact-input mono ${error && !claim.id.trim() ? 'input-error' : ''}" aria-label="Claim ID" placeholder="Claim ID"
                    id=${`claim-id-${claim.key}`} .value=${claim.id} ?readonly=${claim.existing}
                    aria-invalid=${error ? 'true' : nothing} aria-describedby=${error ? `claim-error-${claim.key}` : nothing}
                    @input=${(e: Event) => this.updateClaim(claim.key, { id: (e.target as HTMLInputElement).value })} />
                </div>
                <versola-localized-text-editor fieldId=${`claim-description-${claim.key}`} label="Description" .showLabel=${false}
                  .required=${true} .selectorBelowInput=${true} .value=${claim.description} .locales=${this.locales}
                  @localized-change=${(e: CustomEvent<{ value: Record<string, string> }>) => this.updateClaim(claim.key, { description: e.detail.value })}
                ></versola-localized-text-editor>
                <button type="button" class="icon-button" aria-label=${`Remove claim ${claim.id || 'new'}`} title="Remove claim"
                  @click=${() => this.draftClaims = this.draftClaims.filter(other => other.key !== claim.key)}>${statusIcon('x', 'icon')}</button>
                ${error ? html`<div class="error-message" id=${`claim-error-${claim.key}`} role="alert" style="grid-column: 1 / -1; margin: 0">${error}</div>` : ''}
              </div>
            `;
          })}
        </div>
        <button type="button" class="btn btn-secondary btn-sm" style="margin-top: 0.75rem" @click=${() => this.draftClaims = [...this.draftClaims, this.newClaim()]}>+ Add claim</button>
      </div>
    `;
  }

  private renderEditor() {
    const mode = this.editorMode;
    const group = this.editGroup;
    const idError = this.draftIdError;
    return html`
      <form id="scope-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${mode === 'create' ? html`
          <div class="editor-box">
            <h3>Basics</h3>
            <p class="editor-sub">The ID is what clients request; the description is what people read on the consent screen.</p>
            <div class="form-group">
              <label for="scope-id">Scope ID *</label>
              <input type="text" id="scope-id" class="compact-input mono ${idError ? 'input-error' : ''}" .value=${this.draftId} required
                aria-invalid=${idError ? 'true' : nothing} aria-describedby="scope-id-hint" placeholder="e.g. profile"
                @input=${(e: Event) => this.draftId = (e.target as HTMLInputElement).value} />
              ${idError ? html`<div class="error-message" id="scope-id-hint" role="alert">${idError}</div>`
                : html`<div class="hint" id="scope-id-hint">Lowercase letters, digits and underscores, starting with a letter.</div>`}
            </div>
            ${this.renderDescriptionField('Description')}
          </div>
          ${this.renderClaimsEditor()}
        ` : group === 'general' ? html`
          <div class="editor-box">
            <h3>General</h3>
            <p class="editor-sub">What the consent screen shows for this scope.</p>
            ${this.renderDescriptionField('Description')}
          </div>
        ` : this.renderClaimsEditor()}
      </form>
    `;
  }

  private renderPanel(scope: OAuthScope | null) {
    const mode = this.editorMode;
    if (mode === 'view' && !scope) return '';
    const title = scope ? getLocalizedDescription(scope.description) || scope.id : '';
    const heading = mode === 'create' ? 'Create scope' : mode === 'edit' ? `Edit ${this.editGroup === 'general' ? 'general' : 'claims'}` : title;
    const submitLabel = mode === 'create' ? 'Create scope' : this.editGroup === 'general' ? 'Save general' : 'Save claims';
    return html`
      <versola-side-panel kind="Scope" .heading=${heading} .recordId=${scope?.id ?? ''} idLabel="scope ID" .mode=${mode}
        .parentLabel=${title} .subtitle=${mode === 'create' ? `In ${this.tenantId}. The ID is fixed once created.` : ''}
        .meta=${scope && mode === 'view' ? this.renderMeta(scope) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()}
        @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">
          ${mode === 'view' ? html`${this.renderBanner(scope!)}${this.renderGroups(scope!)}` : this.renderEditor()}
        </div>
        ${mode !== 'view' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="scope-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : submitLabel}</button>
          </div>` : ''}
      </versola-side-panel>
    `;
  }

  render() {
    if (!this.tenantId) {
      return html`
        <content-header title="OAuth Scopes"></content-header>
        <versola-empty-state heading="Select a tenant" text="Select a tenant to manage OAuth scopes."></versola-empty-state>
      `;
    }

    const scope = this.openScope;
    const panel = this.renderPanel(scope);
    if (scope && this.route.layout === 'page') {
      return html`${this.renderRecordPage(scope)}${this.editorMode === 'view' ? '' : panel}`;
    }

    return html`
      <content-header title="OAuth Scopes" description="What a client may request; each scope releases its claims into the ID token and /userinfo.">
        ${this.scopes.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Scope</button>` : ''}
      </content-header>

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load OAuth scopes" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.scopes.length === 0 ? html`
        <versola-empty-state heading="No OAuth scopes yet" text="Create your first OAuth scope to get started">
          ${this.canManage ? html`<button class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Scope</button>` : ''}
        </versola-empty-state>
      ` : html`
        ${this.renderToolbar()}
        ${this.filteredScopes.length === 0 ? html`<versola-empty-state heading="No scopes match"></versola-empty-state>` : this.renderTable()}
      `}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-scopes-list': VersolaScopesList;
  }
}
