import { LitElement, html, css, nothing, type PropertyValues } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, recordStyles } from '../styles/components';
import type { OAuthClient, Resource, ResourceEndpoint, ResourceEndpointId } from '../types';
import { createResource, deleteResource, fetchAllClients, fetchAllPermissions, fetchChallengeSettings, getResources } from '../utils/central-api';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { PERMISSIONS_UPDATED_EVENT, type PermissionsUpdatedDetail, copyToClipboard, formatResourceLabel, indexPermissionsByEndpoint } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { validateResourceId, validateResourceUri } from '../utils/validators';
import { breakHost, breakPath } from './break-text';
import { confirmResourceDelete, type IssuedSecret } from './resource-record';
import { methodBadge } from './resource-endpoints-table';
import { statusIcon } from './status-icon';
import type { DataTableColumn, RowMenuItem } from './data-table';
import './client-picker';
import './content-header';
import './data-table';
import './empty-state';
import './error-card';
import './filter-bar';
import './loading-cards';
import './nav-toggle';
import './resource-record';

type ResourceFilter = 'all' | 'internal' | 'public' | 'rotation';
type EndpointSearch = { method?: string; path: string };

/** `/path` or `METHOD /path`: a search for an endpoint rather than for a resource. */
function parseEndpointSearch(query: string): EndpointSearch | null {
  const normalized = query.trim();
  if (normalized.startsWith('/')) return { path: normalized.toLowerCase() };
  const methodAndPath = /^(GET|POST|PUT|PATCH|DELETE)\s+(\/.*)$/i.exec(normalized);
  return methodAndPath ? { method: methodAndPath[1].toUpperCase(), path: methodAndPath[2].toLowerCase() } : null;
}

const endpointLabel = (endpoint: Pick<ResourceEndpoint, 'method' | 'path'>) => `${endpoint.method} ${endpoint.path}`;

/**
 * Resources: the table of the tenant's resources (ID with its host under it, type, the first
 * endpoints, the first clients), the record page of the one opened (`?view=resources&id=<id>`,
 * `&edit=general|audience|endpoints`) and the create form (`&new=1`). This component loads what
 * the screens share (resources, clients, permissions, the ACR vocabulary) and keeps the secret
 * that create and rotate return until the operator has saved it.
 */
@customElement('versola-resources-list')
export class VersolaResourcesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private resources: Resource[] = [];
  @state() private clients: OAuthClient[] = [];
  @state() private acrVocabulary: Record<string, string[]> = {};
  /** Reverse index: endpoint id -> the permissions that grant it. */
  @state() private permissionsByEndpoint: Map<ResourceEndpointId, string[]> = new Map();
  @state() private searchQuery = '';
  @state() private filter: ResourceFilter = 'all';
  @state() private loading = false;
  @state() private error = '';
  @state() private notice = '';
  @state() private issuedSecret: IssuedSecret | null = null;
  @state() private draftId = '';
  @state() private draftUri = '';
  @state() private draftInternal = false;
  @state() private draftAudience: string[] = [];
  @state() private submitted = false;
  @state() private creating = false;
  @state() private createError = '';
  private loadSeq = 0;
  private draftBaseline = '';

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    recordStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
      }

      *,
      *::before,
      *::after {
        box-sizing: border-box;
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

      .form-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: var(--spacing-xl);
      }

      .form-title {
        font-size: var(--text-4xl);
        font-weight: 800;
        letter-spacing: -0.02em;
        color: var(--text-primary);
        margin: 0;
      }

      .form-subtitle {
        color: var(--text-secondary);
        font-size: var(--text-base);
      }

      .form-subtitle code,
      .editor-sub code,
      .hint code {
        font-family: var(--font-mono);
        font-size: 0.95em;
      }

      .form-actions {
        display: flex;
        gap: 1rem;
        justify-content: flex-end;
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-default);
      }

      input.mono {
        font-family: var(--font-mono);
      }

      .option-cards {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }

      .option-card {
        display: flex;
        flex-direction: column;
        justify-content: flex-start;
      }

      .option-card-text code {
        font-family: var(--font-mono);
      }

      .editor-box .form-group {
        margin-bottom: var(--spacing-md);
      }

      @media (max-width: 720px) {
        .option-cards {
          grid-template-columns: minmax(0, 1fr);
        }

        .form-actions {
          flex-wrap: wrap;
        }
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener(PERMISSIONS_UPDATED_EVENT, this.handlePermissionsUpdated);
  }

  disconnectedCallback() {
    window.removeEventListener(PERMISSIONS_UPDATED_EVENT, this.handlePermissionsUpdated);
    super.disconnectedCallback();
  }

  private handlePermissionsUpdated = (event: Event) => {
    const detail = (event as CustomEvent<PermissionsUpdatedDetail>).detail;
    if (!detail || detail.tenantId !== this.tenantId) return;
    this.permissionsByEndpoint = indexPermissionsByEndpoint(detail.permissions);
  };

  willUpdate(changed: PropertyValues) {
    if (changed.has('tenantId')) {
      this.issuedSecret = null;
      this.notice = '';
    }
    if (changed.has('route')) {
      const previous = changed.get('route') as RecordRoute | undefined;
      if (this.route.create && !previous?.create) this.resetDraft();
      // The secret is for the resource it was issued to: leaving that resource's page drops it.
      if (this.issuedSecret && this.route.id !== this.issuedSecret.resourceId) this.issuedSecret = null;
      if (this.route.id) this.notice = '';
    }
  }

  updated(changed: PropertyValues) {
    if (changed.has('tenantId')) void this.loadData();
  }

  private async loadData() {
    const tenantId = this.tenantId;
    const seq = ++this.loadSeq;
    if (!tenantId) {
      this.resources = [];
      this.permissionsByEndpoint = new Map();
      this.error = '';
      return;
    }
    this.loading = true;
    this.error = '';
    try {
      const [resources, challengeSettings, clients, permissions] = await Promise.all([
        getResources(tenantId),
        fetchChallengeSettings(tenantId),
        fetchAllClients(tenantId),
        fetchAllPermissions(tenantId),
      ]);
      if (seq !== this.loadSeq) return;
      this.resources = resources;
      this.clients = clients;
      this.acrVocabulary = challengeSettings?.acrVocabulary ?? {};
      this.permissionsByEndpoint = indexPermissionsByEndpoint(permissions);
    } catch (error) {
      if (seq !== this.loadSeq) return;
      this.resources = [];
      this.permissionsByEndpoint = new Map();
      this.error = error instanceof Error ? error.message : 'Failed to load resources';
    } finally {
      if (seq === this.loadSeq) this.loading = false;
    }
  }

  // ── routing ─────────────────────────────────────────────────────────────

  private get openResource(): Resource | null {
    return this.route.id ? this.resources.find(resource => resource.resourceId === this.route.id) ?? null : null;
  }

  private open(resource: Resource) {
    dispatchRecordRoute(this, { id: resource.resourceId, edit: null, create: false });
  }

  private openCreate() {
    dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null });
  }

  private close() {
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
  }

  private upsert(resource: Resource) {
    const exists = this.resources.some(candidate => candidate.resourceId === resource.resourceId);
    this.resources = (exists
      ? this.resources.map(candidate => (candidate.resourceId === resource.resourceId ? resource : candidate))
      : [...this.resources, resource]
    ).sort((a, b) => a.resourceId.localeCompare(b.resourceId, undefined, { numeric: true }));
  }

  private handleDeleted(resourceId: string) {
    this.resources = this.resources.filter(resource => resource.resourceId !== resourceId);
    this.notice = `Deleted resource ${resourceId}.`;
    this.close();
  }

  private async deleteFromList(resource: Resource) {
    if (!await confirmResourceDelete(resource, this.permissionsByEndpoint)) return;
    this.error = '';
    try {
      await deleteResource(resource.resourceId);
      this.handleDeleted(resource.resourceId);
    } catch (error) {
      this.notice = '';
      this.createError = error instanceof Error ? `Could not delete ${resource.resourceId}: ${error.message}` : `Could not delete ${resource.resourceId}`;
    }
  }

  // ── list ────────────────────────────────────────────────────────────────

  private matchesFilter(resource: Resource): boolean {
    switch (this.filter) {
      case 'internal': return resource.hasSecret;
      case 'public': return !resource.hasSecret;
      case 'rotation': return resource.hasPreviousSecret;
      default: return true;
    }
  }

  private get endpointSearch(): EndpointSearch | null {
    return parseEndpointSearch(this.searchQuery);
  }

  private matchingEndpoints(resource: Resource): ResourceEndpoint[] {
    const search = this.endpointSearch;
    if (!search) return resource.endpoints;
    return resource.endpoints.filter(endpoint => endpoint.path.toLowerCase().includes(search.path) && (!search.method || endpoint.method === search.method));
  }

  private get filteredResources(): Resource[] {
    const query = this.searchQuery.trim().toLowerCase();
    const search = this.endpointSearch;
    return this.resources.filter(resource => {
      if (!this.matchesFilter(resource)) return false;
      if (search) return this.matchingEndpoints(resource).length > 0;
      return !query
        || resource.resourceId.toLowerCase().includes(query)
        || resource.resource.toLowerCase().includes(query)
        || formatResourceLabel(resource.resource).toLowerCase().includes(query);
    });
  }

  private renderToolbar() {
    const options: Array<{ id: ResourceFilter; label: string }> = [
      { id: 'all', label: 'All' },
      { id: 'internal', label: 'Internal' },
      { id: 'public', label: 'Public' },
      { id: 'rotation', label: 'Secret rotation' },
    ];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search resources" placeholder="Search by ID, host, /path or METHOD /path"
          .value=${this.searchQuery} @input=${(e: Event) => { this.searchQuery = (e.target as HTMLInputElement).value; }} />
        <versola-filter-bar label="Filter resources" .options=${options} .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => { this.filter = e.detail.id as ResourceFilter; }}></versola-filter-bar>
      </div>`;
  }

  private renderTable() {
    const columns: DataTableColumn<Resource>[] = [
      { id: 'resource', label: 'Resource', cell: resource => html`
        <button type="button" class="row-link" aria-label=${`Open resource ${resource.resourceId}`}>
          <span class="row-title mono">${resource.resourceId}</span>
        </button>
        <div class="row-host">${breakHost(formatResourceLabel(resource.resource))}</div>` },
      { id: 'type', label: 'Type', cell: resource => html`
        <div class="cell-lines">
          <span class="chip">${resource.hasSecret ? 'Internal' : 'Public'}</span>
          ${resource.hasPreviousSecret ? html`<span class="chip warn">Secret rotation</span>` : nothing}
        </div>` },
      { id: 'endpoints', label: 'Endpoints', cell: resource => {
        const endpoints = this.matchingEndpoints(resource);
        if (endpoints.length === 0) return html`<span class="muted">None yet</span>`;
        const rest = endpoints.slice(2);
        return html`
          <div class="cell-lines">
            ${endpoints.slice(0, 2).map(endpoint => html`<span class="cell-line">${methodBadge(endpoint.method)}<span>${breakPath(endpoint.path)}</span></span>`)}
            ${rest.length ? html`<span class="muted more-endpoints" title=${rest.map(endpointLabel).join(', ')}>+${rest.length} more ${rest.length === 1 ? 'endpoint' : 'endpoints'}</span>` : nothing}
          </div>`;
      } },
      { id: 'audience', label: 'Audience', cell: resource => {
        if (resource.audience.length === 0) return html`<span class="muted">No clients</span>`;
        const rest = resource.audience.slice(2);
        return html`
          <div class="tag-list">
            ${resource.audience.slice(0, 2).map(id => html`<span class="tag" title=${id}>${id}</span>`)}
            ${rest.length ? html`<span class="chip more" title=${rest.join(', ')}>+${rest.length}</span>` : nothing}
          </div>`;
      } },
    ];
    const menuItems = (resource: Resource): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.open(resource) },
      { label: 'Copy ID', ariaLabel: `Copy resource ID ${resource.resourceId}`, onSelect: () => void copyToClipboard(resource.resourceId) },
      ...(this.canManage ? [{ label: 'Delete resource', danger: true, ariaLabel: `Delete resource ${resource.resourceId}`, onSelect: () => void this.deleteFromList(resource) }] : []),
    ];
    return html`
      <versola-data-table
        .columns=${columns}
        .rows=${this.filteredResources}
        .rowKey=${(resource: Resource) => resource.resourceId}
        .menuItems=${menuItems}
        .menuLabel=${(resource: Resource) => `Actions for ${resource.resourceId}`}
        selectable
        @row-select=${(e: CustomEvent<{ row: Resource }>) => this.open(e.detail.row)}
      ></versola-data-table>`;
  }

  private renderList() {
    return html`
      <content-header title="Resources" description="APIs that edge protects and that clients may request tokens for.">
        ${this.resources.length > 0 && this.canManage ? html`<button slot="actions" class="btn btn-primary" @click=${this.openCreate}>+ Create Resource</button>` : nothing}
      </content-header>

      ${this.notice ? html`
        <div class="banner ok" role="status">
          <div class="banner-text">${this.notice}</div>
          <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.notice = ''; }}>Dismiss</button>
        </div>` : nothing}
      ${this.createError ? html`
        <div class="banner warn" role="alert">
          <div class="banner-text"><div class="banner-title">${statusIcon('alert')}Something went wrong</div><div>${this.createError}</div></div>
          <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.createError = ''; }}>Dismiss</button>
        </div>` : nothing}

      ${this.loading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
      : this.error ? html`<versola-error-card heading="Could not load resources" .message=${this.error} @retry=${() => this.loadData()}></versola-error-card>`
      : this.resources.length === 0 ? html`
        <versola-empty-state heading="No resources yet" text=${this.canManage ? 'Create your first resource to get started' : 'No resources available.'}>
          ${this.canManage ? html`<button class="btn btn-primary" @click=${this.openCreate}>+ Create Resource</button>` : nothing}
        </versola-empty-state>`
      : html`
        ${this.renderToolbar()}
        ${this.filteredResources.length === 0
          ? html`<versola-empty-state heading="No resources match" text="Adjust the search or the filter."></versola-empty-state>`
          : this.renderTable()}`}`;
  }

  // ── create ──────────────────────────────────────────────────────────────

  private get draftSnapshot(): string {
    return JSON.stringify([this.draftId, this.draftUri, this.draftInternal, this.draftAudience]);
  }

  private resetDraft() {
    this.draftId = '';
    this.draftUri = '';
    this.draftInternal = false;
    this.draftAudience = [];
    this.submitted = false;
    this.creating = false;
    this.createError = '';
    this.draftBaseline = this.draftSnapshot;
  }

  private get idError(): string {
    const id = this.draftId.trim();
    if (!id) return this.submitted ? 'A resource ID is required.' : '';
    const result = validateResourceId(id);
    if (!result.valid) return result.error ?? 'Resource ID is invalid';
    if (this.resources.some(resource => resource.resourceId === id)) return 'A resource with this ID already exists.';
    return '';
  }

  private get uriError(): string {
    const uri = this.draftUri.trim();
    if (!uri) return this.submitted ? 'A resource URI is required.' : '';
    return validateResourceUri(uri).error ?? '';
  }

  private async cancelCreate() {
    if (this.draftSnapshot !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { create: false });
  }

  private async submitCreate(event: Event) {
    event.preventDefault();
    if (this.creating || !this.tenantId) return;
    this.submitted = true;
    if (this.idError || this.uriError) return;
    const resourceId = this.draftId.trim();
    const resource = this.draftUri.trim();
    this.creating = true;
    this.createError = '';
    try {
      const created = await createResource(this.tenantId, resourceId, resource, this.draftAudience, [], this.draftInternal);
      this.upsert({ resourceId: created.resourceId, resource, audience: [...this.draftAudience], endpoints: [], hasSecret: this.draftInternal, hasPreviousSecret: false });
      this.issuedSecret = created.secret ? { resourceId: created.resourceId, secret: created.secret, action: 'created' } : null;
      dispatchRecordRoute(this, { id: created.resourceId, edit: null, create: false });
    } catch (error) {
      this.createError = error instanceof Error ? error.message : 'Failed to create the resource';
    } finally {
      this.creating = false;
    }
  }

  private renderCreate() {
    const idError = this.idError;
    const uriError = this.uriError;
    return html`
      <div class="form-header">
        <div class="form-header-lead">
          <versola-nav-toggle></versola-nav-toggle>
          <div class="title-stack">
            <h1 class="form-title">Create resource</h1>
            <div class="form-subtitle">In <code>${this.tenantId}</code>. Endpoints are added on the resource's page once it exists.</div>
          </div>
        </div>
      </div>
      <div class="card">
        <form @submit=${this.submitCreate} novalidate>
          ${this.createError ? html`<div class="editor-error" role="alert">${this.createError}</div>` : nothing}
          <div class="editor-box">
            <h3>Basics</h3>
            <p class="editor-sub">The ID routes requests: edge forwards <code>/resources/{id}/*</code> to the URI.</p>
            <div class="form-group">
              <label for="resource-id">Resource ID *</label>
              <input id="resource-id" type="text" class="compact-input mono ${idError ? 'input-error' : ''}" autocomplete="off" spellcheck="false" placeholder="invoices-api"
                .value=${this.draftId} ?disabled=${this.creating} aria-invalid=${idError ? 'true' : 'false'} aria-describedby="resource-id-hint"
                @input=${(e: Event) => { this.draftId = (e.target as HTMLInputElement).value; }} />
              ${idError
                ? html`<div class="error-message" id="resource-id-hint" role="alert">${idError}</div>`
                : html`<div class="hint" id="resource-id-hint">Lowercase letters, digits and dashes. Fixed once created.</div>`}
            </div>
            <div class="form-group">
              <label for="resource-uri">Absolute resource URI *</label>
              <input id="resource-uri" type="url" class="compact-input ${uriError ? 'input-error' : ''}" autocomplete="off" spellcheck="false" placeholder="https://invoices.internal.example.com"
                .value=${this.draftUri} ?disabled=${this.creating} aria-invalid=${uriError ? 'true' : 'false'} aria-describedby="resource-uri-hint"
                @input=${(e: Event) => { this.draftUri = (e.target as HTMLInputElement).value; }} />
              ${uriError
                ? html`<div class="error-message" id="resource-uri-hint" role="alert">${uriError}</div>`
                : html`<div class="hint" id="resource-uri-hint">Scheme and host only, no path.</div>`}
            </div>
          </div>

          <div class="editor-box">
            <h3>Type</h3>
            <p class="editor-sub">How edge authenticates to the resource. Fixed once created.</p>
            <div class="option-cards" role="group" aria-label="Resource type">
              <button type="button" class="option-card" aria-pressed=${this.draftInternal} ?disabled=${this.creating} @click=${() => { this.draftInternal = true; }}>
                <span class="option-card-title">Internal</span>
                <span class="option-card-text">Edge calls it with a generated secret (HTTP Basic). The secret is shown once, on the new resource's page.</span>
              </button>
              <button type="button" class="option-card" aria-pressed=${!this.draftInternal} ?disabled=${this.creating} @click=${() => { this.draftInternal = false; }}>
                <span class="option-card-title">Public</span>
                <span class="option-card-text">Edge forwards the caller's own access token. The resource validates it.</span>
              </button>
            </div>
          </div>

          <div class="editor-box">
            <h3>Audience</h3>
            <p class="editor-sub">Clients that may request tokens for this resource. Optional.</p>
            <versola-client-picker label="Audience" .heading=${`Clients in ${this.tenantId}`} .clients=${this.clients} .value=${this.draftAudience}
              @change=${(e: CustomEvent<{ value: string[] }>) => { this.draftAudience = e.detail.value; }}></versola-client-picker>
          </div>

          <div class="form-actions">
            <button type="button" class="btn btn-secondary" ?disabled=${this.creating} @click=${() => this.cancelCreate()}>Cancel</button>
            <button type="submit" class="btn btn-primary" ?disabled=${this.creating}>${this.creating ? 'Creating…' : 'Create resource'}</button>
          </div>
        </form>
      </div>`;
  }

  // ── record ──────────────────────────────────────────────────────────────

  private renderRecord() {
    if (this.loading) return html`<versola-loading-cards .count=${3}></versola-loading-cards>`;
    if (this.error) {
      return html`<versola-error-card heading="Could not load resource" .message=${this.error} @retry=${() => this.loadData()}></versola-error-card>`;
    }
    const resource = this.openResource;
    if (!resource) {
      return html`
        <versola-empty-state heading="Resource not found" text="No resource has this ID in this tenant. It may have been deleted.">
          <button type="button" class="btn btn-secondary" @click=${() => this.close()}>Back to resources</button>
        </versola-empty-state>`;
    }
    return html`
      <versola-resource-record
        .resource=${resource}
        .tenantId=${this.tenantId}
        .canManage=${this.canManage}
        .edit=${this.route.edit}
        .clients=${this.clients}
        .permissionsByEndpoint=${this.permissionsByEndpoint}
        .acrVocabulary=${this.acrVocabulary}
        .issuedSecret=${this.issuedSecret}
        @resource-updated=${(e: CustomEvent<{ resource: Resource }>) => this.upsert(e.detail.resource)}
        @resource-deleted=${(e: CustomEvent<{ resourceId: string }>) => this.handleDeleted(e.detail.resourceId)}
        @secret-issued=${(e: CustomEvent<IssuedSecret>) => { this.issuedSecret = e.detail; }}
        @secret-dismiss=${() => { this.issuedSecret = null; }}
      ></versola-resource-record>`;
  }

  render() {
    if (!this.tenantId) return html`<div class="card"><div class="card-body" style="padding: var(--spacing-lg); color: var(--text-secondary)">Select a tenant to manage resources.</div></div>`;
    if (this.route.create && this.canManage) return this.loading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>` : this.renderCreate();
    if (this.route.id) return this.renderRecord();
    return this.renderList();
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-resources-list': VersolaResourcesList;
  }
}
