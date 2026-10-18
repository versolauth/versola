import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../styles/components';
import type { Edge, SecurityProfile, Tenant } from '../types';
import { createTenant, deleteTenant, fetchChallengeSettings, fetchEdges, fetchTenants, updateTenant } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { copyToClipboard } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { validateTenantId } from '../utils/validators';
import { statusIcon } from './status-icon';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from './data-table';
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

type TenantFilter = 'all' | 'edge' | 'unassigned';
type TenantGroup = 'general' | 'edge';

const PROFILES: Array<{ id: SecurityProfile; title: string; text: string }> = [
  { id: 'fapi2', title: 'FAPI 2.0 (recommended)', text: 'Confidential clients only, sender-constrained tokens, pushed authorization requests.' },
  { id: 'standard', title: 'Standard', text: 'Also admits client secrets, RS256 keys and public native clients.' },
];

/**
 * Tenants: the table of every tenant and, for the one selected, the side panel with its
 * description, its edge, a link to its security settings, the Fixed group (the ID and the
 * security profile, which `GET /configuration/tenants` does not carry and is read from the
 * tenant's challenge settings when the panel opens) and the danger card. Editing a group and
 * creating a tenant happen in the same panel; creating one selects it.
 */
@customElement('versola-tenants-list')
export class VersolaTenantsList extends LitElement {
  @property({ type: String }) selectedTenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private tenants: Tenant[] = [];
  @state() private edges: Edge[] = [];
  @state() private searchQuery = '';
  @state() private filter: TenantFilter = 'all';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  /** Security profiles read per opened tenant; `error` when the settings could not be read. */
  @state() private profiles: Map<string, SecurityProfile | 'error'> = new Map();
  @state() private draftId = '';
  @state() private draftDescription = '';
  @state() private draftEdgeId = '';
  @state() private draftProfile: SecurityProfile = 'fapi2';
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @query('versola-data-table') private table?: VersolaDataTable<Tenant>;
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

      .record-page .record-card {
        margin-bottom: var(--spacing-md);
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('route') || changed.has('tenants')) {
      const mode = this.editorMode;
      const draftKey = mode === 'view' ? '' : `${mode}:${this.route.id ?? ''}:${this.route.edit ?? ''}`;
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (mode !== 'view') this.resetDraft();
      }
      const open = this.openTenant;
      if (open && !this.profiles.has(open.id)) void this.loadProfile(open.id);
    }
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [tenants, edges] = await Promise.all([fetchTenants(), fetchEdges().catch(() => [] as Edge[])]);
      this.tenants = tenants;
      this.edges = edges;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load tenants';
      this.tenants = [];
      this.edges = [];
    } finally {
      this.isLoading = false;
    }
  }

  private async loadProfile(tenantId: string) {
    try {
      const settings = await fetchChallengeSettings(tenantId);
      this.profiles = new Map(this.profiles).set(tenantId, settings?.securityProfile ?? 'fapi2');
    } catch {
      this.profiles = new Map(this.profiles).set(tenantId, 'error');
    }
  }

  private notifyTenantsUpdated() {
    window.dispatchEvent(new CustomEvent('versola-tenants-updated', {
      detail: { tenants: this.tenants, selectedTenantId: this.selectedTenantId },
    }));
  }

  private selectTenant(tenantId: string) {
    this.selectedTenantId = tenantId;
    localStorage.setItem('selectedTenantId', tenantId);
    this.dispatchEvent(new CustomEvent('tenant-change', { detail: { tenantId }, bubbles: true, composed: true }));
  }

  // ── routing ──

  private get openTenant(): Tenant | null {
    return this.route.id ? this.tenants.find(tenant => tenant.id === this.route.id) ?? null : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.route.edit && this.openTenant ? 'edit' : 'view';
  }

  private get editGroup(): TenantGroup | null {
    return this.route.edit === 'general' || this.route.edit === 'edge' ? this.route.edit : null;
  }

  private openView(tenant: Tenant) {
    dispatchRecordRoute(this, { id: tenant.id, edit: null, create: false });
  }

  private closePanel() {
    const key = this.route.id;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private openEditor(group: TenantGroup) {
    dispatchRecordRoute(this, { edit: group, create: false });
  }

  private draftBaseline = '';

  private get draftSnapshot(): string {
    return JSON.stringify([this.draftId, this.draftDescription, this.draftEdgeId, this.draftProfile]);
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
    const tenant = this.editorMode === 'edit' ? this.openTenant : null;
    this.draftId = tenant?.id ?? '';
    this.draftDescription = tenant?.description ?? '';
    this.draftEdgeId = tenant?.edgeId ?? '';
    this.draftProfile = 'fapi2';
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
    this.draftBaseline = this.draftSnapshot;
  }

  private get draftIdError(): string {
    const id = this.draftId.trim();
    if (!id) return this.submitted ? 'A tenant ID is required.' : '';
    if (!validateTenantId(id)) return 'Lowercase letters, digits and dashes, starting with a letter.';
    if (this.tenants.some(tenant => tenant.id === id)) return 'A tenant with this ID already exists.';
    return '';
  }

  private get draftDescriptionError(): string {
    return this.submitted && !this.draftDescription.trim() ? 'A description is required.' : '';
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.submitted = true;
    const mode = this.editorMode;
    const group = this.editGroup;
    if (mode === 'create' && this.draftIdError) return;
    if ((mode === 'create' || group === 'general') && !this.draftDescription.trim()) return;

    const existing = this.openTenant;
    const description = mode === 'create' || group === 'general' ? this.draftDescription.trim() : existing!.description ?? '';
    const edgeId = mode === 'create' || group === 'edge' ? this.draftEdgeId || null : existing!.edgeId ?? null;
    const id = mode === 'create' ? this.draftId.trim() : existing!.id;
    const next: Tenant = { id, name: description || id, description, edgeId };

    this.isSaving = true;
    this.formError = '';
    try {
      if (mode === 'create') {
        await createTenant(id, description, edgeId, this.draftProfile);
        this.tenants = [...this.tenants, next].sort((a, b) => a.id.localeCompare(b.id));
        this.profiles = new Map(this.profiles).set(id, this.draftProfile);
        this.selectTenant(id);
        this.notifyTenantsUpdated();
        dispatchRecordRoute(this, { id, edit: null, create: false });
      } else {
        await updateTenant(id, description, edgeId);
        this.tenants = this.tenants.map(tenant => tenant.id === id ? next : tenant);
        this.notifyTenantsUpdated();
        dispatchRecordRoute(this, { edit: null, create: false });
      }
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save tenant';
    } finally {
      this.isSaving = false;
    }
  }

  private async handleDelete(tenant: Tenant) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete tenant',
      messagePrefix: 'Delete tenant ',
      messageSubject: tenant.id,
      messageSuffix: '? Its clients, resources, scopes, permissions, roles and settings go with it.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      await deleteTenant(tenant.id);
      this.tenants = this.tenants.filter(candidate => candidate.id !== tenant.id);
      this.notifyTenantsUpdated();
      if (this.route.id === tenant.id) dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to delete tenant';
    }
  }

  // ── list ──

  private matchesFilter(tenant: Tenant, filter: TenantFilter = this.filter): boolean {
    switch (filter) {
      case 'edge': return !!tenant.edgeId;
      case 'unassigned': return !tenant.edgeId;
      default: return true;
    }
  }

  /** The selected tenant first, then by ID. */
  private get filteredTenants(): Tenant[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.tenants
      .filter(tenant => this.matchesFilter(tenant)
        && (!query || tenant.id.toLowerCase().includes(query) || (tenant.description ?? '').toLowerCase().includes(query)))
      .sort((a, b) => (a.id === this.selectedTenantId ? -1 : b.id === this.selectedTenantId ? 1 : a.id.localeCompare(b.id)));
  }

  private count(filter: TenantFilter): number {
    return this.tenants.filter(tenant => this.matchesFilter(tenant, filter)).length;
  }

  /** The edge as a chip (the table renders cells with its own styles, which carry chips, not tags). */
  private edgeValue(tenant: Tenant) {
    if (!tenant.edgeId) return html`<span class="chip warn">Not assigned</span>`;
    const known = this.edges.some(edge => edge.id === tenant.edgeId);
    return html`<span class="chip">${tenant.edgeId}</span>${this.edges.length && !known ? html` <span class="chip warn">Missing</span>` : ''}`;
  }

  private renderToolbar() {
    const options: Array<{ id: TenantFilter; label: string }> = [
      { id: 'all', label: 'All' },
      { id: 'edge', label: 'On an edge' },
      { id: 'unassigned', label: 'Not assigned' },
    ];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search tenant list" placeholder="ID or description"
          .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
        <versola-filter-bar label="Filter tenants"
          .options=${options.map(option => ({ ...option, count: this.count(option.id) }))}
          .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => this.filter = e.detail.id as TenantFilter}></versola-filter-bar>
      </div>
    `;
  }

  private renderTable() {
    const columns: DataTableColumn<Tenant>[] = [
      { id: 'tenant', label: 'Tenant', cell: tenant => html`
        <button type="button" class="row-link" aria-label=${`Open tenant ${tenant.description || tenant.id} ${tenant.id}`}>
          <span class="row-title">${tenant.description || tenant.id}</span>
          <span class="row-id">${tenant.id}</span>
        </button>
        ${tenant.id === this.selectedTenantId ? html`<div class="row-flags"><span class="chip ok">${statusIcon('check', 'icon', 14)}Selected</span></div>` : nothing}` },
      { id: 'edge', label: 'Edge', cell: tenant => html`<div class="chips">${this.edgeValue(tenant)}</div>` },
    ];
    const menuItems = (tenant: Tenant): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(tenant) },
      { label: 'Copy tenant ID', onSelect: () => void copyToClipboard(tenant.id) },
      ...(tenant.id !== this.selectedTenantId ? [{ label: 'Select tenant', ariaLabel: `Select tenant ${tenant.id}`, onSelect: () => this.selectTenant(tenant.id) }] : []),
      ...(this.canManage ? [{ label: 'Delete tenant', danger: true, ariaLabel: `Delete tenant ${tenant.id}`, onSelect: () => void this.handleDelete(tenant) }] : []),
    ];
    return html`
      <versola-data-table selectable
        .columns=${columns}
        .rows=${this.filteredTenants}
        .rowKey=${(tenant: Tenant) => tenant.id}
        .selectedKey=${this.route.id}
        .menuItems=${menuItems}
        .menuLabel=${(tenant: Tenant) => `Actions for ${tenant.id}`}
        @row-select=${(e: CustomEvent<{ row: Tenant }>) => this.openView(e.detail.row)}
      ></versola-data-table>
    `;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private profileChip(tenant: Tenant) {
    const profile = this.profiles.get(tenant.id);
    if (profile === 'error') return html`<span class="chip warn">Profile unknown</span>`;
    if (profile === undefined) return html`<span class="chip muted">Profile…</span>`;
    return profile === 'fapi2'
      ? html`<span class="chip ok" title="Clients are held to the FAPI 2.0 security profile">${statusIcon('shield', 'icon', 14)}FAPI 2.0</span>`
      : html`<span class="chip">Standard</span>`;
  }

  private profileValue(tenant: Tenant) {
    const profile = this.profiles.get(tenant.id);
    if (profile === 'error') return html`<span class="muted">Could not read the security settings.</span> <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.loadProfile(tenant.id)}>Retry</button>`;
    if (profile === undefined) return html`<span class="muted">Loading…</span>`;
    return PROFILES.find(candidate => candidate.id === profile)?.title ?? profile;
  }

  private openSecuritySettings(tenant: Tenant) {
    if (tenant.id !== this.selectedTenantId) this.selectTenant(tenant.id);
    this.dispatchEvent(new CustomEvent('navigate-view', { detail: { view: 'challenges' }, bubbles: true, composed: true }));
  }

  private renderGroups(tenant: Tenant) {
    return html`
      <versola-record-card heading="General" ?editable=${this.canManage} .body=${html`
        ${this.row('Description', tenant.description || html`<span class="muted">—</span>`)}
      `} @edit=${() => this.openEditor('general')}></versola-record-card>

      <versola-record-card heading="Edge" subheading="The edge that serves this tenant's clients and syncs its configuration." ?editable=${this.canManage} .body=${html`
        ${this.row('Edge', tenant.edgeId ? html`<div class="chips">${this.edgeValue(tenant)}</div>` : html`<span class="muted">None (Central only)</span>`)}
      `} @edit=${() => this.openEditor('edge')}></versola-record-card>

      <versola-record-card heading="Security settings" subheading="Sign-in methods, sessions, tokens and limits live under Challenges & Security." .body=${html`
        <versola-record-row .value=${html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => this.openSecuritySettings(tenant)}>Open security settings</button>`}></versola-record-row>
      `}></versola-record-card>

      <versola-record-card heading="Fixed" fixedLabel="Fixed" .body=${html`
        ${this.row('Tenant ID', html`<span class="mono">${tenant.id}</span>`, 'Set at creation')}
        ${this.row('Security profile', this.profileValue(tenant), 'Chosen at creation; cannot be changed')}
      `}></versola-record-card>

      ${this.canManage ? html`
        <versola-danger-card heading="Delete tenant" text="Its clients, resources, scopes, permissions, roles and settings go with it. This can't be undone."
          actionLabel="Delete tenant" .actionAriaLabel=${`Delete tenant ${tenant.id}`}
          @action=${() => this.handleDelete(tenant)}></versola-danger-card>` : ''}
    `;
  }

  private renderBanner(tenant: Tenant) {
    if (tenant.edgeId) return '';
    return html`
      <div class="banner warn" role="status">
        <div class="banner-text">
          <div class="banner-title">${statusIcon('alert')}Not assigned to an edge</div>
          <div>Sync is edge-scoped, so no edge serves this tenant.</div>
        </div>
        ${this.canManage ? html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => this.openEditor('edge')}>Assign an edge</button>` : ''}
      </div>
    `;
  }

  private renderMeta(tenant: Tenant) {
    return html`
      ${tenant.id === this.selectedTenantId ? html`<span class="chip ok">${statusIcon('check', 'icon', 14)}Selected</span>` : ''}
      ${this.profileChip(tenant)}
      ${tenant.edgeId ? '' : html`<span class="chip warn">Not served</span>`}
    `;
  }

  private renderRecordPage(tenant: Tenant) {
    const title = tenant.description || tenant.id;
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Tenants', onSelect: () => this.closePanel() }, { label: title }]}
        .heading=${title} .recordId=${tenant.id} idLabel="tenant ID"
        .meta=${html`<span class="chip">Tenant</span>${this.renderMeta(tenant)}`}
      ></versola-record-header>
      ${this.renderBanner(tenant)}
      <div class="record-page">${this.renderGroups(tenant)}</div>
    `;
  }

  // ── editor ──

  private renderDescriptionField() {
    const error = this.draftDescriptionError;
    return html`
      <div class="form-group">
        <label for="tenant-description">Description *</label>
        <input type="text" id="tenant-description" class="compact-input ${error ? 'input-error' : ''}" .value=${this.draftDescription} required
          aria-invalid=${error ? 'true' : nothing} aria-describedby=${error ? 'tenant-description-error' : nothing} placeholder="e.g. Example Shop"
          @input=${(e: Event) => this.draftDescription = (e.target as HTMLInputElement).value} />
        ${error ? html`<div class="error-message" id="tenant-description-error" role="alert">${error}</div>` : ''}
      </div>
    `;
  }

  private renderEdgeField() {
    return html`
      <div class="form-group">
        <label for="tenant-edge">Edge (Optional)</label>
        <select id="tenant-edge" class="compact-input" .value=${this.draftEdgeId}
          @change=${(e: Event) => this.draftEdgeId = (e.target as HTMLSelectElement).value}>
          <option value="">None (Central only)</option>
          ${this.edges.map(edge => html`<option value=${edge.id} ?selected=${edge.id === this.draftEdgeId}>${edge.id}</option>`)}
        </select>
        <div class="hint">${this.edges.length ? 'The edge that serves this tenant; sync is edge-scoped.' : 'No edges registered yet; the tenant stays on central only.'}</div>
      </div>
    `;
  }

  private renderEditor() {
    const mode = this.editorMode;
    const idError = this.draftIdError;
    return html`
      <form id="tenant-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${mode === 'create' ? html`
          <div class="editor-box">
            <h3>Basics</h3>
            <p class="editor-sub">The ID names the tenant in every URL and API call; the description is what the console shows.</p>
            <div class="form-group">
              <label for="tenant-id">Tenant ID *</label>
              <input type="text" id="tenant-id" class="compact-input mono ${idError ? 'input-error' : ''}" .value=${this.draftId} required
                aria-invalid=${idError ? 'true' : nothing} aria-describedby="tenant-id-hint" placeholder="e.g. example-shop"
                @input=${(e: Event) => this.draftId = (e.target as HTMLInputElement).value} />
              ${idError ? html`<div class="error-message" id="tenant-id-hint" role="alert">${idError}</div>`
                : html`<div class="hint" id="tenant-id-hint">Lowercase letters, digits and dashes, starting with a letter.</div>`}
            </div>
            ${this.renderDescriptionField()}
          </div>
          <div class="editor-box">
            <h3>Security profile</h3>
            <p class="editor-sub">What the tenant's clients are held to.</p>
            <div class="option-cards" role="group" aria-label="Security profile">
              ${PROFILES.map(profile => html`
                <button type="button" class="option-card" aria-pressed=${this.draftProfile === profile.id} @click=${() => this.draftProfile = profile.id}>
                  <div class="option-card-title">${profile.id === 'fapi2' ? statusIcon('shield', 'icon', 16) : ''}${profile.title}</div>
                  <div class="option-card-text">${profile.text}</div>
                  <div class="option-card-fixed">${statusIcon('lock', 'icon', 13)}Fixed once the tenant is created</div>
                </button>`)}
            </div>
          </div>
          <div class="editor-box">
            <h3>Edge</h3>
            <p class="editor-sub">Can be changed later.</p>
            ${this.renderEdgeField()}
          </div>
        ` : this.editGroup === 'general' ? html`
          <div class="editor-box">
            <h3>General</h3>
            <p class="editor-sub">What the console shows for this tenant.</p>
            ${this.renderDescriptionField()}
          </div>
        ` : html`
          <div class="editor-box">
            <h3>Edge</h3>
            <p class="editor-sub">The edge that serves this tenant. Its clients are reachable through no edge until one is set.</p>
            ${this.renderEdgeField()}
          </div>
        `}
      </form>
    `;
  }

  private renderPanel(tenant: Tenant | null) {
    const mode = this.editorMode;
    if (mode === 'view' && !tenant) return '';
    const title = tenant ? tenant.description || tenant.id : '';
    const heading = mode === 'create' ? 'Create tenant' : mode === 'edit' ? `Edit ${this.editGroup}` : title;
    const submitLabel = mode === 'create' ? 'Create tenant' : this.editGroup === 'general' ? 'Save general' : 'Save edge';
    return html`
      <versola-side-panel kind="Tenant" .heading=${heading} .recordId=${tenant?.id ?? ''} idLabel="tenant ID" .mode=${mode}
        .parentLabel=${title} .subtitle=${mode === 'create' ? 'The ID and the security profile are fixed once created.' : ''}
        .meta=${tenant && mode === 'view' ? this.renderMeta(tenant) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()}
        @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">
          ${mode === 'view' ? html`${this.renderBanner(tenant!)}${this.renderGroups(tenant!)}` : this.renderEditor()}
        </div>
        ${mode !== 'view' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="tenant-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : submitLabel}</button>
          </div>` : ''}
      </versola-side-panel>
    `;
  }

  render() {
    const tenant = this.openTenant;
    const panel = this.renderPanel(tenant);
    if (tenant && this.route.layout === 'page') {
      return html`${this.renderRecordPage(tenant)}${this.editorMode === 'view' ? '' : panel}`;
    }

    return html`
      <content-header title="Tenants" description="Each tenant has its own clients, users' roles, resources and security settings.">
        ${this.tenants.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Tenant</button>` : ''}
      </content-header>

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load tenants" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.tenants.length === 0 ? html`
        <versola-empty-state heading="No tenants yet" text=${this.canManage ? 'Create your first tenant to get started' : 'No tenants available.'}>
          ${this.canManage ? html`<button class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Tenant</button>` : ''}
        </versola-empty-state>
      ` : html`
        ${this.renderToolbar()}
        ${this.filteredTenants.length === 0 ? html`<versola-empty-state heading="No tenants match"></versola-empty-state>` : this.renderTable()}
      `}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-tenants-list': VersolaTenantsList;
  }
}
