import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { OAuthScope, Role, User, UserRoleAssignment, UserSearchField } from '../types';
import { fetchAllRoles } from '../utils/central-api';
import {
  assignUserRole,
  createUser,
  fetchAvailableScopes,
  fetchUserRoles,
  removeUserRole,
  searchUsers,
  updateUser,
} from '../utils/users-api';
import './content-header';
import './loading-cards';
import './user-form';

@customElement('versola-users-list')
export class VersolaUsersList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;

  @state() private users: User[] = [];
  @state() private searchField: UserSearchField = 'login';
  @state() private searchQuery = '';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private showCreateForm = false;
  @state() private editingUser: User | null = null;
  @state() private editingUserAssignments: UserRoleAssignment[] = [];
  @state() private availableRoles: Role[] = [];
  @state() private availableScopes: OAuthScope[] = [];
  @state() private isPreparingForm = false;
  @state() private formError = '';
  @state() private hasSearched = false;
  @state() private userRoles: Record<string, UserRoleAssignment[]> = {};
  @state() private loadingRoles = new Set<string>();
  @state() private errorPopup = '';
  private loadRequestId = 0;
  private rolesTenantId: string | null = null;


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
        display: flex;
        gap: var(--spacing-sm);
        margin-bottom: var(--spacing-lg);
        max-width: 36rem;
      }

      .search-bar select {
        max-width: 8rem;
      }

      .user-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        margin-bottom: var(--spacing-md);
        transition: border-color var(--transition-base);
      }

      .user-card:hover {
        border-color: var(--accent);
      }

      .user-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-lg);
      }

      .user-info {
        flex: 1;
        min-width: 0;
      }

      .user-name {
        font-size: 1.125rem;
        font-weight: 600;
        color: var(--text-primary);
        margin-bottom: 0.25rem;
      }



      .user-id {
        color: var(--accent);
        font-family: var(--font-mono);
        font-size: 0.875rem;
        word-break: break-all;
      }

      .user-actions {
        display: flex;
        gap: 0.5rem;
        margin-left: var(--spacing-md);
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

      .icon-action:hover { color: var(--accent); transform: scale(1.15); }
      .icon-action.danger:hover { color: var(--danger); }

      .user-body {
        border-top: 1px solid var(--border-dark);
        padding: var(--spacing-lg);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
      }

      .prop-row {
        display: grid;
        grid-template-columns: 11rem 1fr;
        gap: var(--spacing-md);
        align-items: baseline;
        font-size: 0.875rem;
      }

      .prop-label {
        color: var(--text-secondary);
        font-size: 0.75rem;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        font-weight: 600;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .prop-value {
        color: var(--text-primary);
        word-break: break-word;
        overflow-wrap: anywhere;
        min-width: 0;
      }

      .prop-value.muted {
        color: var(--text-secondary);
        font-style: italic;
      }

      .card-action-row {
        display: flex;
        gap: var(--spacing-sm);
        padding: var(--spacing-md) var(--spacing-lg);
        border-top: 1px solid var(--border-dark);
      }

      .expand-section {
        border-top: 1px solid var(--border-dark);
        padding: var(--spacing-md) var(--spacing-lg);
      }

      .expand-section-title {
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--text-secondary);
        margin-bottom: var(--spacing-sm);
      }

      .role-row {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        padding: var(--spacing-xs) 0;
        font-size: 0.875rem;
      }

      .role-tag {
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--accent);
      }

      .role-tenant {
        color: var(--text-secondary);
        font-size: 0.75rem;
      }

      .claim-value {
        color: var(--text-primary);
        word-break: break-word;
        overflow-wrap: anywhere;
        min-width: 0;
      }

      .claim-value.bool-true  { color: var(--success, #4ade80); }
      .claim-value.bool-false { color: var(--danger); }

      .no-data {
        color: var(--text-secondary);
        font-style: italic;
        font-size: 0.875rem;
      }

      .empty-state {
        text-align: center;
        padding: 3rem;
        color: var(--text-secondary);
      }

      .empty-state-icon { font-size: 3rem; margin-bottom: 1rem; }
    `,
  ];


  updated(changedProperties: Map<string, unknown>) {
    if (changedProperties.has('tenantId')) {
      this.availableRoles = [];
      this.rolesTenantId = null;
    }
  }

  private async loadUsers() {
    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';
    this.hasSearched = true;
    try {
      const result = await searchUsers(this.searchField, this.searchQuery);
      if (requestId !== this.loadRequestId) return;
      this.users = result;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.users = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load users';
    } finally {
      if (requestId === this.loadRequestId) this.isLoading = false;
    }
  }

  private async ensureRolesLoaded() {
    const tenantId = this.tenantId;
    if (!tenantId || this.rolesTenantId === tenantId) return;
    const roles = await fetchAllRoles(tenantId);
    if (this.tenantId === tenantId) {
      this.availableRoles = roles;
      this.rolesTenantId = tenantId;
    }
  }

  private async ensureScopesLoaded() {
    if (this.availableScopes.length > 0) return;
    this.availableScopes = await fetchAvailableScopes();
  }

  private async loadUserAssignments(userId: string) {
    if (!this.tenantId) {
      this.editingUserAssignments = [];
      return;
    }
    this.editingUserAssignments = await fetchUserRoles(userId, this.tenantId);
  }

  private detectSearchField(query: string): UserSearchField {
    const trimmed = query.trim();
    const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (UUID_RE.test(trimmed)) return 'id';
    if (trimmed.startsWith('+')) return 'phone';
    if (trimmed.includes('@')) return 'email';
    return 'login';
  }

  private resolveDisplayName(user: User): string {
    const claims = user.claims;
    const str = (key: string) => {
      const v = claims[key];
      return typeof v === 'string' ? v.trim() : '';
    };
    const name = str('name');
    if (name) return name;
    const given = str('given_name');
    const family = str('family_name');
    const middle = str('middle_name');
    if (middle) {
      const parts = [given, middle, family].filter(Boolean);
      if (parts.length) return parts.join(' ');
    } else {
      const parts = [family, given].filter(Boolean);
      if (parts.length) return parts.join(' ');
    }
    return '';
  }

  private handleSearchSubmit(e: Event) {
    e.preventDefault();
    if (!this.searchQuery.trim()) return;
    void this.loadUsers();
  }

  private async handleCreateClick() {
    this.isPreparingForm = true;
    try {
      await this.ensureScopesLoaded();
      this.editingUser = null;
      this.editingUserAssignments = [];
      this.showCreateForm = true;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to open form';
    } finally {
      this.isPreparingForm = false;
    }
  }

  private async handleEditClick(user: User, e: Event) {
    e.stopPropagation();
    this.isPreparingForm = true;
    try {
      await Promise.all([
        this.loadUserAssignments(user.id),
        this.ensureRolesLoaded(),
        this.ensureScopesLoaded(),
      ]);
      this.editingUser = user;
      this.showCreateForm = true;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to open user';
    } finally {
      this.isPreparingForm = false;
    }
  }

  private handleFormClose() {
    this.showCreateForm = false;
    this.editingUser = null;
    this.editingUserAssignments = [];
    this.formError = '';
  }

  private async handleFormSubmit(e: CustomEvent) {
    this.formError = '';
    const user = e.detail.user as Omit<User, 'id'> & { id?: string };
    try {
      if (this.editingUser) {
        const updated = user as User;
        await updateUser(this.editingUser, updated);
        this.users = this.users.map(existing => existing.id === updated.id ? updated : existing);
        this.handleFormClose();
      } else {
        const created = await createUser(user);
        this.users = [created, ...this.users];
        this.handleFormClose();
      }
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save user';
    }
  }

  private async handleAssignRole(e: CustomEvent) {
    const user = this.editingUser;
    if (!user) return;
    const { tenantId, roleId } = e.detail as { tenantId: string; roleId: string };
    try {
      await assignUserRole(user.id, tenantId, roleId);
      await this.loadUserAssignments(user.id);
    } catch (error) {
      this.errorPopup = error instanceof Error ? error.message : 'Failed to assign role';
    }
  }

  private async toggleUserRoles(userId: string) {
    if (userId in this.userRoles) {
      const { [userId]: _, ...rest } = this.userRoles;
      this.userRoles = rest;
      return;
    }
    if (!this.tenantId) {
      this.errorPopup = 'Select a tenant first to view user roles.';
      return;
    }
    const tenantId = this.tenantId;
    this.loadingRoles = new Set([...this.loadingRoles, userId]);
    try {
      const roles = await fetchUserRoles(userId, tenantId);
      this.userRoles = { ...this.userRoles, [userId]: roles };
    } catch (error) {
      this.errorPopup = error instanceof Error ? error.message : 'Failed to load roles';
    } finally {
      this.loadingRoles = new Set([...this.loadingRoles].filter(id => id !== userId));
    }
  }

  private renderUserRoles(userId: string) {
    const assignments = this.userRoles[userId] ?? [];
    return html`
      <div class="expand-section">
        <div class="expand-section-title">Roles</div>
        ${assignments.length === 0 ? html`<div class="no-data">No roles assigned</div>` : assignments.map(a => html`
          <div class="role-row">
            <span class="role-tag">${a.roleId}</span>
            <span class="role-tenant">in ${a.tenantId}</span>
          </div>
        `)}
      </div>
    `;
  }

  private renderClaimRows(user: User) {
    return Object.entries(user.claims).map(([key, value]) => {
      const isBool = typeof value === 'boolean';
      const valueClass = isBool ? (value ? 'claim-value bool-true' : 'claim-value bool-false') : 'claim-value';
      return html`
        <div class="prop-row">
          <span class="prop-label">${key}</span>
          <span class="${valueClass}">${String(value)}</span>
        </div>
      `;
    });
  }

  private async handleRemoveRole(e: CustomEvent) {
    const user = this.editingUser;
    if (!user) return;
    const { tenantId, roleId } = e.detail as { tenantId: string; roleId: string };
    try {
      await removeUserRole(user.id, tenantId, roleId);
      await this.loadUserAssignments(user.id);
    } catch (error) {
      this.errorPopup = error instanceof Error ? error.message : 'Failed to remove role';
    }
  }

  private renderErrorPopup() {
    if (!this.errorPopup) return '';
    return html`
      <dialog open style="
        position:fixed;inset:0;margin:auto;
        border:1px solid var(--danger);
        border-radius:var(--radius-lg);
        background:var(--bg-dark-card);
        color:var(--text-primary);
        padding:var(--spacing-xl);
        max-width:28rem;width:90%;
        box-shadow:0 8px 32px rgba(0,0,0,0.5);
        z-index:1000;
      ">
        <p style="margin:0 0 var(--spacing-lg)">${this.errorPopup}</p>
        <div style="display:flex;justify-content:flex-end">
          <button class="btn btn-primary" @click=${() => { this.errorPopup = ''; }}>OK</button>
        </div>
      </dialog>
    `;
  }

  render() {
    if (this.showCreateForm) {
      return html`
        ${this.renderErrorPopup()}
        ${this.formError ? html`
          <dialog open style="
            position:fixed;inset:0;margin:auto;
            border:1px solid var(--danger);
            border-radius:var(--radius-lg);
            background:var(--bg-dark-card);
            color:var(--text-primary);
            padding:var(--spacing-xl);
            max-width:28rem;width:90%;
            box-shadow:0 8px 32px rgba(0,0,0,0.5);
            z-index:1000;
          ">
            <p style="margin:0 0 var(--spacing-lg)">${this.formError}</p>
            <div style="display:flex;justify-content:flex-end">
              <button class="btn btn-primary" @click=${() => { this.formError = ''; }}>OK</button>
            </div>
          </dialog>
        ` : ''}
        <versola-user-form
          .userData=${this.editingUser}
          .tenantId=${this.tenantId}
          .availableRoles=${this.availableRoles}
          .availableScopes=${this.availableScopes}
          .roleAssignments=${this.editingUserAssignments}
          @close=${this.handleFormClose}
          @submit=${this.handleFormSubmit}
          @assign-role=${this.handleAssignRole}
          @remove-role=${this.handleRemoveRole}
        ></versola-user-form>
      `;
    }

    return html`
      ${this.renderErrorPopup()}
      <content-header title="Users" description="Search, create, and assign roles to users">
        <button slot="actions" class="btn btn-primary" ?disabled=${this.isPreparingForm}
          @click=${this.handleCreateClick}>+ Create User</button>
      </content-header>

      <form class="search-bar" @submit=${this.handleSearchSubmit}>
        <select .value=${this.searchField}
          @change=${(e: Event) => {
            this.searchField = (e.target as HTMLSelectElement).value as UserSearchField;
          }}>
          <option value="login">Login</option>
          <option value="email">Email</option>
          <option value="phone">Phone</option>
          <option value="id">ID</option>
        </select>
        <input type="search" placeholder="Search users…" .value=${this.searchQuery}
          @input=${(e: Event) => {
            this.searchQuery = (e.target as HTMLInputElement).value;
            this.searchField = this.detectSearchField(this.searchQuery);
          }} />
        <button type="submit" class="btn btn-secondary">Search</button>
      </form>

      ${!this.hasSearched ? html`
          <div class="card">
            <div class="empty-state">
              <div class="empty-state-icon">🔍</div>
              <h3>Search for a user</h3>
              <p>Enter a query above to find users by login, email, phone, or ID.</p>
            </div>
          </div>
        ` : this.isLoading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
        : this.errorMessage ? html`
          <div class="card">
            <div class="empty-state">
              <div class="empty-state-icon">⚠️</div>
              <h3>Could not load users</h3>
              <p>${this.errorMessage}</p>
              <button class="btn btn-primary" style="margin-top:1rem" @click=${() => this.loadUsers()}>Retry</button>
            </div>
          </div>
        ` : this.users.length === 0 ? html`
          <div class="card">
            <div class="empty-state">
              <h3>No users found</h3>
              <p>Adjust your search or create a new user.</p>
            </div>
          </div>
        ` : html`
          ${this.users.map(user => html`
            <div class="user-card">
              <div class="user-header">
                <div class="user-info">
                  ${(() => {
                    const name = this.resolveDisplayName(user);
                    return name
                      ? html`<div class="user-name">${name}</div>`
                      : html`<div class="user-name no-name">No Name</div>`;
                  })()}
                  <div class="user-id">${user.id}</div>
                </div>
                <div class="user-actions">
                  <button class="icon-action" title="Edit" ?disabled=${this.isPreparingForm}
                    @click=${(e: Event) => this.handleEditClick(user, e)}>✎</button>
                </div>
              </div>
              <div class="user-body">
                <div class="prop-row">
                  <span class="prop-label">Email</span>
                  <span class="prop-value ${user.email ? '' : 'muted'}">${user.email ?? '—'}</span>
                </div>
                <div class="prop-row">
                  <span class="prop-label">Phone</span>
                  <span class="prop-value ${user.phone ? '' : 'muted'}">${user.phone ?? '—'}</span>
                </div>
                <div class="prop-row">
                  <span class="prop-label">Login</span>
                  <span class="prop-value ${user.login ? '' : 'muted'}">${user.login ?? '—'}</span>
                </div>
                ${this.renderClaimRows(user)}
              </div>
              <div class="card-action-row">
                <button class="btn btn-secondary btn-sm"
                  ?disabled=${this.loadingRoles.has(user.id) || !this.tenantId}
                  title=${!this.tenantId ? 'Select a tenant to view roles' : ''}
                  @click=${() => this.toggleUserRoles(user.id)}>
                  ${this.loadingRoles.has(user.id) ? 'Loading…' : user.id in this.userRoles ? 'Hide Roles' : 'Get Roles'}
                </button>
              </div>
              ${user.id in this.userRoles ? this.renderUserRoles(user.id) : ''}
            </div>
          `)}
        `}
    `;
  }
}
