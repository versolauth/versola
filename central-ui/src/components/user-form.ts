import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { parsePhoneNumberFromString } from 'libphonenumber-js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { Resource, Role, User, UserRoleAssignment } from '../types';
import { resolvePermissionEndpointGroups } from '../utils/helpers';

@customElement('versola-user-form')
export class VersolaUserForm extends LitElement {
  @property({ attribute: false }) userData: User | null = null;
  @property({ type: String }) tenantId: string | null = null;
  @property({ attribute: false }) availableRoles: Role[] = [];
  @property({ attribute: false }) availableResources: Resource[] = [];
  @property({ attribute: false }) roleAssignments: UserRoleAssignment[] = [];
  @property({ type: Boolean }) rolesOnly = false;

  @state() private formData: User = {
    id: '',
    email: '',
    phone: '',
    login: '',
    claims: {},
  };

  @state() private selectedRoleId = '';
  @state() private phoneError = '';
  @state() private stagedAdds: Set<string> = new Set();
  @state() private stagedRemoves: Set<string> = new Set();
  @state() private expandedRoleIds: Set<string> = new Set();

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

      .section-title {
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--text-secondary);
        margin-bottom: var(--spacing-sm);
      }

      .role-entry {
        border-bottom: 1px dashed var(--border-dark);
      }

      .role-entry:last-child { border-bottom: none; }

      .role-row {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        padding: var(--spacing-sm) 0;
      }

      .role-details {
        margin: 0 0 var(--spacing-sm) calc(1.125rem + var(--spacing-sm));
        padding: var(--spacing-md);
        background: rgba(0,0,0,0.2);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
      }

      .perm-list {
        display: grid;
        gap: var(--spacing-md);
      }

      .perm-item {
        display: grid;
        gap: var(--spacing-xs);
      }

      .perm-head {
        display: flex;
        align-items: baseline;
        gap: 0.5rem;
        flex-wrap: wrap;
      }

      .perm-id {
        font-family: var(--font-mono);
        font-size: 0.9375rem;
        font-weight: 600;
        color: var(--accent);
        flex-shrink: 0;
      }

      .perm-desc {
        font-size: 0.875rem;
        color: var(--text-secondary);
      }

      .perm-endpoints {
        display: grid;
        gap: var(--spacing-sm);
        padding-left: var(--spacing-md);
      }

      .endpoint-group {
        display: grid;
        gap: 0.25rem;
      }

      .endpoint-resource {
        font-size: 0.8125rem;
        font-weight: 600;
        color: var(--text-secondary);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .endpoint-row {
        display: flex;
        align-items: baseline;
        gap: 0.625rem;
        font-size: 0.8125rem;
        padding-left: var(--spacing-sm);
      }

      .endpoint-method {
        font-family: var(--font-mono);
        font-weight: 600;
        color: var(--accent);
        flex-shrink: 0;
        min-width: 3.5rem;
      }

      .endpoint-path {
        font-family: var(--font-mono);
        color: var(--text-secondary);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .perm-empty {
        font-size: 0.8125rem;
        color: var(--text-secondary);
        font-style: italic;
      }

      .role-preview {
        margin-top: var(--spacing-md);
        padding: var(--spacing-md) var(--spacing-lg);
        background: rgba(0,0,0,0.2);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
      }

      .role-preview .section-title {
        font-size: 0.8125rem;
        margin-bottom: var(--spacing-sm);
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

      .role-row.staged-remove .role-tag {
        text-decoration: line-through;
        color: var(--danger);
        opacity: 0.7;
      }

      .role-row.staged-add .role-tag {
        color: var(--success, #22c55e);
      }

      .role-actions {
        display: flex;
        gap: var(--spacing-sm);
        justify-content: flex-end;
        margin-top: var(--spacing-md);
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    if (this.userData) {
      this.formData = { ...this.userData, claims: { ...this.userData.claims } };
    }
  }

  override updated(changedProperties: Map<PropertyKey, unknown>) {
    if (changedProperties.has('roleAssignments')) {
      this.stagedAdds = new Set();
      this.stagedRemoves = new Set();
      this.selectedRoleId = '';
    }
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
  }

  private handleClose() {
    this.dispatchEvent(new CustomEvent('close', { bubbles: true, composed: true }));
  }

  private handleSubmit(e: Event) {
    e.preventDefault();
    if (!this.validatePhone(this.formData.phone ?? '')) return;

    const user: Omit<User, 'id'> & { id?: string } = {
      ...(this.userData ? { id: this.formData.id } : {}),
      email: this.formData.email?.trim() || undefined,
      phone: this.formData.phone?.trim() || undefined,
      login: this.formData.login?.trim() || undefined,
      claims: this.formData.claims ?? {},
    };

    this.dispatchEvent(new CustomEvent('submit', { detail: { user }, bubbles: true, composed: true }));
  }

  private stageAddRole() {
    const roleId = this.selectedRoleId;
    if (!roleId) return;
    this.stagedRemoves = new Set([...this.stagedRemoves].filter(r => r !== roleId));
    this.stagedAdds = new Set([...this.stagedAdds, roleId]);
    this.selectedRoleId = '';
  }

  private stageRemoveRole(roleId: string) {
    if (this.stagedAdds.has(roleId)) {
      this.stagedAdds = new Set([...this.stagedAdds].filter(r => r !== roleId));
      return;
    }
    this.stagedRemoves = new Set([...this.stagedRemoves, roleId]);
  }

  private undoRemoveRole(roleId: string) {
    this.stagedRemoves = new Set([...this.stagedRemoves].filter(r => r !== roleId));
  }

  private toggleRoleExpand(roleId: string) {
    const next = new Set(this.expandedRoleIds);
    if (next.has(roleId)) next.delete(roleId);
    else next.add(roleId);
    this.expandedRoleIds = next;
  }

  private roleDetails(roleId: string) {
    return this.availableRoles.find(r => r.id === roleId);
  }

  private renderRolePermissions(roleId: string) {
    const role = this.roleDetails(roleId);
    if (!role || role.permissions.length === 0) {
      return html`<div class="perm-empty">No permissions</div>`;
    }
    return html`
      <div class="perm-list">
        ${role.permissions.map(p => {
          const desc = p.description?.en;
          const groups = resolvePermissionEndpointGroups(p, this.availableResources);
          return html`
            <div class="perm-item">
              <div class="perm-head">
                <span class="perm-id">${p.id}</span>
                ${desc && desc !== p.id
                  ? html`<span class="perm-desc" title=${desc}>${desc}</span>`
                  : ''}
              </div>
              ${groups.length > 0
                ? html`<div class="perm-endpoints">
                    ${groups.map(group => html`
                      <div class="endpoint-group">
                        <div class="endpoint-resource" title=${group.title}>${group.title}</div>
                        ${group.endpoints.map(ep => html`
                          <div class="endpoint-row">
                            <span class="endpoint-method">${ep.method}</span>
                            <span class="endpoint-path">${ep.path}</span>
                          </div>
                        `)}
                      </div>
                    `)}
                  </div>`
                : html`<div class="perm-empty">No endpoints</div>`}
            </div>
          `;
        })}
      </div>
    `;
  }

  private renderRoleRow(roleId: string, extraClass: string, actions: unknown) {
    const isExpanded = this.expandedRoleIds.has(roleId);
    return html`
      <div class="role-entry">
        <div class="role-row ${extraClass}">
          <button type="button" class="icon-action" title="Toggle permissions"
            @click=${() => this.toggleRoleExpand(roleId)}>${isExpanded ? '▼' : '▶'}</button>
          <span class="role-tag">${roleId}</span>
          <span style="flex:1"></span>
          ${actions}
        </div>
        ${isExpanded ? html`<div class="role-details">${this.renderRolePermissions(roleId)}</div>` : ''}
      </div>
    `;
  }

  private handleSaveRoles() {
    if (!this.tenantId) return;
    this.dispatchEvent(new CustomEvent('save-roles', {
      detail: {
        tenantId: this.tenantId,
        adds: [...this.stagedAdds],
        removes: [...this.stagedRemoves],
      },
      bubbles: true,
      composed: true,
    }));
  }

  private renderRoles() {
    const tenantId = this.tenantId;
    const tenantAssignments = tenantId ? this.roleAssignments.filter(a => a.tenantId === tenantId) : [];
    const assignedIds = new Set(tenantAssignments.map(a => a.roleId));
    const selectableRoles = this.availableRoles.filter(
      r => !assignedIds.has(r.id) && !this.stagedAdds.has(r.id),
    );

    return html`
      <div class="form-group">
        ${tenantId ? html`
          ${tenantAssignments.length === 0 && this.stagedAdds.size === 0
            ? html`<div class="helper">No roles assigned</div>`
            : ''}

          ${tenantAssignments.map(assignment => this.renderRoleRow(
            assignment.roleId,
            this.stagedRemoves.has(assignment.roleId) ? 'staged-remove' : '',
            this.stagedRemoves.has(assignment.roleId)
              ? html`<button type="button" class="icon-action" title="Undo remove"
                  @click=${() => this.undoRemoveRole(assignment.roleId)}>↩</button>`
              : html`<button type="button" class="icon-action danger" title="Remove role"
                  @click=${() => this.stageRemoveRole(assignment.roleId)}>✕</button>`,
          ))}

          ${[...this.stagedAdds].map(roleId => this.renderRoleRow(
            roleId,
            'staged-add',
            html`<button type="button" class="icon-action danger" title="Cancel add"
              @click=${() => this.stageRemoveRole(roleId)}>✕</button>`,
          ))}

          <div class="role-assign-row">
            <select .value=${this.selectedRoleId}
              @change=${(e: Event) => this.selectedRoleId = (e.target as HTMLSelectElement).value}>
              <option value="">Select a role…</option>
              ${selectableRoles.map(role => html`<option value=${role.id}>${role.id}</option>`)}
            </select>
            <button type="button" class="btn btn-secondary btn-sm" ?disabled=${!this.selectedRoleId}
              @click=${this.stageAddRole}>Add</button>
          </div>
          ${this.selectedRoleId ? html`
            <div class="role-preview">
              <div class="section-title">Permissions</div>
              ${this.renderRolePermissions(this.selectedRoleId)}
            </div>
          ` : ''}
        ` : html`
          <div class="helper">Select a tenant in the sidebar to manage role assignments.</div>
        `}
      </div>
    `;
  }

  render() {
    const title = this.rolesOnly
      ? 'Edit Roles'
      : this.userData ? 'Edit User' : 'Create New User';

    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">${title}</h1>
          ${this.userData ? html`<div class="entity-id-meta">${this.formData.id}</div>` : ''}
        </div>
      </div>

      <div class="card">
        <form @submit=${this.handleSubmit}>
          <div class="form-grid">

            ${!this.rolesOnly ? html`
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
            ` : ''}

            ${this.rolesOnly ? this.renderRoles() : ''}
          </div>

          <div class="form-actions">
            <button type="button" class="btn btn-secondary" @click=${this.handleClose}>
              ${this.rolesOnly ? 'Close' : 'Cancel'}
            </button>
            ${this.rolesOnly ? html`
              <button type="button" class="btn btn-primary"
                ?disabled=${this.stagedAdds.size === 0 && this.stagedRemoves.size === 0}
                @click=${this.handleSaveRoles}>Save roles</button>
            ` : html`
              <button type="submit" class="btn btn-primary">
                ${this.userData ? 'Update User' : 'Create User'}
              </button>
            `}
          </div>
        </form>
      </div>
    `;
  }
}
