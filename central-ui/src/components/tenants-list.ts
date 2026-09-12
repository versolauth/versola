import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles, iconActionStyles } from '../styles/components';
import type { Edge, SubmissionLimits, Tenant } from '../types';
import { createTenant, deleteTenant, fetchTenants, updateTenant, fetchEdges } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { validateTenantId } from '../utils/validators';
import './content-header';
import './loading-cards';

type LimitCategory = 'otpRequest' | 'otpSubmit' | 'passwordSubmit' | 'passkeyAssertion';

/** Mirrors `SubmissionLimits.recommended` in `central`'s `SubmissionLimits.scala` -- keep the
  * two in sync. Pre-fills the create-tenant form; the operator can edit or approve it before
  * submitting, but every category must stay non-empty (the server rejects an empty one).
  */
const RECOMMENDED_SUBMISSION_LIMITS: SubmissionLimits = {
  otpRequest: [{ maxAttempts: 2, windowSeconds: 60 }, { maxAttempts: 5, windowSeconds: 3600 }],
  otpSubmit: [{ maxAttempts: 3, windowSeconds: 120 }, { maxAttempts: 5, windowSeconds: 3600 }],
  passwordSubmit: [{ maxAttempts: 5, windowSeconds: 900 }, { maxAttempts: 10, windowSeconds: 3600 }],
  passkeyAssertion: [{ maxAttempts: 5, windowSeconds: 300 }, { maxAttempts: 10, windowSeconds: 3600 }],
  banDurationSeconds: 1800,
};

function cloneSubmissionLimits(limits: SubmissionLimits): SubmissionLimits {
  return {
    otpRequest: limits.otpRequest.map(tier => ({ ...tier })),
    otpSubmit: limits.otpSubmit.map(tier => ({ ...tier })),
    passwordSubmit: limits.passwordSubmit.map(tier => ({ ...tier })),
    passkeyAssertion: limits.passkeyAssertion.map(tier => ({ ...tier })),
    banDurationSeconds: limits.banDurationSeconds,
  };
}

const LIMIT_CATEGORY_LABELS: Record<LimitCategory, string> = {
  otpRequest: 'OTP request',
  otpSubmit: 'OTP submit',
  passwordSubmit: 'Password submit',
  passkeyAssertion: 'Passkey assertion',
};

@customElement('versola-tenants-list')
export class VersolaTenantsList extends LitElement {
  @property({ type: String }) selectedTenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;

  @state() private tenants: Tenant[] = [];
  @state() private edges: Edge[] = [];
  @state() private isLoading = false;
  @state() private isSubmitting = false;
  @state() private errorMessage = '';
  @state() private formError = '';
  @state() private isFormOpen = false;
  @state() private editingTenantId: string | null = null;
  @state() private tenantIdInput = '';
  @state() private tenantDescriptionInput = '';
  @state() private selectedEdgeId: string = '';
  @state() private submissionLimits: SubmissionLimits = cloneSubmissionLimits(RECOMMENDED_SUBMISSION_LIMITS);

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    iconActionStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
        --compact-field-width: min(100%, var(--compact-field-max-width));
      }
      .stack { display: grid; gap: var(--spacing-lg); }
      .tenant-card {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-lg);
      }
      .tenant-info { min-width: 0; }
      .tenant-name-row {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 0.5rem;
        margin-bottom: 0.375rem;
      }
      .tenant-title {
        color: var(--text-primary);
        font-size: 1rem;
        font-weight: 600;
        word-break: break-word;
      }
      .tenant-title.id-only {
        color: var(--accent);
        font-family: var(--font-mono);
        font-size: 0.875rem;
      }
      .tenant-id {
        color: var(--accent);
        font-family: var(--font-mono);
        font-size: 0.875rem;
        word-break: break-word;
      }
      .tenant-description {
        color: var(--text-secondary);
        font-size: 0.9375rem;
      }
      .tenant-actions {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        margin-left: auto;
      }
      .badge-row {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
      .badge {
        display: inline-flex;
        align-items: center;
        border-radius: 999px;
        padding: 0.25rem 0.625rem;
        font-size: 0.75rem;
        font-weight: 600;
      }
      .badge-selected {
        border: 1px solid rgba(var(--accent-tint), 0.28);
        background: rgba(var(--accent-tint), 0.12);
        color: var(--accent);
      }
      .badge-edge {
        border: 1px solid rgba(147, 147, 147, 0.28);
        background: rgba(147, 147, 147, 0.12);
        color: var(--text-secondary);
        font-family: var(--font-mono);
      }
      .form-actions {
        display: flex;
        justify-content: flex-end;
        gap: 0.75rem;
        margin-top: var(--spacing-lg);
        padding-top: var(--spacing-lg);
        border-top: 1px solid var(--border-dark);
      }
      .limits-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
      }
      .limit-row {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-md);
        align-items: center;
        margin-top: var(--spacing-sm);
      }
      .limit-label {
        font-size: 0.8125rem;
        color: var(--text-secondary);
        width: 120px;
      }
      .limit-tier {
        display: flex;
        align-items: center;
        gap: 0.375rem;
      }
      .limit-input {
        width: 80px;
      }
      .limit-sep {
        color: var(--text-secondary);
      }
      .limit-hint {
        font-size: 0.75rem;
        color: var(--text-secondary);
      }
      .empty-state {
        text-align: center;
        padding: var(--spacing-xl);
        color: var(--text-secondary);
      }
      .error-banner {
        border: 1px solid rgba(var(--danger-tint), 0.35);
        background: rgba(var(--danger-tint), 0.08);
        color: var(--danger);
        border-radius: var(--radius-md);
        padding: 0.875rem 1rem;
      }
      @media (max-width: 768px) {
        .tenant-card {
          align-items: flex-start;
          flex-direction: column;
        }
        .tenant-actions {
          margin-left: 0;
        }
      }
    `,
  ];

  private prioritizeSelectedTenant(tenants: Tenant[]): Tenant[] {
    if (!this.selectedTenantId) {
      return tenants;
    }

    const selectedIndex = tenants.findIndex(tenant => tenant.id === this.selectedTenantId);
    if (selectedIndex <= 0) {
      return tenants;
    }

    const selectedTenant = tenants[selectedIndex];
    return [selectedTenant, ...tenants.slice(0, selectedIndex), ...tenants.slice(selectedIndex + 1)];
  }

  private makeTenant(id: string, description: string, edgeId?: string | null): Tenant {
    return {
      id,
      name: description || id,
      description,
      edgeId,
    };
  }

  private dispatchTenantChange(tenantId: string | null) {
    this.dispatchEvent(new CustomEvent('tenant-change', {
      detail: { tenantId },
      bubbles: true,
      composed: true,
    }));
  }

  private notifyTenantsUpdated() {
    window.dispatchEvent(new CustomEvent('versola-tenants-updated', {
      detail: {
        tenants: this.tenants,
        selectedTenantId: this.selectedTenantId,
      },
    }));
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [tenants, edges] = await Promise.all([
        fetchTenants(),
        fetchEdges(),
      ]);
      this.tenants = this.prioritizeSelectedTenant(tenants);
      this.edges = edges;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load tenants';
      this.tenants = [];
      this.edges = [];
    } finally {
      this.isLoading = false;
    }
  }

  private openCreateForm() {
    this.isFormOpen = true;
    this.editingTenantId = null;
    this.tenantIdInput = '';
    this.tenantDescriptionInput = '';
    this.selectedEdgeId = '';
    this.submissionLimits = cloneSubmissionLimits(RECOMMENDED_SUBMISSION_LIMITS);
    this.formError = '';
  }

  private openEditForm(tenant: Tenant) {
    this.isFormOpen = true;
    this.editingTenantId = tenant.id;
    this.tenantIdInput = tenant.id;
    this.tenantDescriptionInput = tenant.description ?? '';
    this.selectedEdgeId = tenant.edgeId ?? '';
    this.formError = '';
  }

  private closeForm() {
    this.isFormOpen = false;
    this.editingTenantId = null;
    this.tenantIdInput = '';
    this.tenantDescriptionInput = '';
    this.selectedEdgeId = '';
    this.submissionLimits = cloneSubmissionLimits(RECOMMENDED_SUBMISSION_LIMITS);
    this.formError = '';
  }

  private resetSubmissionLimits() {
    this.submissionLimits = cloneSubmissionLimits(RECOMMENDED_SUBMISSION_LIMITS);
  }

  private updateLimitTier(category: LimitCategory, index: number, field: 'maxAttempts' | 'windowSeconds', raw: string) {
    const value = Math.max(1, parseInt(raw, 10) || 1);
    const tiers = this.submissionLimits[category].map((tier, i) => i === index ? { ...tier, [field]: value } : tier);
    this.submissionLimits = { ...this.submissionLimits, [category]: tiers };
  }

  private updateBanDuration(raw: string) {
    this.submissionLimits = { ...this.submissionLimits, banDurationSeconds: Math.max(0, parseInt(raw, 10) || 0) };
  }

  private get isTenantIdInvalid(): boolean {
    return !this.editingTenantId && this.tenantIdInput.length > 0 && !validateTenantId(this.tenantIdInput.trim());
  }

  private handleTenantIdInput(e: Event) {
    this.tenantIdInput = (e.target as HTMLInputElement).value;
  }

  private async handleSubmit(e: Event) {
    e.preventDefault();
    const tenantId = this.tenantIdInput.trim();
    const description = this.tenantDescriptionInput.trim();
    if (!this.editingTenantId && !tenantId) {
      this.formError = 'Tenant ID is required';
      return;
    }
    if (!this.editingTenantId && !validateTenantId(tenantId)) {
      return;
    }
    if (!description) {
      this.formError = 'Tenant description is required';
      return;
    }
    if (!this.editingTenantId && this.submissionLimits.banDurationSeconds <= 0) {
      this.formError = 'Ban duration must be greater than zero';
      return;
    }
    const edgeId = this.selectedEdgeId || null;
    this.isSubmitting = true;
    this.formError = '';
    try {
      if (this.editingTenantId) {
        await updateTenant(tenantId, description, edgeId);
        this.tenants = this.prioritizeSelectedTenant(this.tenants.map(tenant =>
          tenant.id === tenantId ? this.makeTenant(tenantId, description, edgeId) : tenant
        ));
      } else {
        await createTenant(tenantId, description, edgeId, this.submissionLimits);
        this.selectedTenantId = tenantId;
        localStorage.setItem('selectedTenantId', tenantId);
        this.dispatchTenantChange(tenantId);
        this.tenants = this.prioritizeSelectedTenant([
          this.makeTenant(tenantId, description, edgeId),
          ...this.tenants.filter(tenant => tenant.id !== tenantId),
        ]);
      }
      this.notifyTenantsUpdated();
      this.closeForm();
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save tenant';
    } finally {
      this.isSubmitting = false;
    }
  }

  private async handleDelete(tenant: Tenant) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete tenant',
      messagePrefix: 'Delete tenant ',
      messageSubject: tenant.id,
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      await deleteTenant(tenant.id);
      this.tenants = this.tenants.filter(candidate => candidate.id !== tenant.id);
      if (this.editingTenantId === tenant.id) this.closeForm();
      this.notifyTenantsUpdated();
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to delete tenant';
    }
  }

  render() {
    return html`
      <content-header title="Tenants">
        ${this.canManage ? html`<button slot="actions" class="btn btn-primary" @click=${this.openCreateForm}>+ Create Tenant</button>` : ''}
      </content-header>

      <div class="stack">
        ${(this.isFormOpen || (!this.isLoading && this.tenants.length === 0)) ? html`
          <div class="card">
            <div class="card-header">
              <div class="title-stack">
                <h2 class="card-title">${this.editingTenantId ? 'Edit Tenant' : 'Create Tenant'}</h2>
                ${this.editingTenantId ? html`<div class="entity-id-meta">${this.tenantIdInput || '—'}</div>` : ''}
              </div>
            </div>
            <form @submit=${this.handleSubmit}>
              ${!this.editingTenantId ? html`
                <div class="form-group">
                  <label for="tenant-id">Tenant ID *</label>
                  <input id="tenant-id" class="compact-input ${this.isTenantIdInvalid ? 'input-error' : ''}" type="text" .value=${this.tenantIdInput} ?disabled=${this.isSubmitting} @input=${this.handleTenantIdInput} />
                  <div class="hint">Lowercase letters, numbers, hyphen, start with letter</div>
                </div>
              ` : ''}
              <div class="form-group">
                <label for="tenant-description">Description</label>
                <input id="tenant-description" class="compact-input" type="text" .value=${this.tenantDescriptionInput} ?disabled=${this.isSubmitting} @input=${(e: Event) => this.tenantDescriptionInput = (e.target as HTMLInputElement).value} />
              </div>
              <div class="form-group">
                <label for="tenant-edge">Edge (Optional)</label>
                <select
                  id="tenant-edge"
                  class="compact-input"
                  .value=${this.selectedEdgeId}
                  ?disabled=${this.isSubmitting}
                  @change=${(e: Event) => this.selectedEdgeId = (e.target as HTMLSelectElement).value}
                >
                  <option value="">None (Central only)</option>
                  ${this.edges.map(edge => html`
                    <option value=${edge.id} ?selected=${edge.id === this.selectedEdgeId}>${edge.id}</option>
                  `)}
                </select>
                <div class="hint">Assign this tenant to an edge infrastructure endpoint</div>
              </div>
              ${!this.editingTenantId ? html`
                <div class="form-group">
                  <div class="limits-header">
                    <label>Security limits *</label>
                    <button type="button" class="btn btn-secondary btn-sm" @click=${this.resetSubmissionLimits} ?disabled=${this.isSubmitting}>Reset to recommended</button>
                  </div>
                  <div class="hint">Rate limits on password, OTP and passkey submission attempts. Pre-filled with recommended values -- review, edit or approve before creating.</div>
                  ${(Object.keys(LIMIT_CATEGORY_LABELS) as LimitCategory[]).map(category => html`
                    <div class="limit-row">
                      <span class="limit-label">${LIMIT_CATEGORY_LABELS[category]}</span>
                      ${this.submissionLimits[category].map((tier, i) => html`
                        <span class="limit-tier">
                          <input type="number" min="1" class="compact-input limit-input" title="Max attempts"
                            .value=${String(tier.maxAttempts)} ?disabled=${this.isSubmitting}
                            @input=${(e: Event) => this.updateLimitTier(category, i, 'maxAttempts', (e.target as HTMLInputElement).value)} />
                          <span class="limit-sep">/</span>
                          <input type="number" min="1" class="compact-input limit-input" title="Window (seconds)"
                            .value=${String(tier.windowSeconds)} ?disabled=${this.isSubmitting}
                            @input=${(e: Event) => this.updateLimitTier(category, i, 'windowSeconds', (e.target as HTMLInputElement).value)} />
                          <span class="limit-hint">sec</span>
                        </span>
                      `)}
                    </div>
                  `)}
                  <div class="limit-row">
                    <span class="limit-label">Ban duration</span>
                    <input type="number" min="1" class="compact-input limit-input" title="Ban duration (seconds)"
                      .value=${String(this.submissionLimits.banDurationSeconds)} ?disabled=${this.isSubmitting}
                      @input=${(e: Event) => this.updateBanDuration((e.target as HTMLInputElement).value)} />
                    <span class="limit-hint">sec</span>
                  </div>
                </div>
              ` : ''}
              ${this.formError ? html`<div class="error-banner">${this.formError}</div>` : ''}
              <div class="form-actions">
                ${this.tenants.length > 0 ? html`<button type="button" class="btn btn-secondary" @click=${this.closeForm} ?disabled=${this.isSubmitting}>Cancel</button>` : ''}
                <button type="submit" class="btn btn-primary" ?disabled=${this.isSubmitting}>${this.editingTenantId ? 'Save Changes' : 'Create Tenant'}</button>
              </div>
            </form>
          </div>
        ` : ''}

        ${this.errorMessage ? html`<div class="error-banner">${this.errorMessage}</div>` : ''}

        ${this.isLoading ? html`
          <versola-loading-cards .count=${3}></versola-loading-cards>
        ` : !this.isFormOpen && this.tenants.length === 0 ? html`
          <div class="card">
            <div class="empty-state">
              <h3>No tenants yet</h3>
              ${this.canManage ? html`<p>Create your first tenant to get started.</p>` : html`<p>No tenants available.</p>`}
            </div>
          </div>
        ` : this.tenants.map(tenant => html`
          <div class="card tenant-card">
            <div class="tenant-info">
              <div class="tenant-name-row">
                <div class="tenant-title ${tenant.description ? '' : 'id-only'}">${tenant.description || tenant.id}</div>
                <div class="badge-row">
                  ${tenant.id === this.selectedTenantId ? html`<span class="badge badge-selected">Selected</span>` : ''}
                  ${tenant.edgeId ? html`<span class="badge badge-edge">${tenant.edgeId}</span>` : ''}
                </div>
              </div>
              ${tenant.description ? html`<div class="tenant-id">${tenant.id}</div>` : ''}
            </div>
            ${this.canManage ? html`
            <div class="tenant-actions">
              <button class="icon-action" type="button" @click=${() => this.openEditForm(tenant)} title="Edit tenant" aria-label="Edit tenant">✎</button>
              <button class="icon-action danger" type="button" @click=${() => this.handleDelete(tenant)} title="Delete tenant" aria-label="Delete tenant">✕</button>
            </div>` : ''}
          </div>
        `)}
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-tenants-list': VersolaTenantsList;
  }
}