import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles, iconActionStyles } from '../styles/components';
import { OAuthScope, OAuthClaim } from '../types';
import { validateScopeId } from '../utils/validators';

@customElement('versola-scope-form')
export class VersolaScopeForm extends LitElement {
  @property({ type: Object }) scope: OAuthScope | null = null;
  
  @state() private formData: Partial<OAuthScope> = {
    id: '',
    description: { en: '' },
    claims: [],
  };

  @state() private newClaimId = '';
  @state() private newClaimDescEn = '';
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

      .claims-section {
        background: rgba(0, 0, 0, 0.2);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-lg);
      }

      .claims-section-title {
        font-size: 1rem;
        font-weight: 600;
        color: var(--text-primary);
        margin-bottom: var(--spacing-md);
      }

      .claim-item {
        display: flex;
        align-items: center;
        gap: 1rem;
        padding: var(--spacing-md);
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        margin-bottom: var(--spacing-sm);
      }

      .claim-content {
        flex: 1;
      }

      .claim-name {
        font-family: var(--font-mono);
        font-size: 0.875rem;
        color: var(--accent);
        font-weight: 600;
        margin-bottom: 0.25rem;
      }

      .claim-description {
        font-size: 0.8125rem;
        color: var(--text-secondary);
      }

      .add-claim-form {
        display: grid;
        grid-template-columns: minmax(0, 14rem) minmax(0, var(--compact-field-width)) var(--inline-action-button-width);
        gap: 0.5rem;
        margin-top: var(--spacing-md);
        align-items: flex-start;
        width: 100%;
        max-width: min(100%, calc(14rem + var(--compact-field-max-width) + var(--inline-action-button-width) + 1rem));
      }

      .add-claim-form input {
        min-width: 0;
        width: 100%;
        box-sizing: border-box;
      }

      .add-claim-form button {
        width: 100%;
        min-width: 0;
      }

      @media (max-width: 768px) {
        .add-claim-form {
          grid-template-columns: minmax(0, var(--compact-field-width));
          max-width: var(--compact-field-width);
        }
      }

      .form-actions {
        display: flex;
        gap: 1rem;
        justify-content: flex-end;
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-dark);
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    if (this.scope) {
      this.formData = { ...this.scope };
    }
  }

  private handleSubmit(e: Event) {
    e.preventDefault();
    e.stopPropagation();

    // Validate scope ID format only when creating a new scope
    if (!this.scope) {
      if (!validateScopeId((this.formData.id || '').trim())) {
        return;
      }
    }

    const scope: OAuthScope = {
      id: this.formData.id!,
      description: this.formData.description || { en: '' },
      claims: this.formData.claims || [],
    };

    this.dispatchEvent(new CustomEvent('save-scope', {
      detail: { scope },
      bubbles: true,
      composed: true,
    }));
  }

  private handleScopeIdInput(e: Event) {
    const value = (e.target as HTMLInputElement).value;
    this.formData = { ...this.formData, id: value };
  }

  private get isScopeIdInvalid() {
    const scopeId = (this.formData.id || '').trim();
    return !this.scope && scopeId.length > 0 && !validateScopeId(scopeId);
  }

  private handleClose() {
    this.dispatchEvent(new CustomEvent('close', {
      bubbles: true,
      composed: true,
    }));
  }

  private addClaim() {
    if (this.newClaimId.trim() && this.newClaimDescEn.trim()) {
      const newClaim: OAuthClaim = {
        id: this.newClaimId.trim(),
        scopeId: this.formData.id || '',
        description: { en: this.newClaimDescEn.trim() },
      };

      this.formData = {
        ...this.formData,
        claims: [...(this.formData.claims || []), newClaim],
      };

      this.newClaimId = '';
      this.newClaimDescEn = '';
    }
  }

  private handleClaimInputKeydown(e: KeyboardEvent) {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    this.addClaim();
  }

  private removeClaim(claimId: string) {
    this.formData = {
      ...this.formData,
      claims: (this.formData.claims || []).filter(c => c.id !== claimId),
    };
  }

  render() {
    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">
            ${this.scope ? 'Edit Scope' : 'Create New Scope'}
          </h1>
          ${this.scope ? html`<div class="entity-id-meta">${this.formData.id || '—'}</div>` : ''}
        </div>
      </div>

      <div class="card">
        <form @submit=${this.handleSubmit}>
          <div class="form-grid">
            ${!this.scope ? html`
              <div class="form-group">
                <label for="scope-id">Scope ID *</label>
                <input
                  type="text"
                  id="scope-id"
                  class="compact-input ${this.isScopeIdInvalid ? 'input-error' : ''}"
                  .value=${this.formData.id || ''}
                  @input=${this.handleScopeIdInput}
                  required
                  placeholder="e.g., profile"
                />
                <div class="hint">Lowercase letters, numbers, underscore, start with letter</div>
              </div>
            ` : ''}

            <div class="form-group">
              <label for="scope-description">Description *</label>
              <input
                type="text"
                id="scope-description"
                class="compact-input"
                .value=${this.formData.description?.en || ''}
                @input=${(e: Event) => this.formData = {
                  ...this.formData,
                  description: { ...this.formData.description, en: (e.target as HTMLInputElement).value }
                }}
                required
                placeholder="e.g., User profile data"
              />
            </div>

            <div class="claims-section">
              <div class="claims-section-title">Claims</div>
              
              ${(this.formData.claims || []).map(claim => html`
                <div class="claim-item">
                  <div class="claim-content">
                    <div class="claim-name">${claim.id}</div>
                    <div class="claim-description">${claim.description.en}</div>
                  </div>
                  <button
                    type="button"
                    class="icon-action danger"
                    @click=${() => this.removeClaim(claim.id)}
                    title="Remove claim"
                    aria-label=${`Remove claim ${claim.id}`}
                  >
                    ✕
                  </button>
                </div>
              `)}

              <div class="add-claim-form">
                <input
                  type="text"
                  aria-label="Claim ID"
                  .value=${this.newClaimId}
                  @input=${(e: Event) => this.newClaimId = (e.target as HTMLInputElement).value}
                  @keydown=${this.handleClaimInputKeydown}
                  placeholder="Claim ID (e.g., name)"
                />
                <input
                  type="text"
                  aria-label="Claim Description"
                  .value=${this.newClaimDescEn}
                  @input=${(e: Event) => this.newClaimDescEn = (e.target as HTMLInputElement).value}
                  @keydown=${this.handleClaimInputKeydown}
                  placeholder="Description"
                />
                <button type="button" class="btn btn-secondary" @click=${this.addClaim}>
                  Add Claim
                </button>
              </div>
            </div>
          </div>

          <div class="form-actions">
            <button type="button" class="btn btn-secondary" @click=${this.handleClose}>
              Cancel
            </button>
            <button type="submit" class="btn btn-primary">
              ${this.scope ? 'Update Scope' : 'Create Scope'}
            </button>
          </div>
        </form>
      </div>
    `;
  }
}

