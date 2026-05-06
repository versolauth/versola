import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { Tenant } from '../types';
import { fetchTenants } from '../utils/central-api';

@customElement('tenant-selector')
export class TenantSelector extends LitElement {
  @state() private tenants: Tenant[] = [];
  @property({ type: String }) selectedTenantId: string | null = null;
  @property({ type: Boolean }) manageActive = false;
  @state() private isOpen = false;
  @state() private searchQuery = '';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  private readonly handleTenantsUpdated = (event: Event) => {
    const detail = (event as CustomEvent<{ tenants?: Tenant[]; selectedTenantId?: string | null }>).detail;

    if (detail?.tenants) {
      this.applyTenants(detail.tenants, detail.selectedTenantId);
      return;
    }

    void this.loadTenants();
  };

  static styles = [
    theme,
    css`
      :host {
        display: block;
        position: relative;
      }

      .selector-button {
        width: 100%;
        padding: 0.75rem 1rem;
        background: rgba(0, 0, 0, 0.3);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-size: 0.875rem;
        font-family: var(--font-mono);
        cursor: pointer;
        display: flex;
        justify-content: space-between;
        align-items: center;
        transition: all var(--transition-fast);
      }

      .selector-button:hover {
        border-color: var(--accent);
        background: rgba(88, 166, 255, 0.1);
      }

      .selector-label {
        font-size: 0.75rem;
        font-weight: 600;
        color: var(--text-secondary);
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .selector-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.75rem;
        margin-bottom: 0.5rem;
      }

      .manage-button {
        border: none;
        background: none;
        padding: 0;
        color: var(--text-secondary);
        font-size: 0.75rem;
        font-weight: 600;
        cursor: pointer;
        transition: color var(--transition-fast);
      }

      .manage-button:hover,
      .manage-button.active {
        color: var(--accent);
      }

      .tenant-name {
        flex: 1;
        text-align: left;
      }

      .dropdown-arrow {
        color: var(--accent);
        font-size: 0.625rem;
        margin-left: 0.5rem;
        transition: transform var(--transition-fast);
      }

      .dropdown-arrow.open {
        transform: rotate(180deg);
      }

      .dropdown {
        position: absolute;
        top: calc(100% + 0.5rem);
        left: 0;
        right: 0;
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4), 0 0 0 1px rgba(88, 166, 255, 0.1);
        max-height: 320px;
        overflow: hidden;
        z-index: 1000;
        display: flex;
        flex-direction: column;
      }

      .search-box {
        padding: 0.5rem;
        background: var(--bg-dark-card);
      }

      .search-input {
        width: 100%;
        box-sizing: border-box;
        padding: 0.5rem 0.75rem;
        background: rgba(0, 0, 0, 0.4);
        border: 1px solid transparent;
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-size: 0.8125rem;
        font-family: var(--font-mono);
        transition: all var(--transition-fast);
      }

      .search-input:focus {
        outline: none;
        border-color: var(--accent);
        background: rgba(0, 0, 0, 0.6);
      }

      .search-input::placeholder {
        color: var(--text-secondary);
        opacity: 0.5;
      }

      .tenant-list {
        overflow-y: auto;
        max-height: 280px;
        padding: 0.5rem 0.5rem 0.5rem 0.5rem;
        display: flex;
        flex-direction: column;
        align-items: stretch;
      }

      /* Hide scrollbar but keep scrolling functionality */
      .tenant-list::-webkit-scrollbar {
        display: none;
      }

      .tenant-list {
        -ms-overflow-style: none;  /* IE and Edge */
        scrollbar-width: none;  /* Firefox */
      }

      .tenant-item {
        position: relative;
        width: 100%;
        padding: 0.625rem 1rem;
        cursor: pointer;
        transition: all var(--transition-fast);
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--text-primary);
        display: flex;
        justify-content: center;
        align-items: center;
        appearance: none;
        background: var(--bg-dark-card);
        border: 1px solid transparent;
        border-radius: var(--radius-md);
        margin-bottom: 0.375rem;
      }

      .tenant-item:last-child {
        margin-bottom: 0;
      }

      .tenant-item:hover {
        background: rgba(88, 166, 255, 0.08);
        border-color: rgba(88, 166, 255, 0.3);
        transform: translateX(2px);
      }

      .tenant-item.selected {
        background: rgba(88, 166, 255, 0.15);
        border-color: var(--accent);
      }

      .tenant-item.selected:hover {
        background: rgba(88, 166, 255, 0.2);
      }

      .no-results {
        padding: 2rem;
        text-align: center;
        color: var(--text-secondary);
        font-size: 0.875rem;
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    this.loadSelectedTenant();
    void this.loadTenants();
    
    document.addEventListener('click', this.handleOutsideClick);
    window.addEventListener('versola-tenants-updated', this.handleTenantsUpdated);
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    document.removeEventListener('click', this.handleOutsideClick);
    window.removeEventListener('versola-tenants-updated', this.handleTenantsUpdated);
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('selectedTenantId') && this.selectedTenantId) {
      localStorage.setItem('selectedTenantId', this.selectedTenantId);
    }
  }

  private handleOutsideClick = (e: Event) => {
    const path = e.composedPath();
    if (!path.includes(this)) {
      this.isOpen = false;
      this.searchQuery = '';
    }
  };

  private async loadTenants() {
    this.isLoading = true;
    this.errorMessage = '';

    try {
      this.applyTenants(await fetchTenants());
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load tenants';
      this.tenants = [];
    } finally {
      this.isLoading = false;
    }
  }

  private loadSelectedTenant() {
    if (this.selectedTenantId) {
      return;
    }

    const saved = localStorage.getItem('selectedTenantId');
    if (saved) {
      this.selectedTenantId = saved;
    }
  }

  private applyTenants(tenants: Tenant[], preferredTenantId: string | null = this.selectedTenantId || localStorage.getItem('selectedTenantId')) {
    this.tenants = tenants;

    if (tenants.length === 0) {
      this.selectedTenantId = null;
      localStorage.removeItem('selectedTenantId');
      this.dispatchTenantChange(null);
      return;
    }

    const selectedTenantId = preferredTenantId && tenants.some(tenant => tenant.id === preferredTenantId)
      ? preferredTenantId
      : tenants[0].id;

    this.selectedTenantId = selectedTenantId;
    localStorage.setItem('selectedTenantId', selectedTenantId);
    this.dispatchTenantChange(selectedTenantId);
  }

  private dispatchTenantChange(tenantId: string | null) {
    this.dispatchEvent(new CustomEvent('tenant-change', {
      detail: { tenantId },
      bubbles: true,
      composed: true,
    }));
  }

  private handleSelectTenant(e: Event, tenantId: string) {
    e.stopPropagation();
    e.preventDefault();

    this.selectedTenantId = tenantId;
    localStorage.setItem('selectedTenantId', tenantId);
    this.isOpen = false;
    this.searchQuery = '';
    this.dispatchTenantChange(tenantId);
  }

  private handleManageClick(e: Event) {
    e.preventDefault();
    e.stopPropagation();
    this.isOpen = false;
    this.searchQuery = '';
    this.dispatchEvent(new CustomEvent('manage-tenants', {
      bubbles: true,
      composed: true,
    }));
  }

  private toggleDropdown(e: Event) {
    e.stopPropagation();
    if (this.isLoading) return;
    this.isOpen = !this.isOpen;

    if (this.isOpen) {
      setTimeout(() => {
        const input = this.shadowRoot?.querySelector('.search-input') as HTMLInputElement;
        input?.focus();
      }, 100);
    } else {
      this.searchQuery = '';
    }
  }

  private handleSearchInput(e: Event) {
    this.searchQuery = (e.target as HTMLInputElement).value;
  }

  private get filteredTenants(): Tenant[] {
    if (!this.searchQuery) {
      return this.tenants;
    }

    const query = this.searchQuery.toLowerCase();
    return this.tenants.filter(tenant =>
      tenant.id.toLowerCase().includes(query) ||
      tenant.name.toLowerCase().includes(query) ||
      tenant.description?.toLowerCase().includes(query)
    );
  }

  render() {
    const selectedTenant = this.tenants.find(t => t.id === this.selectedTenantId);
    const filteredTenants = this.filteredTenants;

    return html`
      <div class="selector-header">
        <div class="selector-label">Tenant</div>
        <button class="manage-button ${this.manageActive ? 'active' : ''}" type="button" @click=${this.handleManageClick}>Manage</button>
      </div>
      <button class="selector-button" type="button" aria-label="Select tenant" @click=${(e: Event) => this.toggleDropdown(e)} ?disabled=${this.isLoading}>
        <span class="tenant-name">
          ${this.isLoading ? 'Loading tenants...' : selectedTenant?.id || 'Select Tenant'}
        </span>
        <span class="dropdown-arrow ${this.isOpen ? 'open' : ''}">▼</span>
      </button>

      ${this.isOpen ? html`
        <div class="dropdown">
          <div class="search-box">
            <input
              type="text"
              class="search-input"
              aria-label="Search tenants"
              placeholder="Search tenants..."
              .value=${this.searchQuery}
              @input=${this.handleSearchInput}
              @click=${(e: Event) => e.stopPropagation()}
              ?disabled=${this.isLoading}
            />
          </div>

          ${this.errorMessage ? html`
            <div class="no-results">${this.errorMessage}</div>
          ` : this.isLoading ? html`
            <div class="no-results">Loading tenants...</div>
          ` : filteredTenants.length > 0 ? html`
            <div class="tenant-list">
              ${filteredTenants.map(tenant => html`
                <button
                  type="button"
                  class="tenant-item ${tenant.id === this.selectedTenantId ? 'selected' : ''}"
                  @click=${(e: Event) => this.handleSelectTenant(e, tenant.id)}
                >
                  <span>${tenant.id}</span>
                </button>
              `)}
            </div>
          ` : html`
            <div class="no-results">
              No tenants found
            </div>
          `}
        </div>
      ` : ''}
    `;
  }
}

