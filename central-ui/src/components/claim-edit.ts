import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { OAuthScope, User } from '../types';
import { getScopes } from '../utils/central-api';

const EXCLUDED_SCOPES = new Set(['openid', 'offline_access']);

@customElement('versola-claim-edit')
export class VersolaClaimEdit extends LitElement {
  @property({ attribute: false }) user: User | null = null;
  @property({ type: String }) tenantId: string | null = null;

  @state() private scopes: OAuthScope[] = [];
  @state() private loading = false;
  @state() private enabledClaims: Set<string> = new Set();
  @state() private claimValues: Map<string, string> = new Map();
  private originalKeys = new Set<string>();

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    css`
      :host { display: block; }

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

      .section-label {
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--text-secondary);
        margin: var(--spacing-lg) 0 var(--spacing-sm);
      }

      .section-label:first-child { margin-top: 0; }

      .claim-row {
        display: grid;
        grid-template-columns: 1.25rem 12rem 1fr;
        gap: var(--spacing-md);
        align-items: center;
        margin-bottom: var(--spacing-sm);
      }

      .claim-row input[type="checkbox"] {
        width: 1rem;
        height: 1rem;
        cursor: pointer;
        accent-color: var(--accent);
      }

      .claim-label {
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--accent);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .claim-label.muted { color: var(--text-secondary); }

      .actions {
        display: flex;
        justify-content: flex-end;
        gap: var(--spacing-md);
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-dark);
      }
    `,
  ];

  async connectedCallback() {
    super.connectedCallback();
    if (this.user) {
      const claimsObj = this.user.claims || {};
      this.originalKeys = new Set(Object.keys(claimsObj));
      const values = new Map<string, string>();
      const enabled = new Set<string>();
      for (const [k, v] of Object.entries(claimsObj)) {
        values.set(k, typeof v === 'string' ? v : JSON.stringify(v));
        enabled.add(k);
      }
      this.claimValues = values;
      this.enabledClaims = enabled;
    }
    if (this.tenantId) {
      this.loading = true;
      try {
        const all = await getScopes(this.tenantId);
        this.scopes = all.filter(s => !EXCLUDED_SCOPES.has(s.id));
      } finally {
        this.loading = false;
      }
    }
  }

  private isReadOnly(claimId: string): boolean {
    return (claimId === 'email' && !!this.user?.email) ||
           (claimId === 'phone_number' && !!this.user?.phone);
  }

  private readOnlyValue(claimId: string): string {
    if (claimId === 'email') return this.user?.email ?? '';
    if (claimId === 'phone_number') return this.user?.phone ?? '';
    return '';
  }

  private toggleClaim(claimId: string, checked: boolean) {
    const next = new Set(this.enabledClaims);
    if (checked) next.add(claimId);
    else next.delete(claimId);
    this.enabledClaims = next;
  }

  private setValue(claimId: string, value: string) {
    const next = new Map(this.claimValues);
    next.set(claimId, value);
    this.claimValues = next;
  }

  private handleCancel() {
    this.dispatchEvent(new CustomEvent('close', { bubbles: true, composed: true }));
  }

  private handleSave() {
    if (!this.user) return;
    const patch: Record<string, unknown> = {};

    // Deletions: originally set, now unchecked (skip read-only claims)
    for (const key of this.originalKeys) {
      if (!this.enabledClaims.has(key) && !this.isReadOnly(key)) patch[key] = null;
    }

    // Upserts: enabled claims whose value changed (skip read-only claims)
    for (const key of this.enabledClaims) {
      if (this.isReadOnly(key)) continue;
      const raw = this.claimValues.get(key) ?? '';
      let val: unknown = raw;
      if (raw === 'true') val = true;
      else if (raw === 'false') val = false;
      else if (raw.trim() !== '' && !isNaN(Number(raw))) val = Number(raw);
      else {
        try {
          if (raw.startsWith('[') || raw.startsWith('{')) val = JSON.parse(raw);
        } catch { /* string fallback */ }
      }
      if (JSON.stringify(val) !== JSON.stringify(this.user.claims[key])) patch[key] = val;
    }

    if (Object.keys(patch).length > 0) {
      this.dispatchEvent(new CustomEvent('save', {
        detail: { userId: this.user.id, patch },
        bubbles: true,
        composed: true,
      }));
    } else {
      this.handleCancel();
    }
  }

  render() {
    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">Edit Claims</h1>
          <div class="entity-id-meta">${this.user?.id}</div>
        </div>
      </div>

      <div class="card">
        ${this.loading ? html`<div class="helper">Loading scopes…</div>` : this.scopes.map(scope => html`
          <div class="section-label">${scope.id}</div>
          ${scope.claims.map(claim => {
            if (this.isReadOnly(claim.id)) {
              return html`
                <div class="claim-row">
                  <span></span>
                  <span class="claim-label muted" title=${claim.id}>${claim.id}</span>
                  <input class="compact-input" type="text" disabled
                    .value=${this.readOnlyValue(claim.id)} />
                </div>`;
            }
            const enabled = this.enabledClaims.has(claim.id);
            return html`
              <div class="claim-row">
                <input type="checkbox" .checked=${enabled}
                  @change=${(e: Event) => this.toggleClaim(claim.id, (e.target as HTMLInputElement).checked)} />
                <span class="claim-label ${enabled ? '' : 'muted'}" title=${claim.id}>${claim.id}</span>
                ${enabled ? html`
                  <input class="compact-input" type="text"
                    .value=${this.claimValues.get(claim.id) ?? ''}
                    @input=${(e: Event) => this.setValue(claim.id, (e.target as HTMLInputElement).value)} />
                ` : html`<span></span>`}
              </div>`;
          })}
        `)}

        <div class="actions">
          <button class="btn btn-secondary" @click=${this.handleCancel}>Cancel</button>
          <button class="btn btn-primary" @click=${this.handleSave}>Save Changes</button>
        </div>
      </div>
    `;
  }
}
