import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../styles/components';
import { getPermissionCategory, type Locale, type Permission, type Resource, type Role } from '../types';
import { createRole, deleteRole, fetchChallengeSettings, fetchLocales, getPermissions, getResources, getRoles, updateRole } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { copyToClipboard, getLocalizedDescription } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { validateRoleId } from '../utils/validators';
import { localizedValue } from './localized-value';
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
import './permission-info';
import './permission-picker';
import './record-card';
import './record-header';
import './side-panel';

type RoleFilter = 'all' | 'active' | 'inactive' | 'default';
type RoleGroup = 'general' | 'permissions';

const DEFAULT_ROLE_REASON = 'Default role for new registrations: change it in the sign-in settings first.';

/**
 * Roles: the table of the tenant's roles and, for the one selected, the side panel with its
 * description, its permissions grouped by category, the Fixed group and the danger card.
 * Editing a group and creating a role happen in the same panel; the permissions editor and the
 * create form open the wide panel for the picker.
 */
@customElement('versola-roles-list')
export class VersolaRolesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private roles: Role[] = [];
  /** Roles the tenant grants every self-registered account: they cannot be deleted while named there. */
  @state() private defaultRoleIds: Set<string> = new Set();
  @state() private locales: Locale[] = [];
  @state() private searchQuery = '';
  @state() private filter: RoleFilter = 'all';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  /** The tenant's permissions and resources, loaded the first time a panel opens. */
  @state() private permissions: Permission[] | null = null;
  @state() private resources: Resource[] = [];
  @state() private permissionsError = '';
  @state() private draftId = '';
  @state() private draftDescription: Record<string, string> = { en: '' };
  @state() private draftPermissions: Permission[] = [];
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @query('versola-data-table') private table?: VersolaDataTable<Role>;
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

      .permission-tag {
        display: inline-flex;
        align-items: center;
        gap: 0.375rem;
      }

      .record-page .record-card {
        margin-bottom: var(--spacing-md);
      }
    `,
  ];

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId')) {
      this.permissions = null;
      this.resources = [];
      this.permissionsError = '';
      void this.loadData();
    }
    if (changed.has('route') || changed.has('roles')) {
      const mode = this.editorMode;
      const draftKey = mode === 'view' ? '' : `${mode}:${this.route.id ?? ''}:${this.route.edit ?? ''}`;
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (mode !== 'view') this.resetDraft();
      }
      if ((this.route.id || this.route.create) && this.permissions === null && !this.permissionsError) void this.loadPermissions();
    }
  }

  private async loadData() {
    if (!this.tenantId) {
      this.roles = [];
      this.errorMessage = '';
      return;
    }
    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [roles, settings, locales] = await Promise.all([
        getRoles(this.tenantId),
        fetchChallengeSettings(this.tenantId).catch(() => null),
        fetchLocales().catch(() => [] as Locale[]),
      ]);
      if (requestId !== this.loadRequestId) return;
      this.roles = [...roles].sort((a, b) => a.id.localeCompare(b.id));
      this.defaultRoleIds = new Set(settings?.defaultRoles ?? []);
      this.locales = locales;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.roles = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load roles';
    } finally {
      if (requestId === this.loadRequestId) this.isLoading = false;
    }
  }

  private async loadPermissions() {
    if (!this.tenantId) return;
    const tenantId = this.tenantId;
    this.permissionsError = '';
    try {
      const [permissions, resources] = await Promise.all([getPermissions(tenantId), getResources(tenantId).catch(() => [] as Resource[])]);
      if (this.tenantId !== tenantId) return;
      this.permissions = permissions;
      this.resources = resources;
    } catch (error) {
      if (this.tenantId === tenantId) this.permissionsError = error instanceof Error ? error.message : 'Failed to load permissions';
    }
  }

  // ── routing ──

  private get openRole(): Role | null {
    return this.route.id ? this.roles.find(role => role.id === this.route.id) ?? null : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.route.edit && this.openRole ? 'edit' : 'view';
  }

  private get editGroup(): RoleGroup | null {
    return this.route.edit === 'general' || this.route.edit === 'permissions' ? this.route.edit : null;
  }

  private openView(role: Role) {
    dispatchRecordRoute(this, { id: role.id, edit: null, create: false });
  }

  private closePanel() {
    const key = this.route.id;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private openEditor(group: RoleGroup) {
    dispatchRecordRoute(this, { edit: group, create: false });
  }

  private draftBaseline = '';

  private get draftSnapshot(): string {
    return JSON.stringify([this.draftId, this.draftDescription, this.draftPermissions]);
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
    const role = this.editorMode === 'edit' ? this.openRole : null;
    this.draftId = role?.id ?? '';
    this.draftDescription = { ...(role?.description ?? { en: '' }) };
    this.draftPermissions = [...(role?.permissions ?? [])];
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
    this.draftBaseline = this.draftSnapshot;
  }

  private get draftIdError(): string {
    const id = this.draftId.trim();
    if (!id) return this.submitted ? 'A role ID is required.' : '';
    if (!validateRoleId(id)) return 'Lowercase letters, digits and dashes, starting with a letter.';
    if (this.roles.some(role => role.id === id)) return 'A role with this ID already exists.';
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

    const existing = this.openRole;
    const now = new Date().toISOString();
    const description = Object.fromEntries(Object.entries(this.draftDescription).filter(([, value]) => value.trim()));
    const next: Role = mode === 'create'
      ? { id: this.draftId.trim(), description, active: true, permissions: this.draftPermissions, createdAt: now, updatedAt: now }
      : group === 'general'
        ? { ...existing!, description, updatedAt: now }
        : { ...existing!, permissions: this.draftPermissions, updatedAt: now };

    this.isSaving = true;
    this.formError = '';
    try {
      if (mode === 'create') {
        await createRole(this.tenantId, next);
        this.roles = [...this.roles, next].sort((a, b) => a.id.localeCompare(b.id));
        dispatchRecordRoute(this, { id: next.id, edit: null, create: false });
      } else {
        await updateRole(this.tenantId, existing!, next);
        this.roles = this.roles.map(role => role.id === next.id ? next : role);
        dispatchRecordRoute(this, { edit: null, create: false });
      }
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save role';
    } finally {
      this.isSaving = false;
    }
  }

  private async handleDelete(role: Role) {
    if (!this.tenantId || this.defaultRoleIds.has(role.id)) return;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete role',
      messagePrefix: 'Delete role ',
      messageSubject: role.id,
      messageSuffix: '? Users assigned this role lose the permissions it grants.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    try {
      await deleteRole(this.tenantId, role.id);
      this.roles = this.roles.filter(candidate => candidate.id !== role.id);
      if (this.route.id === role.id) dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to delete role';
    }
  }

  // ── list ──

  private matchesFilter(role: Role, filter: RoleFilter = this.filter): boolean {
    switch (filter) {
      case 'active': return role.active;
      case 'inactive': return !role.active;
      case 'default': return this.defaultRoleIds.has(role.id);
      default: return true;
    }
  }

  private get filteredRoles(): Role[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.roles.filter(role => this.matchesFilter(role)
      && (!query || role.id.toLowerCase().includes(query) || getLocalizedDescription(role.description).toLowerCase().includes(query)));
  }

  private count(filter: RoleFilter): number {
    return this.roles.filter(role => this.matchesFilter(role, filter)).length;
  }

  private statusChips(role: Role) {
    return html`
      ${role.active ? html`<span class="chip ok">${statusIcon('check', 'icon', 14)}Active</span>` : html`<span class="chip danger">Inactive</span>`}
      ${this.defaultRoleIds.has(role.id) ? html`<span class="chip">Default for sign-up</span>` : ''}
    `;
  }

  private renderToolbar() {
    const options: Array<{ id: RoleFilter; label: string }> = [
      { id: 'all', label: 'All' },
      { id: 'active', label: 'Active' },
      { id: 'inactive', label: 'Inactive' },
      { id: 'default', label: 'Default for sign-up' },
    ];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search roles" placeholder="ID or description"
          .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
        <versola-filter-bar label="Filter roles"
          .options=${options.map(option => ({ ...option, count: this.count(option.id) }))}
          .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => this.filter = e.detail.id as RoleFilter}></versola-filter-bar>
      </div>
    `;
  }

  private renderTable() {
    const columns: DataTableColumn<Role>[] = [
      { id: 'role', label: 'Role', cell: role => html`
        <button type="button" class="row-link" aria-label=${`Open role ${role.id}`}>
          <span class="row-title">${getLocalizedDescription(role.description) || role.id}</span>
          <span class="row-id">${role.id}</span>
        </button>` },
      { id: 'permissions', label: 'Permissions', cell: role => role.permissions.length
        ? html`<span title=${role.permissions.map(permission => permission.id).join(', ')}>${role.permissions.length}</span>`
        : html`<span class="muted">0</span>` },
      { id: 'status', label: 'Status', cell: role => html`<div class="chips">${this.statusChips(role)}</div>` },
    ];
    const menuItems = (role: Role): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(role) },
      { label: 'Copy role ID', onSelect: () => void copyToClipboard(role.id) },
      ...(this.canManage && !this.defaultRoleIds.has(role.id)
        ? [{ label: 'Delete role', danger: true, ariaLabel: `Delete role ${role.id}`, onSelect: () => void this.handleDelete(role) }] : []),
    ];
    return html`
      <versola-data-table selectable
        .columns=${columns}
        .rows=${this.filteredRoles}
        .rowKey=${(role: Role) => role.id}
        .selectedKey=${this.route.id}
        .menuItems=${menuItems}
        .menuLabel=${(role: Role) => `Actions for ${role.id}`}
        @row-select=${(e: CustomEvent<{ row: Role }>) => this.openView(e.detail.row)}
      ></versola-data-table>
    `;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private permissionGroups(role: Role): Array<{ category: string; permissions: Permission[] }> {
    const groups = new Map<string, Permission[]>();
    for (const permission of [...role.permissions].sort((a, b) => a.id.localeCompare(b.id))) {
      const category = getPermissionCategory(permission.id);
      groups.set(category, [...(groups.get(category) ?? []), permission]);
    }
    return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([category, permissions]) => ({ category, permissions }));
  }

  private renderGroups(role: Role) {
    const isDefault = this.defaultRoleIds.has(role.id);
    return html`
      <versola-record-card heading="General" ?editable=${this.canManage} .body=${html`
        ${this.row('Description', localizedValue(role.description, this.locales))}
      `} @edit=${() => this.openEditor('general')}></versola-record-card>

      <versola-record-card heading=${`Permissions (${role.permissions.length})`} subheading="What a user with this role may call." ?editable=${this.canManage}
        .body=${role.permissions.length ? this.permissionGroups(role).map(group => this.row(group.category, html`
            <div class="tag-list">${group.permissions.map(permission => {
              const full = this.permissions?.find(candidate => candidate.id === permission.id) ?? permission;
              return html`<span class="permission-tag"><span class="tag">${permission.id}</span><versola-permission-info .permission=${full} .resources=${this.resources}></versola-permission-info></span>`;
            })}</div>`))
          : html`<div class="record-row"><div class="record-value muted">No permissions. Users with this role can call nothing.</div></div>`}
        @edit=${() => this.openEditor('permissions')}></versola-record-card>

      <versola-record-card heading="Fixed" fixedLabel="Fixed" .body=${html`
        ${this.row('Role ID', html`<span class="mono">${role.id}</span>`, 'Set at creation')}
        ${this.row('Active', role.active ? 'Yes' : 'No', 'Cannot be changed here yet')}
      `}></versola-record-card>

      ${this.canManage ? html`
        <versola-danger-card heading="Delete role" text="Users assigned this role lose the permissions it grants. This can't be undone."
          actionLabel="Delete role" .actionAriaLabel=${`Delete role ${role.id}`} .disabledReason=${isDefault ? DEFAULT_ROLE_REASON : ''}
          @action=${() => this.handleDelete(role)}></versola-danger-card>` : ''}
    `;
  }

  private renderBanner(role: Role) {
    if (!this.defaultRoleIds.has(role.id)) return '';
    return html`
      <div class="banner info" role="status">
        <div class="banner-text">
          <div class="banner-title">${statusIcon('info')}Granted to every self-registered account</div>
          <div>Change the default roles under Challenges &amp; Security › Registration before deleting it.</div>
        </div>
        <button type="button" class="btn btn-secondary btn-sm"
          @click=${() => this.dispatchEvent(new CustomEvent('navigate-view', { detail: { view: 'challenges' }, bubbles: true, composed: true }))}>Open sign-in settings</button>
      </div>
    `;
  }

  private renderMeta(role: Role) {
    return html`
      ${this.statusChips(role)}
      <span class="chip">${role.permissions.length} ${role.permissions.length === 1 ? 'permission' : 'permissions'}</span>
    `;
  }

  private renderRecordPage(role: Role) {
    const title = getLocalizedDescription(role.description) || role.id;
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Roles', onSelect: () => this.closePanel() }, { label: title }]}
        .heading=${title} .recordId=${role.id} idLabel="role ID"
        .meta=${html`<span class="chip">Role</span>${this.renderMeta(role)}`}
      ></versola-record-header>
      ${this.renderBanner(role)}
      <div class="record-page">${this.renderGroups(role)}</div>
    `;
  }

  // ── editor ──

  private renderDescriptionField() {
    const error = this.draftDescriptionError;
    return html`
      <div class="form-group">
        <label for="role-description">Description *</label>
        <versola-localized-text-editor fieldId="role-description" label="Description" .showLabel=${false} .required=${true}
          .selectorBelowInput=${true} .value=${this.draftDescription} .locales=${this.locales}
          @localized-change=${(e: CustomEvent<{ value: Record<string, string> }>) => this.draftDescription = e.detail.value}
        ></versola-localized-text-editor>
        ${error ? html`<div class="error-message" role="alert">${error}</div>` : html`<div class="hint">English is required; other active locales are optional.</div>`}
      </div>
    `;
  }

  private renderPermissionsEditor() {
    return html`
      <div class="editor-box">
        <h3>Permissions (${this.draftPermissions.length} selected)</h3>
        <p class="editor-sub">Grouped by the text before the first <code>:</code>.</p>
        ${this.permissionsError ? html`
          <div class="editor-error" role="alert">Could not load permissions: ${this.permissionsError}
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.loadPermissions()}>Retry</button></div>`
        : this.permissions === null ? html`<div class="hint">Loading permissions…</div>`
        : html`
          <versola-permission-picker .permissions=${this.permissions} .resources=${this.resources} .value=${this.draftPermissions}
            @change=${(e: CustomEvent<{ value: Permission[] }>) => this.draftPermissions = e.detail.value}></versola-permission-picker>`}
      </div>
    `;
  }

  private renderEditor() {
    const mode = this.editorMode;
    const idError = this.draftIdError;
    return html`
      <form id="role-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${mode === 'create' ? html`
          <div class="editor-box">
            <h3>Basics</h3>
            <p class="editor-sub">The ID is what tokens and the API carry; the description is what people read.</p>
            <div class="form-group">
              <label for="role-id">Role ID *</label>
              <input type="text" id="role-id" class="compact-input mono ${idError ? 'input-error' : ''}" .value=${this.draftId} required
                aria-invalid=${idError ? 'true' : nothing} aria-describedby="role-id-hint" placeholder="e.g. shop-manager"
                @input=${(e: Event) => this.draftId = (e.target as HTMLInputElement).value} />
              ${idError ? html`<div class="error-message" id="role-id-hint" role="alert">${idError}</div>`
                : html`<div class="hint" id="role-id-hint">Lowercase letters, digits and dashes, starting with a letter.</div>`}
            </div>
            ${this.renderDescriptionField()}
          </div>
          ${this.renderPermissionsEditor()}
        ` : this.editGroup === 'general' ? html`
          <div class="editor-box">
            <h3>General</h3>
            <p class="editor-sub">What the console and the user's role list show for this role.</p>
            ${this.renderDescriptionField()}
          </div>
        ` : this.renderPermissionsEditor()}
      </form>
    `;
  }

  private renderPanel(role: Role | null) {
    const mode = this.editorMode;
    if (mode === 'view' && !role) return '';
    const title = role ? getLocalizedDescription(role.description) || role.id : '';
    const heading = mode === 'create' ? 'Create role' : mode === 'edit' ? `Edit ${this.editGroup}` : title;
    const submitLabel = mode === 'create' ? 'Create role' : this.editGroup === 'general' ? 'Save general' : 'Save permissions';
    const wide = mode === 'create' || this.editGroup === 'permissions';
    return html`
      <versola-side-panel kind="Role" .heading=${heading} .recordId=${role?.id ?? ''} idLabel="role ID" .mode=${mode} ?wide=${wide}
        .parentLabel=${title} .subtitle=${mode === 'create' ? `In ${this.tenantId}. The ID is fixed once created.` : ''}
        .meta=${role && mode === 'view' ? this.renderMeta(role) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()}
        @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">
          ${mode === 'view' ? html`${this.renderBanner(role!)}${this.renderGroups(role!)}` : this.renderEditor()}
        </div>
        ${mode !== 'view' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="role-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : submitLabel}</button>
          </div>` : ''}
      </versola-side-panel>
    `;
  }

  render() {
    if (!this.tenantId) {
      return html`
        <content-header title="Roles"></content-header>
        <versola-empty-state heading="Select a tenant" text="Select a tenant to manage roles."></versola-empty-state>
      `;
    }

    const role = this.openRole;
    const panel = this.renderPanel(role);
    if (role && this.route.layout === 'page') {
      return html`${this.renderRecordPage(role)}${this.editorMode === 'view' ? '' : panel}`;
    }

    return html`
      <content-header title="Roles" description="Named sets of permissions assigned to users of this tenant.">
        ${this.roles.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Role</button>` : ''}
      </content-header>

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load roles" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.roles.length === 0 ? html`
        <versola-empty-state heading="No roles yet" text="Create your first role to get started">
          ${this.canManage ? html`<button class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Role</button>` : ''}
        </versola-empty-state>
      ` : html`
        ${this.renderToolbar()}
        ${this.filteredRoles.length === 0 ? html`<versola-empty-state heading="No roles match"></versola-empty-state>` : this.renderTable()}
      `}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-roles-list': VersolaRolesList;
  }
}
