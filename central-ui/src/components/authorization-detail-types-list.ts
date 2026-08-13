import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { AuthorizationDetailType } from '../types';
import {
  createAuthorizationDetailType,
  deleteAuthorizationDetailType,
  fetchAuthorizationDetailTypes,
  updateAuthorizationDetailType,
} from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { getLocalizedDescription } from '../utils/helpers';
import './authorization-detail-type-form';
import './content-header';
import './error-card';
import './loading-cards';

/** Manages the RFC 9396 authorization detail type registry for a tenant. */
@customElement('versola-authorization-detail-types-list')
export class VersolaAuthorizationDetailTypesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;

  @state() private types: AuthorizationDetailType[] = [];
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private showForm = false;
  @state() private editingType: AuthorizationDetailType | null = null;
  @state() private expandedTypes: Set<string> = new Set();
  private loadRequestId = 0;

  updated(changedProperties: Map<string, unknown>) {
    if (changedProperties.has('tenantId')) {
      this.expandedTypes = new Set();
      void this.loadData();
    }
  }

  private async loadData() {
    if (!this.tenantId) {
      this.types = [];
      this.errorMessage = '';
      return;
    }

    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';

    try {
      const result = await fetchAuthorizationDetailTypes(this.tenantId);
      if (requestId !== this.loadRequestId) return;
      this.types = result;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.types = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load authorization detail types';
    } finally {
      if (requestId === this.loadRequestId) {
        this.isLoading = false;
      }
    }
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

      .type-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        margin-bottom: var(--spacing-md);
        overflow: hidden;
      }

      .type-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: var(--spacing-lg);
        cursor: pointer;
      }

      .type-name {
        font-weight: 600;
        color: var(--text-primary);
        margin-bottom: 0.25rem;
      }

      .type-id {
        color: var(--accent);
        font-size: 0.875rem;
        font-family: var(--font-mono);
        word-break: break-all;
      }

      .type-actions {
        display: flex;
        gap: 0.5rem;
        margin-left: var(--spacing-md);
      }

      .type-body {
        border-top: 1px solid var(--border-dark);
        padding: var(--spacing-lg);
      }

      .schema-preview {
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--text-secondary);
        background: rgba(0, 0, 0, 0.2);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-md);
        margin: 0;
        overflow-x: auto;
        white-space: pre;
      }

      .empty-state {
        text-align: center;
        padding: 3rem;
        color: var(--text-secondary);
      }

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

      .icon-action:hover {
        color: var(--accent);
        transform: scale(1.15);
      }

      .icon-action.danger:hover {
        color: var(--danger);
      }
    `,
  ];

  private toggleExpand(type: string) {
    const expanded = new Set(this.expandedTypes);
    if (expanded.has(type)) {
      expanded.delete(type);
    } else {
      expanded.add(type);
    }
    this.expandedTypes = expanded;
  }

  private handleCreateClick() {
    this.editingType = null;
    this.showForm = true;
  }

  private handleEditClick(detailType: AuthorizationDetailType, e: Event) {
    e.stopPropagation();
    this.editingType = detailType;
    this.showForm = true;
  }

  private async handleDeleteClick(detailType: AuthorizationDetailType, e: Event) {
    e.stopPropagation();
    if (!this.tenantId) {
      return;
    }

    const confirmed = await confirmDestructiveAction({
      title: 'Delete authorization detail type',
      messagePrefix: 'Delete authorization detail type ',
      messageSubject: detailType.type,
      messageSuffix: '? Clients requesting it will be rejected.',
      confirmLabel: 'Delete',
    });

    if (!confirmed) {
      return;
    }

    try {
      await deleteAuthorizationDetailType(this.tenantId, detailType.type);
      this.types = this.types.filter(existing => existing.type !== detailType.type);
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to delete authorization detail type');
    }
  }

  private handleFormClose() {
    this.showForm = false;
    this.editingType = null;
  }

  private async handleFormSubmit(e: CustomEvent) {
    if (!this.tenantId) {
      return;
    }

    const detailType = e.detail.detailType as AuthorizationDetailType;

    try {
      if (this.editingType) {
        await updateAuthorizationDetailType(this.tenantId, detailType);
        this.types = this.types.map(existing => existing.type === detailType.type ? detailType : existing);
      } else {
        await createAuthorizationDetailType(this.tenantId, detailType);
        this.types = [detailType, ...this.types];
      }

      this.handleFormClose();
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to save authorization detail type');
    }
  }

  render() {
    if (this.showForm) {
      return html`
        <versola-authorization-detail-type-form
          .detailType=${this.editingType}
          @close=${this.handleFormClose}
          @save-authorization-detail-type=${this.handleFormSubmit}
        ></versola-authorization-detail-type-form>
      `;
    }

    if (this.isLoading) {
      return html`<versola-loading-cards .count=${3}></versola-loading-cards>`;
    }

    if (this.errorMessage) {
      return html`
        <versola-error-card
          heading="Could not load authorization detail types"
          .message=${this.errorMessage}
          @retry=${() => this.loadData()}
        ></versola-error-card>
      `;
    }

    if (this.types.length === 0) {
      return html`
        <div class="card">
          <div class="empty-state">
            <h3>No authorization detail types yet</h3>
            <p>Register a type before clients can use it in <code>authorization_details</code></p>
            ${this.canManage ? html`
              <button class="btn btn-primary" @click=${this.handleCreateClick} style="margin-top: 1rem;">
                + Create Type
              </button>` : ''}
          </div>
        </div>
      `;
    }

    return html`
      ${this.canManage ? html`
        <div style="display: flex; justify-content: flex-end; margin-bottom: var(--spacing-md);">
          <button class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Type</button>
        </div>
      ` : ''}

      ${this.types.map(detailType => html`
        <div class="type-card">
          <div class="type-header" @click=${() => this.toggleExpand(detailType.type)}>
            <div>
              <div class="type-name">${getLocalizedDescription(detailType.description)}</div>
              <div class="type-id">${detailType.type}</div>
            </div>

            ${this.canManage ? html`
              <div class="type-actions" @click=${(e: Event) => e.stopPropagation()}>
                <button
                  type="button"
                  class="icon-action"
                  aria-label=${`Edit authorization detail type ${detailType.type}`}
                  @click=${(e: Event) => this.handleEditClick(detailType, e)}
                  title="Edit"
                >
                  ✎
                </button>
                <button
                  type="button"
                  class="icon-action danger"
                  aria-label=${`Delete authorization detail type ${detailType.type}`}
                  @click=${(e: Event) => this.handleDeleteClick(detailType, e)}
                  title="Delete"
                >
                  ✕
                </button>
              </div>` : ''}
          </div>

          ${this.expandedTypes.has(detailType.type) ? html`
            <div class="type-body">
              <pre class="schema-preview">${JSON.stringify(detailType.schema, null, 2)}</pre>
            </div>
          ` : ''}
        </div>
      `)}
    `;
  }
}
