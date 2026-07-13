import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme, resetStyles } from '../styles/theme';
import type { NavItem } from './navigation';
import { configureCentralApi, fetchMyPermissions } from '../utils/central-api';

import './navigation';
import './clients-list';
import './scopes-list';
import './permissions-list';
import './resources-list';
import './roles-list';
import './tenants-list';
import './edges-list';
import './users-list';
import './forms-list';
import './locales-list';
import './challenges-list';
import './jwks-list';
import './system-settings';


@customElement('versola-admin')
export class VersolaAdmin extends LitElement {
  @property({ type: String, attribute: 'api-url' }) apiUrl: string | null = null;
  @state() private currentView: NavItem = 'clients';
  @state() private currentTenantId: string | null = null;
  @state() private clientToExpandOnLoad: string | null = null;
  @state() private edgeToExpandOnLoad: string | null = null;

  // Permission state resolved from /permissions/me
  @state() private adminPermissions: Set<string> = new Set();
  // Tenant IDs accessible to this admin (null = all tenants visible)
  @state() private allowedTenantIds: string[] | null = null;

  private readonly handlePopState = () => {
    this.loadLocationState();
  };

  connectedCallback() {
    super.connectedCallback();
    this.applyApiConfig();
    this.loadLocationState();
    void this.loadPermissions();
    window.addEventListener('popstate', this.handlePopState);
  }

  disconnectedCallback() {
    window.removeEventListener('popstate', this.handlePopState);
    super.disconnectedCallback();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('apiUrl')) {
      this.applyApiConfig();
      void this.loadPermissions();
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

  private async loadPermissions() {
    try {
      const response = await fetchMyPermissions();
      const central = response.resources['central'];
      this.adminPermissions = new Set(central?.permissions ?? []);
      this.allowedTenantIds = this.adminPermissions.size > 0 ? null : [];
    } catch {
      this.adminPermissions = new Set();
      this.allowedTenantIds = [];
    }
  }

  private hasPermission(view: NavItem): boolean {
    const p = this.adminPermissions;
    switch (view) {
      case 'tenants':
        return p.has('tenants:read');
      case 'edges':
        return p.has('edges:read');
      case 'jwks':
        return p.has('jwks:read');
      case 'clients':
      case 'scopes':
        return p.has('oauth:read');
      case 'permissions':
      case 'roles':
        return p.has('access:read');
      case 'resources':
        return p.has('resources:read');
      case 'challenges':
      case 'system-settings':
        return p.has('security:read');
      case 'users':
        return p.has('users:read');
      case 'forms':
        return p.has('forms:read');
      case 'locales':
        return p.has('locales:read');
      default:
        return false;
    }
  }

  /** Whether the admin may perform mutations (create/edit/delete) for the given view. */
  private canManage(view: NavItem): boolean {
    const p = this.adminPermissions;
    switch (view) {
      case 'tenants':
        return p.has('tenants:manage');
      case 'edges':
        return p.has('edges:manage');
      case 'jwks':
        return p.has('jwks:manage');
      case 'clients':
      case 'scopes':
        return p.has('oauth:manage');
      case 'permissions':
      case 'roles':
        return p.has('access:manage');
      case 'resources':
        return p.has('resources:manage');
      case 'challenges':
      case 'system-settings':
        return p.has('security:manage');
      case 'users':
        return p.has('users:manage');
      case 'forms':
        return p.has('forms:manage');
      case 'locales':
        return p.has('locales:manage');
      default:
        return false;
    }
  }

  /** Whether the admin may perform client secret operations (rotate / delete previous secret). */
  private get canManageSecrets(): boolean {
    return this.adminPermissions.has('oauth:secrets');
  }

  private handleNavChange(e: CustomEvent) {
    this.currentView = e.detail.item;
  }

  private handleTenantChange(e: CustomEvent) {
    this.currentTenantId = e.detail.tenantId;
  }

  private applyApiConfig() {
    configureCentralApi({
      baseUrl: this.apiUrl,
    });
  }

  private loadLocationState() {
    const params = new URL(window.location.href).searchParams;
    const urlView = params.get('view');
    const urlTenantId = params.get('tenant');
    const expandClient = params.get('expandClient');
    const expandEdge = params.get('expandEdge');

    if (urlView === 'clients' || urlView === 'scopes' || urlView === 'permissions' || urlView === 'resources' || urlView === 'roles' || urlView === 'tenants' || urlView === 'edges' || urlView === 'users' || urlView === 'forms' || urlView === 'locales' || urlView === 'challenges' || urlView === 'jwks' || urlView === 'system-settings') {
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

  private renderAccessDenied() {
    return html`
      <div style="display:flex;align-items:center;justify-content:center;min-height:40vh">
        <div style="text-align:center;color:var(--text-secondary)">
          <div style="font-size:3rem;margin-bottom:1rem">🔒</div>
          <h2 style="color:var(--text-primary);margin-bottom:0.5rem">Access Denied</h2>
          <p style="max-width:32rem;margin:0 auto">
            Please contact your system administrator to gain access.
          </p>
        </div>
      </div>
    `;
  }

  private renderView() {
    if (!this.hasPermission(this.currentView)) {
      return this.renderAccessDenied();
    }

    switch (this.currentView) {
      case 'clients':
        return html`<versola-clients-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('clients')} .canManageSecrets=${this.canManageSecrets} .expandClientId=${this.clientToExpandOnLoad} @navigate-to-edge=${this.handleNavigateToEdge}></versola-clients-list>`;
      case 'scopes':
        return html`<versola-scopes-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('scopes')}></versola-scopes-list>`;
      case 'permissions':
        return html`<versola-permissions-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('permissions')}></versola-permissions-list>`;
      case 'resources':
        return html`<versola-resources-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('resources')}></versola-resources-list>`;
      case 'roles':
        return html`<versola-roles-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('roles')}></versola-roles-list>`;
      case 'tenants':
        return html`<versola-tenants-list .selectedTenantId=${this.currentTenantId} .canManage=${this.canManage('tenants')} @tenant-change=${this.handleTenantChange}></versola-tenants-list>`;
      case 'edges':
        return html`<versola-edges-list .expandEdgeId=${this.edgeToExpandOnLoad} .canManage=${this.canManage('edges')} @navigate-to-client=${this.handleNavigateToClient}></versola-edges-list>`;
      case 'users':
        return html`<versola-users-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('users')}></versola-users-list>`;
      case 'forms':
        return html`<versola-forms-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('forms')}></versola-forms-list>`;
      case 'locales':
        return html`<versola-locales-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('locales')}></versola-locales-list>`;
      case 'challenges':
        return html`<versola-challenges-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('challenges')}></versola-challenges-list>`;
      case 'jwks':
        return html`<versola-jwks-list .canManage=${this.canManage('jwks')}></versola-jwks-list>`;
      case 'system-settings':
        return html`<versola-system-settings .canManage=${this.canManage('system-settings')}></versola-system-settings>`;
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
          .permissions=${this.adminPermissions}
          .allowedTenantIds=${this.allowedTenantIds}
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

