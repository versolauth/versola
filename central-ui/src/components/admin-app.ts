import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme, resetStyles } from '../styles/theme';
import type { NavItem } from './navigation';
import { configureCentralApi } from '../utils/central-api';

import './navigation';
import './clients-list';
import './scopes-list';
import './permissions-list';
import './resources-list';
import './roles-list';
import './tenants-list';
import './edges-list';

@customElement('versola-admin')
export class VersolaAdmin extends LitElement {
  @property({ type: String, attribute: 'api-url' }) apiUrl: string | null = null;
  @property({ type: String, attribute: 'auth-token' }) authToken: string | null = null;
  @state() private currentView: NavItem = 'clients';
  @state() private currentTenantId: string | null = null;
  @state() private clientToExpandOnLoad: string | null = null;
  @state() private edgeToExpandOnLoad: string | null = null;

  private readonly handlePopState = () => {
    this.loadLocationState();
  };

  connectedCallback() {
    super.connectedCallback();
    this.applyApiConfig();
    this.loadLocationState();
    window.addEventListener('popstate', this.handlePopState);
  }

  disconnectedCallback() {
    window.removeEventListener('popstate', this.handlePopState);
    super.disconnectedCallback();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('apiUrl') || changed.has('authToken')) {
      this.applyApiConfig();
    }

    if (changed.has('currentView') || changed.has('currentTenantId')) {
      this.persistLocationState();
    }
  }

  static styles = [
    resetStyles,
    theme,
    css`
      :host {
        display: block;
        min-height: 100vh;
        background: var(--bg-dark);
        color: var(--text-primary);
      }

      .app-layout {
        display: flex;
        min-height: 100vh;
      }

      .main-content {
        flex: 1;
        margin-left: 250px;
        padding: 2rem;
        max-width: 1400px;
      }

      @media (max-width: 768px) {
        .main-content {
          margin-left: 0;
          padding: 1rem;
        }
      }
    `,
  ];

  private handleNavChange(e: CustomEvent) {
    this.currentView = e.detail.item;
  }

  private handleTenantChange(e: CustomEvent) {
    this.currentTenantId = e.detail.tenantId;
  }

  private applyApiConfig() {
    configureCentralApi({
      baseUrl: this.apiUrl,
      authToken: this.authToken,
    });
  }

  private loadLocationState() {
    const params = new URL(window.location.href).searchParams;
    const urlView = params.get('view');
    const urlTenantId = params.get('tenant');
    const expandClient = params.get('expandClient');
    const expandEdge = params.get('expandEdge');

    if (urlView === 'clients' || urlView === 'scopes' || urlView === 'permissions' || urlView === 'resources' || urlView === 'roles' || urlView === 'tenants' || urlView === 'edges') {
      this.currentView = urlView;
    }

    this.currentTenantId = urlTenantId || localStorage.getItem('selectedTenantId');
    this.clientToExpandOnLoad = expandClient;
    this.edgeToExpandOnLoad = expandEdge;
  }

  private persistLocationState() {
    const url = new URL(window.location.href);
    url.searchParams.set('view', this.currentView);

    if (this.currentTenantId) {
      url.searchParams.set('tenant', this.currentTenantId);
    } else {
      url.searchParams.delete('tenant');
    }

    url.searchParams.delete('expandClient');
    url.searchParams.delete('expandEdge');

    window.history.replaceState({}, '', url);
  }

  private handleNavigateToClient = (e: CustomEvent) => {
    const { tenantId, clientId } = e.detail;
    if (tenantId) {
      this.currentTenantId = tenantId;
      localStorage.setItem('selectedTenantId', tenantId);
    }
    this.clientToExpandOnLoad = clientId;
    this.currentView = 'clients';
  };

  private handleNavigateToEdge = (e: CustomEvent) => {
    const { edgeId } = e.detail;
    this.edgeToExpandOnLoad = edgeId;
    this.currentView = 'edges';
  };

  private renderView() {
    switch (this.currentView) {
      case 'clients':
        return html`<versola-clients-list .tenantId=${this.currentTenantId} .expandClientId=${this.clientToExpandOnLoad} @navigate-to-edge=${this.handleNavigateToEdge}></versola-clients-list>`;
      case 'scopes':
        return html`<versola-scopes-list .tenantId=${this.currentTenantId}></versola-scopes-list>`;
      case 'permissions':
        return html`<versola-permissions-list .tenantId=${this.currentTenantId}></versola-permissions-list>`;
      case 'resources':
        return html`<versola-resources-list .tenantId=${this.currentTenantId}></versola-resources-list>`;
      case 'roles':
        return html`<versola-roles-list .tenantId=${this.currentTenantId}></versola-roles-list>`;
      case 'tenants':
        return html`<versola-tenants-list .selectedTenantId=${this.currentTenantId} @tenant-change=${this.handleTenantChange}></versola-tenants-list>`;
      case 'edges':
        return html`<versola-edges-list .expandEdgeId=${this.edgeToExpandOnLoad} @navigate-to-client=${this.handleNavigateToClient}></versola-edges-list>`;
      default:
        return html`<versola-clients-list .tenantId=${this.currentTenantId}></versola-clients-list>`;
    }
  }

  render() {
    return html`
      <div class="app-layout">
        <versola-navigation
          .activeItem=${this.currentView}
          .tenantId=${this.currentTenantId}
          @nav-change=${this.handleNavChange}
          @tenant-change=${this.handleTenantChange}
        ></versola-navigation>

        <main class="main-content">
          ${this.renderView()}
        </main>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-admin': VersolaAdmin;
  }
}

