import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import type { Edge } from '../types/index';
import { DEFAULT_REVOCATION_CACHE_SIZE } from '../utils/central-api';
import { validateEdgeId } from '../utils/validators';
import './nav-toggle';

@customElement('versola-edge-form')
export class VersolaEdgeForm extends LitElement {
  @property({ attribute: false }) edge: Edge | null = null;
  @property({ attribute: false }) availableEdgeIds: string[] = [];

  @state() private edgeId = '';
  @state() private revocationCacheSize = String(DEFAULT_REVOCATION_CACHE_SIZE);

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

      /* When the only child of .form-grid is the action row itself, the separator
         and the 2rem of space above it divide actions from fields that aren't
         there, which reads as a broken, half-empty card. Dropped when the row
         stands alone. :only-child is exact here: it matches only when nothing
         else rendered. */
      .form-actions:only-child {
        margin-top: 0;
        padding-top: 0;
        border-top: none;
      }

      @media (max-width: 720px) {
        .form-actions {
          flex-wrap: wrap;
        }

        /* Rotate Key is pushed left by margin-right:auto; once the row wraps
           that leaves it stranded on its own line. */
        .secondary-action-button {
          margin-right: 0;
        }
      }

      .secondary-action-button {
        margin-right: auto;
      }

      .error-message {
        color: var(--danger);
        font-size: 0.875rem;
        margin-top: 0.25rem;
      }

      .hint {
        font-size: 0.8125rem;
        color: var(--text-secondary);
        margin-top: 0.25rem;
      }

      .input-with-prefix {
        display: flex;
        align-items: stretch;
      }

      .input-prefix {
        display: flex;
        align-items: center;
        padding: 0 0.75rem;
        background: rgba(0, 0, 0, 0.3);
        border: 1px solid var(--border-dark);
        border-right: none;
        border-radius: var(--radius-md) 0 0 var(--radius-md);
        color: var(--text-secondary);
        font-family: var(--font-mono);
        font-size: 0.9375rem;
        user-select: none;
      }

      .input-with-prefix .compact-input {
        border-radius: 0 var(--radius-md) var(--radius-md) 0;
        flex: 1;
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    if (this.edge) {
      // Strip the "edge-" prefix when loading an existing edge
      this.edgeId = this.edge.id.startsWith('edge-')
        ? this.edge.id.substring(5)
        : this.edge.id;
      this.revocationCacheSize = String(this.edge.revocationCacheSize);
    }
  }

  private handleEdgeIdInput(e: Event) {
    this.edgeId = (e.target as HTMLInputElement).value;
  }

  private handleCacheSizeInput(e: Event) {
    this.revocationCacheSize = (e.target as HTMLInputElement).value;
  }

  private parsedCacheSize(): number | null {
    const value = Number(this.revocationCacheSize);
    return Number.isInteger(value) && value > 0 ? value : null;
  }

  private handleSubmit(e: Event) {
    e.preventDefault();

    if (this.edge) {
      const cacheSize = this.parsedCacheSize();
      if (cacheSize === null) {
        return;
      }

      this.dispatchEvent(new CustomEvent('submit', {
        detail: { id: this.edge.id, revocationCacheSize: cacheSize },
        bubbles: true,
        composed: true,
      }));
      return;
    }

    const suffix = this.edgeId.trim();
    const fullId = `edge-${suffix}`;

    if (!validateEdgeId(fullId) || this.availableEdgeIds.includes(fullId)) {
      return;
    }

    this.dispatchEvent(new CustomEvent('submit', {
      detail: { id: fullId },
      bubbles: true,
      composed: true,
    }));
  }

  private handleCancel() {
    this.dispatchEvent(new CustomEvent('cancel', {
      bubbles: true,
      composed: true,
    }));
  }

  private handleRotateKey() {
    if (!this.edge) return;
    this.dispatchEvent(new CustomEvent('rotate-key', {
      detail: { edgeId: this.edge.id },
      bubbles: true,
      composed: true,
    }));
  }

  private handleDeleteOldKey() {
    if (!this.edge) return;
    this.dispatchEvent(new CustomEvent('delete-old-key', {
      detail: { edgeId: this.edge.id },
      bubbles: true,
      composed: true,
    }));
  }

  render() {
    const isEditMode = this.edge !== null;
    const suffix = this.edgeId.trim();
    const fullId = `edge-${suffix}`;
    const isValid = suffix && validateEdgeId(fullId);
    const isDuplicate = this.availableEdgeIds.includes(fullId);
    const cacheSize = this.parsedCacheSize();
    const isCacheSizeInvalid = this.revocationCacheSize.trim() !== '' && cacheSize === null;
    const canSubmit = isEditMode
      ? cacheSize !== null && cacheSize !== this.edge!.revocationCacheSize
      : suffix && isValid && !isDuplicate;
    const isEdgeIdInvalid = suffix && (!isValid || isDuplicate);

    return html`
      <div class="form-header">
        <div class="form-header-lead">
          <versola-nav-toggle></versola-nav-toggle>
          <div class="title-stack">
            <h1 class="form-title">
              ${isEditMode ? 'Edit Edge' : 'Create New Edge'}
            </h1>
            ${isEditMode ? html`<div class="entity-id-meta">${this.edge!.id}</div>` : ''}
          </div>
        </div>
      </div>

      <div class="card">
        <form @submit=${this.handleSubmit}>
          <div class="form-grid">
            ${!isEditMode ? html`
              <div class="form-group">
                <label for="edge-id">Edge ID *</label>
                <div class="input-with-prefix">
                  <div class="input-prefix">edge-</div>
                  <input
                    type="text"
                    id="edge-id"
                    class="compact-input ${isEdgeIdInvalid ? 'input-error' : ''}"
                    .value=${this.edgeId}
                    @input=${this.handleEdgeIdInput}
                    required
                    placeholder="us-east-1"
                  />
                </div>
                <div class="hint">Lowercase letters, numbers, hyphen</div>
                ${suffix && !isValid ? html`<div class="error-message">Invalid edge ID format</div>` : ''}
                ${suffix && isDuplicate ? html`<div class="error-message">Edge ID already exists</div>` : ''}
              </div>
            ` : html`
              <div class="form-group">
                <label for="revocation-cache-size">Revocation cache size *</label>
                <input
                  type="number"
                  id="revocation-cache-size"
                  class="compact-input ${isCacheSizeInvalid ? 'input-error' : ''}"
                  min="1"
                  step="1"
                  .value=${this.revocationCacheSize}
                  @input=${this.handleCacheSizeInput}
                  required
                />
                <div class="hint">
                  Revoked tokens and sessions this edge keeps in memory. Going over it costs the
                  edge a database lookup per miss, never a token it should have rejected.
                </div>
                ${isCacheSizeInvalid ? html`<div class="error-message">Must be a positive whole number</div>` : ''}
              </div>
            `}

            <div class="form-actions">
              ${isEditMode ? html`
                ${this.edge!.hasOldKey ? html`
                  <button
                    type="button"
                    class="btn btn-secondary btn-sm secondary-action-button"
                    @click=${this.handleDeleteOldKey}
                    title="Delete old key"
                    aria-label="Delete old key"
                  >Delete old key</button>
                ` : html`
                  <button
                    type="button"
                    class="btn btn-secondary secondary-action-button"
                    @click=${this.handleRotateKey}
                  >Rotate Key</button>
                `}
              ` : ''}
              <button type="button" class="btn btn-secondary" @click=${this.handleCancel}>
                ${isEditMode ? 'Close' : 'Cancel'}
              </button>
              <button type="submit" class="btn btn-primary" ?disabled=${!canSubmit}>
                ${isEditMode ? 'Save' : 'Create Edge'}
              </button>
            </div>
          </div>
        </form>
      </div>
    `;
  }
}
