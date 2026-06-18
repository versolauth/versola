import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { Resource, Role, User, UserRoleAssignment, UserSearchField } from '../types';
import { getPermissions, getResources, getRoles } from '../utils/central-api';
import {
  createUser,
  fetchUserRoles,
  patchUserClaims,
  resetUserLimits,
  searchUsers,
  updateUser,
  updateUserRoles,
} from '../utils/users-api';
import './content-header';
import './error-card';
import './loading-cards';
import './user-form';
import './claim-edit';

@customElement('versola-users-list')
export class VersolaUsersList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;

  @state() private users: User[] = [];
  @state() private searchField: UserSearchField = 'login';
  @state() private searchQuery = '';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private showCreateForm = false;
  @state() private rolesOnlyForm = false;
  @state() private isEditingClaims = false;
  @state() private editingUser: User | null = null;
  @state() private editingUserAssignments: UserRoleAssignment[] = [];
  @state() private availableRoles: Role[] = [];
  @state() private availableResources: Resource[] = [];
  @state() private isPreparingForm = false;
  @state() private formError = '';
  @state() private hasSearched = false;
  @state() private userRoles: Record<string, UserRoleAssignment[]> = {};
  @state() private loadingRoles = new Set<string>();
  @state() private expandedClaims = new Set<string>();
  @state() private resettingLimits = new Set<string>();
  @state() private resetLimitsDone = new Set<string>();
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
        display: flex;
        align-items: center;
        justify-content: center;
        background: none;
        border: none;
        margin: 0;
        padding: 0.25rem;
        cursor: pointer;
        color: var(--text-secondary);
        font-size: 1.125rem;
        transition: all var(--transition-fast);
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
      }

      .expand-section-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-md) var(--spacing-lg);
      }

      .expand-section-title {
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--text-secondary);
        margin: 0;
      }

      .expand-section-content {
        padding: 0 var(--spacing-lg) var(--spacing-md);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-sm);
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
      this.availableResources = [];
      this.rolesTenantId = null;
      this.userRoles = {};
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
    const [, resources, roles] = await Promise.all([
      getPermissions(tenantId),
      getResources(tenantId),
      getRoles(tenantId),
    ]);
    if (this.tenantId === tenantId) {
      this.availableRoles = roles;
      this.availableResources = resources;
      this.rolesTenantId = tenantId;
    }
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

  private handleCreateClick() {
    this.editingUser = null;
    this.editingUserAssignments = [];
    this.showCreateForm = true;
  }

  private async handleEditClick(user: User, e: Event) {
    e.stopPropagation();
    this.isPreparingForm = true;
    try {
      await Promise.all([
        this.loadUserAssignments(user.id),
        this.ensureRolesLoaded(),
      ]);
      this.editingUser = user;
      this.rolesOnlyForm = false;
      this.showCreateForm = true;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to open user';
    } finally {
      this.isPreparingForm = false;
    }
  }

  private async handleEditRolesClick(user: User, e: Event) {
    e.stopPropagation();
    this.isPreparingForm = true;
    try {
      await Promise.all([
        this.loadUserAssignments(user.id),
        this.ensureRolesLoaded(),
      ]);
      this.editingUser = user;
      this.rolesOnlyForm = true;
      this.showCreateForm = true;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to open roles editor';
    } finally {
      this.isPreparingForm = false;
    }
  }

  private handleEditClaimsClick(user: User, e: Event) {
    e.stopPropagation();
    this.editingUser = user;
    this.isEditingClaims = true;
  }

  private handleClaimsClose() {
    this.isEditingClaims = false;
    this.editingUser = null;
  }

  private async handleClaimsSave(e: CustomEvent) {
    const { userId, patch } = e.detail;
    try {
      await patchUserClaims(userId, patch);
      // Update local state
      this.users = this.users.map(u => {
        if (u.id === userId) {
          const updatedClaims = { ...u.claims };
          for (const [k, v] of Object.entries(patch)) {
            if (v === null) delete updatedClaims[k];
            else updatedClaims[k] = v;
          }
          return { ...u, claims: updatedClaims };
        }
        return u;
      });
      this.handleClaimsClose();
    } catch (error) {
      this.errorPopup = error instanceof Error ? error.message : 'Failed to update claims';
    }
  }

  private handleFormClose() {
    this.showCreateForm = false;
    this.rolesOnlyForm = false;
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

  private async handleSaveRoles(e: CustomEvent) {
    const user = this.editingUser;
    if (!user) return;
    const { tenantId, adds, removes } = e.detail as { tenantId: string; adds: string[]; removes: string[] };
    try {
      await updateUserRoles(user.id, tenantId, adds, removes);
      await this.loadUserAssignments(user.id);
      this.handleFormClose();
    } catch (error) {
      this.errorPopup = error instanceof Error ? error.message : 'Failed to update roles';
    }
  }

  private async toggleUserRoles(userId: string) {
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

  private async handleResetLimits(user: User) {
    if (!this.tenantId) {
      this.errorPopup = 'Select a tenant first to reset limits.';
      return;
    }
    const tenantId = this.tenantId;
    this.resettingLimits = new Set([...this.resettingLimits, user.id]);
    this.resetLimitsDone = new Set([...this.resetLimitsDone].filter(id => id !== user.id));
    try {
      await resetUserLimits(user.id, tenantId, user.email, user.phone);
      this.resetLimitsDone = new Set([...this.resetLimitsDone, user.id]);
    } catch (error) {
      this.errorPopup = error instanceof Error ? error.message : 'Failed to reset limits';
    } finally {
      this.resettingLimits = new Set([...this.resettingLimits].filter(id => id !== user.id));
    }
  }

  private renderUserRoles(user: User) {
    const assignments = this.userRoles[user.id] ?? [];
    return html`
      <div class="expand-section">
        <div class="expand-section-header">
          <div class="expand-section-title">Roles</div>
          <button class="icon-action" title="Edit Roles"
            @click=${(e: Event) => this.handleEditRolesClick(user, e)}>✎</button>
        </div>
        ${assignments.length > 0 ? html`
          <div class="expand-section-content">
            ${assignments.map(a => html`
              <div class="role-row">
                <span class="role-tag">${a.roleId}</span>
              </div>
            `)}
          </div>
        ` : ''}
      </div>
    `;
  }

  private toggleUserClaims(userId: string) {
    const next = new Set(this.expandedClaims);
    if (next.has(userId)) next.delete(userId);
    else next.add(userId);
    this.expandedClaims = next;
  }

  private renderUserClaims(user: User) {
    const entries = Object.entries(user.claims);
    return html`
      <div class="expand-section">
        <div class="expand-section-header">
          <div class="expand-section-title">Claims</div>
          <button class="icon-action" title="Edit Claims"
            @click=${(e: Event) => this.handleEditClaimsClick(user, e)}>✎</button>
        </div>
        ${entries.length > 0 ? html`
          <div class="expand-section-content">
            ${entries.map(([key, value]) => {
              const isBool = typeof value === 'boolean';
              const valueClass = isBool
                ? (value ? 'claim-value bool-true' : 'claim-value bool-false')
                : 'claim-value';
              return html`
                <div class="prop-row">
                  <span class="prop-label">${key}</span>
                  <span class="${valueClass}">${String(value)}</span>
                </div>
              `;
            })}
          </div>
        ` : ''}
      </div>
    `;
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
          .availableResources=${this.availableResources}
          .roleAssignments=${this.editingUserAssignments}
          ?rolesOnly=${this.rolesOnlyForm}
          @close=${this.handleFormClose}
          @submit=${this.handleFormSubmit}
          @save-roles=${this.handleSaveRoles}
        ></versola-user-form>
      `;
    }

    if (this.isEditingClaims && this.editingUser) {
      return html`
        <versola-claim-edit
          .user=${this.editingUser}
          .tenantId=${this.tenantId}
          @close=${this.handleClaimsClose}
          @save=${this.handleClaimsSave}
        ></versola-claim-edit>
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
          <versola-error-card heading="Could not load users" .message=${this.errorMessage} @retry=${() => this.loadUsers()}></versola-error-card>
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
              </div>
              ${this.expandedClaims.has(user.id) ? this.renderUserClaims(user) : ''}
              ${user.id in this.userRoles ? this.renderUserRoles(user) : ''}
              <div class="card-action-row">
                ${!this.expandedClaims.has(user.id) ? html`<button class="btn btn-secondary btn-sm"
                  @click=${() => this.toggleUserClaims(user.id)}>Get Claims</button>` : ''}
                ${!(user.id in this.userRoles) ? html`<button class="btn btn-secondary btn-sm"
                  ?disabled=${this.loadingRoles.has(user.id) || !this.tenantId}
                  title=${!this.tenantId ? 'Select a tenant to view roles' : ''}
                  @click=${() => this.toggleUserRoles(user.id)}>
                  ${this.loadingRoles.has(user.id) ? 'Loading…' : 'Get Roles'}
                </button>` : ''}
                <button class="btn btn-secondary btn-sm"
                  ?disabled=${this.resettingLimits.has(user.id) || !this.tenantId}
                  title=${!this.tenantId ? 'Select a tenant to reset limits' : 'Clear rate-limit counters for this user'}
                  @click=${() => this.handleResetLimits(user)}>
                  ${this.resettingLimits.has(user.id)
                    ? 'Resetting…'
                    : this.resetLimitsDone.has(user.id)
                      ? 'Limits Reset ✓'
                      : 'Reset Limits'}
                </button>
              </div>
            </div>
          `)}
        `}
    `;
  }
}
