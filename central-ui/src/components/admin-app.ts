import { LitElement, html, css } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme, resetStyles } from '../styles/theme';
import type { NavItem, VersolaNavigation } from './navigation';
import { configureCentralApi, fetchMyPermissions } from '../utils/central-api';

import './navigation';
// Imported directly (not just transitively via navigation) because the splash
// screen renders the logo without the nav being on the page.
import './versola-logo';
// Likewise: the access-denied state renders a content-header without any list
// component on the page to pull it in.
import './content-header';
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
import './well-known';
import './system-settings';


@customElement('versola-admin')
export class VersolaAdmin extends LitElement {
  @property({ type: String, attribute: 'api-url' }) apiUrl: string | null = null;
  // Where to send an unauthenticated visitor. Defaults (in central-api) to the
  // central-admin preset's edge login; override only if this console is ever
  // wired to a different preset.
  @property({ type: String, attribute: 'login-url' }) loginUrl: string | null = null;
  // 'prefix' (default): the console is reached via the /central/{x} shortcut,
  // relying on an external proxy (Vite dev server / gateway nginx) to rewrite
  // it to edge's real /resources/central/{x} route -- local dev, docker-local,
  // and path-based prod all use this. 'direct': the console has its own
  // origin (e.g. k8s, see #222) and calls edge's real route outright. See
  // centralResourcePath in central-api.ts.
  @property({ type: String, attribute: 'console-mode' }) consoleMode: 'prefix' | 'direct' = 'prefix';
  @state() private currentView: NavItem = 'clients';
  /** Mobile drawer state; ignored by the layout above the 768px breakpoint. */
  @state() private navOpen = false;
  @query('.main-content') private mainContent?: HTMLElement;
  @query('versola-navigation') private navigationEl?: VersolaNavigation;
  @state() private currentTenantId: string | null = null;
  @state() private clientToExpandOnLoad: string | null = null;
  @state() private edgeToExpandOnLoad: string | null = null;

  // Permission state resolved from /permissions/me
  @state() private adminPermissions: Set<string> = new Set();
  /** False until /permissions/me has resolved once.
    *
    * Distinguishes "not loaded yet" from "loaded, and this admin has no
    * permissions" — both of which are an empty adminPermissions set. Without
    * it the app renders its shell and an "Access Denied" panel during the very
    * first request, which for an unauthenticated visitor flashes on screen
    * just before the redirect to login lands.
    */
  @state() private permissionsLoaded = false;
  // Tenant IDs accessible to this admin (null = all tenants visible)
  @state() private allowedTenantIds: string[] | null = null;
  /** Reported by /permissions/me. Defaults to true so a failed load hides
    * non-prod-only affordances rather than exposing them. */
  @state() private isProd = true;

  /** Mirrors the 768px breakpoint in navigation.ts's media query. Kept in sync
    * by hand — if that breakpoint moves, move this one too. */
  private readonly mobileQuery = window.matchMedia('(max-width: 768px)');

  /** Escape closes the drawer — expected of anything overlaying the page, and
    * the only keyboard-reachable way out for someone who opened it. */
  private readonly handleKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && this.navOpen) {
      this.closeNav();
    }
  };

  /** Growing past the breakpoint (a phone rotated to landscape can exceed
    * 768px) turns the drawer back into a static sidebar. Leaving navOpen set
    * would then spring the drawer open on the way back to portrait, so reset
    * it while it can't be seen. */
  private readonly handleBreakpointChange = (event: MediaQueryListEvent) => {
    if (!event.matches) {
      this.navOpen = false;
    }
  };

  private readonly handlePopState = () => {
    this.loadLocationState();
  };

  connectedCallback() {
    super.connectedCallback();
    this.applyApiConfig();
    this.loadLocationState();
    void this.loadPermissions();
    window.addEventListener('popstate', this.handlePopState);
    window.addEventListener('keydown', this.handleKeyDown);
    this.observeBreakpoint(true);
  }

  disconnectedCallback() {
    window.removeEventListener('popstate', this.handlePopState);
    window.removeEventListener('keydown', this.handleKeyDown);
    this.observeBreakpoint(false);
    super.disconnectedCallback();
  }

  /** Subscribes/unsubscribes to breakpoint changes.
    *
    * MediaQueryList only gained addEventListener in Safari 14; older iOS has
    * just the deprecated addListener. Calling the modern API there throws a
    * TypeError right inside connectedCallback, which would break component
    * setup rather than merely the drawer — so it's feature-detected.
    */
  private observeBreakpoint(subscribe: boolean) {
    const query = this.mobileQuery;
    if (typeof query.addEventListener === 'function') {
      if (subscribe) query.addEventListener('change', this.handleBreakpointChange);
      else query.removeEventListener('change', this.handleBreakpointChange);
      return;
    }
    if (subscribe) query.addListener(this.handleBreakpointChange);
    else query.removeListener(this.handleBreakpointChange);
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('apiUrl') || changed.has('loginUrl') || changed.has('consoleMode')) {
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

      /* A flex row so versola-navigation's sticky sidebar — sized only by its
         own content plus stretch, not by an explicit height — is given the
         row's actual height (align-items defaults to stretch). See the
         comment on versola-navigation's :host for why this matters. */
      .app-layout {
        display: flex;
        min-height: 100vh;
      }

      .app-splash {
        display: flex;
        align-items: center;
        justify-content: center;
        min-height: 100vh;
        /* Held only for as long as /permissions/me is in flight, so it fades in
           rather than flashing on a fast response. */
        animation: splash-fade-in 0.4s ease both;
      }

      @keyframes splash-fade-in {
        from { opacity: 0; }
        to { opacity: 1; }
      }

      @media (prefers-reduced-motion: reduce) {
        .app-splash {
          animation: none;
        }
      }

      .main-content {
        flex: 1 1 auto;
        padding: 2rem;
        max-width: 1400px;
        min-width: 0; /* let children shrink instead of overflowing */
      }

      /* Focus is moved here programmatically when the drawer closes (see
         closeNav). It still needs to be *visible* — a keyboard user shouldn't
         be left wondering where focus went — but .main-content spans the full
         page, so a static outline around it reads as a stray border around the
         whole screen rather than a focus indicator, especially since it never
         goes away until focus moves again. Faded flash instead: felt at the
         moment focus lands, gone a moment later.
         :focus-visible rather than :focus as a side benefit: it only fires
         from keyboard-driven closes (Escape, the drawer's own ✕). Clicking the
         backdrop with a mouse skips it, since the mouse user just watched the
         drawer close and doesn't need the same cue. */
      .main-content:focus-visible {
        outline: none;
        animation: focus-flash 0.6s ease-out;
      }

      @keyframes focus-flash {
        from { box-shadow: inset 0 0 0 2px var(--accent); }
        to { box-shadow: inset 0 0 0 2px transparent; }
      }

      @media (prefers-reduced-motion: reduce) {
        .main-content:focus-visible {
          animation: none;
          outline: 2px solid var(--accent);
          outline-offset: -2px;
        }
      }

      /* The backdrop only exists on mobile, where the sidebar is an off-canvas
         drawer. On desktop the sidebar is always visible, so nothing overlays
         the page. The drawer toggle itself lives in content-header, inline with
         each screen's title — see the comments on handleOpenNav/handleCloseNav. */
      .nav-backdrop {
        display: none;
      }

      @media (max-width: 768px) {
        /* versola-navigation switches back to position: fixed below the
           breakpoint (see its own styles), which takes it out of flow — so
           .main-content, as the only remaining flex child, naturally takes
           the full row width without an explicit margin-left override. */
        .main-content {
          padding: 1rem;
        }

        .nav-backdrop.visible {
          display: block;
          position: fixed;
          inset: 0;
          z-index: 150; /* under the drawer, over the content */
          background: rgba(0, 0, 0, 0.5);
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
      this.isProd = response.isProd;
    } catch {
      this.adminPermissions = new Set();
      this.allowedTenantIds = [];
      this.isProd = true;
    } finally {
      // Deliberately never runs on the unauthenticated path: a 401 hands off to
      // a top-level navigation and fetchMyPermissions' promise never settles,
      // so the splash below stays up until the browser leaves for the login
      // page — instead of briefly swapping in the console and an Access Denied.
      this.permissionsLoaded = true;
    }
  }

  private hasPermission(view: NavItem): boolean {
    const p = this.adminPermissions;
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

  /** Whether the admin may perform mutations (create/edit/delete) for the given view. */
  private canManage(view: NavItem): boolean {
    const p = this.adminPermissions;
    switch (view) {
      case 'tenants':
        return p.has('tenants:manage');
      case 'edges':
        return p.has('edges:manage');
      case 'well-known':
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
    // Picking a destination on mobile should reveal it, not leave the drawer
    // covering the screen. No-op on desktop, where the drawer is never open.
    this.closeNav();
  }

  /** Handles the `open-nav` event bubbling out of a versola-nav-toggle.
    *
    * The drawer toggle used to be a `position: fixed` button owned by this
    * component. Being pinned to the viewport, it sat on top of whatever the
    * page happened to be scrolled to — covering card headings mid-page, and
    * covering the drawer's own logo once opened. It now lives inline with each
    * screen's title, so it scrolls with the page like any other content.
    *
    * Opening and closing are separate events rather than one toggle: the open
    * button sits in page headers (which are scattered across a dozen
    * components) while closing happens from inside the drawer, the backdrop,
    * or Escape. Splitting them means the open button never needs to know
    * whether the drawer is open, so no drawer state has to be threaded down
    * through every screen.
    *
    * Focus is moved into the drawer once it's rendered open: the toggle that
    * triggered this lives in <main>, after the drawer in DOM order, and stays
    * focused by default. A keyboard user tabbing from there would continue
    * through the rest of main's content — which the backdrop now visually
    * covers — rather than landing anywhere inside the panel that just opened.
    */
  private handleOpenNav = () => {
    this.navOpen = true;
    void this.updateComplete.then(async () => {
      // Waiting on this component's own updateComplete isn't enough: setting
      // .open on versola-navigation schedules *its* update as a separate,
      // independently-batched microtask, which may not have run yet. Without
      // this, .brand-close can still be undefined when focusClose() runs.
      await this.navigationEl?.updateComplete;
      this.navigationEl?.focusClose();
    });
  };

  private handleCloseNav = () => {
    this.closeNav();
  };

  /** Closes the mobile drawer, moving focus somewhere still visible.
    *
    * The element that triggered the close (a nav item) is inside the drawer,
    * which becomes `visibility: hidden` — leaving focus on it would strand
    * keyboard and screen-reader users on an unreachable element.
    *
    * Focus lands on <main> rather than the toggle button that opened the
    * drawer: that button now lives inside versola-nav-toggle's shadow root,
    * which this component can't reach without reaching through another
    * component's internals. <main> is a stable, always-present target, and
    * landing there puts a screen reader at the top of the content the user
    * just navigated to — arguably a better destination than the button anyway.
    *
    * preventScroll matters: focus() scrolls its target into view by default,
    * which would yank a scrolled page back to the top every time the drawer is
    * dismissed without navigating anywhere (✕, backdrop, Escape).
    *
    * Returns early when already closed, so this never runs on desktop.
    */
  private closeNav() {
    if (!this.navOpen) return;
    this.navOpen = false;
    void this.updateComplete.then(() => {
      this.mainContent?.focus({ preventScroll: true });
    });
  }

  private handleTenantChange(e: CustomEvent) {
    this.currentTenantId = e.detail.tenantId;
  }

  private applyApiConfig() {
    // Always pass both — passing loginUrl even when null lets configureCentralApi
    // reset it to the default if a previously-set login-url attribute is cleared,
    // rather than leaving the old value stuck.
    configureCentralApi({
      baseUrl: this.apiUrl,
      loginUrl: this.loginUrl,
      consoleMode: this.consoleMode,
    });
  }

  private loadLocationState() {
    const params = new URL(window.location.href).searchParams;
    const urlView = params.get('view');
    const urlTenantId = params.get('tenant');
    const expandClient = params.get('expandClient');
    const expandEdge = params.get('expandEdge');

    if (urlView === 'clients' || urlView === 'scopes' || urlView === 'permissions' || urlView === 'resources' || urlView === 'roles' || urlView === 'tenants' || urlView === 'edges' || urlView === 'users' || urlView === 'forms' || urlView === 'locales' || urlView === 'challenges' || urlView === 'well-known' || urlView === 'system-settings') {
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

  /** Renders the "no permission for this view" state.
    *
    * Carries a content-header purely so the drawer toggle is present: this
    * screen renders instead of a list component, so without it an admin whose
    * default view is denied would have no way to reach the menu and no way to
    * navigate anywhere else.
    */
  private renderAccessDenied() {
    return html`
      <content-header title="Access Denied"></content-header>
      <div style="display:flex;align-items:center;justify-content:center;min-height:40vh">
        <div style="text-align:center;color:var(--text-secondary)">
          <div style="font-size:3rem;margin-bottom:1rem">🔒</div>
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
        return html`<versola-users-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('users')} .canRevealPassword=${!this.isProd}></versola-users-list>`;
      case 'forms':
        return html`<versola-forms-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('forms')}></versola-forms-list>`;
      case 'locales':
        return html`<versola-locales-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('locales')}></versola-locales-list>`;
      case 'challenges':
        return html`<versola-challenges-list .tenantId=${this.currentTenantId} .canManage=${this.canManage('challenges')}></versola-challenges-list>`;
      case 'well-known':
        return html`<versola-well-known .canManage=${this.canManage('well-known')}></versola-well-known>`;
      case 'system-settings':
        return html`<versola-system-settings .canManage=${this.canManage('system-settings')}></versola-system-settings>`;
      default:
        return html`<versola-clients-list .tenantId=${this.currentTenantId}></versola-clients-list>`;
    }
  }

  render() {
    // Until we know who the caller is, show only a neutral brand splash — no
    // navigation, no content, no permission verdict. An unauthenticated visitor
    // is on their way to the login page and should never glimpse the console.
    if (!this.permissionsLoaded) {
      return html`
        <div class="app-splash" role="status" aria-label="Loading">
          <versola-logo size="56"></versola-logo>
        </div>
      `;
    }

    return html`
      <div class="app-layout" @open-nav=${this.handleOpenNav} @close-nav=${this.handleCloseNav}>
        <div
          class="nav-backdrop ${this.navOpen ? 'visible' : ''}"
          @click=${this.closeNav}
        ></div>

        <versola-navigation
          .activeItem=${this.currentView}
          .tenantId=${this.currentTenantId}
          .permissions=${this.adminPermissions}
          .allowedTenantIds=${this.allowedTenantIds}
          .open=${this.navOpen}
          .logoutUrl=${'/logout/central-admin'}
          @nav-change=${this.handleNavChange}
          @tenant-change=${this.handleTenantChange}
        ></versola-navigation>

        <main class="main-content" tabindex="-1">
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

