import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme, resetStyles } from '../../styles/theme';
import { bannerStyles, buttonStyles } from '../../styles/components';
import { statusIcon } from '../../components/status-icon';
import { PARTNER_LOGIN_PATH, PARTNER_LOGOUT_PATH } from '../partner-api';
import '../../components/versola-logo';

/**
 * The full-page state of `/partner/api/me` answering 403 `not_a_member` (#549 decision 3): the
 * person signed in with the operator's account, but the partner's edge has no row for them. Nothing
 * about them went anywhere; the attempt is in the partner's audit log as a refused sign-in.
 */
@customElement('versola-partner-no-access')
export class VersolaPartnerNoAccess extends LitElement {
  @property({ type: String }) identifier = '';
  @property({ type: String }) partnerName = '';
  @property({ type: String }) logoutUrl = PARTNER_LOGOUT_PATH;

  static styles = [
    resetStyles,
    theme,
    bannerStyles,
    buttonStyles,
    css`
      :host { display: block; min-height: 100vh; background: var(--bg-page); color: var(--text-primary); }
      .center { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: var(--spacing-xl) var(--spacing-md); }
      .sheet {
        width: min(100%, 30rem);
        background: var(--surface);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-sheet);
        padding: var(--spacing-xl);
      }
      .brand { display: flex; align-items: center; gap: 0.625rem; padding-bottom: var(--spacing-lg); }
      .brand-scope { font-size: var(--text-base); font-weight: 600; color: var(--text-primary); }
      h1 { font-size: var(--text-2xl); font-weight: 800; letter-spacing: -0.02em; margin: 0 0 var(--spacing-sm); }
      p { margin: 0; font-size: var(--text-base); color: var(--text-secondary); line-height: 1.5; }
      .banner { margin: var(--spacing-lg) 0; }
      .actions { display: flex; justify-content: flex-end; gap: 0.75rem; }
    `,
  ];

  render() {
    const partner = this.partnerName || 'this organisation';
    return html`
      <div class="center">
        <div class="sheet" role="main">
          <div class="brand"><versola-logo size="32"></versola-logo><span class="brand-scope">Partner console</span></div>
          <h1>You are signed in, but not on ${partner}'s team</h1>
          <p>You signed in as <b>${this.identifier}</b>, which is not on ${partner}'s team. Ask an admin of ${partner} to invite this phone number or your email, then sign in again.</p>
          <div class="banner info" role="status">
            <div class="banner-text">
              <div class="banner-title">${statusIcon('info')}Nothing about you was sent anywhere</div>
              <div>Membership is checked on ${partner}'s own edge. This attempt is in its audit log as a refused sign-in.</div>
            </div>
          </div>
          <div class="actions">
            <a class="btn btn-secondary" href=${this.logoutUrl}>Log out</a>
            <a class="btn btn-primary" href=${PARTNER_LOGIN_PATH}>Try again</a>
          </div>
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-no-access': VersolaPartnerNoAccess;
  }
}
