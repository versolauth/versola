import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { fetchJwks, deleteJwk, createJwk, updateJwk, fetchServerMetadata, upsertServerMetadata } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import './content-header';
import './error-card';
import './loading-cards';

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
  @state() private isSavingMetadata = false;
  @state() private isEditingMetadata = false;

  @state() private formMode: 'add' | 'edit' | null = null;
  @state() private editingKid = '';
  @state() private jwkInput = '';
  @state() private formError = '';
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
    css`
      :host {
        display: block;
      }

      .section-title {
        font-size: 1.25rem;
        font-weight: 600;
        color: var(--text-primary);
        margin: var(--spacing-xl) 0 var(--spacing-lg) 0;
        display: flex;
        align-items: center;
        justify-content: space-between;
      }

      .metadata-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        padding: var(--spacing-lg);
        margin-bottom: var(--spacing-xl);
      }

      .metadata-json {
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-md);
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--text-secondary);
        overflow-x: auto;
        white-space: pre-wrap;
        word-break: break-all;
        margin: 0;
      }

      .key-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        margin-bottom: var(--spacing-md);
        padding: var(--spacing-lg);
        min-width: 0;
      }

      .key-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
        margin-bottom: var(--spacing-md);
      }

      .key-header-info {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
        min-width: 0;
      }

      .key-id {
        font-family: var(--font-mono);
        font-size: 1rem;
        font-weight: 600;
        color: var(--accent);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .key-meta {
        font-size: 0.8125rem;
        color: var(--text-secondary);
        white-space: nowrap;
      }

      .key-json {
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-md);
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--text-secondary);
        overflow-x: auto;
        white-space: pre-wrap;
        word-break: break-all;
        margin: 0;
      }

      .icon-action {
        background: none;
        border: none;
        padding: 0.5rem;
        cursor: pointer;
        color: var(--text-secondary);
        font-size: 1.25rem;
        transition: all var(--transition-fast);
        flex-shrink: 0;
      }

      .icon-action:hover {
        color: var(--accent);
      }

      .icon-action.danger:hover {
        color: var(--danger);
      }

      .key-actions {
        display: flex;
        gap: var(--spacing-xs);
        flex-shrink: 0;
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
    this.metadataInput = JSON.stringify(this.metadata, null, 2);
  }

  private handleCancelMetadata() {
    this.isEditingMetadata = false;
    this.metadataInput = JSON.stringify(this.metadata, null, 2);
  }

  private async handleSaveMetadata() {
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(this.metadataInput);
    } catch {
      alert('Invalid JSON in metadata');
      return;
    }

    this.isSavingMetadata = true;
    try {
      await upsertServerMetadata(parsed);
      this.metadata = parsed;
      this.isEditingMetadata = false;
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to save metadata');
    } finally {
      this.isSavingMetadata = false;
    }
  }

  private handleAddClick() {
    this.formMode = 'add';
    this.editingKid = '';
    this.jwkInput = '';
    this.formError = '';
  }

  private handleEditClick(key: Record<string, unknown>) {
    this.formMode = 'edit';
    this.editingKid = typeof key['kid'] === 'string' ? key['kid'] : '';
    this.jwkInput = JSON.stringify(key, null, 2);
    this.formError = '';
  }

  private handleCancelForm() {
    this.formMode = null;
    this.editingKid = '';
    this.jwkInput = '';
    this.formError = '';
  }

  private async handleSubmitForm() {
    this.formError = '';
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(this.jwkInput);
    } catch {
      this.formError = 'Invalid JSON. Please paste a valid JWK object.';
      return;
    }
    if (typeof parsed['kid'] !== 'string' || !parsed['kid']) {
      this.formError = "JWK must contain a non-empty string 'kid' field.";
      return;
    }
    if (this.formMode === 'edit' && parsed['kid'] !== this.editingKid) {
      this.formError = "Changing 'kid' is not supported. Delete this key and add a new one instead.";
      return;
    }
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
        <h3 class="add-form-title">\${isEdit ? \`Edit JWK · \${this.editingKid}\` : 'Add JWK'}</h3>
        <div class="form-group">
          <label class="form-label" for="jwk-input">JWK JSON</label>
          <textarea
            id="jwk-input"
            class="form-control"
            rows="10"
            placeholder='{"kid": "my-key-id", "kty": "RSA", "use": "sig", ...}'
            .value=\${this.jwkInput}
            @input=\${(e: Event) => { this.jwkInput = (e.target as HTMLTextAreaElement).value; }}
          ></textarea>
          \${isEdit ? html\`<div class="form-hint">The 'kid' cannot be changed when editing.</div>\` : ''}
          \${this.formError ? html\`<div class="add-error">\${this.formError}</div>\` : ''}
        </div>
        <div class="add-form-actions">
          <button
            class="btn btn-primary"
            @click=\${this.handleSubmitForm}
            ?disabled=\${this.isSubmitting}
          >\${this.isSubmitting ? 'Saving…' : isEdit ? 'Save Changes' : 'Add Key'}</button>
          <button class="btn btn-secondary" @click=\${this.handleCancelForm}>Cancel</button>
        </div>
      </div>
    `;
  }

  private renderKey(key: Record<string, unknown>) {
    const kid = typeof key['kid'] === 'string' ? key['kid'] : '(no kid)';
    const kty = typeof key['kty'] === 'string' ? key['kty'] : '';
    const alg = typeof key['alg'] === 'string' ? key['alg'] : '';
    return html`
      <div class="key-card">
        <div class="key-header">
          <div class="key-header-info">
            <span class="key-id">\${kid}</span>
            <span class="key-meta">\${kty}\${alg ? \` · \${alg}\` : ''}</span>
          </div>
          \${this.canManage ? html\`
          <div class="key-actions">
            <button
              class="icon-action"
              title="Edit key"
              aria-label="Edit key \${kid}"
              @click=\${() => this.handleEditClick(key)}
            >✎</button>
            <button
              class="icon-action danger"
              title="Delete key"
              aria-label="Delete key \${kid}"
              @click=\${() => this.handleDeleteKey(kid)}
            >✕</button>
          </div>\` : ''}
        </div>
        <pre class="key-json">\${JSON.stringify(key, null, 2)}</pre>
      </div>
    `;
  }

  render() {
    return html`
      <content-header
        title="Well Known"
        description="Public configuration and keys served by this central instance"
      ></content-header>

      <div class="section-title">
        <span>Server Metadata</span>
        \${this.canManage && !this.isEditingMetadata ? html\`
          <button class="btn btn-primary" @click=\${this.handleEditMetadata}>Edit Metadata</button>
        \` : ''}
      </div>

      \${this.isLoadingMetadata ? html\`<versola-loading-cards .count=\${1}></versola-loading-cards>\` : this.renderMetadata()}

      <div class="section-title">
        <span>JWKS</span>
        \${this.canManage ? html\`
        <button slot="actions" class="btn btn-primary" @click=\${this.handleAddClick}>
          + Add Key
        </button>\` : ''}
      </div>

      \${this.formMode !== null ? this.renderForm() : ''}

      \${this.isLoading
        ? html\`<versola-loading-cards .count=\${3}></versola-loading-cards>\`
        : this.errorMessage
          ? html\`
            <versola-error-card
              heading="Could not load JWKS"
              .message=\${this.errorMessage}
              @retry=\${() => this.loadData()}
            ></versola-error-card>
          \`
          : this.keys.length === 0 && this.formMode === null
            ? html\`
              <div class="card">
                <div class="empty-state">
                  <div class="empty-state-icon">🔑</div>
                  <p>No keys found.</p>
                  \${this.canManage ? html\`
                  <button class="btn btn-primary" @click=\${this.handleAddClick} style="margin-top: 1rem;">
                    + Add Key
                  </button>\` : ''}
                </div>
              </div>
            \`
            : this.keys.map(k => this.renderKey(k))
      }
    `;
  }

  private renderMetadata() {
    if (this.isEditingMetadata) {
      return html`
        <div class="metadata-card">
          <div class="form-group">
            <textarea
              class="form-control"
              rows="15"
              .value=\${this.metadataInput}
              @input=\${(e: Event) => this.metadataInput = (e.target as HTMLTextAreaElement).value}
            ></textarea>
          </div>
          <div style="display: flex; gap: 1rem; margin-top: 1rem;">
            <button class="btn btn-primary" @click=\${this.handleSaveMetadata} ?disabled=\${this.isSavingMetadata}>
              \${this.isSavingMetadata ? 'Saving...' : 'Save Metadata'}
            </button>
            <button class="btn btn-secondary" @click=\${this.handleCancelMetadata}>Cancel</button>
          </div>
        </div>
      `;
    }

    return html`
      <div class="metadata-card">
        <pre class="metadata-json">\${JSON.stringify(this.metadata, null, 2)}</pre>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-well-known': VersolaWellKnown;
  }
}
