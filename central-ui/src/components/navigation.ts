import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import './versola-logo';
import './tenant-selector';

export type NavItem = 'clients' | 'scopes' | 'permissions' | 'resources' | 'roles' | 'tenants' | 'edges';

@customElement('versola-navigation')
export class VersolaNavigation extends LitElement {
  @property({ type: String }) activeItem: NavItem = 'clients';
  @property({ type: String }) tenantId: string | null = null;

  static styles = [
    theme,
    css`
      :host {
        display: block;
        background: var(--bg-dark);
        border-right: 1px solid var(--border-dark);
        height: 100vh;
        width: 250px;
        position: fixed;
        left: 0;
        top: 0;
        overflow-y: auto;
      }

      .brand {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        padding: 1.5rem;
        border-bottom: 1px solid var(--border-dark);
        text-decoration: none;
      }

      .brand-logo {
        flex-shrink: 0;
        line-height: 0;
      }

      .brand-name {
        font-size: 1.25rem;
        font-weight: 700;
        background: var(--accent-gradient);
        -webkit-background-clip: text;
        -webkit-text-fill-color: transparent;
        background-clip: text;
      }

      .tenant-section {
        padding: var(--spacing-lg);
        border-bottom: 1px solid var(--border-dark);
      }

      .nav {
        padding: 1rem;
      }

      .nav-section {
        margin-bottom: 1.5rem;
      }

      .nav-section-title {
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--text-secondary);
        padding: 0.5rem 0.75rem;
        margin-bottom: 0.25rem;
      }

      .nav-item {
        display: block;
        padding: 0.75rem;
        border-radius: var(--radius-md);
        color: var(--text-secondary);
        text-decoration: none;
        font-size: 0.875rem;
        font-weight: 500;
        cursor: pointer;
        transition: all var(--transition-fast);
        margin-bottom: 0.25rem;
      }

      .nav-item:hover {
        background: rgba(88, 166, 255, 0.1);
        color: var(--accent);
      }

      .nav-item.active {
        background: rgba(88, 166, 255, 0.15);
        color: var(--accent);
      }

      .nav-item-icon {
        font-size: 1.2rem;
        width: 20px;
        text-align: center;
      }
    `,
  ];

  private handleNavClick(item: NavItem) {
    this.dispatchEvent(new CustomEvent('nav-change', {
      detail: { item },
      bubbles: true,
      composed: true,
    }));
  }

  render() {
    return html`
      <div class="brand">
        <div class="brand-logo">
          <versola-logo size="40"></versola-logo>
        </div>
        <div class="brand-name">Versola</div>
      </div>

      <div class="tenant-section">
        <tenant-selector
          .selectedTenantId=${this.tenantId}
          .manageActive=${this.activeItem === 'tenants'}
          @manage-tenants=${() => this.handleNavClick('tenants')}
        ></tenant-selector>
      </div>

      <nav class="nav">
        <div class="nav-section">
          <div class="nav-section-title">General</div>
          <div
            class="nav-item ${this.activeItem === 'clients' ? 'active' : ''}"
            @click=${() => this.handleNavClick('clients')}
          >
            Clients
          </div>
          <div
            class="nav-item ${this.activeItem === 'scopes' ? 'active' : ''}"
            @click=${() => this.handleNavClick('scopes')}
          >
            Scopes
          </div>
          <div
            class="nav-item ${this.activeItem === 'permissions' ? 'active' : ''}"
            @click=${() => this.handleNavClick('permissions')}
          >
            Permissions
          </div>
          <div
            class="nav-item ${this.activeItem === 'resources' ? 'active' : ''}"
            @click=${() => this.handleNavClick('resources')}
          >
            Resources
          </div>
          <div
            class="nav-item ${this.activeItem === 'roles' ? 'active' : ''}"
            @click=${() => this.handleNavClick('roles')}
          >
            Roles
          </div>
        </div>

        <div class="nav-section">
          <div class="nav-section-title">Global</div>
          <div
            class="nav-item ${this.activeItem === 'edges' ? 'active' : ''}"
            @click=${() => this.handleNavClick('edges')}
          >
            Edges
          </div>
        </div>
      </nav>
    `;
  }
}

