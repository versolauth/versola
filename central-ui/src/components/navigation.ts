import { LitElement, html, css } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { getStoredTheme, toggleTheme, type ThemeName } from '../utils/theme';
import './versola-logo';
import './tenant-selector';

export type NavItem = 'clients' | 'scopes' | 'permissions' | 'resources' | 'roles' | 'tenants' | 'edges' | 'users' | 'forms' | 'locales' | 'challenges' | 'well-known' | 'system-settings';

@customElement('versola-navigation')
export class VersolaNavigation extends LitElement {
  @property({ type: String }) activeItem: NavItem = 'clients';
  @property({ type: String }) tenantId: string | null = null;
  @property({ attribute: false }) permissions: Set<string> = new Set();
  @property({ attribute: false }) allowedTenantIds: string[] | null = null;
  /** Mobile drawer state. Reflected so the `:host([open])` rule below can match. */
  @property({ type: Boolean, reflect: true }) open = false;
  @property({ type: String }) logoutUrl = '/logout/central-admin';
  @query('.brand-close') private closeButton?: HTMLButtonElement;
  /** Mirrors whatever's currently applied to <html> (see src/utils/theme.ts).
    * Read once at construction rather than derived on every render — the only
    * other place that changes it is this component's own toggle handler. */
  @state() private currentTheme: ThemeName = getStoredTheme();

  /** Called by admin-app right after opening the drawer.
    *
    * Without this, focus stays on the header toggle the user just activated —
    * which the backdrop then visually covers, since it renders in normal
    * document flow inside <main>, after the drawer in DOM order. A keyboard
    * user pressing Tab from there continues through the rest of main's
    * (now-obscured) content instead of landing in the panel that just opened.
    * Landing on the drawer's own close button puts them somewhere real and
    * gives an immediate, obvious way back out.
    */
  focusClose() {
    this.closeButton?.focus();
  }

  static styles = [
    theme,
    css`
      /* :host is a plain sizer: width fixed, height left to the flex row
         (admin-app's .app-layout stretches it to the row's full height). The
         panel actually pinned to the viewport is .nav-panel below — splitting
         them matters because a single element can't both size itself to an
         arbitrarily tall document *and* cap its own height at 100vh to stay
         on-screen while scrolling. Collapsing the two (e.g. position: fixed
         with height: 100vh directly on :host, as this used to be) forces that
         same element to size to the viewport regardless of the document's
         actual height: correct for interactive scrolling, but a tool that
         captures the full scrollable page (a full-page screenshot, print/PDF)
         then renders one viewport's worth of sidebar and flat background for
         the rest of a taller document — wrong the moment any screen (e.g.
         resources' endpoint editor) grows past the fold. */
      :host {
        display: block;
        width: 250px;
        flex: none;
        align-self: stretch;
      }

      .nav-panel {
        display: flex;
        flex-direction: column;
        background: var(--bg-dark);
        border-right: 1px solid var(--border-dark);
        height: 100vh;
        position: sticky;
        top: 0;
        overflow: hidden;
      }

      .nav-scroll {
        flex: 1 1 auto;
        overflow-y: auto;
        min-height: 0;
      }

      /* On narrow screens the sidebar becomes an off-canvas drawer: fixed to
         the viewport and translated out of view until opened, rather than
         sized into the document flow. There's no taller-than-viewport concern
         to design around here — an overlay drawer is *meant* to be exactly
         one viewport tall — so :host reverts to owning position/size directly
         instead of deferring to .nav-panel, same as before this component had
         a document-height sizer to worry about. */
      @media (max-width: 768px) {
        :host {
          /* Local duration so the visibility delay below can reference exactly
             the same value; --transition-fast bundles duration+easing together
             and so can't be reused as a delay. */
          --drawer-duration: 0.15s;

          position: fixed;
          top: 0;
          left: 0;
          width: 250px;
          height: 100vh;
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

        /* .nav-panel's own position: sticky (set unconditionally above, for
           the desktop layout) would otherwise fight with :host's position:
           fixed for what's actually pinned to the viewport here; static
           hands full control back to :host, and the panel simply fills it. */
        .nav-panel {
          position: static;
          height: 100%;
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
         the base :host rule — overriding only the latter would leave the open
         state animating. */
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

      /* The drawer's own close button. Lives here, inside the panel, rather
         than as an overlay pinned to the viewport: laid out as a sibling of the
         logo it can't cover it, and it slides away with the panel instead of
         lingering over the page. Only meaningful on mobile, where the panel is
         a drawer that can be closed at all. */
      .brand-close {
        display: none;
      }

      @media (max-width: 768px) {
        .brand-close {
          display: flex;
          align-items: center;
          justify-content: center;
          flex: none;
          margin-left: auto;
          width: 2.25rem;
          height: 2.25rem;
          padding: 0;
          border: 1px solid var(--border-dark);
          border-radius: var(--radius-md);
          background: transparent;
          color: var(--text-secondary);
          font-family: var(--font-family);
          font-size: 1.125rem;
          line-height: 1;
          cursor: pointer;
          transition: all var(--transition-fast);
        }

        .brand-close:hover {
          border-color: var(--accent);
          color: var(--accent);
        }
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
        background: rgba(var(--accent-tint), 0.1);
        color: var(--accent);
      }

      .nav-item.active {
        background: rgba(var(--accent-tint), 0.15);
        color: var(--accent);
      }

      .nav-item-icon {
        font-size: 1.2rem;
        width: 20px;
        text-align: center;
      }

      .nav-footer {
        flex: none;
        padding: 1rem;
        border-top: 1px solid var(--border-dark);
      }

      .nav-footer .nav-item {
        margin-bottom: 0;
      }

      .theme-toggle {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.5rem;
        padding: 0.75rem;
        border-radius: var(--radius-md);
        margin-bottom: 0.25rem;
        color: var(--text-secondary);
        font-size: 0.875rem;
        font-weight: 500;
      }

      /* A two-position switch rather than a single icon button: the icon
         alone doesn't communicate which mode is *currently* active without
         a label, and this sidebar already uses plain-language nav items over
         icons elsewhere (see navItem above) — a switch with both states
         visible keeps that same at-a-glance clarity for theme too. */
      .theme-switch {
        display: inline-flex;
        border: 1px solid var(--border-dark);
        border-radius: 9999px;
        padding: 2px;
        gap: 2px;
      }

      .theme-switch-option {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 1.75rem;
        height: 1.5rem;
        border: none;
        border-radius: 9999px;
        background: transparent;
        color: var(--text-secondary);
        cursor: pointer;
        font-size: 0.9rem;
        line-height: 1;
        transition: all var(--transition-fast);
      }

      .theme-switch-option.active {
        background: rgba(var(--accent-tint), 0.18);
        color: var(--accent);
      }

      .theme-switch-option:hover:not(.active) {
        color: var(--text-primary);
      }
    `,
  ];

  private handleCloseClick() {
    this.dispatchEvent(new CustomEvent('close-nav', {
      bubbles: true,
      composed: true,
    }));
  }

  private handleThemeToggle = () => {
    this.currentTheme = toggleTheme();
  };

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
      case 'well-known':
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
      <div class="nav-panel">
        <div class="nav-scroll">
          <div class="brand">
            <div class="brand-logo">
              <versola-logo size="40"></versola-logo>
            </div>
            <div class="brand-name">Versola</div>
            <button
              type="button"
              class="brand-close"
              @click=${this.handleCloseClick}
              aria-label="Close navigation menu"
            >✕</button>
          </div>

          <div class="tenant-section">
            <tenant-selector
              .selectedTenantId=${this.tenantId}
              .allowedTenantIds=${this.allowedTenantIds}
            ></tenant-selector>
          </div>

          <nav class="nav">
            <div class="nav-section">
              <div class="nav-section-title">Tenant Scoped</div>
              ${this.navItem('challenges', 'Challenges & Security')}
              ${this.navItem('clients', 'Clients')}
              ${this.navItem('permissions', 'Permissions')}
              ${this.navItem('resources', 'Resources')}
              ${this.navItem('roles', 'Roles')}
              ${this.navItem('scopes', 'Scopes')}
            </div>

            <div class="nav-section">
              <div class="nav-section-title">Global</div>
              ${this.navItem('edges', 'Edges')}
              ${this.navItem('forms', 'Forms')}
              ${this.navItem('locales', 'Locales')}
              ${this.navItem('system-settings', 'System Settings')}
              ${this.navItem('tenants', 'Tenants')}
              ${this.navItem('users', 'Users')}
              ${this.navItem('well-known', 'Well Known')}
            </div>
          </nav>
        </div>

        <div class="nav-footer">
          <div class="theme-toggle">
            <span>Theme</span>
            <span class="theme-switch" role="radiogroup" aria-label="Color theme">
              <button
                type="button"
                class="theme-switch-option ${this.currentTheme === 'light' ? 'active' : ''}"
                role="radio"
                aria-checked=${this.currentTheme === 'light'}
                aria-label="Light theme"
                title="Light theme"
                @click=${() => { if (this.currentTheme !== 'light') this.handleThemeToggle(); }}
              >☀️</button>
              <button
                type="button"
                class="theme-switch-option ${this.currentTheme === 'dark' ? 'active' : ''}"
                role="radio"
                aria-checked=${this.currentTheme === 'dark'}
                aria-label="Dark theme"
                title="Dark theme"
                @click=${() => { if (this.currentTheme !== 'dark') this.handleThemeToggle(); }}
              >🌙</button>
            </span>
          </div>
          <a class="nav-item" href=${this.logoutUrl}>Log out</a>
        </div>
      </div>
    `;
  }
}

