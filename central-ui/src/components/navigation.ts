import { LitElement, html, css } from 'lit';
import { customElement, property, query } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import './versola-logo';
import './tenant-selector';
import './theme-switcher';

export type NavItem = 'security' | 'clients' | 'resources' | 'scopes' | 'permissions' | 'roles' | 'otp-templates' | 'authorization-details'
  | 'tenants' | 'edges' | 'users' | 'keys' | 'system-settings' | 'forms' | 'themes';

@customElement('versola-navigation')
export class VersolaNavigation extends LitElement {
  @property({ type: String }) activeItem: NavItem = 'clients';
  @property({ type: String }) tenantId: string | null = null;
  @property({ attribute: false }) permissions: Set<string> = new Set();
  @property({ attribute: false }) allowedTenantIds: string[] | null = null;
  /** Mobile drawer state. Reflected so the `:host([open])` rule below can match. */
  @property({ type: Boolean, reflect: true }) open = false;
  @property({ type: String }) logoutUrl = '/logout/central-admin';
  // Edge's own route (kept unprefixed like /resources elsewhere - see vite.config.ts's
  // dev proxy and deploy.md's nginx table), not the console's /central prefix: the
  // Account Settings page is auth's self-service surface, not part of the admin API.
  @property({ type: String }) accountSettingsUrl = '/resources/auth/settings';
  @query('.brand-close') private closeButton?: HTMLButtonElement;

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
        background: var(--sidebar-bg);
        border-right: 1px solid var(--border-default);
        height: 100vh;
        position: sticky;
        top: 0;
        overflow: hidden;
      }

      /* The brand, tenant and nav list scroll as one when they are taller
         than the viewport; the footer stays put. The scrollbar is always
         reserved and thin so a list that does not fit is seen to continue
         rather than looking cut off under the footer (macOS overlay
         scrollbars hide until touched). */
      .nav-scroll {
        flex: 1 1 auto;
        overflow-y: auto;
        min-height: 0;
        scrollbar-width: thin;
        scrollbar-gutter: stable;
        overscroll-behavior: contain;
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
          z-index: var(--z-drawer);
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
          box-shadow: var(--shadow-lg);
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
        gap: 0.625rem;
        padding: 1.125rem 1.5rem 1rem;
        border-bottom: 1px solid var(--border-default);
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
          border: 1.5px solid var(--border-input);
          border-radius: var(--radius-md);
          background: transparent;
          color: var(--text-secondary);
          font-family: var(--font-family);
          font-size: var(--text-xl);
          line-height: 1;
          cursor: pointer;
          transition: all var(--transition-fast);
        }

        .brand-close:hover {
          border-color: var(--text-primary);
          color: var(--text-primary);
        }

        /* In the drawer the items are touch targets: 44px, not the desktop's 33. */
        .nav-item {
          min-height: 2.75rem;
          padding-top: 0.625rem;
          padding-bottom: 0.625rem;
        }
      }

      .brand-logo {
        flex-shrink: 0;
        line-height: 0;
      }

      /* The website's monoline wordmark (Wordmark.astro), in ink next to the mark. */
      .brand-wordmark {
        display: block;
        height: 20px;
        width: auto;
        color: var(--text-primary);
      }

      .tenant-section {
        padding: 0.75rem var(--spacing-lg) var(--spacing-md);
        border-bottom: 1px solid var(--border-default);
      }

      .nav {
        padding: 0.5rem 1rem;
      }

      .nav-section {
        margin-bottom: 0.75rem;
      }

      /* A group label is a kicker in the brand colour, as the site's section labels. */
      .nav-section-title {
        font-size: var(--text-xs);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.1em;
        color: var(--accent);
        padding: 0.375rem 0.75rem;
        margin-bottom: 0.125rem;
      }

      .nav-item {
        display: flex;
        align-items: center;
        width: 100%;
        padding: 0.375rem 0.75rem;
        border: 0;
        border-radius: var(--radius-md);
        background: none;
        color: var(--text-secondary);
        text-decoration: none;
        text-align: left;
        font: inherit;
        font-size: var(--text-base);
        font-weight: 500;
        line-height: 1.5;
        cursor: pointer;
        transition: background var(--transition-fast), color var(--transition-fast);
        margin-bottom: 0.125rem;
      }

      /* Hover is a neutral fill; active is the accent fill plus a bar on the
         left edge, so the two never read alike. The weight does not change:
         a bolder active label would shift the text's width. */
      .nav-item:hover {
        background: var(--sidebar-hover-bg);
        color: var(--text-primary);
      }

      .nav-item.active,
      .nav-item.active:hover {
        background: var(--accent-subtle);
        color: var(--accent-text);
        box-shadow: inset 3px 0 0 var(--accent);
        /* Square the left corners so the bar is a bar, not a curved sliver. */
        border-radius: 0 var(--radius-md) var(--radius-md) 0;
      }

      .nav-item:focus-visible,
      .brand-close:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: -2px;
      }

      .nav-footer {
        flex: none;
        padding: 0.5rem 1rem;
        border-top: 1px solid var(--border-default);
        background: var(--sidebar-bg);
      }

      .nav-footer .nav-item {
        margin-bottom: 0;
      }

      .theme-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.5rem;
        padding: 0.125rem 0.75rem 0.375rem;
        color: var(--text-secondary);
        font-size: var(--text-base);
        font-weight: 500;
      }
    `,
  ];

  private handleCloseClick() {
    this.dispatchEvent(new CustomEvent('close-nav', {
      bubbles: true,
      composed: true,
    }));
  }

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
      case 'keys':
        return p.has('jwks:read');
      case 'clients':
      case 'scopes':
        return p.has('oauth:read');
      case 'permissions':
      case 'roles':
        return p.has('access:read');
      case 'resources':
        return p.has('resources:read');
      case 'security':
      case 'otp-templates':
      case 'authorization-details':
      case 'system-settings':
        return p.has('security:read');
      case 'users':
        return p.has('users:read');
      case 'forms':
      case 'themes':
        return p.has('forms:read');
      default:
        return false;
    }
  }

  private navItem(item: NavItem, label: string) {
    if (!this.can(item)) return html``;
    const active = this.activeItem === item;
    return html`
      <button
        type="button"
        class="nav-item ${active ? 'active' : ''}"
        aria-current=${active ? 'page' : 'false'}
        @click=${() => this.handleNavClick(item)}
      >${label}</button>
    `;
  }

  render() {
    return html`
      <div class="nav-panel">
        <div class="nav-scroll">
          <div class="brand">
            <div class="brand-logo">
              <versola-logo size="36"></versola-logo>
            </div>
            <svg class="brand-wordmark" role="img" aria-label="Versola" viewBox="-2 0 286 56" fill="none" stroke="currentColor" stroke-width="8" stroke-linecap="round" stroke-linejoin="round"><path d="M2 22 L18 48 L34 22"/><path d="M50 35 H76 A13 13 0 1 0 72.5 44.3"/><path d="M98 22 V48 M98 36 C98 26 104 22 114 22"/><path d="M158.5 27 C156.5 23 152.5 22 148.5 22 C142.5 22 138.5 25 138.5 29 C138.5 33 142.5 34.5 148.5 36 C154.5 37.5 158.5 39 158.5 42.5 C158.5 46.5 154.5 48.5 148.5 48.5 C143.5 48.5 139.5 46.5 138 42.5"/><circle cx="193.5" cy="35" r="13"/><path d="M228.5 6 V48"/><circle cx="263.5" cy="35" r="13"/><path d="M276.5 22 V48"/></svg>
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

          <nav class="nav" aria-label="Sections">
            <div class="nav-section">
              <div class="nav-section-title">Tenant</div>
              ${this.navItem('security', 'Security')}
              ${this.navItem('clients', 'Clients')}
              ${this.navItem('resources', 'Resources')}
              ${this.navItem('scopes', 'Scopes')}
              ${this.navItem('permissions', 'Permissions')}
              ${this.navItem('roles', 'Roles')}
              ${this.navItem('otp-templates', 'OTP templates')}
              ${this.navItem('authorization-details', 'Authorization details')}
            </div>

            <div class="nav-section">
              <div class="nav-section-title">Global</div>
              ${this.navItem('tenants', 'Tenants')}
              ${this.navItem('edges', 'Edges')}
              ${this.navItem('users', 'Users')}
              ${this.navItem('keys', 'Keys & metadata')}
              ${this.navItem('system-settings', 'System settings')}
              ${this.navItem('forms', 'Forms')}
              ${this.navItem('themes', 'Themes')}
            </div>
          </nav>
        </div>

        <div class="nav-footer">
          <div class="theme-row">
            <span>Theme</span>
            <versola-theme-switcher></versola-theme-switcher>
          </div>
          <a class="nav-item" href=${this.accountSettingsUrl}>My Security</a>
          <a class="nav-item" href=${this.logoutUrl}>Log out</a>
        </div>
      </div>
    `;
  }
}
