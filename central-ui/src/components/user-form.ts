import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { parsePhoneNumberFromString } from 'libphonenumber-js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { OAuthScope, Role, User, UserRoleAssignment } from '../types';

const BOOLEAN_CLAIMS = new Set(['email_verified', 'phone_number_verified']);

@customElement('versola-user-form')
export class VersolaUserForm extends LitElement {
  @property({ attribute: false }) userData: User | null = null;
  @property({ type: String }) tenantId: string | null = null;
  @property({ attribute: false }) availableRoles: Role[] = [];
  @property({ attribute: false }) availableScopes: OAuthScope[] = [];
  @property({ attribute: false }) roleAssignments: UserRoleAssignment[] = [];

  @state() private formData: User = {
    id: '',
    email: '',
    phone: '',
    login: '',
    claims: {},
  };

  @state() private enabledScopes = new Set<string>();
  @state() private selectedRoleId = '';
  @state() private phoneError = '';

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
      }

      .error-text {
        color: var(--error, #ef4444);
        font-size: 0.75rem;
        margin-top: 0.25rem;
      }

      .form-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: var(--spacing-xl);
      }

      .form-title {
        font-size: 2rem;
        font-weight: 700;
        color: var(--text-primary);
        margin: 0;
      }

      .form-grid {
        display: grid;
        gap: var(--spacing-lg);
      }

      .form-actions {
        display: flex;
        gap: 1rem;
        justify-content: flex-end;
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-dark);
      }

      .scope-group {
        display: grid;
        gap: 0.5rem;
        margin-top: 0.5rem;
      }

      .scope-block {
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-sm);
        transition: all var(--transition-fast);
      }

      .checkbox-item {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.5rem;
        cursor: pointer;
        transition: all var(--transition-fast);
      }

      .checkbox-item:hover {
        border-color: var(--accent);
        background: rgba(88, 166, 255, 0.05);
      }

      .scope-block:has(.checkbox-item:hover) {
        border-color: var(--accent);
      }

      .checkbox-item input[type="checkbox"] {
        cursor: pointer;
      }

      .checkbox-content {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        min-width: 0;
        flex: 1;
      }

      .checkbox-item label {
        cursor: pointer;
        margin: 0;
        font-weight: normal;
        text-transform: none;
        letter-spacing: normal;
        font-size: 0.875rem;
        color: var(--text-primary);
        font-family: var(--font-mono);
        user-select: none;
        -webkit-user-select: none;
        -moz-user-select: none;
        min-width: 0;
        overflow-wrap: anywhere;
      }

      .scope-desc {
        color: var(--text-secondary);
        font-size: 0.8125rem;
      }

      .claim-list {
        margin-top: var(--spacing-sm);
        padding: 0 var(--spacing-md) var(--spacing-md);
        display: grid;
        gap: var(--spacing-sm);
      }

      .claim-row {
        display: grid;
        grid-template-columns: 12rem 1fr;
        gap: var(--spacing-md);
        align-items: center;
      }

      .claim-label {
        display: flex;
        flex-direction: column;
      }

      .claim-id {
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--text-primary);
      }

      .claim-desc {
        color: var(--text-secondary);
        font-size: 0.75rem;
      }

      .claim-input {
        width: 100%;
      }

      .claim-checkbox {
        justify-self: start;
        cursor: pointer;
      }

      .section-title {
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
        padding: var(--spacing-sm) 0;
        border-bottom: 1px dashed var(--border-dark);
      }

      .role-row:last-child { border-bottom: none; }

      .role-tag {
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--accent);
      }

      .role-tenant {
        color: var(--text-secondary);
        font-size: 0.75rem;
      }

      .role-assign-row {
        display: flex;
        gap: var(--spacing-sm);
        margin-top: var(--spacing-md);
        align-items: center;
      }

      .role-assign-row select { max-width: 16rem; }

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

      .helper {
        color: var(--text-secondary);
        font-size: 0.8125rem;
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    if (this.userData) {
      this.formData = { ...this.userData, claims: { ...this.userData.claims } };
      this.enabledScopes = this.deriveEnabledScopes(this.userData.claims ?? {});
    }
  }

  updated(changedProperties: Map<string, unknown>) {
    if (changedProperties.has('availableScopes')) {
      this.enabledScopes = this.deriveEnabledScopes(this.formData.claims ?? {});
    }
  }

  // Maps indexed form fields to the claim ID used to locate their scope
  private static readonly FIELD_CLAIM: Record<string, string> = {
    email: 'email',
    phone: 'phone_number',
  };

  private deriveEnabledScopes(claims: Record<string, unknown>): Set<string> {
    const enabled = new Set<string>();
    for (const scope of this.availableScopes) {
      if (scope.claims.some(claim => claim.id in claims)) enabled.add(scope.id);
    }
    // Auto-expand scopes whose indexed field already has a value
    for (const [field, claimId] of Object.entries(VersolaUserForm.FIELD_CLAIM)) {
      const value = (this.formData as Record<string, unknown>)[field] as string | undefined;
      if (value?.trim()) {
        const scope = this.availableScopes.find(s => s.claims.some(c => c.id === claimId));
        if (scope) enabled.add(scope.id);
      }
    }
    return enabled;
  }

  private validatePhone(value: string): boolean {
    if (!value.trim()) {
      this.phoneError = '';
      return true;
    }
    const parsed = parsePhoneNumberFromString(value);
    if (parsed?.isValid()) {
      this.phoneError = '';
      return true;
    }
    this.phoneError = 'Invalid phone number (use E.164 format, e.g. +15551234567)';
    return false;
  }

  private patchField<K extends keyof User>(key: K, value: User[K]) {
    this.formData = { ...this.formData, [key]: value };
    if (key === 'phone') this.validatePhone(value as string);
    // Auto-expand / auto-collapse the matching scope when an indexed field changes
    const claimId = VersolaUserForm.FIELD_CLAIM[key as string];
    if (!claimId) return;
    const scope = this.availableScopes.find(s => s.claims.some(c => c.id === claimId));
    if (!scope) return;
    const trimmed = (value as string | undefined)?.trim();
    const next = new Set(this.enabledScopes);
    if (trimmed) {
      next.add(scope.id);
    } else {
      // Collapse only when no claim values remain in that scope
      const currentClaims = this.formData.claims ?? {};
      const hasValues = scope.claims.some(c => c.id !== claimId && currentClaims[c.id] != null);
      if (!hasValues) next.delete(scope.id);
    }
    this.enabledScopes = next;
  }

  private toggleScope(scopeId: string) {
    const next = new Set(this.enabledScopes);
    if (next.has(scopeId)) {
      next.delete(scopeId);
      const scope = this.availableScopes.find(s => s.id === scopeId);
      if (scope) {
        const claims = { ...(this.formData.claims ?? {}) };
        for (const claim of scope.claims) delete claims[claim.id];
        this.formData = { ...this.formData, claims };
      }
    } else {
      next.add(scopeId);
    }
    this.enabledScopes = next;
  }

  private setClaimValue(claimId: string, value: unknown) {
    const claims = { ...(this.formData.claims ?? {}) };
    if (value === '' || value === undefined || value === null) {
      delete claims[claimId];
    } else {
      claims[claimId] = value;
    }
    this.formData = { ...this.formData, claims };
  }

  private handleClose() {
    this.dispatchEvent(new CustomEvent('close', { bubbles: true, composed: true }));
  }

  private handleSubmit(e: Event) {
    e.preventDefault();
    if (!this.validatePhone(this.formData.phone ?? '')) return;

    // Strip indexed fields from claims — they're stored as top-level columns, not as claim duplicates
    const INDEXED_CLAIMS = new Set(['email', 'phone_number']);
    const rawClaims = this.formData.claims ?? {};
    const claims = Object.fromEntries(
      Object.entries(rawClaims).filter(([key]) => !INDEXED_CLAIMS.has(key))
    );

    const user: Omit<User, 'id'> & { id?: string } = {
      ...(this.userData ? { id: this.formData.id } : {}),
      email: this.formData.email?.trim() || undefined,
      phone: this.formData.phone?.trim() || undefined,
      login: this.formData.login?.trim() || undefined,
      claims,
    };

    this.dispatchEvent(new CustomEvent('submit', { detail: { user }, bubbles: true, composed: true }));
  }

  private handleAssignRole() {
    if (!this.tenantId || !this.selectedRoleId) return;
    this.dispatchEvent(new CustomEvent('assign-role', {
      detail: { tenantId: this.tenantId, roleId: this.selectedRoleId },
      bubbles: true,
      composed: true,
    }));
    this.selectedRoleId = '';
  }

  private handleRemoveRole(assignment: UserRoleAssignment) {
    this.dispatchEvent(new CustomEvent('remove-role', {
      detail: { tenantId: assignment.tenantId, roleId: assignment.roleId },
      bubbles: true,
      composed: true,
    }));
  }

  private renderScopes() {
    if (this.availableScopes.length === 0) {
      return html`<div class="helper">Loading scopes…</div>`;
    }
    const claims = this.formData.claims ?? {};
    const email = this.formData.email?.trim();
    const phone = this.formData.phone?.trim();

    // Claims whose value is locked because they're stored as indexed columns
    const lockedClaimValue: Record<string, string | undefined> = {
      email: email,
      phone_number: phone,
    };
    return html`
      <div class="scope-group">
        ${this.availableScopes.map(scope => {
          const enabled = this.enabledScopes.has(scope.id);
          const visibleClaims = scope.claims;
          return html`
            <div class="scope-block">
              <div class="checkbox-item" @click=${() => this.toggleScope(scope.id)}>
                <input
                  type="checkbox"
                  id="scope-${scope.id}"
                  .checked=${enabled}
                  @click=${(e: Event) => e.stopPropagation()}
                  @change=${() => this.toggleScope(scope.id)}
                />
                <div class="checkbox-content">
                  <label for="scope-${scope.id}" @click=${(e: Event) => e.preventDefault()}>${scope.id}</label>
                  <span class="scope-desc">${scope.description['en'] ?? ''}</span>
                </div>
              </div>
              ${enabled ? html`
                <div class="claim-list">
                  ${visibleClaims.map(claim => {
                    const value = claims[claim.id];
                    const isBool = BOOLEAN_CLAIMS.has(claim.id);
                    const lockedValue = lockedClaimValue[claim.id];
                    const isLocked = lockedValue !== undefined;
                    return html`
                      <div class="claim-row">
                        <div class="claim-label">
                          <span class="claim-id">${claim.id}</span>
                          <span class="claim-desc">${claim.description['en'] ?? ''}</span>
                        </div>
                        ${isLocked ? html`
                          <input class="claim-input compact-input" type="text" .value=${lockedValue} disabled />
                        ` : isBool ? html`
                          <input type="checkbox" class="claim-checkbox" .checked=${value === true}
                            @change=${(e: Event) => this.setClaimValue(claim.id, (e.target as HTMLInputElement).checked)} />
                        ` : html`
                          <input class="claim-input compact-input" type="text"
                            .value=${value == null ? '' : String(value)}
                            @input=${(e: Event) => this.setClaimValue(claim.id, (e.target as HTMLInputElement).value)} />
                        `}
                      </div>
                    `;
                  })}
                </div>
              ` : ''}
            </div>
          `;
        })}
      </div>
    `;
  }

  private renderRoles() {
    const tenantId = this.tenantId;
    const tenantAssignments = tenantId ? this.roleAssignments.filter(a => a.tenantId === tenantId) : [];
    const otherAssignments = tenantId
      ? this.roleAssignments.filter(a => a.tenantId !== tenantId)
      : this.roleAssignments;
    const usedRoles = new Set(tenantAssignments.map(a => a.roleId));
    const selectableRoles = this.availableRoles.filter(r => !usedRoles.has(r.id));

    return html`
      <div class="form-group">
        ${tenantId ? html`
          <div class="section-title">Roles in ${tenantId}</div>
          ${tenantAssignments.length === 0
            ? html`<div class="helper">No roles assigned in this tenant</div>`
            : tenantAssignments.map(assignment => html`
                <div class="role-row">
                  <span class="role-tag">${assignment.roleId}</span>
                  <span style="flex:1"></span>
                  <button type="button" class="icon-action danger" title="Remove role"
                    @click=${() => this.handleRemoveRole(assignment)}>✕</button>
                </div>
              `)}

          <div class="role-assign-row">
            <select .value=${this.selectedRoleId}
              @change=${(e: Event) => this.selectedRoleId = (e.target as HTMLSelectElement).value}>
              <option value="">Select a role…</option>
              ${selectableRoles.map(role => html`<option value=${role.id}>${role.id}</option>`)}
            </select>
            <button type="button" class="btn btn-secondary btn-sm" ?disabled=${!this.selectedRoleId}
              @click=${this.handleAssignRole}>Assign</button>
          </div>
        ` : html`
          <div class="section-title">Roles</div>
          <div class="helper">Select a tenant in the sidebar to manage role assignments.</div>
        `}

        ${otherAssignments.length > 0 ? html`
          <div class="section-title" style="margin-top: var(--spacing-lg)">Other tenants</div>
          ${otherAssignments.map(assignment => html`
            <div class="role-row">
              <span class="role-tag">${assignment.roleId}</span>
              <span class="role-tenant">in ${assignment.tenantId}</span>
            </div>
          `)}
        ` : ''}
      </div>
    `;
  }

  render() {
    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">${this.userData ? 'Edit User' : 'Create New User'}</h1>
          ${this.userData ? html`<div class="entity-id-meta">${this.formData.id}</div>` : ''}
        </div>
      </div>

      <div class="card">
        <form @submit=${this.handleSubmit}>
          <div class="form-grid">


            <div class="form-group">
              <label for="user-email">Email</label>
              <input id="user-email" class="compact-input" type="email"
                .value=${this.formData.email ?? ''}
                @input=${(e: Event) => this.patchField('email', (e.target as HTMLInputElement).value)}
                placeholder="user@example.com" />
            </div>

            <div class="form-group">
              <label for="user-phone">Phone</label>
              <input id="user-phone" class="compact-input ${this.phoneError ? 'input-error' : ''}" type="text"
                .value=${this.formData.phone ?? ''}
                @input=${(e: Event) => this.patchField('phone', (e.target as HTMLInputElement).value)}
                placeholder="+15551234567" />
              ${this.phoneError ? html`<div class="error-text">${this.phoneError}</div>` : ''}
            </div>

            <div class="form-group">
              <label for="user-login">Login</label>
              <input id="user-login" class="compact-input" type="text"
                .value=${this.formData.login ?? ''}
                @input=${(e: Event) => this.patchField('login', (e.target as HTMLInputElement).value)}
                placeholder="alice" />
            </div>

            <div class="form-group">
              <label>Claims</label>
              <div class="hint" style="margin-bottom: var(--spacing-sm)">Enable scopes and set values for their claims</div>
              ${this.renderScopes()}
            </div>

            ${this.userData ? this.renderRoles() : ''}
          </div>

          <div class="form-actions">
            <button type="button" class="btn btn-secondary" @click=${this.handleClose}>Cancel</button>
            <button type="submit" class="btn btn-primary">
              ${this.userData ? 'Update User' : 'Create User'}
            </button>
          </div>
        </form>
      </div>
    `;
  }
}
