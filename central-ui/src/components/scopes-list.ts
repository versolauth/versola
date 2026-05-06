import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { OAuthScope } from '../types';
import { createScope, deleteScope, fetchAllScopes, updateScope } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { getLocalizedDescription } from '../utils/helpers';
import './scope-form';
import './content-header';
import './loading-cards';

@customElement('versola-scopes-list')
export class VersolaScopesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;

  @state() private scopes: OAuthScope[] = [];
  @state() private searchQuery = '';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private showCreateForm = false;
  @state() private editingScope: OAuthScope | null = null;
  @state() private expandedScopes: Set<string> = new Set();
  private loadRequestId = 0;

  updated(changedProperties: Map<string, unknown>) {
    if (changedProperties.has('tenantId')) {
      this.expandedScopes = new Set();
    }

    if (changedProperties.has('tenantId')) {
      void this.loadData();
    }
  }

  private async loadData() {
    if (!this.tenantId) {
      this.scopes = [];
      this.errorMessage = '';
      return;
    }

    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';

    try {
      const result = await fetchAllScopes(this.tenantId);
      if (requestId !== this.loadRequestId) return;
      this.scopes = result;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.scopes = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load scopes';
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

      .search-bar {
        margin-bottom: var(--spacing-lg);
        max-width: 28rem;
      }

      .header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: var(--spacing-xl);
      }

      .title {
        font-size: 2rem;
        font-weight: 700;
        color: var(--text-primary);
        margin: 0;
      }

      .scope-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        margin-bottom: var(--spacing-md);
        transition: border-color var(--transition-base);
      }

      .scope-card:hover {
        border-color: var(--accent);
      }

      .scope-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-lg);
        cursor: pointer;
        user-select: none;
      }

      .scope-info {
        flex: 1;
      }

      .scope-name {
        font-size: 1.125rem;
        font-weight: 600;
        color: var(--text-primary);
        margin-bottom: 0.25rem;
      }

      .scope-id {
        color: var(--accent);
        font-size: 0.875rem;
        font-family: var(--font-mono);
        word-break: break-all;
      }

      .scope-description {
        color: var(--text-secondary);
        font-size: 0.875rem;
      }

      .scope-actions {
        display: flex;
        gap: 0.5rem;
        margin-left: var(--spacing-md);
      }

      .scope-body {
        border-top: 1px solid var(--border-dark);
        padding: var(--spacing-lg);
      }

      .claims-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
        gap: var(--spacing-md);
      }

      .claim-item {
        background: rgba(0, 0, 0, 0.2);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-md);
      }

      .claim-name {
        font-family: var(--font-mono);
        font-size: 0.875rem;
        color: var(--accent);
        margin-bottom: 0.25rem;
        font-weight: 600;
      }

      .claim-description {
        font-size: 0.8125rem;
        color: var(--text-secondary);
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

  private toggleExpand(scopeId: string) {
    if (this.expandedScopes.has(scopeId)) {
      this.expandedScopes.delete(scopeId);
    } else {
      this.expandedScopes.add(scopeId);
    }
    this.requestUpdate();
  }

  private handleCreateClick() {
    this.showCreateForm = true;
  }

  private handleEditClick(scope: OAuthScope, e: Event) {
    e.stopPropagation();
    this.editingScope = scope;
    this.showCreateForm = true;
  }

  private async handleDeleteClick(scope: OAuthScope, e: Event) {
    e.stopPropagation();
    if (!this.tenantId) {
      return;
    }

    const confirmed = await confirmDestructiveAction({
      title: 'Delete scope',
      messagePrefix: 'Delete scope ',
      messageSubject: scope.id,
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });

    if (!confirmed) {
      return;
    }

    try {
      await deleteScope(this.tenantId, scope.id);
      this.removeScopeFromState(scope.id);
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to delete scope');
    }
  }

  private handleFormClose() {
    this.showCreateForm = false;
    this.editingScope = null;
  }

  private updateScopeInState(scope: OAuthScope, previousId: string) {
    this.scopes = this.scopes.map(existing =>
      existing.id === previousId ? scope : existing,
    );

    if (this.editingScope?.id === previousId) {
      this.editingScope = scope;
    }
  }

  private addScopeToState(scope: OAuthScope) {
    this.scopes = [scope, ...this.scopes];
    this.expandedScopes = new Set([...this.expandedScopes, scope.id]);
  }

  private removeScopeFromState(scopeId: string) {
    this.scopes = this.scopes.filter(scope => scope.id !== scopeId);

    const expandedScopes = new Set(this.expandedScopes);
    expandedScopes.delete(scopeId);
    this.expandedScopes = expandedScopes;

    if (this.editingScope?.id === scopeId) {
      this.editingScope = null;
      this.showCreateForm = false;
    }
  }

  private async handleFormSubmit(e: CustomEvent) {
    if (!this.tenantId) {
      return;
    }

    const scope = e.detail.scope as OAuthScope;

    try {
      if (this.editingScope) {
        const previousId = this.editingScope.id;
        await updateScope(this.tenantId, this.editingScope, scope);
        this.updateScopeInState(scope, previousId);
      } else {
        await createScope(this.tenantId, scope);
        this.addScopeToState(scope);
      }

      this.handleFormClose();
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to save scope');
    }
  }

  private get filteredScopes(): OAuthScope[] {
    const query = this.searchQuery.trim().toLowerCase();
    if (!query) return this.scopes;
    return this.scopes.filter(scope => scope.id.toLowerCase().includes(query));
  }

  render() {
    if (this.showCreateForm) {
      return html`
        <versola-scope-form
          .scope=${this.editingScope}
          @close=${this.handleFormClose}
          @save-scope=${this.handleFormSubmit}
        ></versola-scope-form>
      `;
    }

    return html`
      <content-header
        title="OAuth Scopes"
      >
        ${this.scopes.length > 0 ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick}>
            + Create Scope
          </button>
        ` : ''}
      </content-header>

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <div class="card">
          <div class="empty-state">
            <div class="empty-state-icon">⚠️</div>
            <h3>Could not load OAuth scopes</h3>
            <p>${this.errorMessage}</p>
            <button class="btn btn-primary" @click=${() => this.loadData()} style="margin-top: 1rem;">
              Retry
            </button>
          </div>
        </div>
      ` : this.scopes.length === 0 ? html`
        <div class="card">
          <div class="empty-state">
            <h3>No OAuth scopes yet</h3>
            <p>Create your first OAuth scope to get started</p>
            <button class="btn btn-primary" @click=${this.handleCreateClick} style="margin-top: 1rem;">
              + Create Scope
            </button>
          </div>
        </div>
      ` : this.filteredScopes.length === 0 ? html`
        <div class="search-bar">
          <input class="form-input" type="search" aria-label="Search scopes" .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} placeholder="Search scopes by ID" />
        </div>
        <div class="card">
          <div class="empty-state">
            <h3>No scopes match your search</h3>
          </div>
        </div>
      ` : html`
        <div class="search-bar">
          <input class="form-input" type="search" aria-label="Search scopes" .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} placeholder="Search scopes by ID" />
        </div>
        ${this.filteredScopes.map(scope => {
          const isExpanded = this.expandedScopes.has(scope.id);
          return html`
            <div class="scope-card">
              <div class="scope-header" @click=${() => this.toggleExpand(scope.id)}>
                <div class="scope-info">
                  <div class="scope-name">${getLocalizedDescription(scope.description)}</div>
                  <div class="scope-id">${scope.id}</div>
                </div>

                <div class="scope-actions" @click=${(e: Event) => e.stopPropagation()}>
                  <button
                    type="button"
                    class="icon-action"
                    aria-label=${`Edit scope ${scope.id}`}
                    @click=${(e: Event) => this.handleEditClick(scope, e)}
                    title="Edit"
                  >
                    ✎
                  </button>
                  <button
                    type="button"
                    class="icon-action danger"
                    aria-label=${`Delete scope ${scope.id}`}
                    @click=${(e: Event) => this.handleDeleteClick(scope, e)}
                    title="Delete"
                  >
                    ✕
                  </button>
                </div>
              </div>

              ${isExpanded && scope.claims.length > 0 ? html`
                <div class="scope-body">
                  <div class="claims-grid">
                    ${scope.claims.map(claim => html`
                      <div class="claim-item">
                        <div class="claim-name">${claim.id}</div>
                        <div class="claim-description">
                          ${getLocalizedDescription(claim.description)}
                        </div>
                      </div>
                    `)}
                  </div>
                </div>
              ` : ''}
            </div>
          `;
        })}
      `}
    `;
  }
}

