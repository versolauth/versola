import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { badgeStyles, buttonStyles, cardStyles, formStyles } from '../styles/components';
import type { Edge, Tenant } from '../types';
import {
  deleteEdge,
  deleteOldEdgeKey,
  fetchEdges,
  fetchTenants,
  registerEdge,
  rotateEdgeKey,
  setEdgeDpopNonce,
} from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { copyToClipboard } from '../utils/helpers';
import './edge-form';
import './content-header';
import './error-card';
import './loading-cards';

@customElement('versola-edges-list')
export class VersolaEdgesList extends LitElement {
  @property({ type: String }) expandEdgeId: string | null = null;
  @property({ type: Boolean }) canManage = false;

  @state() private edges: Edge[] = [];
  @state() private tenants: Tenant[] = [];
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private showCreateForm = false;
  @state() private editingEdge: Edge | null = null;
  @state() private expandedEdges: Set<string> = new Set();
  @state() private generatedKey: { edgeId: string; keyId: string; privateKey: string; action: 'created' | 'rotated' } | null = null;
  @state() private copyFeedback = '';
  @state() private openInfoKey: string | null = null;
  @state() private savingDpopEdgeId: string | null = null;

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
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

      .edge-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        margin-bottom: var(--spacing-md);
        transition: border-color var(--transition-base);
      }

      .edge-card:hover {
        border-color: var(--accent);
      }

      .edge-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-lg);
        cursor: pointer;
        user-select: none;
      }

      .edge-info {
        flex: 1;
        min-width: 0;
      }

      .edge-id {
        font-family: var(--font-mono);
        font-size: 1rem;
        font-weight: 600;
        color: var(--accent);
        display: flex;
        align-items: center;
        gap: 0.75rem;
        /* A long id and the "Key Rotation" badge can't share one line on a
           phone; let the badge drop below rather than overflow the card. */
        flex-wrap: wrap;
        overflow-wrap: anywhere;
      }

      .edge-actions {
        display: flex;
        gap: var(--spacing-sm);
        margin-left: var(--spacing-md);
      }

      .edge-body {
        padding: 0 var(--spacing-lg) var(--spacing-lg) var(--spacing-lg);
        border-top: 1px solid var(--border-dark);
      }

      .edge-body-content {
        padding-top: var(--spacing-lg);
      }

      .info-text {
        color: var(--text-secondary);
        font-size: 0.875rem;
        margin-bottom: var(--spacing-md);
      }

      .edge-actions-row {
        display: flex;
        gap: var(--spacing-md);
        align-items: center;
      }

      .icon-action {
        background: none;
        border: none;
        padding: 0.5rem;
        cursor: pointer;
        color: var(--text-secondary);
        font-size: 1.25rem;
        transition: all var(--transition-fast);
      }

      .icon-action:hover {
        color: var(--accent);
      }

      .icon-action.danger:hover {
        color: var(--danger);
      }

      .secret-banner {
        background: rgba(var(--accent-tint), 0.1);
        border: 1px solid var(--accent);
        border-radius: var(--radius-lg);
        padding: var(--spacing-lg);
        margin-bottom: var(--spacing-lg);
      }

      .secret-banner-header {
        font-size: 1.125rem;
        font-weight: 600;
        margin-bottom: var(--spacing-md);
        color: var(--accent);
      }

      .secret-row {
        margin-bottom: var(--spacing-md);
      }

      .secret-row:last-child {
        margin-bottom: 0;
      }

      .secret-row-label {
        font-size: 0.75rem;
        font-weight: 600;
        color: var(--text-secondary);
        text-transform: uppercase;
        letter-spacing: 0.05em;
        margin-bottom: 0.5rem;
      }

      .secret-value {
        padding: var(--spacing-md);
        background: rgba(0, 0, 0, 0.25);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        font-family: var(--font-mono);
        font-size: 0.875rem;
        word-break: break-all;
        color: var(--text-primary);
      }

      .secret-banner-actions {
        display: flex;
        gap: var(--spacing-md);
        margin-top: var(--spacing-lg);
      }

      .copy-feedback {
        color: var(--success, #3fb950);
        font-size: 0.875rem;
      }

      .linked-tenants {
        margin-bottom: var(--spacing-lg);
      }

      .linked-tenants-label {
        font-size: 0.875rem;
        font-weight: 600;
        color: var(--text-secondary);
        margin-bottom: var(--spacing-sm);
      }

      .no-tenants {
        font-size: 0.875rem;
        color: var(--text-secondary);
        font-style: italic;
      }

      .tenant-badges {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
      }

      .tenant-badge {
        display: inline-flex;
        align-items: center;
        padding: 0.25rem 0.75rem;
        background: rgba(var(--accent-tint), 0.12);
        border: 1px solid rgba(var(--accent-tint), 0.28);
        border-radius: 999px;
        color: var(--accent);
        font-size: 0.8125rem;
        font-weight: 600;
        font-family: var(--font-mono);
      }

      .dpop-section {
        margin-bottom: var(--spacing-lg);
      }

      .dpop-label-row {
        display: flex;
        align-items: center;
        gap: 0.4rem;
        margin-bottom: var(--spacing-sm);
      }

      .dpop-label {
        font-size: 0.875rem;
        font-weight: 600;
        color: var(--text-secondary);
      }

      .dpop-toggle {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        font-size: 0.875rem;
        color: var(--text-primary);
        cursor: pointer;
      }

      .option-info { position: relative; display: inline-flex; align-items: center; flex: none; }
      .option-info-button {
        flex: none;
        border: 1px solid rgba(var(--accent-tint), 0.4);
        border-radius: 999px;
        background: rgba(var(--accent-tint), 0.12);
        color: var(--accent);
        font-size: 0.75rem;
        font-weight: 700;
        line-height: 1;
        padding: 0.25rem 0.45rem;
        cursor: pointer;
        font-family: var(--font-family);
      }
      .option-info-button:hover { background: rgba(var(--accent-tint), 0.18); border-color: rgba(var(--accent-tint), 0.55); }
      .option-info-button:focus-visible { outline: none; box-shadow: 0 0 0 2px rgba(var(--accent-tint), 0.2); }
      .option-tooltip {
        position: absolute;
        left: 0;
        top: calc(100% + 0.4rem);
        z-index: 20;
        min-width: 18rem;
        max-width: min(28rem, 75vw);
        padding: 0.75rem;
        border: 1px solid rgba(var(--accent-tint), 0.28);
        border-radius: var(--radius-md);
        background: var(--surface-overlay);
        box-shadow: var(--surface-overlay-shadow);
        display: none;
      }
      .option-info.option-info-open .option-tooltip { display: block; }
      .option-tooltip-title { margin-bottom: 0.5rem; color: var(--accent); font-size: 0.8125rem; font-weight: 600; }
      .option-tooltip-item { color: var(--text-primary); font-size: 0.75rem; line-height: 1.45; }
      .option-tooltip-item + .option-tooltip-item { margin-top: 0.375rem; }

      .empty-state {
        text-align: center;
        padding: 3rem;
        color: var(--text-secondary);
      }

      .empty-state-icon {
        font-size: 3rem;
        margin-bottom: 1rem;
      }

      /* This screen was the only list without a narrow-screen rule for its card
         header. A long edge id plus the "Key Rotation" badge plus two icon
         buttons don't fit one row on a phone, so the row is stacked to match
         the other lists. */
      @media (max-width: 720px) {
        .edge-header {
          flex-direction: column;
          align-items: flex-start;
        }

        .edge-actions {
          margin-left: 0;
        }
      }
    `,
  ];

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [edges, tenants] = await Promise.all([
        fetchEdges(),
        fetchTenants(),
      ]);
      this.edges = edges;
      this.tenants = tenants;
    } catch (error) {
      this.edges = [];
      this.tenants = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load edges';
    } finally {
      this.isLoading = false;
    }
  }

  private handleCreateClick() {
    this.showCreateForm = true;
    this.editingEdge = null;
    this.generatedKey = null;
    this.copyFeedback = '';
  }

  private handleEditClick(edge: Edge) {
    this.showCreateForm = true;
    this.editingEdge = edge;
    this.generatedKey = null;
    this.copyFeedback = '';
  }

  private toggleExpand(edgeId: string) {
    const newExpanded = new Set(this.expandedEdges);
    if (newExpanded.has(edgeId)) {
      newExpanded.delete(edgeId);
    } else {
      newExpanded.add(edgeId);
    }
    this.expandedEdges = newExpanded;
  }

  private async handleEdgeSubmit(e: CustomEvent) {
    this.errorMessage = '';
    try {
      const { id } = e.detail;
      const result = await registerEdge(id);
      this.generatedKey = { edgeId: id, keyId: result.keyId, privateKey: result.privateKey, action: 'created' };
      this.edges = [...this.edges, { id, hasOldKey: false, requireDpopNonce: true }];
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to register edge';
    }
  }

  private async handleRotateKeyFromForm(e: CustomEvent) {
    const edgeId = e.detail.edgeId;
    const confirmed = await confirmDestructiveAction({
      title: 'Rotate Edge Key',
      messagePrefix: 'Rotate key for edge ',
      messageSubject: edgeId,
      messageSuffix: '? Both the old and new keys will work during the transition period. Update your edge configuration with the new private key when ready.',
      confirmLabel: 'Rotate Key',
    });
    if (!confirmed) return;

    this.errorMessage = '';
    try {
      const result = await rotateEdgeKey(edgeId);
      this.generatedKey = { edgeId, keyId: result.keyId, privateKey: result.privateKey, action: 'rotated' };
      this.edges = this.edges.map(e => e.id === edgeId ? { ...e, hasOldKey: true } : e);
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to rotate key';
    }
  }

  private async handleDeleteOldKeyFromForm(e: CustomEvent) {
    const edgeId = e.detail.edgeId;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete Old Edge Key',
      messagePrefix: 'Delete the old key for edge ',
      messageSubject: edgeId,
      messageSuffix: '? Only the new key will work after deletion. Make sure all edge instances are using the new key.',
      confirmLabel: 'Delete Old Key',
    });
    if (!confirmed) return;

    this.errorMessage = '';
    try {
      await deleteOldEdgeKey(edgeId);
      this.edges = this.edges.map(e => e.id === edgeId ? { ...e, hasOldKey: false } : e);
      if (this.editingEdge && this.editingEdge.id === edgeId) {
        this.editingEdge = { ...this.editingEdge, hasOldKey: false };
      }
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to delete old key';
    }
  }

  private async handleDeleteEdge(edge: Edge) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete edge',
      messagePrefix: 'Delete edge ',
      messageSubject: edge.id,
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;

    this.errorMessage = '';
    try {
      await deleteEdge(edge.id);
      this.edges = this.edges.filter(e => e.id !== edge.id);
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to delete edge';
    }
  }

  private handleCancelForm() {
    this.showCreateForm = false;
    this.generatedKey = null;
    this.copyFeedback = '';
  }

  private handleDismissKey() {
    this.generatedKey = null;
    this.copyFeedback = '';
    // If we're in create form, close it and return to list
    if (this.showCreateForm) {
      this.showCreateForm = false;
    }
  }

  private async handleCopyKey() {
    if (!this.generatedKey) return;
    await copyToClipboard(this.generatedKey.privateKey);
    this.copyFeedback = 'Copied!';
    setTimeout(() => {
      this.copyFeedback = '';
    }, 2000);
  }

  private get sortedEdges() {
    return [...this.edges].sort((a, b) => a.id.localeCompare(b.id));
  }

  private getLinkedTenants(edgeId: string): Tenant[] {
    return this.tenants.filter(tenant => tenant.edgeId === edgeId);
  }

  private toggleInfo(key: string) {
    this.openInfoKey = this.openInfoKey === key ? null : key;
  }

  private async handleDpopNonceChange(edge: Edge, requireDpopNonce: boolean) {
    this.errorMessage = '';
    this.savingDpopEdgeId = edge.id;
    try {
      await setEdgeDpopNonce(edge.id, requireDpopNonce);
      this.edges = this.edges.map(e => e.id === edge.id ? { ...e, requireDpopNonce } : e);
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to update the DPoP nonce requirement';
    } finally {
      this.savingDpopEdgeId = null;
    }
  }

  private renderDpopSection(edge: Edge) {
    const saving = this.savingDpopEdgeId === edge.id;

    return html`
      <div class="dpop-section">
        <div class="dpop-label-row">
          <span class="dpop-label">DPoP Nonce</span>
          <div
            class=${`option-info ${this.openInfoKey === edge.id ? 'option-info-open' : ''}`}
            @click=${(event: Event) => event.stopPropagation()}
          >
            <button
              type="button"
              class="option-info-button"
              aria-label="DPoP nonce requirement info"
              aria-expanded=${this.openInfoKey === edge.id ? 'true' : 'false'}
              @click=${() => this.toggleInfo(edge.id)}
            >i</button>
            <div class="option-tooltip" role="tooltip">
              <div class="option-tooltip-title">Require a DPoP nonce</div>
              <div class="option-tooltip-item">A DPoP nonce (RFC 9449 section 9) is a short-lived value this edge issues and the client must sign into its next proof. It bounds how long a captured proof stays usable on a proxied call — the one place a stolen proof is actually worth replaying, since it buys the attacker a real API request.</div>
              <div class="option-tooltip-item">On, every proof this edge checks must carry a nonce it issued. A call without one is answered with <code>use_dpop_nonce</code> and a fresh nonce in the <code>DPoP-Nonce</code> header, which the client is expected to retry over. The cost is one extra round trip per client per nonce lifetime, not per request.</div>
              <div class="option-tooltip-item">Off is not "prefer a nonce": the claim stops being read and no nonce is ever issued. A challenge over a nonce this edge does not insist on is one a nonce-less retry walks straight past, so there is no middle setting to offer.</div>
              <div class="option-tooltip-item">Before turning it off: nothing breaks, and it takes effect on this edge's next configuration sync. Before turning it on: every client calling through this edge with DPoP must implement the <code>use_dpop_nonce</code> retry, or its proxied calls start failing.</div>
              <div class="option-tooltip-item">Governs this edge only, and only the calls it proxies. Auth's own <code>/token</code> and <code>/userinfo</code> follow the tenant's <strong>Require DPoP nonce</strong> setting instead.</div>
            </div>
          </div>
        </div>
        ${this.canManage ? html`
          <label class="dpop-toggle">
            <input
              type="checkbox"
              .checked=${edge.requireDpopNonce}
              ?disabled=${saving}
              @change=${(event: Event) =>
                this.handleDpopNonceChange(edge, (event.target as HTMLInputElement).checked)}
            />
            Required on every proxied call
          </label>
        ` : html`
          <div class="info-text">${edge.requireDpopNonce ? 'Required on every proxied call' : 'Not required'}</div>
        `}
      </div>
    `;
  }

  private renderLinkedTenants(edgeId: string) {
    const linkedTenants = this.getLinkedTenants(edgeId);

    if (linkedTenants.length === 0) {
      return html`
        <div class="linked-tenants">
          <div class="linked-tenants-label">Linked Tenants</div>
          <div class="no-tenants">No tenants assigned to this edge</div>
        </div>
      `;
    }

    return html`
      <div class="linked-tenants">
        <div class="linked-tenants-label">Linked Tenants</div>
        <div class="tenant-badges">
          ${linkedTenants.map(tenant => html`
            <span class="tenant-badge">${tenant.id}</span>
          `)}
        </div>
      </div>
    `;
  }

  render() {
    if (this.showCreateForm) {
      // If we have a generated key, only show the banner (hide the form)
      if (this.generatedKey) {
        return html`${this.renderKeyBanner()}`;
      }

      return html`
        <versola-edge-form
          .edge=${this.editingEdge}
          .availableEdgeIds=${this.edges.map(e => e.id)}
          @submit=${this.handleEdgeSubmit}
          @cancel=${this.handleCancelForm}
          @rotate-key=${this.handleRotateKeyFromForm}
          @delete-old-key=${this.handleDeleteOldKeyFromForm}
        ></versola-edge-form>
      `;
    }

    return html`
      <content-header
        title="Edges"
      >
        ${this.canManage && this.edges.length > 0 ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick}>
            + Create Edge
          </button>
        ` : ''}
      </content-header>

      ${this.isLoading ? html`
        <versola-loading-cards count="3"></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load edges" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.sortedEdges.length === 0 ? html`
        <div class="card">
          <div class="empty-state">
            <h3>No edges yet</h3>
            ${this.canManage ? html`
            <p>Create your first edge to get started</p>
            <button class="btn btn-primary" @click=${this.handleCreateClick} style="margin-top: 1rem;">
              + Create Edge
            </button>` : html`<p>No edges available.</p>`}
          </div>
        </div>
      ` : html`
        ${this.sortedEdges.map(edge => {
          const isExpanded = this.expandedEdges.has(edge.id);
          const hasGeneratedKey = this.generatedKey?.edgeId === edge.id;

          return html`
            <div class="edge-card">
              <div class="edge-header" @click=${() => this.toggleExpand(edge.id)}>
                <div class="edge-info">
                  <div class="edge-id">
                    ${edge.id}
                    ${edge.hasOldKey ? html`
                      <span class="badge badge-warning">Key Rotation</span>
                    ` : ''}
                  </div>
                </div>
                ${this.canManage ? html`
                <div class="edge-actions" @click=${(e: Event) => e.stopPropagation()}>
                  <button
                    class="icon-action"
                    @click=${() => this.handleEditClick(edge)}
                    title="Edit edge"
                    aria-label="Edit edge ${edge.id}"
                  >
                    ✎
                  </button>
                  <button
                    class="icon-action danger"
                    @click=${() => this.handleDeleteEdge(edge)}
                    title="Delete edge"
                    aria-label="Delete edge ${edge.id}"
                  >
                    ✕
                  </button>
                </div>` : ''}
              </div>

              ${isExpanded ? html`
                <div class="edge-body">
                  <div class="edge-body-content">
                    ${hasGeneratedKey ? this.renderKeyBanner() : html`
                      ${this.renderLinkedTenants(edge.id)}
                      ${this.renderDpopSection(edge)}
                    `}
                  </div>
                </div>
              ` : ''}
            </div>
          `;
        })}
      `}
    `;
  }

  private renderKeyBanner() {
    if (!this.generatedKey) return '';

    return html`
      <div class="secret-banner">
        <div class="secret-banner-header">
          🔑 Edge Key ${this.generatedKey.action === 'created' ? 'Generated' : 'Rotated'}
        </div>
        <p style="color: var(--text-secondary); margin-bottom: var(--spacing-lg);">
          ⚠️ <strong>Save this private key now!</strong> You won't be able to see it again.
        </p>

        <div class="secret-row">
          <div class="secret-row-label">Edge ID</div>
          <div class="secret-value">${this.generatedKey.edgeId}</div>
        </div>

        <div class="secret-row">
          <div class="secret-row-label">Key ID</div>
          <div class="secret-value">${this.generatedKey.keyId}</div>
        </div>

        <div class="secret-row">
          <div class="secret-row-label">Private Key</div>
          <div class="secret-value">${this.generatedKey.privateKey}</div>
        </div>

        <div class="secret-banner-actions">
          <button class="btn btn-primary" @click=${this.handleCopyKey}>
            Copy Private Key
          </button>
          ${this.copyFeedback ? html`<span class="copy-feedback">${this.copyFeedback}</span>` : ''}
          <button class="btn btn-secondary" @click=${this.handleDismissKey}>
            I've Saved It
          </button>
        </div>
      </div>
    `;
  }
}

