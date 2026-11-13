import { LitElement, html, css } from 'lit';
import { customElement, property, query } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import type { StaffLevel } from './partner-types';
import '../components/versola-logo';
import '../components/versola-wordmark';
import '../components/theme-switcher';

export type PartnerNavItem = 'overview' | 'clients' | 'resources' | 'edges' | 'revocation' | 'audit' | 'team' | 'settings';

export const PARTNER_NAV_ITEMS: PartnerNavItem[] = ['overview', 'clients', 'resources', 'edges', 'revocation', 'audit', 'team', 'settings'];

/** Which items a staff level sees (#549 decision 3): a viewer and a developer never see Team or Settings. */
export function canSee(item: PartnerNavItem, level: StaffLevel): boolean {
  if (item === 'team' || item === 'settings') return level === 'admin';
  return true;
}

/**
 * The partner console's sidebar: the operator's shell without a tenant selector, with the brand
 * card `<partner>` / `Partner console`, the nav (Overview … Audit, then Organisation: Team, Settings,
 * hidden by level), the theme switcher and Log out, which goes to the edge's logout.
 */
@customElement('versola-partner-navigation')
export class VersolaPartnerNavigation extends LitElement {
  @property({ type: String }) activeItem: PartnerNavItem = 'overview';
  @property({ type: String }) partnerName = '';
  @property({ type: String }) level: StaffLevel = 'viewer';
  @property({ type: String }) staffDisplay = '';
  @property({ type: Boolean, reflect: true }) open = false;
  @property({ type: String }) logoutUrl = '/partner/logout';
  @query('.brand-close') private closeButton?: HTMLButtonElement;

  focusClose() {
    this.closeButton?.focus();
  }

  static styles = [
    theme,
    css`
      :host { display: block; width: 250px; flex: none; align-self: stretch; }

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

      .nav-scroll { flex: 1 1 auto; overflow-y: auto; min-height: 0; scrollbar-width: thin; scrollbar-gutter: stable; overscroll-behavior: contain; }

      @media (max-width: 768px) {
        :host {
          --drawer-duration: 0.15s;
          position: fixed;
          top: 0;
          left: 0;
          width: 250px;
          height: 100vh;
          z-index: var(--z-drawer);
          transform: translateX(-100%);
          visibility: hidden;
          transition: transform var(--drawer-duration) ease, visibility 0s linear var(--drawer-duration);
          box-shadow: var(--shadow-lg);
        }
        .nav-panel { position: static; height: 100%; }
        :host([open]) { transform: translateX(0); visibility: visible; transition: transform var(--drawer-duration) ease, visibility 0s; }
      }

      @media (prefers-reduced-motion: reduce) {
        :host, :host([open]) { transition: none; }
      }

      .brand {
        display: flex;
        align-items: center;
        gap: 0.625rem;
        padding: 1.125rem 1.5rem 1rem;
        border-bottom: 1px solid var(--border-default);
      }

      /* Whose console this is: where the operator's shell has its tenant selector, a card of the same
         weight with the partner's initial, its name and what this is. Read-only, so no chevron. */
      .partner-section { padding: 0.75rem var(--spacing-lg) 0.25rem; }
      .partner {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        padding: 0.625rem 0.875rem;
        background: var(--field);
        border: 1.5px solid var(--border-input);
        border-radius: var(--radius-md);
        min-width: 0;
      }
      .partner-mark {
        flex: none;
        width: 2rem;
        height: 2rem;
        border-radius: var(--radius-md);
        background: var(--accent-subtle);
        color: var(--accent-text);
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-weight: 700;
        font-size: var(--text-base);
      }
      .partner-text { display: grid; min-width: 0; line-height: 1.3; }
      .partner-name { font-size: var(--text-base); font-weight: 600; color: var(--text-primary); overflow-wrap: anywhere; }
      .partner-kind { font-size: var(--text-sm); color: var(--text-secondary); }

      .brand-close { display: none; }
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
        }
        .brand-close:hover { border-color: var(--text-primary); color: var(--text-primary); }
        .nav-item { min-height: 2.75rem; padding-top: 0.625rem; padding-bottom: 0.625rem; }
      }

      .who {
        padding: 0.75rem var(--spacing-lg) var(--spacing-md);
        border-bottom: 1px solid var(--border-default);
        font-size: var(--text-sm);
        color: var(--text-secondary);
        display: grid;
        gap: 0.125rem;
      }
      .who b { color: var(--text-primary); font-weight: 600; overflow-wrap: anywhere; }

      .nav { padding: 0.5rem 1rem; }
      .nav-section { margin-bottom: 0.75rem; }
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
      .nav-item:hover { background: var(--sidebar-hover-bg); color: var(--text-primary); }
      .nav-item.active, .nav-item.active:hover {
        background: var(--accent-subtle);
        color: var(--accent-text);
        box-shadow: inset 3px 0 0 var(--accent);
        border-radius: 0 var(--radius-md) var(--radius-md) 0;
      }
      .nav-item:focus-visible, .brand-close:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: -2px; }

      .nav-footer { flex: none; padding: 0.5rem 1rem; border-top: 1px solid var(--border-default); background: var(--sidebar-bg); }
      .nav-footer .nav-item { margin-bottom: 0; }
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

  private navItem(item: PartnerNavItem, label: string) {
    if (!canSee(item, this.level)) return html``;
    const active = this.activeItem === item;
    return html`
      <button type="button" class="nav-item ${active ? 'active' : ''}" aria-current=${active ? 'page' : 'false'}
        @click=${() => this.dispatchEvent(new CustomEvent('nav-change', { detail: { item }, bubbles: true, composed: true }))}>${label}</button>
    `;
  }

  render() {
    return html`
      <div class="nav-panel">
        <div class="nav-scroll">
          <div class="brand">
            <versola-logo size="36"></versola-logo>
            <versola-wordmark></versola-wordmark>
            <button type="button" class="brand-close" aria-label="Close navigation menu"
              @click=${() => this.dispatchEvent(new CustomEvent('close-nav', { bubbles: true, composed: true }))}>✕</button>
          </div>

          <div class="partner-section">
            <div class="partner">
              <span class="partner-mark" aria-hidden="true">${(this.partnerName || '?').slice(0, 1).toUpperCase()}</span>
              <span class="partner-text"><span class="partner-name">${this.partnerName}</span><span class="partner-kind">Partner console</span></span>
            </div>
          </div>

          <div class="who"><b>${this.staffDisplay}</b><span>${this.level}</span></div>

          <nav class="nav" aria-label="Sections">
            <div class="nav-section">
              <div class="nav-section-title">Partner</div>
              ${this.navItem('overview', 'Overview')}
              ${this.navItem('clients', 'Clients')}
              ${this.navItem('resources', 'Resources')}
              ${this.navItem('edges', 'Edges')}
              ${this.navItem('revocation', 'Sessions & revocation')}
              ${this.navItem('audit', 'Audit')}
            </div>
            ${this.level === 'admin' ? html`
              <div class="nav-section">
                <div class="nav-section-title">Organisation</div>
                ${this.navItem('team', 'Team')}
                ${this.navItem('settings', 'Settings')}
              </div>` : ''}
          </nav>
        </div>

        <div class="nav-footer">
          <div class="theme-row">
            <span>Theme</span>
            <versola-theme-switcher></versola-theme-switcher>
          </div>
          <a class="nav-item" href=${this.logoutUrl}>Log out</a>
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-navigation': VersolaPartnerNavigation;
  }
}
