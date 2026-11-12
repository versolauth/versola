import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme, resetStyles } from '../../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, tagStyles, wizardStyles } from '../../styles/components';
import { statusIcon } from '../../components/status-icon';
import { validateClientId } from '../../utils/validators';
import { partnerAccessTtlError, partnerLimitViolations, redirectUriWithinDomains, ttlCapsLabel, ttlLabel } from '../../utils/partners';
import { createClient, writeErrorMessage } from '../partner-api';
import type { PartnerClient, PartnerClientKind, PartnerMe, PartnerSettings } from '../partner-types';
import { partnerViewStyles } from '../partner-styles';
import '../../components/nav-toggle';

type Step = 1 | 2 | 3;

type Draft = {
  kind: PartnerClientKind | null; id: string; clientName: string; logoUri: string; policyUri: string; tosUri: string;
  redirectUris: string[]; redirectInput: string; scope: string[]; accessTokenTtlMinutes: string; refreshTokenTtlDays: string;
  backChannelLogoutUri: string; authMethod: 'tls_client_auth' | 'private_key_jwt';
};

/** The consent screen as a sketch: the name, the logo letter and the scopes, in the operator's words. Nothing is rendered by central. */
export function consentSketch(name: string, scopes: string[], operator: string, partner: string) {
  const items: Record<string, string> = { profile: 'Your name and profile', email: 'Your email address', phone: 'Your phone number', offline_access: 'Access while you are away' };
  return html`
    <div class="consent-sketch" aria-label="Consent screen, as a sketch">
      <span class="consent-logo" aria-hidden="true">${(name || partner || '?').slice(0, 1).toUpperCase()}</span>
      <h3>${name || partner} wants to sign you in</h3>
      <p>with your ${operator} account</p>
      <ul>${scopes.filter(scope => scope !== 'openid').map(scope => html`<li>${items[scope] ?? scope}</li>`)}</ul>
      <p class="fine">${name || partner} is a partner of ${operator}. You can revoke this later in your account.</p>
    </div>
  `;
}

export const consentSketchStyles = css`
  .consent-sketch {
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-inner);
    padding: var(--spacing-md);
    display: grid;
    gap: 0.5rem;
    max-width: 24rem;
    background: var(--bg-page);
  }
  .consent-sketch h3 { margin: 0; font-size: var(--text-base); }
  .consent-sketch p { margin: 0; font-size: var(--text-sm); color: var(--text-secondary); }
  .consent-sketch ul { margin: 0; padding-left: 1.25rem; font-size: var(--text-sm); }
  .consent-sketch .fine { font-size: var(--text-xs); }
  .consent-logo { width: 2.5rem; height: 2.5rem; border-radius: var(--radius-md); background: var(--accent-subtle); color: var(--accent-text); display: inline-flex; align-items: center; justify-content: center; font-weight: 800; }
`;

/**
 * Create client, the partner's way: the operator's wizard with the operator's choices already
 * made (consent, flow, credential, group), kind web or native only, the limits inline in the
 * fields, and a last step that says what applies at once and what the operator reviews. Ends in
 * the `pending` record (fires `created`).
 */
@customElement('versola-partner-client-wizard')
export class VersolaPartnerClientWizard extends LitElement {
  @property({ attribute: false }) me!: PartnerMe;
  @property({ attribute: false }) existingIds: string[] = [];
  @property({ attribute: false }) defaults: PartnerSettings['defaults'] | null = null;

  @state() private step: Step = 1;
  @state() private submitted = false;
  @state() private draft: Draft = {
    kind: null, id: '', clientName: '', logoUri: '', policyUri: '', tosUri: '', redirectUris: [], redirectInput: '', scope: ['openid'],
    accessTokenTtlMinutes: '5', refreshTokenTtlDays: '14', backChannelLogoutUri: '', authMethod: 'tls_client_auth',
  };
  @state() private redirectError = '';
  @state() private isSaving = false;
  @state() private submitError = '';

  static styles = [
    theme,
    resetStyles,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    wizardStyles,
    partnerViewStyles,
    consentSketchStyles,
    css`
      :host { --compact-field-max-width: 22.8rem; }
      .form-header { display: flex; align-items: center; gap: var(--spacing-md); margin-bottom: var(--spacing-xl); min-width: 0; }
      .form-title { font-size: var(--text-4xl); font-weight: 800; letter-spacing: -0.02em; color: var(--text-primary); margin: 0; }
      .form-subtitle { font-size: var(--text-base); color: var(--text-secondary); margin-top: 0.25rem; }

      .kind-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr)); gap: var(--spacing-md); }
      .kind-card {
        text-align: left;
        background: var(--bg-page);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-lg);
        padding: 1rem;
        cursor: pointer;
        display: grid;
        gap: 0.4rem;
        font: inherit;
        color: var(--text-primary);
      }
      .kind-card:hover { border-color: var(--accent); }
      .kind-card:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
      .kind-card[aria-pressed='true'] { border-color: var(--accent); background: var(--accent-subtle); box-shadow: 0 0 0 2px var(--accent-border); }
      .kind-name { font-size: var(--text-md); font-weight: 600; }
      .kind-blurb { font-size: var(--text-sm); color: var(--text-secondary); line-height: 1.45; }

      .uri-list { display: grid; gap: 0.375rem; margin-top: 0.5rem; }
      .uri-item { display: flex; gap: 0.5rem; align-items: center; font-family: var(--font-mono); font-size: var(--text-sm); overflow-wrap: anywhere; }
      .uri-item.outside { color: var(--warning-text); }
      .inline-row { display: flex; gap: 0.5rem; align-items: flex-start; max-width: min(100%, 28rem); }
      .inline-row input { flex: 1; min-width: 0; max-width: none; }
      .field-pair { display: grid; grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr)); gap: var(--spacing-md); max-width: 22.8rem; }
      .field-pair input { max-width: none; }

    `,
  ];

  private defaultsApplied = false;
  private capsApplied = false;

  private withinCaps(draft: Draft): Draft {
    const { accessTokenTtlMax, refreshTokenTtlMax } = this.limits;
    return {
      ...draft,
      accessTokenTtlMinutes: String(Math.min(Number(draft.accessTokenTtlMinutes), Math.max(1, Math.floor(accessTokenTtlMax / 60)))),
      refreshTokenTtlDays: String(Math.min(Number(draft.refreshTokenTtlDays), Math.max(1, Math.floor(refreshTokenTtlMax / 86_400)))),
    };
  }

  /** The defaults from Settings arrive after the first render when the wizard is the entry URL; they apply once, before anything was typed. */
  willUpdate(changed: Map<string, unknown>) {
    if (changed.has('me') && !this.capsApplied) {
      // The lifetimes start inside the operator's caps, so a partner with a tighter cap than the usual 5 min / 14 days does not open on an error.
      this.capsApplied = true;
      this.draft = this.withinCaps(this.draft);
    }
    if (changed.has('defaults') && this.defaults && !this.defaultsApplied) {
      this.defaultsApplied = true;
      this.draft = {
        ...this.draft,
        accessTokenTtlMinutes: this.defaults.accessTokenTtl ? String(Math.max(1, Math.round(this.defaults.accessTokenTtl / 60))) : this.draft.accessTokenTtlMinutes,
        refreshTokenTtlDays: this.defaults.refreshTokenTtl ? String(Math.max(1, Math.round(this.defaults.refreshTokenTtl / 86_400))) : this.draft.refreshTokenTtlDays,
        backChannelLogoutUri: this.defaults.backChannelLogoutUri ?? '',
      };
      this.draft = this.withinCaps(this.draft);
    }
  }

  private set(patch: Partial<Draft>) {
    this.draft = { ...this.draft, ...patch };
    // A device client's tokens are DPoP-bound, which central refuses under an hour: start it there instead of on a value it would reject.
    if (patch.kind === 'native' && this.accessSeconds < 3600) this.draft = { ...this.draft, accessTokenTtlMinutes: '60' };
  }

  private get limits() {
    return this.me.partner.limits;
  }

  private get outside(): string[] {
    return partnerLimitViolations(this.limits, { redirectUris: this.draft.redirectUris, scope: this.draft.scope, accessTokenTtl: this.accessSeconds, refreshTokenTtl: this.refreshSeconds });
  }

  private get accessSeconds() {
    return Number(this.draft.accessTokenTtlMinutes) * 60;
  }

  private get refreshSeconds() {
    return Number(this.draft.refreshTokenTtlDays) * 86_400;
  }

  private get errors(): Partial<Record<keyof Draft, string>> {
    const errors: Partial<Record<keyof Draft, string>> = {};
    const draft = this.draft;
    if (!draft.id.trim()) errors.id = 'A client ID is required.';
    else if (!validateClientId(draft.id.trim())) errors.id = 'Lowercase letters, digits and hyphens, starting with a letter.';
    else if (this.existingIds.includes(draft.id.trim())) errors.id = 'You already have a client with this ID.';
    if (!draft.clientName.trim()) errors.clientName = 'A display name is required: users see it on the consent screen.';
    if (draft.logoUri.trim() && !/^https:\/\/\S+$/.test(draft.logoUri.trim())) errors.logoUri = 'An https URL.';
    if (draft.redirectUris.length === 0) errors.redirectUris = 'At least one redirect URI.';
    const positive = (value: string) => /^\d+$/.test(value.trim()) && Number(value) > 0;
    if (!positive(draft.accessTokenTtlMinutes)) errors.accessTokenTtlMinutes = 'A whole number of minutes.';
    else if (partnerAccessTtlError(this.accessSeconds, this.limits.accessTokenTtlMax, draft.kind === 'native')) errors.accessTokenTtlMinutes = partnerAccessTtlError(this.accessSeconds, this.limits.accessTokenTtlMax, draft.kind === 'native');
    if (!positive(draft.refreshTokenTtlDays)) errors.refreshTokenTtlDays = 'A whole number of days.';
    else if (this.refreshSeconds > this.limits.refreshTokenTtlMax) errors.refreshTokenTtlDays = `At most ${ttlLabel(this.limits.refreshTokenTtlMax)}: the operator's cap.`;
    if (draft.backChannelLogoutUri.trim() && !/^https:\/\/\S+$/.test(draft.backChannelLogoutUri.trim())) errors.backChannelLogoutUri = 'An https URL.';
    return errors;
  }

  private shown(key: keyof Draft): string | undefined {
    return this.submitted ? this.errors[key] : undefined;
  }

  private addRedirect() {
    const uri = this.draft.redirectInput.trim();
    if (!uri) return;
    if (!/^https:\/\/[^\s]+$/.test(uri)) {
      this.redirectError = 'An https URL.';
      return;
    }
    if (this.draft.redirectUris.includes(uri)) {
      this.redirectError = 'Already in the list.';
      return;
    }
    this.redirectError = '';
    this.set({ redirectUris: [...this.draft.redirectUris, uri], redirectInput: '' });
  }

  private next() {
    this.submitted = true;
    if (Object.keys(this.errors).length > 0) {
      void this.updateComplete.then(() => this.renderRoot.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus());
      return;
    }
    this.step = 3;
  }

  private async submit() {
    if (this.isSaving) return;
    this.isSaving = true;
    this.submitError = '';
    const draft = this.draft;
    try {
      const client = await createClient({
        id: draft.id.trim(), kind: draft.kind ?? 'web', clientName: draft.clientName.trim(), logoUri: draft.logoUri.trim() || null,
        policyUri: draft.policyUri.trim() || null, tosUri: draft.tosUri.trim() || null, redirectUris: draft.redirectUris, scope: draft.scope,
        accessTokenTtl: this.accessSeconds, refreshTokenTtl: this.refreshSeconds, backChannelLogoutUri: draft.backChannelLogoutUri.trim() || null,
        authMethod: draft.authMethod,
      });
      this.dispatchEvent(new CustomEvent<PartnerClient>('created', { detail: client, bubbles: true, composed: true }));
    } catch (error) {
      this.submitError = writeErrorMessage(error);
    } finally {
      this.isSaving = false;
    }
  }

  // ── rendering ──

  private renderStepper() {
    const labels = ['Kind', 'Basics & sign-in', 'Review & submit'];
    return html`<div class="wizard-steps">${labels.map((label, index) => {
      const step = index + 1;
      const state = step === this.step ? 'active' : step < this.step ? 'done' : '';
      return html`${index > 0 ? html`<span class="wizard-step-sep"></span>` : ''}
        <span class="wizard-step ${state}" aria-current=${step === this.step ? 'step' : 'false'}><span class="wizard-step-dot">${step < this.step ? '✓' : step}</span>${label}</span>`;
    })}</div>`;
  }

  private lock(text: string, why: string) {
    return html`<div class="plan-line"><span class="plan-mark">${statusIcon('lock', '', 14)}</span><span><span>${text}</span><div class="plan-why">${why}</div></span></div>`;
  }

  private fixedRows() {
    const me = this.me;
    return html`<div class="plan">
      ${this.lock('Flow: authorization code + PKCE, PAR required', 'Set by the operator for every partner client.')}
      ${this.lock(`Consent: always shown, names ${me.partner.displayName}`, 'Users see who they sign in to.')}
      ${this.lock(this.draft.authMethod === 'tls_client_auth' ? `Client authentication: tls_client_auth with your group certificate (urn:versola:edge-group:${me.partner.id})` : 'Client authentication: private_key_jwt with the keys your edge holds', 'Your edges authenticate as this client; nothing to copy.')}
      ${this.lock(`Served by the edges of group ${me.partner.id}`, 'Your own edges only.')}
      ${this.draft.kind === 'native' ? this.lock('DPoP-bound access tokens', `The device holds its tokens, so DPoP is their only sender-constraint. They live at least ${ttlLabel(3600)}, as for every client on this server.`) : ''}
    </div>`;
  }

  private renderKindStep() {
    const kinds: Array<{ id: PartnerClientKind; name: string; blurb: string; examples: string }> = [
      { id: 'web', name: 'Web app', blurb: 'A site with a backend, served by your edge. Users sign in; tokens stay in the edge session.', examples: 'Checkout, dashboard, portal' },
      { id: 'native', name: 'Mobile or desktop app', blurb: 'An app installed on a device, signing in through your edge by a verified App Link.', examples: 'iOS, Android, Electron' },
    ];
    return html`
      <div class="wizard-head"><div class="wizard-panel-title">What are you building?</div><div class="wizard-panel-sub">A service app with no user is not available to a partner: your clients sign the operator's users in.</div></div>
      <div class="kind-grid">${kinds.map(kind => html`
        <button type="button" class="kind-card" aria-pressed=${this.draft.kind === kind.id} @click=${() => this.set({ kind: kind.id })}>
          <span class="kind-name">${kind.name}</span><span class="kind-blurb">${kind.blurb}</span><span class="kind-blurb">${kind.examples}</span>
        </button>`)}</div>
      <div style="margin-top:var(--spacing-lg)">${this.fixedRows()}</div>
      <div class="form-actions">
        <span class="note">Step 1 of 3 · a lock marks what the operator set</span>
        <button type="button" class="btn btn-secondary" @click=${() => this.dispatchEvent(new CustomEvent('cancel', { bubbles: true, composed: true }))}>Cancel</button>
        <button type="button" class="btn btn-primary" ?disabled=${!this.draft.kind} @click=${() => (this.step = 2)}>Continue to basics</button>
      </div>
    `;
  }

  private field(key: keyof Draft, label: string, hint: string, options: { type?: string; placeholder?: string } = {}) {
    const error = this.shown(key);
    const id = `client-${key}`;
    return html`
      <div class="form-group">
        <label for=${id}>${label}</label>
        <input type=${options.type ?? 'text'} id=${id} class="compact-input ${error ? 'input-error' : ''}" .value=${String(this.draft[key])} placeholder=${options.placeholder ?? ''}
          aria-invalid=${error ? 'true' : nothing} aria-describedby=${`${id}-hint`}
          @input=${(e: Event) => this.set({ [key]: (e.target as HTMLInputElement).value } as Partial<Draft>)} />
        ${error ? html`<div class="error-message" id=${`${id}-hint`} role="alert">${error}</div>` : html`<div class="hint" id=${`${id}-hint`}>${hint}</div>`}
      </div>`;
  }

  private renderBasicsStep() {
    const me = this.me;
    const limits = this.limits;
    const draft = this.draft;
    const outside = this.outside;
    const redirectOutside = draft.redirectUris.filter(uri => !redirectUriWithinDomains(uri, limits.redirectDomains));
    const redirectError = this.redirectError || this.shown('redirectUris');
    return html`
      <div class="wizard-head"><div class="wizard-panel-title">The basics and sign-in</div><div class="wizard-panel-sub">What is inside your limits applies as soon as the operator approves the client; the operator reviews the name and logo, and anything outside the limits.</div></div>
      <div class="layout with-aside">
        <div>
          <div class="editor-box">
            <h3>Application</h3>
            ${this.field('id', 'Client ID *', 'Lowercase letters, digits and hyphens. Fixed once created.', { placeholder: 'pilot-pay-shop' })}
            ${this.field('clientName', 'Display name *', 'Shown on the consent screen. The operator approves it.', { placeholder: me.partner.displayName })}
            ${this.field('logoUri', 'Logo URL', 'Shown on the consent screen; the operator approves any change. Optional.', { type: 'url', placeholder: 'https://' })}
            <div class="field-pair">
              ${this.field('policyUri', 'Privacy policy URL', 'Linked from the consent screen. Optional.', { type: 'url' })}
              ${this.field('tosUri', 'Terms URL', 'Optional.', { type: 'url' })}
            </div>
          </div>
          <div class="editor-box">
            <h3>Sign-in</h3>
            <p class="editor-sub">Within your limits; the operator's rules (passkeys, OTP, step-up) apply on top.</p>
            <div class="form-group">
              <label for="client-redirect">Redirect URIs *</label>
              <div class="inline-row">
                <input type="url" id="client-redirect" class="${redirectError ? 'input-error' : ''}" .value=${draft.redirectInput} placeholder=${`https://app.${limits.redirectDomains[0] ?? 'example'}/complete`}
                  aria-invalid=${redirectError ? 'true' : nothing} aria-describedby="client-redirect-hint"
                  @input=${(e: Event) => { this.redirectError = ''; this.set({ redirectInput: (e.target as HTMLInputElement).value }); }}
                  @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter') { e.preventDefault(); this.addRedirect(); } }} />
                <button type="button" class="btn btn-secondary" @click=${() => this.addRedirect()}>Add</button>
              </div>
              ${redirectError ? html`<div class="error-message" id="client-redirect-hint" role="alert">${redirectError}</div>`
                : html`<div class="hint" id="client-redirect-hint">https, under ${limits.redirectDomains.join(', ')}. Press Enter to add another.</div>`}
              ${draft.redirectUris.length ? html`<div class="uri-list">${draft.redirectUris.map(uri => {
                const bad = !redirectUriWithinDomains(uri, limits.redirectDomains);
                return html`<div class="uri-item ${bad ? 'outside' : ''}">
                  <span>${uri}</span>
                  ${bad ? html`<span class="chip warn">outside your domains</span>` : ''}
                  <button type="button" class="btn btn-secondary btn-sm" aria-label=${`Remove ${uri}`} @click=${() => this.set({ redirectUris: draft.redirectUris.filter(candidate => candidate !== uri) })}>Remove</button>
                </div>`;
              })}</div>` : ''}
              ${redirectOutside.length ? html`<div class="hint" style="color:var(--warning-text)">${redirectOutside.length === 1 ? 'One URI is' : `${redirectOutside.length} URIs are`} outside your redirect domains (${limits.redirectDomains.join(', ')}). Keep ${redirectOutside.length === 1 ? 'it' : 'them'} and the client waits for the operator, or remove ${redirectOutside.length === 1 ? 'it' : 'them'}.</div>` : ''}
            </div>
            <div class="form-group">
              <span class="checkbox-list" role="group" aria-labelledby="client-scopes-label">
                <label id="client-scopes-label">Scopes</label>
                ${limits.scopes.map(scope => html`
                  <label class="checkbox-row">
                    <input type="checkbox" .checked=${scope === 'openid' || draft.scope.includes(scope)} ?disabled=${scope === 'openid'}
                      @change=${(e: Event) => {
                        const checked = (e.target as HTMLInputElement).checked;
                        this.set({ scope: checked ? [...draft.scope, scope] : draft.scope.filter(candidate => candidate !== scope) });
                      }} />
                    <span class="mono">${scope}</span>${scope === 'openid' ? html`<span class="muted small">required</span>` : ''}
                  </label>`)}
              </span>
              <div class="hint">Only the scopes ${me.partner.operator.name} allowed you are listed. Others can be requested from the operator.</div>
            </div>
            <div class="field-pair">
              ${this.field('accessTokenTtlMinutes', 'Access token, minutes *', `Cap: ${ttlLabel(limits.accessTokenTtlMax)}.`, { type: 'number' })}
              ${this.field('refreshTokenTtlDays', 'Refresh token, days *', `Cap: ${ttlLabel(limits.refreshTokenTtlMax)}.`, { type: 'number' })}
            </div>
            ${this.field('backChannelLogoutUri', 'Back-channel logout URI', 'Where auth tells your edge that a user signed out everywhere. Defaults to your edge\'s public URL.', { type: 'url' })}
            ${limits.clientAuthMethods.length > 1 ? html`
              <div class="form-group">
                <label id="client-auth-label">Client authentication</label>
                <div class="seg" role="group" aria-labelledby="client-auth-label">
                  <button type="button" aria-pressed=${draft.authMethod === 'tls_client_auth'} @click=${() => this.set({ authMethod: 'tls_client_auth' })}>group certificate</button>
                  <button type="button" aria-pressed=${draft.authMethod === 'private_key_jwt'} @click=${() => this.set({ authMethod: 'private_key_jwt' })}>private_key_jwt</button>
                </div>
                <div class="hint">Both are allowed by the operator; your edges present whichever you choose.</div>
              </div>` : ''}
            ${this.fixedRows()}
          </div>
          <div class="form-actions">
            <span class="note">Step 2 of 3 · ${ttlCapsLabel(limits)}</span>
            <button type="button" class="btn btn-secondary" @click=${() => (this.step = 1)}>Back</button>
            <button type="button" class="btn btn-primary" @click=${() => this.next()}>Continue to review</button>
          </div>
        </div>
        <div class="aside">
          <section class="review-group" aria-label="What the user will see">
            <div class="review-head"><h2>What the user will see</h2></div>
            ${consentSketch(draft.clientName.trim(), draft.scope, me.partner.operator.name, me.partner.displayName)}
          </section>
          <section class="review-group" aria-label="What happens on submit">
            <div class="review-head"><h2>What happens on submit</h2></div>
            <div class="review-row"><span>Registered with the operator, bound to your group</span><span class="chip ok">at once</span></div>
            <div class="review-row"><span>Display name${draft.logoUri.trim() ? ' and logo' : ''}</span><span class="chip warn">operator</span></div>
            ${outside.map(field => html`<div class="review-row"><span>${field === 'redirectUris' ? 'Redirect URI outside your domains' : field === 'scope' ? 'A scope outside your list' : `${field} above the cap`}</span><span class="chip warn">operator</span></div>`)}
            <div class="review-row"><span>Synced to your edges</span><span class="chip">after approval</span></div>
          </section>
        </div>
      </div>
    `;
  }

  private reviewGroup(title: string, step: Step, rows: Array<[string, unknown]>) {
    return html`
      <section class="review-group" aria-label=${title}>
        <div class="review-head"><h2>${title}</h2><button type="button" class="review-change" aria-label=${`Change ${title.toLowerCase()}`} @click=${() => (this.step = step)}>Change</button></div>
        ${rows.map(([label, value]) => html`<div class="review-row"><span class="review-label">${label}</span><span class="review-value">${value}</span></div>`)}
      </section>`;
  }

  private renderReviewStep() {
    const draft = this.draft;
    const me = this.me;
    const outside = this.outside;
    return html`
      <div class="wizard-head"><div class="wizard-panel-title">Review and submit</div><div class="wizard-panel-sub">The client is registered now and waits for ${me.partner.operator.name}: the operator will review the name and logo${outside.length ? ', and what is outside your limits' : ''}. It cannot sign anyone in until then.</div></div>
      <div class="layout with-aside">
        <div>
          ${this.reviewGroup('Kind', 1, [['Kind', draft.kind === 'native' ? 'Mobile or desktop app' : 'Web app']])}
          ${this.reviewGroup('Application', 2, [
            ['Client ID', html`<span class="mono">${draft.id.trim()}</span>`],
            ['Display name', html`${draft.clientName.trim()} <span class="chip warn">operator</span>`],
            ['Logo', draft.logoUri.trim() ? html`<span class="mono">${draft.logoUri.trim()}</span> <span class="chip warn">operator</span>` : html`<span class="muted">None</span>`],
          ])}
          ${this.reviewGroup('Sign-in', 2, [
            ['Redirect URIs', html`<div class="uri-list">${draft.redirectUris.map(uri => html`<div class="uri-item">${uri}${redirectUriWithinDomains(uri, this.limits.redirectDomains) ? '' : html`<span class="chip warn">operator</span>`}</div>`)}</div>`],
            ['Scopes', html`<div class="tag-list">${draft.scope.map(scope => html`<span class="tag">${scope}</span>`)}</div>`],
            ['Lifetimes', `access ${ttlLabel(this.accessSeconds)} · refresh ${ttlLabel(this.refreshSeconds)}`],
            ['Back-channel logout', draft.backChannelLogoutUri.trim() ? html`<span class="mono">${draft.backChannelLogoutUri.trim()}</span>` : html`<span class="muted">None</span>`],
            ['Client authentication', draft.authMethod === 'tls_client_auth' ? 'group certificate' : 'private_key_jwt'],
          ])}
          ${this.submitError ? html`<div class="editor-error" role="alert">${this.submitError}</div>` : ''}
        </div>
        <div class="aside">
          <section class="review-group" aria-label="What the user will see">
            <div class="review-head"><h2>What the user will see</h2></div>
            ${consentSketch(draft.clientName.trim(), draft.scope, me.partner.operator.name, me.partner.displayName)}
          </section>
        </div>
      </div>
      <div class="form-actions">
        <span class="note">Step 3 of 3 · the operator will review the name and logo</span>
        <button type="button" class="btn btn-secondary" @click=${() => (this.step = 2)} ?disabled=${this.isSaving}>Back</button>
        <button type="button" class="btn btn-primary" @click=${() => this.submit()} ?disabled=${this.isSaving}>${this.isSaving ? 'Submitting…' : 'Submit for approval'}</button>
      </div>
    `;
  }

  render() {
    return html`
      <div class="form-header">
        <versola-nav-toggle></versola-nav-toggle>
        <div>
          <h1 class="form-title">Create client</h1>
          <div class="form-subtitle">The operator's wizard with the operator's choices already made. The client waits for ${this.me.partner.operator.name}'s approval.</div>
        </div>
      </div>
      <div class="card">
        ${this.renderStepper()}
        ${this.step === 1 ? this.renderKindStep() : this.step === 2 ? this.renderBasicsStep() : this.renderReviewStep()}
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-client-wizard': VersolaPartnerClientWizard;
  }
}
