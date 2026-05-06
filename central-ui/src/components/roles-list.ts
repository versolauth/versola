import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { Permission, Resource, Role } from '../types';
import {
  createRole,
  deleteRole,
  fetchAllPermissions,
  fetchResources,
  fetchAllRoles,
  updateRole,
} from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { getLocalizedDescription } from '../utils/helpers';
import './role-form';
import './content-header';
import './loading-cards';

@customElement('versola-roles-list')
export class VersolaRolesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;

  @state() private roles: Role[] = [];
  @state() private searchQuery = '';
  @state() private showCreateForm = false;
  @state() private editingRole: Role | null = null;
  @state() private expandedRoles: Set<string> = new Set();
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private availablePermissions: Permission[] = [];
  @state() private availableResources: Resource[] = [];
  @state() private isPreparingForm = false;
  private loadRequestId = 0;
  private formPermissionsTenantId: string | null = null;

  updated(changedProperties: Map<string, unknown>) {
    if (changedProperties.has('tenantId')) {
      this.expandedRoles = new Set();
      this.availablePermissions = [];
      this.availableResources = [];
      this.formPermissionsTenantId = null;
    }

    if (changedProperties.has('tenantId')) {
      void this.loadData();
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
      const result = await fetchAllRoles(this.tenantId);
      if (requestId !== this.loadRequestId) return;
      this.roles = result;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.roles = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load roles';
    } finally {
      if (requestId === this.loadRequestId) {
        this.isLoading = false;
      }
    }
  }

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    css`
      :host {
        display: block;
      }

      .search-bar {
        margin-bottom: var(--spacing-lg);
        max-width: 28rem;
      }

      .header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: var(--spacing-xl);
      }

      .title {
        font-size: 2rem;
        font-weight: 700;
        color: var(--text-primary);
        margin: 0;
      }

      .role-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        margin-bottom: var(--spacing-md);
        transition: border-color var(--transition-base);
      }

      .role-card:hover {
        border-color: var(--accent);
      }

      .role-card.inactive {
        opacity: 0.6;
      }

      .role-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-lg);
        cursor: pointer;
        user-select: none;
      }

      .role-info {
        flex: 1;
      }

      .role-name {
        font-size: 1.125rem;
        font-weight: 600;
        color: var(--text-primary);
        margin-bottom: 0.25rem;
        display: flex;
        align-items: center;
        gap: 0.75rem;
      }

      .role-id {
        color: var(--accent);
        font-size: 0.875rem;
        font-family: var(--font-mono);
        word-break: break-all;
      }

      .role-description {
        color: var(--text-secondary);
        font-size: 0.875rem;
      }

      .role-actions {
        display: flex;
        gap: 0.5rem;
        margin-left: var(--spacing-md);
      }

      .badge {
        display: inline-block;
        padding: 0.25rem 0.625rem;
        border-radius: var(--radius-sm);
        font-size: 0.75rem;
        font-weight: 600;
      }

      .badge-success {
        background: rgba(63, 185, 80, 0.15);
        color: var(--success);
      }

      .badge-danger {
        background: rgba(248, 81, 73, 0.15);
        color: var(--danger);
      }

      .role-body {
        border-top: 1px solid var(--border-dark);
        padding: var(--spacing-lg);
      }

      .permissions-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
        gap: var(--spacing-sm);
      }

      .permission-item {
        background: rgba(0, 0, 0, 0.2);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-sm) var(--spacing-md);
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--accent);
      }

      .permission-id {
        display: block;
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

      .icon-action {
        background: none;
        border: none;
        padding: 0.25rem;
        cursor: pointer;
        color: var(--text-secondary);
        font-size: 1.125rem;
        transition: all var(--transition-fast);
        line-height: 1;
      }

      .icon-action:hover {
        color: var(--accent);
        transform: scale(1.15);
      }

      .icon-action.danger:hover {
        color: var(--danger);
      }
    `,
  ];

  private toggleExpand(roleId: string) {
    if (this.expandedRoles.has(roleId)) {
      this.expandedRoles.delete(roleId);
    } else {
      this.expandedRoles.add(roleId);
    }
    this.requestUpdate();
  }

  private handleCreateClick() {
    void this.openForm(null);
  }

  private handleEditClick(role: Role, e: Event) {
    e.stopPropagation();
    void this.openForm(role);
  }

  private async ensureFormPermissionsLoaded() {
    const tenantId = this.tenantId;

    if (!tenantId || this.formPermissionsTenantId === tenantId) {
      return;
    }

    const [permissions, resources] = await Promise.all([
      fetchAllPermissions(tenantId),
      fetchResources(tenantId),
    ]);

    if (this.tenantId === tenantId) {
      this.availablePermissions = permissions;
      this.availableResources = resources;
      this.formPermissionsTenantId = tenantId;
    }
  }

  private async openForm(role: Role | null) {
    if (!this.tenantId) {
      this.errorMessage = 'Select a tenant first to manage roles.';
      return;
    }

    this.isPreparingForm = true;

    try {
      await this.ensureFormPermissionsLoaded();
      this.editingRole = role;
      this.showCreateForm = true;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load permissions';
    } finally {
      this.isPreparingForm = false;
    }
  }

  private async handleDeleteClick(role: Role, e: Event) {
    e.stopPropagation();
    if (!this.tenantId) {
      return;
    }

    const confirmed = await confirmDestructiveAction({
      title: 'Delete role',
      messagePrefix: 'Delete role ',
      messageSubject: role.id,
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });

    if (!confirmed) {
      return;
    }

    try {
      await deleteRole(this.tenantId, role.id);
      this.removeRoleFromState(role.id);
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to delete role');
    }
  }

  private handleFormClose() {
    this.showCreateForm = false;
    this.editingRole = null;
    this.isPreparingForm = false;
  }

  private updateRoleInState(role: Role, previousId: string) {
    this.roles = this.roles.map(existing =>
      existing.id === previousId ? role : existing,
    );

    if (this.editingRole?.id === previousId) {
      this.editingRole = role;
    }
  }

  private addRoleToState(role: Role) {
    this.roles = [role, ...this.roles];
    this.expandedRoles = new Set([...this.expandedRoles, role.id]);
  }

  private removeRoleFromState(roleId: string) {
    this.roles = this.roles.filter(role => role.id !== roleId);

    const expandedRoles = new Set(this.expandedRoles);
    expandedRoles.delete(roleId);
    this.expandedRoles = expandedRoles;

    if (this.editingRole?.id === roleId) {
      this.editingRole = null;
      this.showCreateForm = false;
    }
  }

  private async handleFormSubmit(e: CustomEvent) {
    if (!this.tenantId) {
      return;
    }

    const role = e.detail.role as Role;

    try {
      if (this.editingRole) {
        const previousId = this.editingRole.id;
        await updateRole(this.tenantId, this.editingRole, role);
        this.updateRoleInState(role, previousId);
      } else {
        await createRole(this.tenantId, role);
        this.addRoleToState(role);
      }

      this.handleFormClose();
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to save role');
    }
  }

  private get filteredRoles(): Role[] {
    const query = this.searchQuery.trim().toLowerCase();
    if (!query) return this.roles;
    return this.roles.filter(role => role.id.toLowerCase().includes(query));
  }

  render() {
    if (this.isPreparingForm && !this.showCreateForm) {
      return html`
        <content-header
          title="Roles"
        ></content-header>
        <div class="card">
          <div class="empty-state">
            <div class="empty-state-icon">⏳</div>
            <h3>Loading role permissions…</h3>
            <p>Fetching available permissions for the selected tenant.</p>
          </div>
        </div>
      `;
    }

    if (this.showCreateForm) {
      return html`
        <versola-role-form
          .roleData=${this.editingRole}
          .availablePermissions=${this.availablePermissions}
          .availableResources=${this.availableResources}
          @close=${this.handleFormClose}
          @submit=${this.handleFormSubmit}
        ></versola-role-form>
      `;
    }

    return html`
      <content-header
        title="Roles"
      >
        ${this.roles.length > 0 ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick} ?disabled=${this.isPreparingForm || !this.tenantId}>
            + Create Role
          </button>
        ` : ''}
      </content-header>

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <div class="card">
          <div class="empty-state">
            <div class="empty-state-icon">⚠️</div>
            <h3>Could not load roles</h3>
            <p>${this.errorMessage}</p>
            <button class="btn btn-primary" @click=${() => this.loadData()} style="margin-top: 1rem;">
              Retry
            </button>
          </div>
        </div>
      ` : this.roles.length === 0 ? html`
        <div class="card">
          <div class="empty-state">
            <h3>No roles yet</h3>
            <p>Create your first role to get started</p>
            <button class="btn btn-primary" @click=${this.handleCreateClick} ?disabled=${this.isPreparingForm || !this.tenantId} style="margin-top: 1rem;">
              + Create Role
            </button>
          </div>
        </div>
      ` : this.filteredRoles.length === 0 ? html`
        <div class="search-bar">
          <input class="form-input" type="search" aria-label="Search roles" .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} placeholder="Search roles by ID" />
        </div>
        <div class="card">
          <div class="empty-state">
            <h3>No roles match your search</h3>
          </div>
        </div>
      ` : html`
        <div>
          <div class="search-bar">
            <input class="form-input" type="search" aria-label="Search roles" .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} placeholder="Search roles by ID" />
          </div>
          ${this.filteredRoles.map(role => {
            const isExpanded = this.expandedRoles.has(role.id);
            return html`
            <div class="role-card ${!role.active ? 'inactive' : ''}">
              <div class="role-header" @click=${() => this.toggleExpand(role.id)}>
                <div class="role-info">
                  <div class="role-name">
                    ${getLocalizedDescription(role.description)}
                    ${!role.active ? html`
                      <span class="badge badge-danger">Inactive</span>
                    ` : ''}
                  </div>
                  <div class="role-id">${role.id}</div>
                </div>

                <div class="role-actions" @click=${(e: Event) => e.stopPropagation()}>
                  <button
                    class="icon-action"
                    @click=${(e: Event) => this.handleEditClick(role, e)}
                    ?disabled=${this.isPreparingForm}
                    title="Edit"
                    aria-label=${`Edit role ${role.id}`}
                  >
                    ✎
                  </button>
                  <button
                    class="icon-action danger"
                    @click=${(e: Event) => this.handleDeleteClick(role, e)}
                    title="Delete"
                    aria-label=${`Delete role ${role.id}`}
                  >
                    ✕
                  </button>
                </div>
              </div>

              ${isExpanded && role.permissions.length > 0 ? html`
                <div class="role-body">
                  <div class="permissions-grid">
                    ${role.permissions.map(perm => html`
                      <div class="permission-item" title="${perm.id}">
                        <span class="permission-id">${perm.id}</span>
                      </div>
                    `)}
                  </div>
                </div>
              ` : ''}
            </div>
          `;
          })}
        </div>
      `}
    `;
  }
}

