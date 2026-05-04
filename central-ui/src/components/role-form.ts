import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { Role, Permission, Resource } from '../types';
import { resolvePermissionEndpointGroups } from '../utils/helpers';
import { validateRoleId } from '../utils/validators';

@customElement('versola-role-form')
export class VersolaRoleForm extends LitElement {
  @property({ attribute: false }) roleData: Role | null = null;
  @property({ attribute: false }) availablePermissions: Permission[] = [];
  @property({ attribute: false }) availableResources: Resource[] = [];
  
  @state() private formData: Partial<Role> = {
    id: '',
    description: { en: '' },
    active: true,
    permissions: [],
  };

  @state() private openInfoKey: string | null = null;

  private handleDocumentClick = () => {
    this.openInfoKey = null;
  };

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
        --compact-field-width: min(100%, var(--compact-field-max-width));
        --inline-action-button-width: 5.25rem;
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

      .checkbox-group {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
        gap: 0.75rem;
        margin-top: 0.5rem;
      }

      .checkbox-item {
        display: flex;
        align-items: flex-start;
        gap: 0.75rem;
        padding: 0.75rem;
        background: rgba(0, 0, 0, 0.2);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        cursor: pointer;
        transition: all var(--transition-fast);
      }

      .checkbox-item:hover {
        border-color: var(--accent);
        background: rgba(88, 166, 255, 0.05);
      }

      .checkbox-item input[type="checkbox"] {
        cursor: pointer;
        margin-top: 2px;
      }

      .checkbox-content {
        flex: 1;
        min-width: 0;
      }

      .checkbox-title-row {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 0.5rem;
        min-width: 0;
      }

      .checkbox-label {
        cursor: pointer;
        margin: 0;
        font-weight: 500;
        text-transform: none;
        letter-spacing: normal;
        font-size: 0.875rem;
        color: var(--text-primary);
        font-family: var(--font-mono);
        display: block;
        margin-bottom: 0.25rem;
        user-select: none;
        -webkit-user-select: none;
        -moz-user-select: none;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        flex: 1;
      }

      .checkbox-description {
        font-size: 0.75rem;
        color: var(--text-secondary);
        font-family: var(--font-family);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
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
        padding: 0.25rem 0.45rem;
        cursor: pointer;
        font-family: var(--font-family);
      }

      .option-info {
        position: relative;
        display: inline-flex;
        align-items: center;
        flex: none;
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
        right: 0;
        top: calc(100% + 0.4rem);
        z-index: 20;
        min-width: 18rem;
        max-width: min(28rem, 75vw);
        max-height: 18rem;
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

      .option-tooltip-list,
      .option-tooltip-groups,
      .option-endpoint-list {
        display: grid;
        gap: 0.375rem;
      }

      .option-tooltip-item,
      .option-endpoint-path {
        color: var(--text-primary);
        font-size: 0.75rem;
        line-height: 1.4;
        overflow-wrap: anywhere;
      }

      .option-tooltip-empty {
        color: var(--text-secondary);
        font-size: 0.75rem;
      }

      .option-tooltip-group {
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-sm);
        background: rgba(255, 255, 255, 0.03);
        padding: 0.625rem 0.75rem;
      }

      .option-tooltip-group-title {
        color: var(--accent);
        font-size: 0.75rem;
        font-weight: 600;
        font-family: var(--font-mono);
        margin-bottom: 0.25rem;
        overflow-wrap: anywhere;
      }

      .option-endpoint-row {
        color: var(--text-primary);
        font-size: 0.75rem;
        line-height: 1.4;
        font-family: var(--font-mono);
        overflow-wrap: anywhere;
      }

      .option-endpoint-method {
        color: var(--text-primary);
        font-weight: 600;
      }

      .form-actions {
        display: flex;
        gap: 1rem;
        justify-content: flex-end;
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-dark);
      }

      .permissions-section {
        background: rgba(0, 0, 0, 0.2);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        padding: var(--spacing-lg);
      }

      .permissions-section-title {
        font-size: 1rem;
        font-weight: 600;
        color: var(--text-primary);
        margin-bottom: var(--spacing-md);
      }

    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    document.addEventListener('click', this.handleDocumentClick);
    if (this.roleData) {
      this.formData = { ...this.roleData };
    }
  }

  disconnectedCallback() {
    document.removeEventListener('click', this.handleDocumentClick);
    super.disconnectedCallback();
  }

  private handleSubmit(e: Event) {
    e.preventDefault();

    // Validate role ID format only when creating a new role
    if (!this.roleData) {
      if (!validateRoleId((this.formData.id || '').trim())) {
        return;
      }
    }

    const now = new Date().toISOString();
    const role: Role = {
      id: this.formData.id!,
      description: this.formData.description || { en: '' },
      active: this.formData.active ?? true,
      permissions: this.formData.permissions || [],
      createdAt: this.roleData?.createdAt || now,
      updatedAt: now,
    };

    this.dispatchEvent(new CustomEvent('submit', {
      detail: { role },
      bubbles: true,
      composed: true,
    }));
  }

  private handleRoleIdInput(e: Event) {
    const value = (e.target as HTMLInputElement).value;
    this.formData = { ...this.formData, id: value };
  }

  private get isRoleIdInvalid() {
    const roleId = (this.formData.id || '').trim();
    return !this.roleData && roleId.length > 0 && !validateRoleId(roleId);
  }

  private handleClose() {
    this.dispatchEvent(new CustomEvent('close', {
      bubbles: true,
      composed: true,
    }));
  }

  private togglePermission(permission: Permission) {
    const permissions = this.formData.permissions || [];
    const exists = permissions.some(p => p.id === permission.id);
    
    this.formData = {
      ...this.formData,
      permissions: exists
        ? permissions.filter(p => p.id !== permission.id)
        : [...permissions, permission],
    };
  }

  private getPermissionInfoGroups(permission: Permission) {
    return resolvePermissionEndpointGroups(permission, this.availableResources);
  }

  private get sortedPermissions() {
    return [...this.availablePermissions].sort((a, b) => a.id.localeCompare(b.id));
  }

  private toggleInfo(key: string) {
    this.openInfoKey = this.openInfoKey === key ? null : key;
  }

  private renderOptionInfo(key: string, title: string, content: unknown, ariaLabel: string) {
    return html`
      <div class=${`option-info ${this.openInfoKey === key ? 'option-info-open' : ''}`} @click=${(e: Event) => e.stopPropagation()}>
        <button
          type="button"
          class="option-info-button"
          aria-label=${ariaLabel}
          aria-expanded=${this.openInfoKey === key ? 'true' : 'false'}
          @click=${() => this.toggleInfo(key)}
        >i</button>
        <div class="option-tooltip" role="tooltip">
          <div class="option-tooltip-title">${title}</div>
          ${content}
        </div>
      </div>
    `;
  }

  private renderPermissionInfo(permission: Permission) {
    const groups = this.getPermissionInfoGroups(permission);

    return this.renderOptionInfo(
      `permission:${permission.id}`,
      'Resources & endpoints',
      groups.length > 0 ? html`
        <div class="option-tooltip-groups">
          ${groups.map(group => html`
            <div class="option-tooltip-group">
              <div class="option-tooltip-group-title">${group.title}</div>
              <div class="option-endpoint-list">
                ${group.endpoints.map(endpoint => html`
                  <div class="option-endpoint-row">
                    <span class="option-endpoint-method">${endpoint.method}</span>
                    <span>${endpoint.path}</span>
                  </div>
                `)}
              </div>
            </div>
          `)}
        </div>
      ` : html`<div class="option-tooltip-empty">No endpoints</div>`,
      `Show endpoints for permission ${permission.id}`,
    );
  }

  render() {
    const selectedPermissions = this.formData.permissions || [];

    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">
            ${this.roleData ? 'Edit Role' : 'Create New Role'}
          </h1>
          ${this.roleData ? html`<div class="entity-id-meta">${this.formData.id || '—'}</div>` : ''}
        </div>
      </div>

      <div class="card">
        <form @submit=${this.handleSubmit}>
          <div class="form-grid">
            ${!this.roleData ? html`
              <div class="form-group">
                <label for="role-id">Role ID *</label>
                <input
                  type="text"
                  id="role-id"
                  class="compact-input ${this.isRoleIdInvalid ? 'input-error' : ''}"
                  .value=${this.formData.id || ''}
                  @input=${this.handleRoleIdInput}
                  required
                  placeholder="e.g., admin"
                />
                <div class="hint">Lowercase letters, numbers, underscore, start with letter</div>
              </div>
            ` : ''}

            <div class="form-group">
              <label for="role-description">Description *</label>
              <input
                type="text"
                id="role-description"
                class="compact-input"
                .value=${this.formData.description?.en || ''}
                @input=${(e: Event) => this.formData = {
                  ...this.formData,
                  description: { ...this.formData.description, en: (e.target as HTMLInputElement).value }
                }}
                required
                placeholder="e.g., Full administrative access"
              />
            </div>

            <div class="form-group">
              <label>
                <input
                  type="checkbox"
                  .checked=${this.formData.active ?? true}
                  @change=${(e: Event) => this.formData = { ...this.formData, active: (e.target as HTMLInputElement).checked }}
                />
                Active
              </label>
            </div>

            <div class="permissions-section">
              <div class="permissions-section-title">
                Permissions (${selectedPermissions.length} selected)
              </div>
              ${this.availablePermissions.length === 0 ? html`<div class="helper-text">No permissions available for this tenant yet.</div>` : ''}

              <div class="checkbox-group">
                ${this.sortedPermissions.map(perm => {
                  const isChecked = selectedPermissions.some(p => p.id === perm.id);
                  return html`
                    <div class="checkbox-item" @click=${() => this.togglePermission(perm)}>
                      <input
                        type="checkbox"
                        id="perm-${perm.id}"
                        .checked=${isChecked}
                        @click=${(e: Event) => e.stopPropagation()}
                        @change=${() => this.togglePermission(perm)}
                      />
                      <div class="checkbox-content">
                        <div class="checkbox-title-row">
                          <label class="checkbox-label" for="perm-${perm.id}" title=${perm.id} @click=${(e: Event) => e.preventDefault()}>
                            ${perm.id}
                          </label>
                          ${this.renderPermissionInfo(perm)}
                        </div>
                        <div class="checkbox-description" title=${perm.description.en}>
                          ${perm.description.en}
                        </div>
                      </div>
                    </div>
                  `;
                })}
              </div>
            </div>
          </div>

          <div class="form-actions">
            <button type="button" class="btn btn-secondary" @click=${this.handleClose}>
              Cancel
            </button>
            <button type="submit" class="btn btn-primary">
              ${this.roleData ? 'Update Role' : 'Create Role'}
            </button>
          </div>
        </form>
      </div>
    `;
  }
}

