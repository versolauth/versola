import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles, iconActionStyles } from '../styles/components';
import { fetchJwks, deleteJwk, createJwk, updateJwk, fetchServerMetadata, upsertServerMetadata } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { validateJsonObject } from '../utils/helpers';
import { tokenize } from '../utils/code-highlight';
import { codeHighlightStyles } from '../styles/code-highlight';
import './content-header';
import './error-card';
import './loading-cards';
import './code-editor';

// RFC 7517: 'kty' is the only strictly required JWK member; 'kid' is required
// here so keys can be identified/managed individually.
const REQUIRED_JWK_FIELDS = ['kid', 'kty'];

// RFC 8414 authorization server metadata: minimal set of fields needed for
// OAuth/OIDC discovery to work end-to-end.
const REQUIRED_METADATA_FIELDS = ['issuer', 'authorization_endpoint', 'token_endpoint', 'jwks_uri', 'response_types_supported'];

@customElement('versola-well-known')
export class VersolaWellKnown extends LitElement {
  @property({ type: Boolean }) canManage = false;

  @state() private keys: Record<string, unknown>[] = [];
  @state() private metadata: Record<string, unknown> | null = null;
  @state() private isLoading = false;
  @state() private isLoadingMetadata = false;
  @state() private errorMessage = '';
  @state() private metadataError = '';
  @state() private metadataInput = '';
  @state() private metadataFormError = '';
  @state() private metadataInvalid = false;
  @state() private isSavingMetadata = false;
  @state() private isEditingMetadata = false;

  @state() private formMode: 'add' | 'edit' | null = null;
  @state() private editingKid = '';
  @state() private jwkInput = '';
  @state() private formError = '';
  @state() private jwkInvalid = false;
  @state() private isSubmitting = false;

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
    void this.loadMetadata();
  }

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    iconActionStyles,
    codeHighlightStyles,
    css`
      :host {
        display: block;
        min-width: 0;
      }

      .section-title {
        font-size: 1.25rem;
        font-weight: 600;
        color: var(--text-primary);
        margin: var(--spacing-xl) 0 var(--spacing-lg) 0;
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
        flex-wrap: wrap;
      }

      .metadata-card {
        margin-bottom: var(--spacing-xl);
        min-width: 0;
      }

      .metadata-json {
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-md);
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--text-secondary);
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        margin: 0;
        max-width: 100%;
        box-sizing: border-box;
        display: block;
      }

      .key-card {
        margin-bottom: var(--spacing-md);
        min-width: 0;
      }

      .key-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
        margin-bottom: var(--spacing-md);
        flex-wrap: wrap;
      }

      .key-header-info {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
        min-width: 0;
        flex: 1;
      }

      .key-id {
        font-family: var(--font-mono);
        font-size: 1rem;
        font-weight: 600;
        color: var(--accent);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        min-width: 0;
      }

      .key-meta {
        font-size: 0.8125rem;
        color: var(--text-secondary);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        min-width: 0;
      }

      .key-json {
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-md);
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--text-secondary);
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        margin: 0;
        max-width: 100%;
        box-sizing: border-box;
        display: block;
      }

      .key-actions {
        display: flex;
        gap: var(--spacing-sm);
        flex-shrink: 0;
        align-items: center;
      }

      .form-hint {
        font-size: 0.8125rem;
        color: var(--text-secondary);
        margin-top: var(--spacing-sm);
      }

      .add-form-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        padding: var(--spacing-lg);
        margin-bottom: var(--spacing-lg);
      }

      .add-form-title {
        font-size: 1.1rem;
        font-weight: 600;
        color: var(--text-primary);
        margin: 0 0 var(--spacing-lg) 0;
      }

      .add-form-actions {
        display: flex;
        gap: var(--spacing-md);
        margin-top: var(--spacing-md);
      }

      .add-error {
        color: var(--danger, #f85149);
        font-size: 0.875rem;
        margin-top: var(--spacing-sm);
      }

      .empty-state {
        text-align: center;
        padding: 3rem;
        color: var(--text-secondary);
      }

      .empty-state-icon {
        font-size: 3rem;
        margin-bottom: 1rem;
      }
    `,
  ];

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const jwks = await fetchJwks();
      this.keys = jwks.keys ?? [];
    } catch (err) {
      this.errorMessage = err instanceof Error ? err.message : 'Failed to load JWKS';
    } finally {
      this.isLoading = false;
    }
  }

  private async loadMetadata() {
    this.isLoadingMetadata = true;
    this.metadataError = '';
    try {
      this.metadata = await fetchServerMetadata();
      this.metadataInput = JSON.stringify(this.metadata, null, 2);
    } catch (err) {
      this.metadataError = err instanceof Error ? err.message : 'Failed to load server metadata';
    } finally {
      this.isLoadingMetadata = false;
    }
  }

  private handleEditMetadata() {
    this.isEditingMetadata = true;
    this.metadataInput = this.metadata !== null ? JSON.stringify(this.metadata, null, 2) : '{}';
    this.metadataFormError = '';
    this.metadataInvalid = false;
  }

  private handleCancelMetadata() {
    this.isEditingMetadata = false;
    this.metadataInput = this.metadata !== null ? JSON.stringify(this.metadata, null, 2) : '{}';
    this.metadataFormError = '';
    this.metadataInvalid = false;
  }

  private handleMetadataInput(value: string) {
    this.metadataInput = value;
    const error = validateJsonObject(value, REQUIRED_METADATA_FIELDS);
    this.metadataInvalid = !!error;
    this.metadataFormError = error === 'Invalid JSON.' ? '' : (error ?? '');
  }

  private async handleSaveMetadata() {
    const error = validateJsonObject(this.metadataInput, REQUIRED_METADATA_FIELDS);
    this.metadataInvalid = !!error;
    this.metadataFormError = error === 'Invalid JSON.' ? '' : (error ?? '');
    if (error) return;
    const parsed = JSON.parse(this.metadataInput) as Record<string, unknown>;

    this.isSavingMetadata = true;
    try {
      await upsertServerMetadata(parsed);
      this.metadata = parsed;
      this.isEditingMetadata = false;
    } catch (err) {
      this.metadataFormError = err instanceof Error ? err.message : 'Failed to save metadata';
    } finally {
      this.isSavingMetadata = false;
    }
  }

  private handleAddClick() {
    this.formMode = 'add';
    this.editingKid = '';
    this.jwkInput = '';
    this.formError = '';
    this.jwkInvalid = false;
  }

  private handleEditClick(key: Record<string, unknown>) {
    this.formMode = 'edit';
    this.editingKid = typeof key['kid'] === 'string' ? key['kid'] : '';
    this.jwkInput = JSON.stringify(key, null, 2);
    this.formError = '';
    this.jwkInvalid = false;
  }

  private handleCancelForm() {
    this.formMode = null;
    this.editingKid = '';
    this.jwkInput = '';
    this.formError = '';
    this.jwkInvalid = false;
  }

  private handleJwkInput(value: string) {
    this.jwkInput = value;
    const error = this.validateJwkInput(value);
    this.jwkInvalid = !!error;
    this.formError = error === 'Invalid JSON.' ? '' : error;
  }

  private validateJwkInput(value: string): string {
    const error = validateJsonObject(value, REQUIRED_JWK_FIELDS);
    if (error) return error;
    if (this.formMode === 'edit') {
      const parsed = JSON.parse(value) as Record<string, unknown>;
      if (parsed['kid'] !== this.editingKid) {
        return "Changing 'kid' is not supported. Delete this key and add a new one instead.";
      }
    }
    return '';
  }

  private async handleSubmitForm() {
    const error = this.validateJwkInput(this.jwkInput);
    this.jwkInvalid = !!error;
    this.formError = error === 'Invalid JSON.' ? '' : error;
    if (error) return;
    const parsed = JSON.parse(this.jwkInput) as Record<string, unknown>;
    this.isSubmitting = true;
    try {
      if (this.formMode === 'edit') {
        await updateJwk(parsed);
      } else {
        await createJwk(parsed);
      }
      this.handleCancelForm();
      await this.loadData();
    } catch (err) {
      this.formError = err instanceof Error ? err.message : 'Failed to save key';
    } finally {
      this.isSubmitting = false;
    }
  }

  private async handleDeleteKey(kid: string) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete JWK',
      messagePrefix: 'Delete key ',
      messageSubject: kid,
      messageSuffix: ' from the JWKS? This cannot be undone.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;

    this.errorMessage = '';
    try {
      await deleteJwk(kid);
      this.keys = this.keys.filter(k => k['kid'] !== kid);
    } catch (err) {
      this.errorMessage = err instanceof Error ? err.message : 'Failed to delete key';
    }
  }

  private renderForm() {
    const isEdit = this.formMode === 'edit';
    return html`
      <div class="add-form-card">
        <h3 class="add-form-title">${isEdit ? `Edit JWK · ${this.editingKid}` : 'Add JWK'}</h3>
        <div class="form-group">
          <label class="form-label" for="jwk-input">JWK JSON</label>
          <versola-code-editor
            id="jwk-input"
            language="json"
            rows="10"
            .value=${this.jwkInput}
            ?invalid=${this.jwkInvalid}
            @code-input=${(e: CustomEvent<{ value: string }>) => this.handleJwkInput(e.detail.value)}
          ></versola-code-editor>
          ${isEdit ? html`<div class="form-hint">The 'kid' cannot be changed when editing.</div>` : ''}
          ${this.formError ? html`<div class="add-error">${this.formError}</div>` : ''}
        </div>
        <div class="add-form-actions">
          <button
            class="btn btn-primary"
            @click=${this.handleSubmitForm}
            ?disabled=${this.isSubmitting || this.jwkInvalid}
          >${this.isSubmitting ? 'Saving…' : isEdit ? 'Save Changes' : 'Add Key'}</button>
          <button class="btn btn-secondary" @click=${this.handleCancelForm}>Cancel</button>
        </div>
      </div>
    `;
  }

  private renderJson(json: string) {
    return tokenize(json, 'json').map(t => html`<span class=${`ch-tok ch-${t.kind}`}>${t.value}</span>`);
  }

  private renderKey(key: Record<string, unknown>) {
    const kid = typeof key['kid'] === 'string' ? key['kid'] : '(no kid)';
    const kty = typeof key['kty'] === 'string' ? key['kty'] : '';
    const alg = typeof key['alg'] === 'string' ? key['alg'] : '';
    const json = JSON.stringify(key, null, 2);

    return html`
      <div class="card key-card">
        <div class="key-header">
          <div class="key-header-info">
            <span class="key-id">${kid}</span>
            <span class="key-meta">${kty}${alg ? ` · ${alg}` : ''}</span>
          </div>
          <div class="key-actions">
            ${this.canManage ? html`
              <button
                class="icon-action"
                title="Edit key"
                aria-label="Edit key ${kid}"
                @click=${() => this.handleEditClick(key)}
              >✎</button>
              <button
                class="icon-action danger"
                title="Delete key"
                aria-label="Delete key ${kid}"
                @click=${() => this.handleDeleteKey(kid)}
              >✕</button>
            ` : ''}
          </div>
        </div>
        <pre class="key-json">${this.renderJson(json)}</pre>
      </div>
    `;
  }

  render() {
    return html`
      <content-header
        title="Well Known"
        description="Public configuration and keys served by this central instance"
      >
        ${this.canManage ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleAddClick}>
            + Add Key
          </button>
        ` : ''}
      </content-header>

      <div class="section-title">
        <span>JWKS</span>
      </div>

      ${this.formMode !== null
        ? this.renderForm()
        : this.isLoading
          ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
          : this.errorMessage
            ? html`
              <versola-error-card
                heading="Could not load JWKS"
                .message=${this.errorMessage}
                @retry=${() => this.loadData()}
              ></versola-error-card>
            `
            : this.keys.length === 0
              ? html`
                <div class="card">
                  <div class="empty-state">
                    <div class="empty-state-icon">🔑</div>
                    <p>No keys found.</p>
                    ${this.canManage ? html`
                    <button class="btn btn-primary" @click=${this.handleAddClick} style="margin-top: 1rem;">
                      + Add Key
                    </button>` : ''}
                  </div>
                </div>
              `
              : this.keys.map(k => this.renderKey(k))
      }

      <div class="section-title">
        <span>Server Metadata</span>
      </div>

      ${this.isLoadingMetadata ? html`<versola-loading-cards .count=${1}></versola-loading-cards>` :
        this.metadataError ? html`
          <versola-error-card
            heading="Could not load server metadata"
            .message=${this.metadataError}
            @retry=${() => this.loadMetadata()}
          ></versola-error-card>
        ` : this.renderMetadata()}
    `;
  }

  private renderMetadata() {
    if (this.isEditingMetadata) {
      return html`
        <div class="card metadata-card">
          <div class="form-group">
            <versola-code-editor
              language="json"
              rows="15"
              .value=${this.metadataInput}
              ?invalid=${this.metadataInvalid}
              @code-input=${(e: CustomEvent<{ value: string }>) => this.handleMetadataInput(e.detail.value)}
            ></versola-code-editor>
            ${this.metadataFormError ? html`<div class="add-error">${this.metadataFormError}</div>` : ''}
          </div>
          <div style="display: flex; gap: 1rem; margin-top: 1rem;">
            <button class="btn btn-primary" @click=${this.handleSaveMetadata} ?disabled=${this.isSavingMetadata || this.metadataInvalid}>
              ${this.isSavingMetadata ? 'Saving...' : 'Save Metadata'}
            </button>
            <button class="btn btn-secondary" @click=${this.handleCancelMetadata}>Cancel</button>
          </div>
        </div>
      `;
    }

    if (!this.metadata) {
      return html`
        <div class="card metadata-card">
          <div class="empty-state">
            <div class="empty-state-icon">📄</div>
            <p>No server metadata available.</p>
            ${this.canManage ? html`
              <button class="btn btn-primary" @click=${this.handleEditMetadata} style="margin-top: 1rem;">
                Set Metadata
              </button>
            ` : ''}
          </div>
        </div>
      `;
    }

    const json = JSON.stringify(this.metadata, null, 2);

    return html`
      <div class="card metadata-card">
        ${this.canManage ? html`
          <div class="key-header" style="justify-content: flex-end;">
             <div class="key-actions">
               <button
                 class="icon-action"
                 title="Edit Metadata"
                 @click=${this.handleEditMetadata}
               >✎</button>
             </div>
          </div>
        ` : ''}
        <pre class="metadata-json">${this.renderJson(json)}</pre>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-well-known': VersolaWellKnown;
  }
}
