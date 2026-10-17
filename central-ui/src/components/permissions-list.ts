import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, methodBadgeStyles, recordStyles, tagStyles } from '../styles/components';
import type { Locale, OAuthClient, Permission, Resource, ResourceEndpointId, Role } from '../types';
import { createPermission, deletePermission, fetchAllClients, fetchLocales, getPermissions, getResources, getRoles, updatePermission } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { PERMISSIONS_UPDATED_EVENT, copyToClipboard, formatResourceLabel, getLocalizedDescription } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { validatePermission } from '../utils/validators';
import { localizedValue } from './localized-value';
import { statusIcon } from './status-icon';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from './data-table';
import './content-header';
import './danger-card';
import './data-table';
import './empty-state';
import './endpoint-picker';
import './error-card';
import './filter-bar';
import './loading-cards';
import './localized-text-editor';
import './record-card';
import './record-header';
import './side-panel';

type PermissionGroup = 'general' | 'endpoints';
type ResolvedEndpoint = { method: string; path: string };
type ResourceEndpoints = { resource: Resource; endpoints: ResolvedEndpoint[] };

/**
 * Permissions: the table of the tenant's permissions and, for the one selected, the side panel
 * with its description, its endpoints grouped per resource, who grants it (roles and service
 * clients), the Fixed group and the danger card. Editing a group and creating a permission
 * happen in the same panel; the endpoints editor and the create form open the wide panel.
 */
@customElement('versola-permissions-list')
export class VersolaPermissionsList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private permissions: Permission[] = [];
  @state() private resources: Resource[] = [];
  @state() private locales: Locale[] = [];
  @state() private searchQuery = '';
  /** `all`, `unlinked`, `detached`, or `resource:<resourceId>`. */
  @state() private filter = 'all';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  /** Roles and clients, loaded the first time a panel opens, for "Granted by". */
  @state() private roles: Role[] | null = null;
  @state() private clients: OAuthClient[] | null = null;
  @state() private grantedByError = '';
  @state() private draftId = '';
  @state() private draftDescription: Record<string, string> = { en: '' };
  @state() private draftEndpointIds: ResourceEndpointId[] = [];
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @query('versola-data-table') private table?: VersolaDataTable<Permission>;
  private loadRequestId = 0;
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
    methodBadgeStyles,
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

      .endpoint-list {
        display: grid;
        gap: 0.375rem;
      }

      .endpoint {
        display: flex;
        gap: 0.625rem;
        align-items: center;
        flex-wrap: wrap;
      }

      .record-page .record-card {
        margin-bottom: var(--spacing-md);
      }
    `,
  ];

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId')) {
      this.roles = null;
      this.clients = null;
      this.grantedByError = '';
      void this.loadData();
    }
    if (changed.has('route') || changed.has('permissions')) {
      const mode = this.editorMode;
      const draftKey = mode === 'view' ? '' : `${mode}:${this.route.id ?? ''}:${this.route.edit ?? ''}`;
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (mode !== 'view') this.resetDraft();
      }
      if (this.route.id && this.roles === null && !this.grantedByError) void this.loadGrantedBy();
    }
  }

  private async loadData() {
    if (!this.tenantId) {
      this.permissions = [];
      this.resources = [];
      this.errorMessage = '';
      return;
    }
    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [permissions, resources, locales] = await Promise.all([
        getPermissions(this.tenantId),
        getResources(this.tenantId).catch(() => [] as Resource[]),
        fetchLocales().catch(() => [] as Locale[]),
      ]);
      if (requestId !== this.loadRequestId) return;
      this.permissions = [...permissions].sort((a, b) => a.id.localeCompare(b.id));
      this.resources = resources;
      this.locales = locales;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.permissions = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load permissions';
    } finally {
      if (requestId === this.loadRequestId) this.isLoading = false;
    }
  }

  private async loadGrantedBy() {
    if (!this.tenantId) return;
    const tenantId = this.tenantId;
    this.grantedByError = '';
    try {
      const [roles, clients] = await Promise.all([getRoles(tenantId), fetchAllClients(tenantId).catch(() => [] as OAuthClient[])]);
      if (this.tenantId !== tenantId) return;
      this.roles = roles;
      this.clients = clients;
    } catch (error) {
      if (this.tenantId === tenantId) this.grantedByError = error instanceof Error ? error.message : 'Failed to load roles';
    }
  }

  /** Tells the resources list, which shows the reverse endpoint -> permissions index, to rebuild it. */
  private notifyPermissionsUpdated() {
    window.dispatchEvent(new CustomEvent(PERMISSIONS_UPDATED_EVENT, { detail: { tenantId: this.tenantId, permissions: this.permissions } }));
  }

  // ── resolution ──

  private resolve(permission: Permission): { groups: ResourceEndpoints[]; detached: ResourceEndpointId[] } {
    const groups = new Map<string, ResourceEndpoints>();
    const detached: ResourceEndpointId[] = [];
    for (const endpointId of permission.endpointIds ?? []) {
      const resource = this.resources.find(candidate => candidate.endpoints.some(endpoint => endpoint.id === endpointId));
      const endpoint = resource?.endpoints.find(candidate => candidate.id === endpointId);
      if (!resource || !endpoint) { detached.push(endpointId); continue; }
      const group = groups.get(resource.resourceId) ?? { resource, endpoints: [] };
      group.endpoints.push({ method: endpoint.method, path: endpoint.path });
      groups.set(resource.resourceId, group);
    }
    return { groups: [...groups.values()].sort((a, b) => a.resource.resource.localeCompare(b.resource.resource)), detached };
  }

  // ── routing ──

  private get openPermission(): Permission | null {
    return this.route.id ? this.permissions.find(permission => permission.id === this.route.id) ?? null : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.route.edit && this.openPermission ? 'edit' : 'view';
  }

  private get editGroup(): PermissionGroup | null {
    return this.route.edit === 'general' || this.route.edit === 'endpoints' ? this.route.edit : null;
  }

  private openView(permission: Permission) {
    dispatchRecordRoute(this, { id: permission.id, edit: null, create: false });
  }

  private closePanel() {
    const key = this.route.id;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private openEditor(group: PermissionGroup) {
    dispatchRecordRoute(this, { edit: group, create: false });
  }

  private draftBaseline = '';

  private get draftSnapshot(): string {
    return JSON.stringify([this.draftId, this.draftDescription, this.draftEndpointIds]);
  }

  /** Closing an editor with unsaved changes asks first; the draft is dropped only on a yes. */
  private async cancelEditor() {
    if (this.draftSnapshot !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  private handleCreateClick() {
    dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null });
  }

  // ── draft ──

  private resetDraft() {
    const permission = this.editorMode === 'edit' ? this.openPermission : null;
    this.draftId = permission?.id ?? '';
    this.draftDescription = { ...(permission?.description ?? { en: '' }) };
    this.draftEndpointIds = [...(permission?.endpointIds ?? [])];
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
    this.draftBaseline = this.draftSnapshot;
  }

  private get draftIdError(): string {
    const id = this.draftId.trim();
    if (!id) return this.submitted ? 'A permission ID is required.' : '';
    if (!validatePermission(id)) return 'Lowercase segments separated by dots or colons, each starting with a letter.';
    if (this.permissions.some(permission => permission.id === id)) return 'A permission with this ID already exists.';
    return '';
  }

  private get draftDescriptionError(): string {
    return this.submitted && !(this.draftDescription.en ?? '').trim() ? 'A description in English is required.' : '';
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (!this.tenantId || this.isSaving) return;
    this.submitted = true;
    const mode = this.editorMode;
    const group = this.editGroup;
    if (mode === 'create' && this.draftIdError) return;
    if ((mode === 'create' || group === 'general') && !(this.draftDescription.en ?? '').trim()) return;

    const existing = this.openPermission;
    const description = Object.fromEntries(Object.entries(this.draftDescription).filter(([, value]) => value.trim()));
    const endpointIds = [...new Set(this.draftEndpointIds)].sort((a, b) => String(a).localeCompare(String(b)));
    const next: Permission = mode === 'create'
      ? { id: this.draftId.trim(), description, endpointIds }
      : group === 'general'
        ? { ...existing!, description }
        : { ...existing!, endpointIds };

    this.isSaving = true;
    this.formError = '';
    try {
      if (mode === 'create') {
        await createPermission(this.tenantId, next);
        this.permissions = [...this.permissions, next].sort((a, b) => a.id.localeCompare(b.id));
        this.notifyPermissionsUpdated();
        dispatchRecordRoute(this, { id: next.id, edit: null, create: false });
      } else {
        await updatePermission(this.tenantId, existing!, next);
        this.permissions = this.permissions.map(permission => permission.id === next.id ? next : permission);
        this.notifyPermissionsUpdated();
        dispatchRecordRoute(this, { edit: null, create: false });
      }
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save permission';
    } finally {
      this.isSaving = false;
    }
  }

  private async handleDelete(permission: Permission) {
    if (!this.tenantId) return;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete permission',
      messagePrefix: 'Delete permission ',
      messageSubject: permission.id,
      messageSuffix: '? Roles and service clients that hold it lose it.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    try {
      await deletePermission(this.tenantId, permission.id);
      this.permissions = this.permissions.filter(candidate => candidate.id !== permission.id);
      this.notifyPermissionsUpdated();
      if (this.route.id === permission.id) dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to delete permission';
    }
  }

  // ── list ──

  private matchesFilter(permission: Permission, filter: string = this.filter): boolean {
    if (filter === 'all') return true;
    const { groups, detached } = this.resolve(permission);
    if (filter === 'unlinked') return groups.length === 0 && detached.length === 0;
    if (filter === 'detached') return detached.length > 0;
    return groups.some(group => `resource:${group.resource.resourceId}` === filter);
  }

  private get filteredPermissions(): Permission[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.permissions.filter(permission => this.matchesFilter(permission)
      && (!query || permission.id.toLowerCase().includes(query) || getLocalizedDescription(permission.description).toLowerCase().includes(query)));
  }

  private count(filter: string): number {
    return this.permissions.filter(permission => this.matchesFilter(permission, filter)).length;
  }

  private renderToolbar() {
    const resourceOptions = [...this.resources]
      .sort((a, b) => a.resource.localeCompare(b.resource))
      .map(resource => ({ id: `resource:${resource.resourceId}`, label: formatResourceLabel(resource.resource) }))
      .filter(option => this.count(option.id) > 0);
    const options = [
      { id: 'all', label: 'All' },
      ...resourceOptions,
      { id: 'unlinked', label: 'Unlinked' },
      ...(this.count('detached') > 0 ? [{ id: 'detached', label: 'Detached' }] : []),
    ];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search permissions" placeholder="Search permissions by ID or description"
          .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
        <versola-filter-bar label="Filter permissions"
          .options=${options.map(option => ({ ...option, count: this.count(option.id) }))}
          .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => this.filter = e.detail.id}></versola-filter-bar>
      </div>
    `;
  }

  private renderTable() {
    const columns: DataTableColumn<Permission>[] = [
      { id: 'permission', label: 'Permission', cell: permission => html`
        <button type="button" class="row-link" aria-label=${`Open permission ${permission.id}`}>
          <span class="row-title">${getLocalizedDescription(permission.description) || permission.id}</span>
          <span class="row-id">${permission.id}</span>
        </button>` },
      { id: 'resources', label: 'Resources', cell: permission => {
        const { groups } = this.resolve(permission);
        return groups.length ? html`<div class="chips">${groups.map(group => html`<span class="chip">${formatResourceLabel(group.resource.resource)}</span>`)}</div>` : html`<span class="muted">—</span>`;
      } },
      { id: 'endpoints', label: 'Endpoints', cell: permission => {
        const { groups, detached } = this.resolve(permission);
        const linked = groups.reduce((sum, group) => sum + group.endpoints.length, 0);
        return html`<div class="chips">${linked || !detached.length ? html`<span class=${linked ? '' : 'muted'}>${linked}</span>` : ''}
          ${detached.length ? html`<span class="chip warn">${detached.length} detached</span>` : ''}</div>`;
      } },
    ];
    const menuItems = (permission: Permission): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(permission) },
      { label: 'Copy permission ID', onSelect: () => void copyToClipboard(permission.id) },
      ...(this.canManage ? [{ label: 'Delete permission', danger: true, ariaLabel: `Delete permission ${permission.id}`, onSelect: () => void this.handleDelete(permission) }] : []),
    ];
    return html`
      <versola-data-table selectable
        .columns=${columns}
        .rows=${this.filteredPermissions}
        .rowKey=${(permission: Permission) => permission.id}
        .selectedKey=${this.route.id}
        .menuItems=${menuItems}
        .menuLabel=${(permission: Permission) => `Actions for ${permission.id}`}
        @row-select=${(e: CustomEvent<{ row: Permission }>) => this.openView(e.detail.row)}
      ></versola-data-table>
    `;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private renderEndpoints(endpoints: ResolvedEndpoint[]) {
    return html`<div class="endpoint-list">${endpoints.map(endpoint => html`
      <div class="endpoint"><span class=${`method-badge method-${endpoint.method.toLowerCase()}`}>${endpoint.method}</span><span class="endpoint-path">${endpoint.path}</span></div>`)}</div>`;
  }

  private renderGroups(permission: Permission) {
    const { groups, detached } = this.resolve(permission);
    const roles = this.roles?.filter(role => role.permissions.some(candidate => candidate.id === permission.id)) ?? null;
    const clients = this.clients?.filter(client => client.permissions.includes(permission.id)) ?? null;
    const loading = html`<span class="muted">Loading…</span>`;
    const retry = html`<span class="muted">Could not load.</span> <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.loadGrantedBy()}>Retry</button>`;
    return html`
      <versola-record-card heading="General" ?editable=${this.canManage} .body=${html`
        ${this.row('Description', localizedValue(permission.description, this.locales))}
      `} @edit=${() => this.openEditor('general')}></versola-record-card>

      <versola-record-card heading="Endpoints" subheading="What a token carrying this permission may call through edge." ?editable=${this.canManage}
        .body=${groups.length || detached.length ? html`
          ${groups.map(group => this.row(formatResourceLabel(group.resource.resource), this.renderEndpoints(group.endpoints)))}
          ${detached.length ? this.row('Detached endpoints', html`<span class="chip warn">${detached.map(id => `#${id}`).join(', ')}</span>`) : ''}`
          : html`<div class="record-row"><div class="record-value muted">No endpoints linked. The permission can be granted but opens nothing.</div></div>`}
        @edit=${() => this.openEditor('endpoints')}></versola-record-card>

      <versola-record-card heading="Granted by" subheading="Change this from the role or the client." .body=${html`
        ${this.row('Roles', this.grantedByError ? retry : roles === null ? loading
          : roles.length ? html`<div class="tag-list">${roles.map(role => html`<span class="tag">${role.id}</span>`)}</div>` : html`<span class="muted">No role grants it.</span>`)}
        ${this.row('Service clients', this.grantedByError ? retry : clients === null ? loading
          : clients.length ? html`<div class="tag-list">${clients.map(client => html`<span class="tag">${client.id}</span>`)}</div>` : html`<span class="muted">No client holds it.</span>`)}
      `}></versola-record-card>

      <versola-record-card heading="Fixed" fixedLabel="Fixed" .body=${html`
        ${this.row('Permission ID', html`<span class="mono">${permission.id}</span>`, 'Set at creation; roles and tokens carry this value')}
      `}></versola-record-card>

      ${this.canManage ? html`
        <versola-danger-card heading="Delete permission" text="Roles and service clients that hold it lose it. This can't be undone."
          actionLabel="Delete permission" .actionAriaLabel=${`Delete permission ${permission.id}`}
          @action=${() => this.handleDelete(permission)}></versola-danger-card>` : ''}
    `;
  }

  private renderBanner(permission: Permission) {
    const { detached } = this.resolve(permission);
    if (detached.length === 0) return '';
    return html`
      <div class="banner warn" role="status">
        <div class="banner-text">
          <div class="banner-title">${statusIcon('alert')}${detached.length} linked ${detached.length === 1 ? 'endpoint' : 'endpoints'} no longer ${detached.length === 1 ? 'exists' : 'exist'} in Resources</div>
          <div>Edit Endpoints to remove them.</div>
        </div>
      </div>
    `;
  }

  private renderMeta(permission: Permission) {
    const { groups, detached } = this.resolve(permission);
    const linked = groups.reduce((sum, group) => sum + group.endpoints.length, 0);
    return html`
      <span class="chip">${linked} ${linked === 1 ? 'endpoint' : 'endpoints'}</span>
      ${detached.length ? html`<span class="chip warn">Detached endpoints</span>` : ''}
    `;
  }

  private renderRecordPage(permission: Permission) {
    const title = getLocalizedDescription(permission.description) || permission.id;
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Permissions', onSelect: () => this.closePanel() }, { label: title }]}
        .heading=${title} .recordId=${permission.id} idLabel="permission ID"
        .meta=${html`<span class="chip">Permission</span>${this.renderMeta(permission)}`}
      ></versola-record-header>
      ${this.renderBanner(permission)}
      <div class="record-page">${this.renderGroups(permission)}</div>
    `;
  }

  // ── editor ──

  private renderDescriptionField() {
    const error = this.draftDescriptionError;
    return html`
      <div class="form-group">
        <label for="permission-description">Description *</label>
        <versola-localized-text-editor fieldId="permission-description" label="Description" .showLabel=${false} .required=${true}
          .selectorBelowInput=${true} .value=${this.draftDescription} .locales=${this.locales}
          @localized-change=${(e: CustomEvent<{ value: Record<string, string> }>) => this.draftDescription = e.detail.value}
        ></versola-localized-text-editor>
        ${error ? html`<div class="error-message" role="alert">${error}</div>` : html`<div class="hint">English is required; other active locales are optional.</div>`}
      </div>
    `;
  }

  private renderEndpointsEditor() {
    return html`
      <div class="editor-box">
        <h3>Endpoints</h3>
        <p class="editor-sub">Add a resource, then the endpoints of it this permission opens.</p>
        <versola-endpoint-picker .resources=${this.resources} .value=${this.draftEndpointIds}
          @change=${(e: CustomEvent<{ value: ResourceEndpointId[] }>) => this.draftEndpointIds = e.detail.value}></versola-endpoint-picker>
      </div>
    `;
  }

  private renderEditor() {
    const mode = this.editorMode;
    const idError = this.draftIdError;
    return html`
      <form id="permission-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${mode === 'create' ? html`
          <div class="editor-box">
            <h3>Basics</h3>
            <p class="editor-sub">The ID is what roles and tokens carry; the description is what people read.</p>
            <div class="form-group">
              <label for="permission-id">Permission ID *</label>
              <input type="text" id="permission-id" class="compact-input mono ${idError ? 'input-error' : ''}" .value=${this.draftId} required
                aria-invalid=${idError ? 'true' : nothing} aria-describedby="permission-id-hint" placeholder="e.g. orders:read"
                @input=${(e: Event) => this.draftId = (e.target as HTMLInputElement).value} />
              ${idError ? html`<div class="error-message" id="permission-id-hint" role="alert">${idError}</div>`
                : html`<div class="hint" id="permission-id-hint">Lowercase segments separated by dots or colons, each starting with a letter.</div>`}
            </div>
            ${this.renderDescriptionField()}
          </div>
          ${this.renderEndpointsEditor()}
        ` : this.editGroup === 'general' ? html`
          <div class="editor-box">
            <h3>General</h3>
            <p class="editor-sub">What the console shows for this permission.</p>
            ${this.renderDescriptionField()}
          </div>
        ` : this.renderEndpointsEditor()}
      </form>
    `;
  }

  private renderPanel(permission: Permission | null) {
    const mode = this.editorMode;
    if (mode === 'view' && !permission) return '';
    const title = permission ? getLocalizedDescription(permission.description) || permission.id : '';
    const heading = mode === 'create' ? 'Create permission' : mode === 'edit' ? `Edit ${this.editGroup}` : title;
    const submitLabel = mode === 'create' ? 'Create permission' : this.editGroup === 'general' ? 'Save general' : 'Save endpoints';
    const wide = mode === 'create' || this.editGroup === 'endpoints';
    return html`
      <versola-side-panel kind="Permission" .heading=${heading} .recordId=${permission?.id ?? ''} idLabel="permission ID" .mode=${mode} ?wide=${wide}
        .parentLabel=${title} .subtitle=${mode === 'create' ? `In ${this.tenantId}. The ID is fixed once created.` : ''}
        .meta=${permission && mode === 'view' ? this.renderMeta(permission) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()}
        @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">
          ${mode === 'view' ? html`${this.renderBanner(permission!)}${this.renderGroups(permission!)}` : this.renderEditor()}
        </div>
        ${mode !== 'view' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="permission-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : submitLabel}</button>
          </div>` : ''}
      </versola-side-panel>
    `;
  }

  render() {
    if (!this.tenantId) {
      return html`
        <content-header title="Permissions"></content-header>
        <versola-empty-state heading="Select a tenant" text="Select a tenant to manage permissions."></versola-empty-state>
      `;
    }

    const permission = this.openPermission;
    const panel = this.renderPanel(permission);
    if (permission && this.route.layout === 'page') {
      return html`${this.renderRecordPage(permission)}${this.editorMode === 'view' ? '' : panel}`;
    }

    return html`
      <content-header title="Permissions" description="What a role or a service client may call; each permission opens endpoints of the tenant's resources.">
        ${this.permissions.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Permission</button>` : ''}
      </content-header>

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load permissions" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.permissions.length === 0 ? html`
        <versola-empty-state heading="No permissions yet" text="Create your first permission to get started">
          ${this.canManage ? html`<button class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Permission</button>` : ''}
        </versola-empty-state>
      ` : html`
        ${this.renderToolbar()}
        ${this.filteredPermissions.length === 0 ? html`<versola-empty-state heading="No permissions match"></versola-empty-state>` : this.renderTable()}
      `}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-permissions-list': VersolaPermissionsList;
  }
}
