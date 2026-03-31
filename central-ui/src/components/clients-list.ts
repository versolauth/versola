import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { badgeStyles, buttonStyles, cardStyles, formStyles } from '../styles/components';
import { OAuthClient, OAuthScope, Permission, Resource } from '../types';
import {
  createClient,
  deleteClient,
  deletePreviousClientSecret,
  fetchAllClients,
  fetchAllPermissions,
  fetchClientPresets,
  fetchResources,
  fetchAllScopes,
  rotateClientSecret,
  updateClient,
} from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { copyToClipboard, formatDuration } from '../utils/helpers';
import './client-form';
import './content-header';
import './loading-cards';
import './preset-form';

@customElement('versola-clients-list')
export class VersolaClientsList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: String }) expandClientId: string | null = null;

  @state() private clients: OAuthClient[] = [];
  @state() private searchQuery = '';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private showCreateForm = false;
  @state() private editingClient: OAuthClient | null = null;
  @state() private expandedClients: Set<string> = new Set();
  @state() private expandedPresets: Set<string> = new Set(); // Track which clients have presets section expanded
  @state() private expandedPresetCards: Set<string> = new Set(); // Track which individual preset cards are expanded
  @state() private availableScopes: OAuthScope[] = [];
  @state() private availablePermissions: Permission[] = [];
  @state() private availableResources: Resource[] = [];
  @state() private isPreparingForm = false;
  @state() private createdSecret: { clientName: string; secret: string; action: 'created' | 'rotated' } | null = null;
  @state() private copyFeedback = '';
  @state() private editingPresetsForClient: OAuthClient | null = null;
  @state() private presetDrafts: AuthorizationPreset[] = [];
  @state() private expandedPresetEditors: Set<string> = new Set();
  @state() private showPresetForm = false;
  @state() private editingPreset: AuthorizationPreset | null = null;
  @state() private isSavingPresets = false;
  private loadRequestId = 0;
  private formOptionsTenantId: string | null = null;

  updated(changedProperties: Map<string, unknown>) {
    if (changedProperties.has('tenantId')) {
      this.expandedClients = new Set();
      this.availableScopes = [];
      this.availablePermissions = [];
      this.availableResources = [];
      this.createdSecret = null;
      this.copyFeedback = '';
      this.formOptionsTenantId = null;
    }

    if (changedProperties.has('tenantId')) {
      void this.loadData();
    }

    if (changedProperties.has('expandClientId') && this.expandClientId) {
      this.expandedClients = new Set([...this.expandedClients, this.expandClientId]);
    }
  }

  private async loadData() {
    if (!this.tenantId) {
      this.clients = [];
      this.errorMessage = '';
      return;
    }

    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';

    try {
      const result = await fetchAllClients(this.tenantId);
      if (requestId !== this.loadRequestId) return;
      this.clients = result;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.clients = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load clients';
    } finally {
      if (requestId === this.loadRequestId) {
        this.isLoading = false;
      }
    }
  }

  static styles = [
    theme,
    badgeStyles,
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

      .client-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        margin-bottom: var(--spacing-md);
        transition: border-color var(--transition-base);
      }

      .client-card:hover {
        border-color: var(--accent);
      }

      .client-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-lg);
        cursor: pointer;
        user-select: none;
      }

      .client-info {
        flex: 1;
      }

      .client-name {
        font-size: 1.125rem;
        font-weight: 600;
        color: var(--text-primary);
        margin-bottom: 0.25rem;
        display: flex;
        align-items: center;
        gap: 0.75rem;
      }

      .client-id {
        font-family: var(--font-mono);
        font-size: 0.875rem;
        color: var(--accent);
        word-break: break-all;
      }

      .client-actions {
        display: flex;
        gap: 0.5rem;
        margin-left: var(--spacing-md);
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

      .client-body {
        border-top: 1px solid var(--border-dark);
        padding: var(--spacing-lg);
      }

      .details-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
        gap: var(--spacing-lg);
      }

      .detail-section {
        background: rgba(0, 0, 0, 0.2);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        padding: var(--spacing-md);
      }

      .detail-label {
        font-size: 0.75rem;
        font-weight: 600;
        color: var(--text-secondary);
        text-transform: uppercase;
        letter-spacing: 0.05em;
        margin-bottom: 0.5rem;
      }

      .detail-value {
        color: var(--text-primary);
        font-size: 0.875rem;
      }

      .uri-list, .scope-list, .permission-list {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
      }

      .uri-item {
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--accent);
        word-break: break-all;
      }

      .tag-list {
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem;
      }

      .tag {
        padding: 0.25rem 0.5rem;
        background: rgba(88, 166, 255, 0.15);
        color: var(--accent);
        border-radius: var(--radius-sm);
        font-size: 0.75rem;
        font-family: var(--font-mono);
      }

      .secret-banner {
        margin-bottom: var(--spacing-lg);
        border-color: rgba(63, 185, 80, 0.35);
        background: linear-gradient(180deg, rgba(63, 185, 80, 0.08), rgba(63, 185, 80, 0.04));
      }

      .secret-banner-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: var(--spacing-md);
        margin-bottom: var(--spacing-md);
      }

      .secret-banner-title {
        margin: 0;
        font-size: 1rem;
        color: var(--text-primary);
      }

      .secret-banner-text {
        margin: 0.35rem 0 0;
        color: var(--text-secondary);
        font-size: 0.875rem;
      }

      .secret-value {
        margin: 0;
        padding: 0.875rem 1rem;
        background: rgba(0, 0, 0, 0.25);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-family: var(--font-mono);
        font-size: 0.875rem;
        line-height: 1.5;
        word-break: break-all;
      }

      .secret-banner-actions {
        display: flex;
        gap: 0.75rem;
        align-items: center;
        margin-top: var(--spacing-md);
      }

      .copy-feedback {
        font-size: 0.8125rem;
        color: var(--success, #3fb950);
      }

      .btn-ghost {
        background: transparent;
        color: var(--text-secondary);
        border-color: var(--border-dark);
      }

      .btn-ghost:not(:disabled):hover {
        color: var(--text-primary);
        border-color: var(--text-secondary);
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

      /* Authorization Presets */
      .presets-section {
        margin-top: var(--spacing-lg);
        border-top: 1px solid var(--border-dark);
        padding-top: var(--spacing-lg);
      }

      .presets-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        cursor: pointer;
        user-select: none;
        padding: var(--spacing-sm) 0;
      }

      .presets-header:hover .presets-title {
        color: var(--accent);
      }

      .presets-title {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        font-weight: 600;
        font-size: 0.9375rem;
        color: var(--text-primary);
        transition: color var(--transition-fast);
      }

      .expand-icon {
        font-size: 0.75rem;
        color: var(--text-secondary);
      }

      .presets-body {
        padding-top: var(--spacing-md);
      }

      .empty-presets {
        padding: var(--spacing-lg);
        text-align: center;
        color: var(--text-secondary);
        font-size: 0.875rem;
        background: rgba(0, 0, 0, 0.2);
        border-radius: var(--radius-md);
      }

      .presets-list {
        display: grid;
        gap: var(--spacing-md);
      }

      .preset-card {
        background: rgba(0, 0, 0, 0.2);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        transition: border-color var(--transition-fast);
        overflow: hidden;
      }

      .preset-card:hover {
        border-color: var(--accent);
      }

      .preset-card-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-md);
        cursor: pointer;
        user-select: none;
        transition: background var(--transition-fast);
      }

      .preset-card-header:hover {
        background: rgba(88, 166, 255, 0.05);
      }

      .preset-info {
        flex: 1;
        min-width: 0;
      }

      .preset-name {
        font-weight: 600;
        font-size: 0.9375rem;
        color: var(--text-primary);
        margin-bottom: 0.25rem;
      }

      .preset-id {
        font-size: 0.8125rem;
        color: var(--accent);
        font-family: var(--font-mono);
      }

      .preset-actions {
        display: flex;
        gap: var(--spacing-sm);
      }

      .preset-body {
        padding: 0 var(--spacing-md) var(--spacing-md);
        border-top: 1px solid var(--border-dark);
      }

      .preset-details {
        display: grid;
        gap: var(--spacing-sm);
        padding-top: var(--spacing-md);
      }

      .preset-row {
        display: flex;
        gap: var(--spacing-sm);
        font-size: 0.8125rem;
      }

      .preset-label {
        color: var(--text-secondary);
        font-weight: 500;
        min-width: 7rem;
      }

      .preset-value {
        color: var(--text-primary);
        font-family: var(--font-mono);
        word-break: break-all;
      }
    `,
  ];

  private get filteredClients(): OAuthClient[] {
    const query = this.searchQuery.trim().toLowerCase();
    if (!query) return this.clients;
    return this.clients.filter(client => client.id.toLowerCase().includes(query));
  }

  private async toggleExpand(clientId: string) {
    if (this.expandedClients.has(clientId)) {
      this.expandedClients.delete(clientId);
    } else {
      this.expandedClients.add(clientId);
      // Load presets when expanding if not already loaded
      await this.loadPresetsForClient(clientId);
    }
    this.requestUpdate();
  }

  private togglePresetExpand(presetId: string) {
    if (this.expandedPresetCards.has(presetId)) {
      this.expandedPresetCards.delete(presetId);
    } else {
      this.expandedPresetCards.add(presetId);
    }
    this.requestUpdate();
  }

  private async loadPresetsForClient(clientId: string) {
    if (!this.tenantId) return;

    const client = this.clients.find(c => c.id === clientId);
    if (!client) return;

    // Skip if already loaded
    if (client.authorizationPresets !== undefined) return;

    try {
      const presets = await fetchClientPresets(this.tenantId, clientId);
      // Update the client with loaded presets
      this.clients = this.clients.map(c =>
        c.id === clientId ? { ...c, authorizationPresets: presets } : c
      );
    } catch (error) {
      console.error(`Failed to load presets for client ${clientId}:`, error);
      // Set empty array on error to avoid retrying
      this.clients = this.clients.map(c =>
        c.id === clientId ? { ...c, authorizationPresets: [] } : c
      );
    }
  }

  private handleCreateClick() {
    void this.openForm(null);
  }

  private handleEditClick(client: OAuthClient) {
    void this.openForm(client);
  }

  private async ensureFormOptionsLoaded() {
    const tenantId = this.tenantId;

    if (!tenantId || this.formOptionsTenantId === tenantId) {
      return;
    }

    const [scopes, permissions, resources] = await Promise.all([
      fetchAllScopes(tenantId),
      fetchAllPermissions(tenantId),
      fetchResources(tenantId),
    ]);

    if (this.tenantId === tenantId) {
      this.availableScopes = scopes;
      this.availablePermissions = permissions;
      this.availableResources = resources;
      this.formOptionsTenantId = tenantId;
    }
  }

  private async openForm(client: OAuthClient | null) {
    if (!this.tenantId) {
      this.errorMessage = 'Select a tenant first to manage OAuth clients.';
      return;
    }

    this.createdSecret = null;
    this.copyFeedback = '';
    this.isPreparingForm = true;

    try {
      await this.ensureFormOptionsLoaded();
      this.editingClient = client;
      this.showCreateForm = true;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load form options';
    } finally {
      this.isPreparingForm = false;
    }
  }

  private async handleDeleteClick(client: OAuthClient) {
    if (!this.tenantId) {
      return;
    }

    const confirmed = await confirmDestructiveAction({
      title: 'Delete client',
      messagePrefix: 'Delete client ',
      messageSubject: client.id,
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });

    if (!confirmed) {
      return;
    }

    try {
      await deleteClient(this.tenantId, client.id);
      this.removeClientFromState(client.id);
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to delete client');
    }
  }

  private handleFormClose() {
    this.showCreateForm = false;
    this.editingClient = null;
    this.isPreparingForm = false;
  }

  private dismissCreatedSecret() {
    this.createdSecret = null;
    this.copyFeedback = '';
  }

  private async handleCopySecret() {
    if (!this.createdSecret) {
      return;
    }

    const copied = await copyToClipboard(this.createdSecret.secret);
    this.copyFeedback = copied ? 'Secret copied to clipboard.' : 'Could not copy secret.';
  }

  private updateClientSecretState(clientId: string, hasPreviousSecret: boolean) {
    this.clients = this.clients.map(client =>
      client.id === clientId ? { ...client, hasPreviousSecret } : client,
    );

    if (this.editingClient?.id === clientId) {
      this.editingClient = { ...this.editingClient, hasPreviousSecret };
    }
  }

  private updateClientInState(client: OAuthClient, previousId: string) {
    const existingClient = this.clients.find(current => current.id === previousId);
    const updatedClient: OAuthClient = {
      ...existingClient,
      ...client,
      hasPreviousSecret: existingClient?.hasPreviousSecret ?? this.editingClient?.hasPreviousSecret ?? false,
      tenantId: existingClient?.tenantId ?? this.tenantId ?? undefined,
    };

    this.clients = this.clients.map(current =>
      current.id === previousId ? updatedClient : current,
    );

    if (this.editingClient?.id === previousId) {
      this.editingClient = updatedClient;
    }
  }

  private addClientToState(client: OAuthClient) {
    const createdClient: OAuthClient = {
      ...client,
      hasPreviousSecret: client.hasPreviousSecret ?? false,
      tenantId: client.tenantId ?? this.tenantId ?? undefined,
    };

    this.clients = [createdClient, ...this.clients];
    this.expandedClients = new Set([...this.expandedClients, createdClient.id]);
  }

  private removeClientFromState(clientId: string) {
    this.clients = this.clients.filter(client => client.id !== clientId);

    const expandedClients = new Set(this.expandedClients);
    expandedClients.delete(clientId);
    this.expandedClients = expandedClients;

    if (this.editingClient?.id === clientId) {
      this.editingClient = null;
      this.showCreateForm = false;
    }
  }

  private async handleRotateSecret(e: CustomEvent<{ clientId: string; clientName: string }>) {
    e.stopPropagation();

    if (!this.tenantId) {
      return;
    }

    const { clientId, clientName } = e.detail;

    try {
      const secret = await rotateClientSecret(this.tenantId, clientId);
      this.updateClientSecretState(clientId, true);
      this.handleFormClose();

      this.createdSecret = {
        clientName,
        secret,
        action: 'rotated',
      };
      this.copyFeedback = '';
      this.expandedClients = new Set([...this.expandedClients, clientId]);
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to rotate client secret');
    }
  }

  private async handleDeletePreviousSecret(e: CustomEvent<{ clientId: string; clientName: string }>) {
    e.stopPropagation();

    if (!this.tenantId) {
      return;
    }

    const { clientId, clientName } = e.detail;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete old secret',
      messagePrefix: 'Delete the old secret for ',
      messageSubject: clientId,
      messageSuffix: '? This action cannot be undone.',
      confirmLabel: 'Delete',
    });

    if (!confirmed) {
      return;
    }

    try {
      await deletePreviousClientSecret(this.tenantId, clientId);
      this.updateClientSecretState(clientId, false);
      this.handleFormClose();
      this.expandedClients = new Set([...this.expandedClients, clientId]);
    } catch (error) {
      alert(error instanceof Error ? error.message : `Failed to delete old secret for ${clientName}`);
    }
  }

  private async handleFormSubmit(e: CustomEvent) {
    if (!this.tenantId) {
      return;
    }

    const client = e.detail.client as OAuthClient;

    try {
      let generatedSecret: string | null = null;

      if (this.editingClient) {
        const previousId = this.editingClient.id;
        await updateClient(this.tenantId, this.editingClient, client);
        this.updateClientInState(client, previousId);
      } else {
        generatedSecret = await createClient(this.tenantId, client);
        this.addClientToState(client);
      }

      this.handleFormClose();

      if (generatedSecret) {
        this.createdSecret = {
          clientName: client.clientName,
          secret: generatedSecret,
          action: 'created',
        };
        this.copyFeedback = '';
        this.expandedClients = new Set([...this.expandedClients, client.id]);
      }
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to save client');
    }
  }

  render() {
    const secretTitle = this.createdSecret?.action === 'rotated'
      ? `Secret rotated: ${this.createdSecret.clientName}`
      : this.createdSecret
        ? `Client created: ${this.createdSecret.clientName}`
        : '';
    const secretText = this.createdSecret?.action === 'rotated'
      ? 'Copy the new client secret now. It may not be shown again.'
      : 'Copy this secret now. It may not be shown again.';

    if (this.isPreparingForm && !this.showCreateForm) {
      return html`
        <content-header
          title="OAuth Clients"
        ></content-header>
        <div class="card empty-state">
          <div class="empty-state-icon">⏳</div>
          <h3>Loading form options…</h3>
          <p>Fetching scopes and permissions for the selected tenant.</p>
        </div>
      `;
    }

    if (this.showPresetForm && this.editingPresetsForClient) {
      return html`
        <content-header title="OAuth Clients"></content-header>
        <versola-preset-form
          .preset=${this.editingPreset}
          .client=${this.editingPresetsForClient}
          .availableScopes=${this.availableScopes}
          @close=${this.handlePresetFormClose}
          @submit=${this.handlePresetFormSubmit}
        ></versola-preset-form>
      `;
    }

    if (this.editingPresetsForClient) {
      return html`
        <content-header title="OAuth Clients"></content-header>
        ${this.renderPresetsEditForm()}
      `;
    }

    if (this.showCreateForm) {
      return html`
        ${this.createdSecret ? html`
          <div class="card secret-banner">
            <div class="secret-banner-header">
              <div>
                <h3 class="secret-banner-title">${secretTitle}</h3>
                <p class="secret-banner-text">${secretText}</p>
              </div>
              <button class="btn btn-ghost btn-sm" @click=${this.dismissCreatedSecret}>Dismiss</button>
            </div>

            <pre class="secret-value">${this.createdSecret.secret}</pre>

            <div class="secret-banner-actions">
              <button class="btn btn-primary btn-sm" @click=${this.handleCopySecret}>Copy secret</button>
              ${this.copyFeedback ? html`<span class="copy-feedback">${this.copyFeedback}</span>` : ''}
            </div>
          </div>
        ` : ''}
        <versola-client-form
          .client=${this.editingClient}
          .availableScopes=${this.availableScopes}
          .availablePermissions=${this.availablePermissions}
          .availableResources=${this.availableResources}
          .availableClientIds=${this.clients.map(client => client.id)}
          @close=${this.handleFormClose}
          @delete-previous-secret=${this.handleDeletePreviousSecret}
          @rotate-secret=${this.handleRotateSecret}
          @submit=${this.handleFormSubmit}
        ></versola-client-form>
      `;
    }

    return html`
      <content-header
        title="OAuth Clients"
      >
        ${this.clients.length > 0 ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick} ?disabled=${this.isPreparingForm || !this.tenantId}>
            + Create Client
          </button>
        ` : ''}
      </content-header>

      ${this.createdSecret ? html`
        <div class="card secret-banner">
          <div class="secret-banner-header">
            <div>
              <h3 class="secret-banner-title">${secretTitle}</h3>
              <p class="secret-banner-text">${secretText}</p>
            </div>
            <button class="btn btn-ghost btn-sm" @click=${this.dismissCreatedSecret}>Dismiss</button>
          </div>

          <pre class="secret-value">${this.createdSecret.secret}</pre>

          <div class="secret-banner-actions">
            <button class="btn btn-primary btn-sm" @click=${this.handleCopySecret}>Copy secret</button>
            ${this.copyFeedback ? html`<span class="copy-feedback">${this.copyFeedback}</span>` : ''}
          </div>
        </div>
      ` : ''}

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <div class="card">
          <div class="empty-state">
            <div class="empty-state-icon">⚠️</div>
            <h3>Could not load OAuth clients</h3>
            <p>${this.errorMessage}</p>
            <button class="btn btn-primary" @click=${() => this.loadData()} style="margin-top: 1rem;">
              Retry
            </button>
          </div>
        </div>
      ` : this.clients.length === 0 ? html`
        <div class="card">
          <div class="empty-state">
            <h3>No OAuth clients yet</h3>
            <p>Create your first OAuth client to get started</p>
            <button class="btn btn-primary" @click=${this.handleCreateClick} ?disabled=${this.isPreparingForm || !this.tenantId} style="margin-top: 1rem;">
              + Create Client
            </button>
          </div>
        </div>
      ` : this.filteredClients.length === 0 ? html`
        <div class="search-bar">
          <input class="form-input" type="search" aria-label="Search clients" .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} placeholder="Search clients by client ID" />
        </div>
        <div class="card">
          <div class="empty-state">
            <h3>No clients match your search</h3>
          </div>
        </div>
      ` : html`
        <div>
          <div class="search-bar">
            <input class="form-input" type="search" aria-label="Search clients" .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} placeholder="Search clients by client ID" />
          </div>
          ${this.filteredClients.map(client => {
            const isExpanded = this.expandedClients.has(client.id);
            return html`
              <div class="client-card">
                <div class="client-header" @click=${() => this.toggleExpand(client.id)}>
                  <div class="client-info">
                    <div class="client-name">
                      ${client.clientName}
                      ${client.hasPreviousSecret ? html`
                        <span class="badge badge-warning">Secret Rotation</span>
                      ` : ''}
                    </div>
                    <div class="client-id">${client.id}</div>
                  </div>

                  <div class="client-actions" @click=${(e: Event) => e.stopPropagation()}>
                    <button
                      type="button"
                      class="icon-action"
                      aria-label=${`Edit client ${client.id}`}
                      @click=${() => this.handleEditClick(client)}
                      ?disabled=${this.isPreparingForm}
                      title="Edit"
                    >
                      ✎
                    </button>
                    <button
                      type="button"
                      class="icon-action danger"
                      aria-label=${`Delete client ${client.id}`}
                      @click=${() => this.handleDeleteClick(client)}
                      title="Delete"
                    >
                      ✕
                    </button>
                  </div>
                </div>

                ${isExpanded ? html`
                  <div class="client-body">
                    <div class="details-grid">
                      <div class="detail-section">
                        <div class="detail-label">Redirect URIs</div>
                        <div class="uri-list">
                          ${client.redirectUris.map(uri => html`
                            <div class="uri-item">${uri}</div>
                          `)}
                        </div>
                      </div>

                      ${client.externalAudience.length > 0 ? html`
                        <div class="detail-section">
                          <div class="detail-label">External Audience</div>
                          <div class="uri-list">
                            ${client.externalAudience.map(aud => html`
                              <div class="uri-item">${aud}</div>
                            `)}
                          </div>
                        </div>
                      ` : ''}

                      <div class="detail-section">
                        <div class="detail-label">OAuth Scopes</div>
                        <div class="tag-list">
                          ${client.scope.map(s => html`
                            <span class="tag">${s}</span>
                          `)}
                        </div>
                      </div>

                      <div class="detail-section">
                        <div class="detail-label">Permissions</div>
                        <div class="tag-list">
                          ${client.permissions.map(p => html`
                            <span class="tag">${p}</span>
                          `)}
                        </div>
                      </div>

                      <div class="detail-section">
                        <div class="detail-label">Access Token TTL</div>
                        <div class="detail-value">${formatDuration(client.accessTokenTtl)}</div>
                      </div>

                      ${client.edgeId ? html`
                        <div class="detail-section">
                          <div class="detail-label">Edge</div>
                          <div class="detail-value">
                            <button
                              type="button"
                              class="client-link"
                              @click=${() => this.navigateToEdge(client.edgeId!)}
                              aria-label=${`Open edge ${client.edgeId}`}
                            >
                              ${client.edgeId}
                            </button>
                          </div>
                        </div>
                      ` : ''}
                    </div>

                    ${this.renderAuthorizationPresets(client)}
                  </div>
                ` : ''}
              </div>
            `;
          })}
        </div>
      `}
    `;
  }

  private togglePresetsExpand(clientId: string) {
    const newExpanded = new Set(this.expandedPresets);
    if (newExpanded.has(clientId)) {
      newExpanded.delete(clientId);
    } else {
      newExpanded.add(clientId);
    }
    this.expandedPresets = newExpanded;
  }

  private renderAuthorizationPresets(client: OAuthClient) {
    const presets = client.authorizationPresets || [];
    const isPresetsExpanded = this.expandedPresets.has(client.id);

    return html`
      <div class="presets-section">
        <div
          class="presets-header"
          @click=${() => this.togglePresetsExpand(client.id)}
        >
          <div class="presets-title">
            <span class="expand-icon">${isPresetsExpanded ? '▼' : '▶'}</span>
            Authorization Presets
          </div>
          <button
            type="button"
            class="icon-action"
            @click=${(e: Event) => {
              e.stopPropagation();
              this.handleEditPresets(client);
            }}
            title="Edit presets"
            aria-label="Edit presets for ${client.id}"
          >
            ✎
          </button>
        </div>

        ${isPresetsExpanded ? html`
          <div class="presets-body">
            ${presets.length === 0 ? html`
              <div class="empty-presets">
                No authorization presets configured. Add a preset to simplify OAuth login flows.
              </div>
            ` : html`
              <div class="presets-list">
                ${presets.map(preset => this.renderPresetCard(client, preset))}
              </div>
            `}
          </div>
        ` : ''}
      </div>
    `;
  }


  private renderPresetsEditForm() {
    if (!this.editingPresetsForClient) return '';

    return html`
      <div class="card">
        <h2>Edit Authorization Presets</h2>
        <p style="color: var(--text-secondary); margin-bottom: 1.5rem;">
          Manage authorization presets for ${this.editingPresetsForClient.clientName}
        </p>

        ${this.presetDrafts.length === 0 ? html`
          <div class="empty-presets" style="text-align: center; padding: 2rem;">
            <p>No presets yet. Add your first preset.</p>
          </div>
        ` : html`
          <div class="presets-list">
            ${this.presetDrafts.map(preset => this.renderEditablePresetCard(preset))}
          </div>
        `}

        <div style="margin-top: 1rem;">
          <button
            type="button"
            class="btn btn-secondary"
            @click=${this.handleAddPreset}
            ?disabled=${this.isSavingPresets}
          >
            + Add Preset
          </button>
        </div>

        <div style="display: flex; gap: 1rem; margin-top: 1.5rem; padding-top: 1.5rem; border-top: 1px solid var(--border);">
          <button
            type="button"
            class="btn btn-ghost"
            @click=${this.handleCancelPresetsEdit}
            ?disabled=${this.isSavingPresets}
          >
            Cancel
          </button>
          <button
            type="button"
            class="btn btn-primary"
            @click=${this.handlePresetsUpdate}
            ?disabled=${this.isSavingPresets}
          >
            ${this.isSavingPresets ? 'Updating...' : 'Update Presets'}
          </button>
        </div>
      </div>
    `;
  }

  private renderEditablePresetCard(preset: AuthorizationPreset) {
    const isExpanded = this.expandedPresetEditors.has(preset.id);

    return html`
      <div class="preset-card">
        <div class="preset-card-header" style="cursor: default;">
          <button
            type="button"
            style="all: unset; display: flex; align-items: center; gap: 0.75rem; flex: 1; cursor: pointer;"
            @click=${() => this.togglePresetEditor(preset.id)}
          >
            <span style="color: var(--text-secondary); font-size: 0.875rem;">${isExpanded ? '▾' : '▸'}</span>
            <div class="preset-info">
              <div class="preset-name">${preset.description}</div>
              <div class="preset-id">${preset.id}</div>
            </div>
          </button>
          <div class="preset-actions" style="display: flex; gap: 0.5rem;">
            <button
              type="button"
              class="icon-action"
              @click=${() => this.handleEditPreset(preset)}
              title="Edit preset"
              ?disabled=${this.isSavingPresets}
            >
              ✎
            </button>
            <button
              type="button"
              class="icon-action danger"
              @click=${() => this.handleDeletePreset(preset)}
              title="Delete preset"
              ?disabled=${this.isSavingPresets}
            >
              ✕
            </button>
          </div>
        </div>

        ${isExpanded ? html`
          <div class="preset-body">
            <div class="preset-details">
              <div class="preset-row">
                <span class="preset-label">Redirect URI:</span>
                <span class="preset-value">${preset.redirectUri}</span>
              </div>
              <div class="preset-row">
                <span class="preset-label">Scope:</span>
                <span class="preset-value">${preset.scope.join(', ')}</span>
              </div>
              <div class="preset-row">
                <span class="preset-label">Response Type:</span>
                <span class="preset-value">${preset.responseType}</span>
              </div>
              ${preset.uiLocales && preset.uiLocales.length > 0 ? html`
                <div class="preset-row">
                  <span class="preset-label">UI Locales:</span>
                  <span class="preset-value">${preset.uiLocales.join(', ')}</span>
                </div>
              ` : ''}
              ${preset.customParameters && Object.keys(preset.customParameters).length > 0 ? html`
                <div class="preset-row">
                  <span class="preset-label">Custom Parameters:</span>
                  <div class="preset-value">
                    ${Object.entries(preset.customParameters).map(([key, values]) => html`
                      <div style="margin-bottom: 0.25rem;">
                        <span style="color: var(--accent); font-weight: 600;">${key}:</span>
                        <span>${values.join(', ')}</span>
                      </div>
                    `)}
                  </div>
                </div>
              ` : ''}
            </div>
          </div>
        ` : ''}
      </div>
    `;
  }


  private renderPresetCard(client: OAuthClient, preset: AuthorizationPreset) {
    const isExpanded = this.expandedPresetCards.has(preset.id);

    return html`
      <div class="preset-card">
        <div class="preset-card-header" @click=${() => this.togglePresetExpand(preset.id)}>
          <div class="preset-info">
            <div class="preset-name">${preset.description}</div>
            <div class="preset-id">${preset.id}</div>
          </div>
        </div>

        ${isExpanded ? html`
          <div class="preset-body">
            <div class="preset-details">
              <div class="preset-row">
                <span class="preset-label">Redirect URI:</span>
                <span class="preset-value">${preset.redirectUri}</span>
              </div>
              <div class="preset-row">
                <span class="preset-label">Scope:</span>
                <span class="preset-value">${preset.scope.join(', ')}</span>
              </div>
              <div class="preset-row">
                <span class="preset-label">Response Type:</span>
                <span class="preset-value">${preset.responseType}</span>
              </div>
              ${preset.uiLocales && preset.uiLocales.length > 0 ? html`
                <div class="preset-row">
                  <span class="preset-label">UI Locales:</span>
                  <span class="preset-value">${preset.uiLocales.join(', ')}</span>
                </div>
              ` : ''}
              ${preset.customParameters && Object.keys(preset.customParameters).length > 0 ? html`
                <div class="preset-row">
                  <span class="preset-label">Custom Parameters:</span>
                  <div class="preset-value">
                    ${Object.entries(preset.customParameters).map(([key, values]) => html`
                      <div style="margin-bottom: 0.25rem;">
                        <span style="color: var(--accent); font-weight: 600;">${key}:</span>
                        <span>${values.join(', ')}</span>
                      </div>
                    `)}
                  </div>
                </div>
              ` : ''}
            </div>
          </div>
        ` : ''}
      </div>
    `;
  }

  private handleEditPresets(client: OAuthClient) {
    this.editingPresetsForClient = client;
    this.presetDrafts = [...(client.authorizationPresets || [])];
    this.expandedPresetEditors = new Set(); // Start with all cards collapsed
  }

  private handleCancelPresetsEdit() {
    this.editingPresetsForClient = null;
    this.presetDrafts = [];
    this.expandedPresetEditors = new Set();
  }

  private handleAddPreset() {
    this.editingPreset = null;
    this.showPresetForm = true;
  }

  private handleEditPreset(preset: AuthorizationPreset) {
    this.editingPreset = preset;
    this.showPresetForm = true;
  }

  private handleDeletePreset(preset: AuthorizationPreset) {
    this.presetDrafts = this.presetDrafts.filter(p => p.id !== preset.id);
  }

  private handlePresetFormClose() {
    this.showPresetForm = false;
    this.editingPreset = null;
  }

  private handlePresetFormSubmit(e: CustomEvent) {
    const { preset } = e.detail as { preset: AuthorizationPreset };

    if (this.editingPreset) {
      // Editing existing preset
      this.presetDrafts = this.presetDrafts.map(p =>
        p.id === preset.id ? preset : p
      );
    } else {
      // Adding new preset
      this.presetDrafts = [...this.presetDrafts, preset];
    }

    this.handlePresetFormClose();
  }

  private togglePresetEditor(presetId: string) {
    if (this.expandedPresetEditors.has(presetId)) {
      this.expandedPresetEditors.delete(presetId);
    } else {
      this.expandedPresetEditors.add(presetId);
    }
    this.requestUpdate();
  }

  private async handlePresetsUpdate() {
    if (!this.editingPresetsForClient || !this.tenantId) return;

    this.isSavingPresets = true;

    try {
      const response = await fetch('/v1/configuration/auth-request-presets', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          tenantId: this.tenantId,
          clientId: this.editingPresetsForClient.id,
          presets: this.presetDrafts.map(p => ({
            id: p.id,
            description: p.description,
            redirectUri: p.redirectUri,
            scope: p.scope,
            responseType: p.responseType,
            uiLocales: p.uiLocales,
            customParameters: p.customParameters,
          })),
        }),
      });

      if (!response.ok) {
        const text = await response.text();
        throw new Error(text || 'Failed to save presets');
      }

      const updatedClient = {
        ...this.editingPresetsForClient,
        authorizationPresets: this.presetDrafts,
      };

      this.updateClientInState(updatedClient, updatedClient.id);
      this.handleCancelPresetsEdit();
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to save presets');
    } finally {
      this.isSavingPresets = false;
    }
  }

  private navigateToEdge(edgeId: string) {
    this.dispatchEvent(new CustomEvent('navigate-to-edge', {
      detail: { edgeId },
      bubbles: true,
      composed: true,
    }));
  }
}

