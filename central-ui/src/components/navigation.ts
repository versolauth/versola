import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import './versola-logo';
import './tenant-selector';

export type NavItem = 'clients' | 'scopes' | 'permissions' | 'resources' | 'roles' | 'tenants' | 'edges' | 'users' | 'forms' | 'locales' | 'challenges' | 'jwks' | 'system-settings';

@customElement('versola-navigation')
export class VersolaNavigation extends LitElement {
  @property({ type: String }) activeItem: NavItem = 'clients';
  @property({ type: String }) tenantId: string | null = null;
  @property({ attribute: false }) permissions: Set<string> = new Set();
  @property({ attribute: false }) allowedTenantIds: string[] | null = null;
  /** Mobile drawer state. Reflected so the `:host([open])` rule below can match. */
  @property({ type: Boolean, reflect: true }) open = false;

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

      /* On narrow screens the sidebar becomes an off-canvas drawer: it stays
         fixed (so it scrolls with nothing) but sits translated out of view
         until opened, instead of permanently covering 250px of a ~390px wide
         screen. Above this breakpoint it's the normal always-visible sidebar,
         so no transform applies. */
      @media (max-width: 768px) {
        :host {
          /* Local duration so the visibility delay below can reference exactly
             the same value; --transition-fast bundles duration+easing together
             and so can't be reused as a delay. */
          --drawer-duration: 0.15s;

          z-index: 200;
          transform: translateX(-100%);
          /* visibility (not just the transform) so the closed drawer leaves the
             a11y tree and tab order — otherwise keyboard/screen-reader users can
             tab into an off-screen menu.
             visibility is a discrete property, so rather than relying on how a
             given browser interpolates it mid-transition, the change is pushed
             to the very end with an explicit delay: closing keeps the drawer
             visible for the whole slide-out, then hides it. */
          visibility: hidden;
          transition:
            transform var(--drawer-duration) ease,
            visibility 0s linear var(--drawer-duration);
          box-shadow: 2px 0 12px rgba(0, 0, 0, 0.4);
        }

        :host([open]) {
          transform: translateX(0);
          /* Opening is the mirror image: become visible immediately (no delay)
             so the slide-in is actually seen. */
          visibility: visible;
          transition:
            transform var(--drawer-duration) ease,
            visibility 0s;
        }
      }

      /* Users who prefer reduced motion get the same states without sliding.
         Both selectors are listed because :host([open]) is more specific than
         :host — overriding only the latter would leave the open state animating. */
      @media (prefers-reduced-motion: reduce) {
        :host,
        :host([open]) {
          transition: none;
        }
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

  private can(view: NavItem): boolean {
    const p = this.permissions;
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

  private navItem(item: NavItem, label: string) {
    if (!this.can(item)) return html``;
    return html`
      <div
        class="nav-item ${this.activeItem === item ? 'active' : ''}"
        @click=${() => this.handleNavClick(item)}
      >${label}</div>
    `;
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
          .allowedTenantIds=${this.allowedTenantIds}
        ></tenant-selector>
      </div>

      <nav class="nav">
        <div class="nav-section">
          <div class="nav-section-title">General</div>
          ${this.navItem('clients', 'Clients')}
          ${this.navItem('scopes', 'Scopes')}
          ${this.navItem('permissions', 'Permissions')}
          ${this.navItem('resources', 'Resources')}
          ${this.navItem('roles', 'Roles')}
          ${this.navItem('challenges', 'Challenges & Security')}
        </div>

        <div class="nav-section">
          <div class="nav-section-title">Global</div>
          ${this.navItem('edges', 'Edges')}
          ${this.navItem('forms', 'Forms')}
          ${this.navItem('jwks', 'JWKS')}
          ${this.navItem('locales', 'Locales')}
          ${this.navItem('system-settings', 'System Settings')}
          ${this.navItem('tenants', 'Tenants')}
          ${this.navItem('users', 'Users')}
        </div>
      </nav>
    `;
  }
}

