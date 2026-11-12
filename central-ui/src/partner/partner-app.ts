import { LitElement, html, css } from 'lit';
import { customElement, query, state } from 'lit/decorators.js';
import { theme, resetStyles } from '../styles/theme';
import { bannerStyles, buttonStyles } from '../styles/components';
import { statusIcon } from '../components/status-icon';
import { watchSystemTheme } from '../utils/theme';
import { EMPTY_RECORD_ROUTE, type RecordRoute } from '../utils/record-route';
import { formatDate } from '../utils/helpers';
import { PARTNER_LOGOUT_PATH, fetchMe, isPartnerApiError } from './partner-api';
import type { PartnerMe } from './partner-types';
import { PARTNER_NAV_ITEMS, canSee, type PartnerNavItem, type VersolaPartnerNavigation } from './partner-navigation';
import './partner-navigation';
import '../components/versola-logo';
import '../components/content-header';
import './views/partner-no-access';
import './views/partner-overview';
import './views/partner-clients';
import './views/partner-resources';
import './views/partner-edges';
import './views/partner-revocation';
import './views/partner-audit';
import './views/partner-team';
import './views/partner-settings';

/**
 * The partner console (#549): what a partner's staff see on the partner edge. The shell loads
 * `/partner/api/me` first: a staff member who is not on the team gets the No access page, a
 * suspended partner a banner on every screen, and the level decides what the nav shows. Every
 * URL is `?view=<item>` with the record route (`id`, `edit`, `new`, `layout`) the sections share
 * with the operator console.
 */
@customElement('versola-partner-console')
export class VersolaPartnerConsole extends LitElement {
  @state() private me: PartnerMe | null = null;
  @state() private loaded = false;
  @state() private notAMember: { identifier: string; partner: string } | null = null;
  @state() private loadError = '';
  @state() private currentView: PartnerNavItem = 'overview';
  @state() private route: RecordRoute = EMPTY_RECORD_ROUTE;
  @state() private navOpen = false;
  @query('.main-content') private mainContent?: HTMLElement;
  @query('versola-partner-navigation') private navigationEl?: VersolaPartnerNavigation;
  private unwatchSystemTheme: () => void = () => {};

  private readonly handleKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && this.navOpen) this.closeNav();
  };

  private readonly handlePopState = () => {
    this.loadLocationState();
  };

  static styles = [
    resetStyles,
    theme,
    bannerStyles,
    buttonStyles,
    css`
      :host { display: block; min-height: 100vh; background: var(--bg-page); color: var(--text-primary); }
      .app-layout { display: flex; min-height: 100vh; }
      .app-splash { display: flex; align-items: center; justify-content: center; min-height: 100vh; animation: splash-fade-in 0.4s ease both; }
      @keyframes splash-fade-in { from { opacity: 0; } to { opacity: 1; } }
      @media (prefers-reduced-motion: reduce) { .app-splash { animation: none; } }
      .main-content {
        flex: 1 1 auto;
        padding: 2rem;
        padding-right: calc(2rem + var(--panel-inset, 0rem));
        transition: padding-right 0.15s ease;
        max-width: 1400px;
        min-width: 0;
      }
      .main-content:focus-visible { outline: none; animation: focus-flash 0.6s ease-out; }
      @keyframes focus-flash { from { box-shadow: inset 0 0 0 2px var(--accent); } to { box-shadow: inset 0 0 0 2px transparent; } }
      @media (prefers-reduced-motion: reduce) {
        .main-content { transition: none; }
        .main-content:focus-visible { animation: none; outline: 2px solid var(--accent); outline-offset: -2px; }
      }
      .nav-backdrop { display: none; }
      @media (max-width: 768px) {
        .main-content { padding: 1rem; }
        .nav-backdrop.visible { display: block; position: fixed; inset: 0; z-index: var(--z-backdrop); background: var(--backdrop); }
      }
      .load-error { max-width: 32rem; margin: 10vh auto; padding: var(--spacing-lg); }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    this.loadLocationState();
    void this.loadMe();
    window.addEventListener('popstate', this.handlePopState);
    window.addEventListener('keydown', this.handleKeyDown);
    this.unwatchSystemTheme = watchSystemTheme();
  }

  disconnectedCallback() {
    window.removeEventListener('popstate', this.handlePopState);
    window.removeEventListener('keydown', this.handleKeyDown);
    this.unwatchSystemTheme();
    super.disconnectedCallback();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('currentView') || changed.has('route')) this.persistLocationState();
  }

  private async loadMe() {
    this.loadError = '';
    try {
      this.me = await fetchMe();
      this.notAMember = null;
    } catch (error) {
      if (isPartnerApiError(error, 'not_a_member')) this.notAMember = { identifier: error.identifier ?? 'this account', partner: error.partner ?? '' };
      else this.loadError = error instanceof Error ? error.message : 'Could not reach the partner edge';
    } finally {
      this.loaded = true;
    }
  }

  private loadLocationState() {
    const params = new URL(window.location.href).searchParams;
    const view = params.get('view');
    if (view && (PARTNER_NAV_ITEMS as string[]).includes(view)) this.currentView = view as PartnerNavItem;
    this.route = {
      id: params.get('id'),
      edit: params.get('edit'),
      create: params.get('new') === '1',
      layout: params.get('layout') === 'page' ? 'page' : null,
      tab: params.get('tab'),
      params: {},
    };
  }

  private persistLocationState() {
    const url = new URL(window.location.href);
    url.searchParams.set('view', this.currentView);
    const route = this.route;
    for (const [key, value] of [['id', route.id], ['edit', route.edit], ['new', route.create ? '1' : null], ['layout', route.layout], ['tab', route.tab]] as const) {
      if (value) url.searchParams.set(key, value);
      else url.searchParams.delete(key);
    }
    window.history.replaceState({}, '', url);
  }

  private handleNavChange(e: CustomEvent<{ item: PartnerNavItem }>) {
    this.currentView = e.detail.item;
    this.route = EMPTY_RECORD_ROUTE;
    this.closeNav();
  }

  private handleRecordRoute = (e: CustomEvent<Partial<RecordRoute>>) => {
    const next = { ...this.route, ...e.detail };
    if (e.detail.id !== undefined && e.detail.id !== this.route.id && !('tab' in e.detail)) next.tab = null;
    this.route = next;
  };

  /** A view sends the staff member to another view, to one record of it when `id` is given. */
  private handleNavigateView = (e: CustomEvent<{ view: PartnerNavItem; id?: string; create?: boolean }>) => {
    this.route = { ...EMPTY_RECORD_ROUTE, id: e.detail.id ?? null, create: !!e.detail.create };
    this.currentView = e.detail.view;
  };

  private handleOpenNav = () => {
    this.navOpen = true;
    void this.updateComplete.then(async () => {
      await this.navigationEl?.updateComplete;
      this.navigationEl?.focusClose();
    });
  };

  private closeNav() {
    if (!this.navOpen) return;
    this.navOpen = false;
    void this.updateComplete.then(() => this.mainContent?.focus({ preventScroll: true }));
  }

  private get logoutUrl(): string {
    return PARTNER_LOGOUT_PATH;
  }

  private renderStatusBanner(me: PartnerMe) {
    if (me.partner.status === 'suspended') {
      return html`
        <div class="banner warn" role="alert">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('alert')}${me.partner.operator.name} has suspended you</div>
            <div>Your clients cannot sign anyone in, your edges cannot renew their certificates, and nothing here can be changed until the operator resumes you. Contact ${me.partner.operator.name}.</div>
          </div>
        </div>`;
    }
    if (me.partner.status === 'onboarding') {
      return html`
        <div class="banner info" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('info')}Onboarding: your first edge has not enrolled yet</div>
            <div>Start it with its enrollment token${me.partner.reviewAt ? `; the agreement is reviewed on ${formatDate(me.partner.reviewAt)}` : ''}.</div>
          </div>
        </div>`;
    }
    return '';
  }

  private renderView(me: PartnerMe) {
    if (!canSee(this.currentView, me.staff.level)) {
      return html`
        <content-header title="No access" description="This section is for admins of your organisation."></content-header>`;
    }
    const level = me.staff.level;
    const canEdit = level !== 'viewer' && me.partner.status !== 'suspended';
    const isAdmin = level === 'admin';
    switch (this.currentView) {
      case 'clients':
        return html`<versola-partner-clients .me=${me} .route=${this.route} ?canManage=${canEdit}></versola-partner-clients>`;
      case 'resources':
        return html`<versola-partner-resources .me=${me} .route=${this.route} ?canManage=${canEdit}></versola-partner-resources>`;
      case 'edges':
        return html`<versola-partner-edges .me=${me} .route=${this.route} ?canManage=${canEdit}></versola-partner-edges>`;
      case 'revocation':
        return html`<versola-partner-revocation .me=${me} ?canManage=${isAdmin && me.partner.status !== 'suspended'}></versola-partner-revocation>`;
      case 'audit':
        return html`<versola-partner-audit .me=${me} .route=${this.route}></versola-partner-audit>`;
      case 'team':
        return html`<versola-partner-team .me=${me} .route=${this.route} ?canManage=${isAdmin}></versola-partner-team>`;
      case 'settings':
        return html`<versola-partner-settings .me=${me} .route=${this.route} ?canManage=${isAdmin && me.partner.status !== 'suspended'}></versola-partner-settings>`;
      default:
        return html`<versola-partner-overview .me=${me}></versola-partner-overview>`;
    }
  }

  render() {
    if (!this.loaded) {
      return html`<div class="app-splash" role="status" aria-label="Loading"><versola-logo size="56"></versola-logo></div>`;
    }
    if (this.notAMember !== null) {
      return html`<versola-partner-no-access .identifier=${this.notAMember.identifier} .partnerName=${this.notAMember.partner} .logoutUrl=${this.logoutUrl}></versola-partner-no-access>`;
    }
    const me = this.me;
    if (!me) {
      return html`
        <div class="load-error">
          <div class="banner warn" role="alert">
            <div class="banner-text">
              <div class="banner-title">${statusIcon('alert')}Could not load the partner console</div>
              <div>${this.loadError}</div>
            </div>
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.loaded = false; void this.loadMe(); }}>Retry</button>
          </div>
        </div>`;
    }

    return html`
      <div class="app-layout" @open-nav=${this.handleOpenNav} @close-nav=${() => this.closeNav()}
        @record-route=${this.handleRecordRoute} @navigate-view=${this.handleNavigateView}>
        <div class="nav-backdrop ${this.navOpen ? 'visible' : ''}" @click=${() => this.closeNav()}></div>
        <versola-partner-navigation
          .activeItem=${this.currentView}
          .partnerName=${me.partner.displayName}
          .level=${me.staff.level}
          .staffDisplay=${me.staff.display ?? me.staff.sub}
          .open=${this.navOpen}
          .logoutUrl=${this.logoutUrl}
          @nav-change=${this.handleNavChange}
        ></versola-partner-navigation>
        <main class="main-content" tabindex="-1">
          ${this.renderStatusBanner(me)}
          ${this.renderView(me)}
        </main>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-console': VersolaPartnerConsole;
  }
}
