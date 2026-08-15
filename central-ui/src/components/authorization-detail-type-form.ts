import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { AuthorizationDetailType } from '../types';
import { validateAuthorizationDetailType } from '../utils/validators';
import './nav-toggle';
import './code-editor';

const DEFAULT_SCHEMA = `{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "type": { "type": "string" },
    "locations": { "type": "array", "items": { "type": "string" } },
    "actions": { "type": "array", "items": { "type": "string" } }
  },
  "required": ["type"],
  "unevaluatedProperties": false
}`;

@customElement('versola-authorization-detail-type-form')
export class VersolaAuthorizationDetailTypeForm extends LitElement {
  @property({ type: Object }) detailType: AuthorizationDetailType | null = null;

  @state() private type = '';
  @state() private description = '';
  @state() private schemaText = DEFAULT_SCHEMA;
  @state() private schemaError = '';

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
        --challenge-button-width: 12rem;
        --challenge-button-height: 2.75rem;
      }

      .btn {
        box-sizing: border-box;
        flex: 0 0 var(--challenge-button-width);
        width: var(--challenge-button-width);
        min-height: var(--challenge-button-height);
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

      .schema-error {
        color: var(--danger);
        font-size: 0.8125rem;
        margin-top: 0.375rem;
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    if (this.detailType) {
      this.type = this.detailType.type;
      this.description = this.detailType.description.en ?? '';
      this.schemaText = JSON.stringify(this.detailType.schema, null, 2);
    }
  }

  private get isTypeInvalid(): boolean {
    return this.type.length > 0 && !validateAuthorizationDetailType(this.type);
  }

  private schemaValidationError(text: string): string {
    try {
      const parsed: unknown = JSON.parse(text);
      return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
        ? ''
        : 'Schema must be a JSON object';
    } catch (error) {
      return error instanceof Error ? error.message : 'Schema must be valid JSON';
    }
  }

  private handleClose() {
    this.dispatchEvent(new CustomEvent('close', { bubbles: true, composed: true }));
  }

  private handleSubmit(e: Event) {
    e.preventDefault();

    this.schemaError = this.schemaValidationError(this.schemaText);
    if (this.schemaError) return;
    const schema = JSON.parse(this.schemaText) as Record<string, unknown>;

    this.dispatchEvent(new CustomEvent('save-authorization-detail-type', {
      detail: {
        detailType: {
          type: this.type.trim(),
          description: { en: this.description.trim() },
          schema,
        } satisfies AuthorizationDetailType,
      },
      bubbles: true,
      composed: true,
    }));
  }

  render() {
    return html`
      <div class="form-header">
        <div class="form-header-lead">
          <versola-nav-toggle></versola-nav-toggle>
          <div class="title-stack">
            <h1 class="form-title">
              ${this.detailType ? 'Edit Authorization Detail Type' : 'Create Authorization Detail Type'}
            </h1>
            ${this.detailType ? html`<div class="entity-id-meta">${this.type || '—'}</div>` : ''}
          </div>
        </div>
      </div>

      <div class="card">
        <form @submit=${this.handleSubmit}>
          <div class="form-grid">
            ${!this.detailType ? html`
              <div class="form-group">
                <label for="detail-type">Type *</label>
                <input
                  type="text"
                  id="detail-type"
                  class="compact-input ${this.isTypeInvalid ? 'input-error' : ''}"
                  .value=${this.type}
                  @input=${(e: Event) => this.type = (e.target as HTMLInputElement).value}
                  required
                  placeholder="e.g., payment_initiation"
                />
                <div class="hint">The value clients send as the <code>type</code> member of an authorization detail</div>
              </div>
            ` : ''}

            <div class="form-group">
              <label for="detail-type-description">Description *</label>
              <input
                type="text"
                id="detail-type-description"
                class="compact-input"
                .value=${this.description}
                @input=${(e: Event) => this.description = (e.target as HTMLInputElement).value}
                required
                placeholder="e.g., Payment initiation"
              />
            </div>

            <div class="form-group">
              <label for="detail-type-schema">JSON Schema *</label>
              <versola-code-editor
                id="detail-type-schema"
                language="json"
                .rows=${20}
                .value=${this.schemaText}
                .invalid=${this.schemaError.length > 0}
                @code-input=${(e: CustomEvent<{ value: string }>) => {
                  this.schemaText = e.detail.value;
                  this.schemaError = this.schemaValidationError(this.schemaText);
                }}
              ></versola-code-editor>
              <div class="hint">
                JSON Schema 2020-12. Detail objects of this type are rejected unless they validate against it —
                use <code>unevaluatedProperties: false</code> to reject unknown members.
              </div>
              ${this.schemaError ? html`<div class="schema-error" role="alert">${this.schemaError}</div>` : ''}
            </div>
          </div>

          <div class="form-actions">
            <button type="button" class="btn btn-secondary" @click=${this.handleClose}>
              Cancel
            </button>
            <button type="submit" class="btn btn-primary">
              ${this.detailType ? 'Update Type' : 'Create Type'}
            </button>
          </div>
        </form>
      </div>
    `;
  }
}
