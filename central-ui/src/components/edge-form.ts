import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import type { Edge } from '../types/index';
import { validateEdgeId } from '../utils/validators';

@customElement('versola-edge-form')
export class VersolaEdgeForm extends LitElement {
  @property({ attribute: false }) edge: Edge | null = null;
  @property({ attribute: false }) availableEdgeIds: string[] = [];

  @state() private edgeId = '';

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

      .secondary-action-button {
        margin-right: auto;
      }

      .error-message {
        color: var(--error);
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
    }
  }

  private handleEdgeIdInput(e: Event) {
    this.edgeId = (e.target as HTMLInputElement).value;
  }

  private handleSubmit(e: Event) {
    e.preventDefault();
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
    const canSubmit = suffix && isValid && !isDuplicate;
    const isEdgeIdInvalid = suffix && (!isValid || isDuplicate);

    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">
            ${isEditMode ? 'Edit Edge' : 'Create New Edge'}
          </h1>
          ${isEditMode ? html`<div class="entity-id-meta">${this.edge!.id}</div>` : ''}
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
            ` : ''}

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
              ${!isEditMode ? html`
                <button type="submit" class="btn btn-primary" ?disabled=${!canSubmit}>
                  Create Edge
                </button>
              ` : ''}
            </div>
          </div>
        </form>
      </div>
    `;
  }
}
