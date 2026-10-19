import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { Tenant } from '../types';
import { fetchTenants } from '../utils/central-api';

@customElement('tenant-selector')
export class TenantSelector extends LitElement {
  @state() private tenants: Tenant[] = [];
  @property({ type: String }) selectedTenantId: string | null = null;

  /** When non-null, only tenants whose id is in this list are shown. null means all. */
  @property({ attribute: false }) allowedTenantIds: string[] | null = null;
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

      /* Looks like the console's <select> (formStyles in styles/components.ts):
         same padding, same chevron drawn at the same inset from the right
         edge, so the control lines up with every other field. */
      .selector-button {
        width: 100%;
        padding: 0.75rem 2.75rem 0.75rem 1rem;
        background: var(--surface);
        background-image: var(--select-chevron);
        background-position: calc(100% - 1rem) 50%;
        background-size: 0.625rem 0.375rem;
        background-repeat: no-repeat;
        border: 1px solid var(--border-input);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-size: var(--text-base);
        font-family: var(--font-mono);
        cursor: pointer;
        display: flex;
        align-items: center;
        text-align: left;
        transition: border-color var(--transition-fast), background-color var(--transition-fast);
      }

      .selector-button:hover {
        border-color: var(--accent);
        background-color: var(--accent-subtle);
      }

      .selector-button:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      .selector-label {
        font-size: var(--text-xs);
        font-weight: 600;
        color: var(--text-secondary);
        text-transform: uppercase;
        letter-spacing: 0.05em;
        margin-bottom: 0.5rem;
      }


      .tenant-name {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .dropdown {
        position: absolute;
        top: calc(100% + 0.5rem);
        left: 0;
        right: 0;
        background: var(--bg-page);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-lg);
        max-height: 320px;
        overflow: hidden;
        z-index: var(--z-dropdown);
        display: flex;
        flex-direction: column;
      }

      .search-box {
        padding: 0.5rem;
        background: var(--surface);
      }

      .search-input {
        width: 100%;
        box-sizing: border-box;
        padding: 0.5rem 0.75rem;
        background: var(--bg-page);
        border: 1px solid var(--border-input);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-size: var(--text-sm);
        font-family: var(--font-mono);
        transition: all var(--transition-fast);
      }

      .search-input:focus {
        outline: 2px solid var(--focus-ring);
        outline-offset: 1px;
        border-color: var(--accent);
      }

      .search-input::placeholder {
        color: var(--text-secondary);
        opacity: 0.7;
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
        font-size: var(--text-sm);
        color: var(--text-primary);
        display: flex;
        justify-content: center;
        align-items: center;
        appearance: none;
        background: var(--surface);
        border: 1px solid transparent;
        border-radius: var(--radius-md);
        margin-bottom: 0.375rem;
      }

      .tenant-item:last-child {
        margin-bottom: 0;
      }

      .tenant-item:hover {
        background: var(--accent-subtle);
        border-color: var(--accent-border);
        transform: translateX(2px);
      }

      .tenant-item.selected {
        background: var(--accent-subtle);
        border-color: var(--accent);
      }

      .tenant-item.selected:hover {
        background: var(--accent-subtle);
      }

      .no-results {
        padding: 2rem;
        text-align: center;
        color: var(--text-secondary);
        font-size: var(--text-base);
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
    if (changed.has('allowedTenantIds')) {
      void this.loadTenants();
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

  private applyTenants(allTenants: Tenant[], preferredTenantId: string | null = this.selectedTenantId || localStorage.getItem('selectedTenantId')) {
    const tenants = this.allowedTenantIds !== null
      ? allTenants.filter(t => this.allowedTenantIds!.includes(t.id))
      : allTenants;
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
      <div class="selector-label">Tenant</div>
      <button class="selector-button" type="button" aria-label=${`Select tenant: ${this.isLoading ? 'Loading tenants...' : selectedTenant?.id || 'Select Tenant'}`} aria-haspopup="listbox" aria-expanded=${this.isOpen}
        @click=${(e: Event) => this.toggleDropdown(e)} ?disabled=${this.isLoading}>
        <span class="tenant-name">
          ${this.isLoading ? 'Loading tenants...' : selectedTenant?.id || 'Select Tenant'}
        </span>
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

