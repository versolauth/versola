import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { Resource, Role, User, UserRoleAssignment, UserSearchField } from '../types';
import { getPermissions, getResources, getRoles } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import {
  createUser,
  deletePasskey,
  fetchUserRoles,
  listPasskeys,
  fetchUserSessions,
  invalidateUserSession,
  patchUserClaims,
  renamePasskey,
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
  @state() private userSessions: Record<string, UserSession[]> = {};
  @state() private loadingSessions = new Set<string>();
  @state() private expandedClaims = new Set<string>();
  @state() private resettingLimits = new Set<string>();
  @state() private resetLimitsDone = new Set<string>();
  @state() private userPasskeys: Record<string, PasskeyInfo[]> = {};
  @state() private loadingPasskeys = new Set<string>();
  @state() private errorPopup = '';
  @state() private openInfoKey: string | null = null;
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
      
      .session-card {
        padding: var(--spacing-xs) 0;
        border-bottom: 1px solid var(--border-subtle, rgba(255,255,255,0.06));
        font-size: 0.875rem;
      }

      .session-card:last-child {
        border-bottom: none;
      }

      .session-card-info {
        display: flex;
        flex-direction: column;
        gap: 0.125rem;
      }

      .session-platform {
        font-weight: 600;
        color: var(--text-primary);
        font-size: 0.8125rem;
        text-transform: capitalize;
      }

      .session-browser {
        color: var(--text-secondary);
        font-size: 0.75rem;
      }

      .session-client {
        color: var(--text-secondary);
        font-size: 0.75rem;
        font-family: var(--font-mono);
      }

      .session-date {
        color: var(--text-tertiary, var(--text-secondary));
        font-size: 0.75rem;
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

      .passkey-card {
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        overflow: hidden;
      }

      .passkey-card-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: var(--spacing-md) var(--spacing-md);
        border-bottom: 1px solid var(--border-dark);
        gap: var(--spacing-md);
      }

      .passkey-card-name {
        font-size: 0.9375rem;
        font-weight: 600;
        color: var(--text-primary);
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .passkey-card-name.muted {
        font-weight: 400;
        font-style: italic;
        color: var(--text-secondary);
      }

      .passkey-card-actions {
        display: flex;
        gap: var(--spacing-xs);
        flex-shrink: 0;
      }

      .passkey-card-body {
        padding: var(--spacing-md) var(--spacing-md);
        display: grid;
        grid-template-columns: 9rem 1fr;
        gap: var(--spacing-sm) var(--spacing-md);
        font-size: 0.8125rem;
      }

      .passkey-prop-label {
        color: var(--text-secondary);
        font-size: 0.75rem;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        font-weight: 600;
        align-self: center;
      }

      .passkey-prop-value {
        color: var(--text-primary);
        word-break: break-all;
        overflow-wrap: anywhere;
        min-width: 0;
        align-self: center;
      }

      .passkey-prop-value.muted {
        color: var(--text-secondary);
        font-style: italic;
      }

      .passkey-prop-value.mono {
        font-family: var(--font-mono);
        font-size: 0.75rem;
        color: var(--accent);
      }

      .passkey-badges {
        display: flex;
        gap: var(--spacing-xs);
        flex-wrap: wrap;
      }

      .passkey-badge {
        display: inline-flex;
        align-items: center;
        gap: 0.25rem;
        padding: 0.125rem 0.5rem;
        border-radius: 999px;
        font-size: 0.7rem;
        font-weight: 600;
        letter-spacing: 0.03em;
        background: rgba(99,102,241,0.12);
        color: var(--accent);
        border: 1px solid rgba(99,102,241,0.25);
      }

      .passkey-badge.synced {
        background: rgba(74,222,128,0.1);
        color: var(--success, #4ade80);
        border-color: rgba(74,222,128,0.25);
      }

      .passkey-badge.not-backed-up {
        background: rgba(148,163,184,0.08);
        color: var(--text-secondary);
        border-color: rgba(148,163,184,0.18);
      }

      .option-info {
        position: relative;
        display: inline-flex;
        align-items: center;
        flex: none;
      }

      .option-info-button {
        flex: none;
        border: 1px solid rgba(88, 166, 255, 0.4);
        border-radius: 999px;
        background: rgba(88, 166, 255, 0.12);
        color: var(--accent);
        font-size: 0.75rem;
        font-weight: 700;
        line-height: 1;
        width: 1.25rem;
        height: 1.25rem;
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        padding: 0;
      }

      .option-info-button:hover {
        background: rgba(88, 166, 255, 0.18);
        border-color: rgba(88, 166, 255, 0.55);
      }

      .option-info-button:focus-visible {
        outline: none;
        box-shadow: 0 0 0 2px rgba(88, 166, 255, 0.2);
      }

      .option-tooltip {
        position: absolute;
        left: 0;
        top: calc(100% + 0.4rem);
        z-index: 20;
        min-width: 20rem;
        max-width: min(30rem, 75vw);
        max-height: 22rem;
        overflow: auto;
        padding: 0.75rem;
        border: 1px solid rgba(88, 166, 255, 0.28);
        border-radius: var(--radius-md);
        background: linear-gradient(180deg, rgba(22, 27, 34, 0.98), rgba(13, 17, 23, 0.98));
        box-shadow: 0 10px 24px rgba(0, 0, 0, 0.35);
        display: none;
      }

      .option-info.option-info-open .option-tooltip {
        display: block;
      }

      .option-tooltip-title {
        margin-bottom: 0.5rem;
        color: var(--accent);
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .option-tooltip-group {
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-sm);
        background: rgba(255, 255, 255, 0.03);
        padding: 0.5rem 0.625rem;
        margin-bottom: 0.375rem;
      }

      .option-tooltip-group-title {
        font-size: 0.7rem;
        font-weight: 600;
        color: var(--accent);
        text-transform: uppercase;
        letter-spacing: 0.04em;
        margin-bottom: 0.25rem;
      }

      .option-tooltip-item {
        color: var(--text-primary);
        font-size: 0.75rem;
        line-height: 1.4;
        overflow-wrap: anywhere;
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

  private async toggleUserSessions(userId: string) {
    this.loadingSessions = new Set([...this.loadingSessions, userId]);
    try {
      const sessions = await fetchUserSessions(userId);
      this.userSessions = { ...this.userSessions, [userId]: sessions };
    } catch (error) {
      this.errorPopup = error instanceof Error ? error.message : 'Failed to load sessions';
    } finally {
      this.loadingSessions = new Set([...this.loadingSessions].filter(id => id !== userId));
    }
  }

  private async handleInvalidateSession(userId: string) {
    try {
      await invalidateUserSession(userId);
      const sessions = await fetchUserSessions(userId);
      this.userSessions = {...this.userSessions, [userId]: sessions};
    } catch (error) {
      this.errorPopup = error instanceof Error ? error.message : 'Failed to invalidate session';
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

  private async toggleUserPasskeys(userId: string) {
    if (userId in this.userPasskeys) {
      const { [userId]: _removed, ...rest } = this.userPasskeys;
      this.userPasskeys = rest;
      return;
    }
    this.loadingPasskeys = new Set([...this.loadingPasskeys, userId]);
    try {
      const passkeys = await listPasskeys(userId);
      this.userPasskeys = { ...this.userPasskeys, [userId]: passkeys };
    } catch (error) {
      this.errorPopup = error instanceof Error ? error.message : 'Failed to load passkeys';
    } finally {
      this.loadingPasskeys = new Set([...this.loadingPasskeys].filter(id => id !== userId));
    }
  }

  private async handleRenamePasskey(userId: string, passkey: PasskeyInfo) {
    const next = window.prompt('Passkey name', passkey.name ?? '');
    if (next === null) return;
    const trimmed = next.trim();
    const name = trimmed.length > 0 ? trimmed : null;
    try {
      await renamePasskey(userId, passkey.id, name);
      this.userPasskeys = {
        ...this.userPasskeys,
        [userId]: (this.userPasskeys[userId] ?? []).map(p => p.id === passkey.id ? { ...p, name } : p),
      };
    } catch (error) {
      this.errorPopup = error instanceof Error ? error.message : 'Failed to rename passkey';
    }
  }

  private async handleDeletePasskey(userId: string, passkey: PasskeyInfo) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete passkey',
      messagePrefix: 'Delete passkey ',
      messageSubject: passkey.name ?? passkey.id,
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    try {
      await deletePasskey(userId, passkey.id);
      this.userPasskeys = {
        ...this.userPasskeys,
        [userId]: (this.userPasskeys[userId] ?? []).filter(p => p.id !== passkey.id),
      };
    } catch (error) {
      this.errorPopup = error instanceof Error ? error.message : 'Failed to delete passkey';
    }
  }

  private toggleInfo(key: string) {
    this.openInfoKey = this.openInfoKey === key ? null : key;
  }

  private renderUserPasskeys(user: User) {
    const passkeys = this.userPasskeys[user.id] ?? [];
    return html`
      <div class="expand-section">
        <div class="expand-section-header">
          <div style="display:flex;align-items:center;gap:var(--spacing-xs)">
            <div class="expand-section-title">Passkeys</div>
            <div class=${`option-info ${this.openInfoKey === 'passkey-info' ? 'option-info-open' : ''}`}
              @click=${(e: Event) => e.stopPropagation()}>
              <button
                type="button"
                class="option-info-button"
                aria-label="Passkey properties explained"
                aria-expanded=${this.openInfoKey === 'passkey-info' ? 'true' : 'false'}
                @click=${() => this.toggleInfo('passkey-info')}
              >i</button>
              <div class="option-tooltip" role="tooltip">
                <div class="option-tooltip-title">Passkey properties</div>
                <div class="option-tooltip-group">
                  <div class="option-tooltip-group-title">Device type</div>
                  <div class="option-tooltip-item"><strong>Multi-device</strong> — synced via a cloud keychain; works on all the user's devices.</div>
                  <div class="option-tooltip-item"><strong>Single-device</strong> — bound to one authenticator (e.g. a hardware key); cannot be transferred.</div>
                </div>
                <div class="option-tooltip-group">
                  <div class="option-tooltip-group-title">Synced</div>
                  <div class="option-tooltip-item">The credential has been backed up to a cloud provider (iCloud Keychain, Google Password Manager, …). If not synced, losing that device loses this credential.</div>
                </div>
                <div class="option-tooltip-group">
                  <div class="option-tooltip-group-title">Backup eligible</div>
                  <div class="option-tooltip-item">The authenticator can be backed up. Software passkeys (Touch ID, Windows Hello) usually are; hardware keys (YubiKey) usually are not. Eligible does not mean it has already synced.</div>
                </div>
                <div class="option-tooltip-group">
                  <div class="option-tooltip-group-title">Transports</div>
                  <div class="option-tooltip-item"><strong>internal</strong> — built into the device (Touch ID, Face ID).</div>
                  <div class="option-tooltip-item"><strong>hybrid</strong> — phone used as a key for another device via QR / Bluetooth.</div>
                  <div class="option-tooltip-item"><strong>usb</strong> — security key over USB.</div>
                  <div class="option-tooltip-item"><strong>nfc</strong> — key tapped over NFC.</div>
                  <div class="option-tooltip-item"><strong>ble</strong> — key over Bluetooth Low Energy.</div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div class="expand-section-content">
          ${passkeys.length === 0
            ? html`<div class="no-data">No passkeys registered.</div>`
            : passkeys.map(passkey => html`
              <div class="passkey-card">
                <div class="passkey-card-header">
                  <div class="passkey-card-name ${passkey.name ? '' : 'muted'}">
                    ${passkey.name ?? 'Unnamed passkey'}
                  </div>
                  <div class="passkey-card-actions">
                    <button class="icon-action" title="Rename"
                      @click=${() => this.handleRenamePasskey(user.id, passkey)}>✎</button>
                    <button class="icon-action danger" title="Delete"
                      @click=${() => this.handleDeletePasskey(user.id, passkey)}>✕</button>
                  </div>
                </div>
                <div class="passkey-card-body">
                  <span class="passkey-prop-label">Device type</span>
                  <span class="passkey-prop-value">${passkey.deviceType}</span>

                  <span class="passkey-prop-label">Status</span>
                  <span class="passkey-prop-value">
                    <span class="passkey-badges">
                      ${passkey.backedUp
                        ? html`<span class="passkey-badge synced">✓ synced</span>`
                        : html`<span class="passkey-badge not-backed-up">not synced</span>`}
                      ${passkey.backupEligible
                        ? html`<span class="passkey-badge">backup eligible</span>`
                        : ''}
                    </span>
                  </span>

                  ${passkey.transports.length > 0 ? html`
                    <span class="passkey-prop-label">Transports</span>
                    <span class="passkey-prop-value">
                      <span class="passkey-badges">
                        ${passkey.transports.map(t => html`<span class="passkey-badge">${t}</span>`)}
                      </span>
                    </span>
                  ` : ''}

                  <span class="passkey-prop-label">Last used</span>
                  <span class="passkey-prop-value ${passkey.lastUsedAt ? '' : 'muted'}">
                    ${passkey.lastUsedAt ? this.formatDate(passkey.lastUsedAt) : '—'}
                  </span>

                  <span class="passkey-prop-label">Created</span>
                  <span class="passkey-prop-value">${this.formatDate(passkey.createdAt)}</span>

                  <span class="passkey-prop-label">ID</span>
                  <span class="passkey-prop-value mono">${passkey.id}</span>
                </div>
              </div>
            `)}
        </div>
      </div>
    `;
  }

  private formatDate(value: string): string {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
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

  private renderUserSessions(user: User) {
    const sessions = this.userSessions[user.id] ?? [];
    const formatDate = (iso: string) =>
        new Date(iso).toLocaleDateString('en-GB');

    return html`
      <div class="expand-section">
        <div class="expand-section-header">
          <div class="expand-section-title">Sessions</div>
          <button class="icon-action danger" title="Invalidate all sessions"
                  @click=${() => this.handleInvalidateSession(user.id)}>Invalidate all</button>
        </div>
        ${sessions.length > 0 ? html`
          <div class="expand-section-content">
            ${sessions.map(s => html`
              <div class="session-card">
                <div class="session-card-info">
                  <span class="session-platform">${s.platform}</span>
                  <span class="session-browser">
                  ${s.browser ? `${s.browser}${s.version ? ` ${s.version}` : ''}` : '—'}
                </span>
                  <span class="session-client">${s.clientId}</span>
                  ${s.createdAt ? html`<span class="session-date">${formatDate(s.createdAt)}</span>` : ''}
                </div>
              </div>
            `)}
          </div>
        ` : html`
          <div class="expand-section-content">
            <span class="no-data">No active sessions</span>
          </div>
        `}
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
              ${user.id in this.userSessions ? this.renderUserSessions(user) : ''}
              ${user.id in this.userPasskeys ? this.renderUserPasskeys(user) : ''}
              <div class="card-action-row">
                ${!this.expandedClaims.has(user.id) ? html`<button class="btn btn-secondary btn-sm"
                  @click=${() => this.toggleUserClaims(user.id)}>Get Claims</button>` : ''}
                ${!(user.id in this.userRoles) ? html`<button class="btn btn-secondary btn-sm"
                  ?disabled=${this.loadingRoles.has(user.id) || !this.tenantId}
                  title=${!this.tenantId ? 'Select a tenant to view roles' : ''}
                  @click=${() => this.toggleUserRoles(user.id)}>
                  ${this.loadingRoles.has(user.id) ? 'Loading…' : 'Get Roles'}
                </button>` : ''}
                ${!(user.id in this.userSessions) ? html`<button class="btn btn-secondary btn-sm"
                  ?disabled=${this.loadingSessions.has(user.id)}
                  @click=${() => this.toggleUserSessions(user.id)}>
                  ${this.loadingSessions.has(user.id) ? 'Loading…' : 'Get Sessions'}
                </button>` : ''}
                ${!(user.id in this.userPasskeys) ? html`<button class="btn btn-secondary btn-sm"
                  ?disabled=${this.loadingPasskeys.has(user.id)}
                  @click=${() => this.toggleUserPasskeys(user.id)}>
                  ${this.loadingPasskeys.has(user.id) ? 'Loading…' : 'Get Passkeys'}
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