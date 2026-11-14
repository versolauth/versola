import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme, resetStyles } from '../styles/theme';
import { buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, tagStyles, wizardStyles } from '../styles/components';
import type { CreatedPartner, OAuthScope, PartnerLimits } from '../types';
import { createPartner } from '../utils/central-api';
import { validateEdgeId } from '../utils/validators';
import { defaultPartnerLimits, redirectUriWithinDomains, ttlCapsLabel, validatePartnerId } from '../utils/partners';
import { statusIcon } from './status-icon';
import './nav-toggle';

type Step = 1 | 2 | 3 | 4;

type Draft = {
  id: string; displayName: string; logoUri: string; email: string; phone: string; reviewAt: string;
  scopes: string[]; redirectDomains: string; maxClients: string; maxEdges: string; accessTokenTtlMinutes: string; refreshTokenTtlDays: string;
  consoleUrl: string; edgeId: string; edgePublicUrl: string;
};

const STEP_LABELS = ['Organisation', 'Limits', 'Edge & console', 'Review'];

/**
 * Add partner (#549): four steps on the client wizard's shell. One request creates the partner,
 * its edge group, its console client, its first edge and the enrollment token; the list shows the
 * token once, on the new record. Fires `created` with what central
 * answered, `cancel` to go back to the list.
 */
@customElement('versola-partner-wizard')
export class VersolaPartnerWizard extends LitElement {
  @property({ type: String }) tenantId = '';
  @property({ attribute: false }) scopes: OAuthScope[] = [];
  @property({ attribute: false }) existingPartnerIds: string[] = [];
  @property({ attribute: false }) existingEdgeIds: string[] = [];

  @state() private step: Step = 1;
  @state() private submitted: Record<number, boolean> = {};
  @state() private draft: Draft = {
    id: '', displayName: '', logoUri: '', email: '', phone: '', reviewAt: '',
    scopes: ['openid'], redirectDomains: '', maxClients: '10', maxEdges: '3', accessTokenTtlMinutes: '10', refreshTokenTtlDays: '30',
    consoleUrl: '', edgeId: '', edgePublicUrl: '',
  };
  @state() private isSaving = false;
  @state() private submitError = '';

  static styles = [
    theme,
    resetStyles,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    formStyles,
    editorStyles,
    wizardStyles,
    css`
      :host { display: block; --compact-field-max-width: 22.8rem; --wizard-aside-width: 22rem; }

      .form-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--spacing-xl); }
      .form-title { font-size: var(--text-4xl); font-weight: 800; letter-spacing: -0.02em; color: var(--text-primary); margin: 0; }
      .form-subtitle { font-size: var(--text-base); color: var(--text-secondary); margin-top: 0.25rem; }

      .form-actions { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: center; justify-content: flex-end; margin-top: var(--spacing-lg); padding-top: var(--spacing-lg); border-top: 1px solid var(--border-default); }
      .wizard-actions-note { margin-right: auto; font-size: var(--text-sm); color: var(--text-secondary); }

      .mono { font-family: var(--font-mono); font-size: var(--text-sm); overflow-wrap: anywhere; }
      .muted { color: var(--text-secondary); }
      .small { font-size: var(--text-sm); }

      .checkbox-list { display: grid; gap: 0.375rem; }
      .checkbox-row { display: flex; gap: 0.625rem; align-items: center; font-size: var(--text-base); color: var(--text-primary); text-transform: none; letter-spacing: 0; font-weight: 400; margin: 0; cursor: pointer; }
      .checkbox-row input { width: 1rem; height: 1rem; margin: 0; accent-color: var(--accent); flex: none; }
      .checkbox-row input:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
      .checkbox-row .mono { font-size: var(--text-base); }

      .field-pair { display: grid; grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr)); gap: var(--spacing-md); max-width: 22.8rem; }
      .field-pair input { max-width: none; }
      input[type='date'] { width: 100%; max-width: min(100%, 22.8rem); padding: 0.6875rem 1rem; background: var(--field); border: 1.5px solid var(--border-input); border-radius: var(--radius-md); color: var(--text-primary); font-family: var(--font-family); font-size: var(--text-base); }
      input[type='date']:focus { outline: 2px solid var(--focus-ring); outline-offset: 2px; background: var(--surface); border-color: var(--text-primary); }
      input[readonly] { color: var(--text-secondary); }
      .input-with-prefix { display: flex; align-items: stretch; max-width: min(100%, 22.8rem); }
      .input-prefix { display: flex; align-items: center; padding: 0 0.75rem; background: var(--surface-inset); border: 1.5px solid var(--border-input); border-right: 0; border-radius: var(--radius-md) 0 0 var(--radius-md); color: var(--text-secondary); font-family: var(--font-mono); font-size: var(--text-base); user-select: none; }
      .input-with-prefix input { border-radius: 0 var(--radius-md) var(--radius-md) 0; flex: 1; min-width: 0; max-width: none; }

    `,
  ];

  private set(patch: Partial<Draft>) {
    this.draft = { ...this.draft, ...patch };
  }

  // ── validation ──

  private get domains(): string[] {
    return this.draft.redirectDomains.split('\n').map(line => line.trim().toLowerCase()).filter(Boolean);
  }

  private get step1Errors(): Partial<Record<keyof Draft, string>> {
    const errors: Partial<Record<keyof Draft, string>> = {};
    const draft = this.draft;
    const id = draft.id.trim();
    if (!id) errors.id = 'A partner ID is required.';
    else if (!validatePartnerId(id)) errors.id = 'A slug: lowercase letters, digits and dashes, no dots or colons.';
    else if (this.existingPartnerIds.includes(id)) errors.id = 'A partner with this ID already exists.';
    if (!draft.displayName.trim()) errors.displayName = 'A display name is required: users see it on the consent screen.';
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(draft.email.trim())) errors.email = 'A contact email is required.';
    if (draft.logoUri.trim() && !/^https:\/\/\S+$/.test(draft.logoUri.trim())) errors.logoUri = 'The logo is an https URL.';
    return errors;
  }

  private get step2Errors(): Partial<Record<keyof Draft, string>> {
    const errors: Partial<Record<keyof Draft, string>> = {};
    const draft = this.draft;
    const domains = this.domains;
    if (domains.length === 0) errors.redirectDomains = 'At least one domain: a partner client cannot redirect anywhere otherwise.';
    else if (domains.some(domain => !/^(?!-)[a-z0-9-]+(\.[a-z0-9-]+)+$/.test(domain))) errors.redirectDomains = 'One domain per line, such as pilotpay.example. No scheme, no path.';
    const positive = (value: string) => /^\d+$/.test(value.trim()) && Number(value) > 0;
    if (!positive(draft.maxClients)) errors.maxClients = 'A whole number above zero.';
    if (!positive(draft.maxEdges)) errors.maxEdges = 'A whole number above zero.';
    if (!positive(draft.accessTokenTtlMinutes) || Number(draft.accessTokenTtlMinutes) > 24 * 60) errors.accessTokenTtlMinutes = 'Between 1 minute and 24 hours.';
    if (!positive(draft.refreshTokenTtlDays) || Number(draft.refreshTokenTtlDays) > 365) errors.refreshTokenTtlDays = 'Between 1 and 365 days.';
    return errors;
  }

  private get step3Errors(): Partial<Record<keyof Draft, string>> {
    const errors: Partial<Record<keyof Draft, string>> = {};
    const draft = this.draft;
    const consoleUrl = draft.consoleUrl.trim();
    if (!consoleUrl) errors.consoleUrl = 'The URL the partner console is served at.';
    else if (!redirectUriWithinDomains(consoleUrl, this.domains)) errors.consoleUrl = `An https URL under one of the redirect domains (${this.domains.join(', ') || 'none yet'}).`;
    const edgeId = this.edgeId;
    if (!draft.edgeId.trim()) errors.edgeId = 'An edge ID is required.';
    else if (!validateEdgeId(edgeId)) errors.edgeId = 'Lowercase letters, digits and dashes.';
    else if (this.existingEdgeIds.includes(edgeId)) errors.edgeId = 'An edge with this ID already exists.';
    const publicUrl = draft.edgePublicUrl.trim();
    if (!publicUrl) errors.edgePublicUrl = "The edge's public URL, where users and back-channel logout reach it.";
    else if (!/^https:\/\/[^\s/]+\/?$/.test(publicUrl)) errors.edgePublicUrl = 'An https origin, such as https://login.pilotpay.example.';
    return errors;
  }

  private errorsOf(step: Step) {
    return step === 1 ? this.step1Errors : step === 2 ? this.step2Errors : step === 3 ? this.step3Errors : {};
  }

  private shownErrors(step: Step): Partial<Record<keyof Draft, string>> {
    return this.submitted[step] ? this.errorsOf(step) : {};
  }

  private next(step: Step) {
    this.submitted = { ...this.submitted, [step]: true };
    if (Object.keys(this.errorsOf(step)).length > 0) {
      void this.updateComplete.then(() => this.renderRoot.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus());
      return;
    }
    this.step = (step + 1) as Step;
  }

  private get limits(): PartnerLimits {
    const draft = this.draft;
    return {
      ...defaultPartnerLimits(),
      scopes: [...new Set(['openid', ...draft.scopes])],
      redirectDomains: this.domains,
      maxClients: Number(draft.maxClients),
      maxEdges: Number(draft.maxEdges),
      accessTokenTtlMax: Number(draft.accessTokenTtlMinutes) * 60,
      refreshTokenTtlMax: Number(draft.refreshTokenTtlDays) * 86_400,
      clientAuthMethods: ['tls_client_auth'],
    };
  }

  /** Every edge is registered as `edge-<suffix>`, as the Edges view does. */
  private get edgeId(): string {
    return `edge-${this.draft.edgeId.trim()}`;
  }

  private get consoleRedirectUri(): string {
    return `${this.draft.consoleUrl.trim().replace(/\/$/, '')}/complete`;
  }

  private async handleSubmit() {
    if (this.isSaving) return;
    for (const step of [1, 2, 3] as const) {
      if (Object.keys(this.errorsOf(step)).length > 0) {
        this.submitted = { ...this.submitted, [step]: true };
        this.step = step;
        return;
      }
    }
    this.isSaving = true;
    this.submitError = '';
    const draft = this.draft;
    try {
      const created = await createPartner({
        partner: {
          id: draft.id.trim(),
          tenantId: this.tenantId,
          displayName: draft.displayName.trim(),
          logoUri: draft.logoUri.trim() || null,
          contact: { email: draft.email.trim(), phone: draft.phone.trim() || null },
          limits: this.limits,
          reviewAt: draft.reviewAt ? new Date(`${draft.reviewAt}T00:00:00Z`).toISOString() : null,
        },
        consoleClient: { redirectUri: this.consoleRedirectUri },
        firstEdge: { id: this.edgeId, publicUrl: draft.edgePublicUrl.trim().replace(/\/$/, '') },
      });
      this.dispatchEvent(new CustomEvent<CreatedPartner>('created', { detail: created, bubbles: true, composed: true }));
    } catch (error) {
      this.submitError = error instanceof Error ? error.message : 'Failed to add the partner';
    } finally {
      this.isSaving = false;
    }
  }

  // ── rendering ──

  private field(step: Step, key: keyof Draft, label: string, hint: string, options: { type?: string; placeholder?: string; readonly?: boolean } = {}) {
    const errors = this.shownErrors(step);
    const error = errors[key];
    const id = `partner-${key}`;
    return html`
      <div class="form-group">
        <label for=${id}>${label}</label>
        <input type=${options.type ?? 'text'} id=${id} class="compact-input ${error ? 'input-error' : ''} ${key === 'id' || key === 'edgeId' ? 'mono' : ''}"
          .value=${String(this.draft[key])} placeholder=${options.placeholder ?? ''} ?readonly=${options.readonly}
          aria-invalid=${error ? 'true' : nothing} aria-describedby=${`${id}-hint`}
          @input=${(e: Event) => this.set({ [key]: (e.target as HTMLInputElement).value } as Partial<Draft>)} />
        ${error ? html`<div class="error-message" id=${`${id}-hint`} role="alert">${error}</div>` : html`<div class="hint" id=${`${id}-hint`}>${hint}</div>`}
      </div>
    `;
  }

  private prefixedEdgeField() {
    const error = this.shownErrors(3).edgeId;
    return html`
      <div class="form-group">
        <label for="partner-edgeId">Edge ID *</label>
        <div class="input-with-prefix">
          <span class="input-prefix" aria-hidden="true">edge-</span>
          <input type="text" id="partner-edgeId" class="compact-input mono ${error ? 'input-error' : ''}" .value=${this.draft.edgeId} placeholder="pilot-eu-1"
            aria-invalid=${error ? 'true' : nothing} aria-describedby="partner-edgeId-hint"
            @input=${(e: Event) => this.set({ edgeId: (e.target as HTMLInputElement).value })} />
        </div>
        ${error ? html`<div class="error-message" id="partner-edgeId-hint" role="alert">${error}</div>`
          : html`<div class="hint" id="partner-edgeId-hint">Registered as <span class="mono">${this.edgeId}</span>. Lowercase letters, digits and dashes.</div>`}
      </div>
    `;
  }

  private renderStepper() {
    return html`
      <div class="wizard-steps">
        ${STEP_LABELS.map((label, index) => {
          const step = index + 1;
          const state = step === this.step ? 'active' : step < this.step ? 'done' : '';
          return html`
            ${index > 0 ? html`<span class="wizard-step-sep"></span>` : ''}
            <span class="wizard-step ${state}" aria-current=${step === this.step ? 'step' : 'false'}>
              <span class="wizard-step-dot">${step < this.step ? '✓' : step}</span>${label}
            </span>`;
        })}
      </div>
    `;
  }

  private head(title: string, subtitle: string) {
    return html`<div class="wizard-head"><div class="wizard-panel-title">${title}</div><div class="wizard-panel-sub">${subtitle}</div></div>`;
  }

  private planLine(text: string, why: string) {
    return html`<div class="plan-line"><span class="plan-mark">${statusIcon('lock', '', 14)}</span><span><span>${text}</span><div class="plan-why">${why}</div></span></div>`;
  }

  private renderStep1() {
    return html`
      ${this.head('Who the partner is', 'What users see on the consent screen, and who the operator calls.')}
      <div class="editor-box">
        <h3>Organisation</h3>
        ${this.field(1, 'id', 'Partner ID *', 'A slug, fixed once added. It names the edge group and the console client too.', { placeholder: 'pilot-pay' })}
        ${this.field(1, 'displayName', 'Display name *', 'Shown on the consent screen: "<name> wants to sign you in". Changing it later needs your approval.', { placeholder: 'Pilot Pay' })}
        ${this.field(1, 'logoUri', 'Logo URL', 'An https URL of an SVG or PNG, shown next to the name. Optional.', { type: 'url', placeholder: 'https://' })}
      </div>
      <div class="editor-box">
        <h3>Contact</h3>
        ${this.field(1, 'email', 'Contact email *', 'Incidents, the review date, suspensions.', { type: 'email', placeholder: 'security@pilotpay.example' })}
        ${this.field(1, 'phone', 'Contact phone', 'Optional.', { placeholder: '+7 …' })}
        <div class="form-group">
          <label for="partner-reviewAt">Review date</label>
          <input type="date" id="partner-reviewAt" .value=${this.draft.reviewAt} aria-describedby="partner-reviewAt-hint"
            @input=${(e: Event) => this.set({ reviewAt: (e.target as HTMLInputElement).value })} />
          <div class="hint" id="partner-reviewAt-hint">A reminder to look at the partner again. Optional; nothing stops on that date.</div>
        </div>
      </div>
      <div class="form-actions">
        <span class="wizard-actions-note">Step 1 of 4 · the partner's own staff is its own business: nothing about them is created here</span>
        <button type="button" class="btn btn-secondary" @click=${() => this.dispatchEvent(new CustomEvent('cancel', { bubbles: true, composed: true }))}>Cancel</button>
        <button type="button" class="btn btn-primary" @click=${() => this.next(1)}>Continue to limits</button>
      </div>
    `;
  }

  private renderStep2() {
    const draft = this.draft;
    const errors = this.shownErrors(2);
    const scopeIds = [...new Set(['openid', ...this.scopes.map(scope => scope.id), ...draft.scopes])];
    return html`
      ${this.head('What the partner may do by itself', 'Inside these limits its staff act alone; beyond them a request comes to you as an approval.')}
      <div class="editor-box">
        <h3>What a client may request</h3>
        <div class="form-group">
          <span class="checkbox-list" role="group" aria-labelledby="partner-scopes-label">
            <label id="partner-scopes-label">Allowed scopes</label>
            ${scopeIds.map(scope => html`
              <label class="checkbox-row">
                <input type="checkbox" .checked=${scope === 'openid' || draft.scopes.includes(scope)} ?disabled=${scope === 'openid'}
                  @change=${(e: Event) => {
                    const checked = (e.target as HTMLInputElement).checked;
                    this.set({ scopes: checked ? [...draft.scopes, scope] : draft.scopes.filter(candidate => candidate !== scope) });
                  }} />
                <span class="mono">${scope}</span>${scope === 'openid' ? html`<span class="muted small">always</span>` : ''}
              </label>`)}
          </span>
          <div class="hint">Caps what any client of the partner may ask users for.</div>
        </div>
        <div class="form-group">
          <label for="partner-redirectDomains">Redirect domains *</label>
          <textarea id="partner-redirectDomains" class="compact-input ${errors.redirectDomains ? 'input-error' : ''}" rows="3" .value=${draft.redirectDomains}
            aria-invalid=${errors.redirectDomains ? 'true' : nothing} aria-describedby="partner-redirectDomains-hint" placeholder="pilotpay.example"
            @input=${(e: Event) => this.set({ redirectDomains: (e.target as HTMLTextAreaElement).value })}></textarea>
          ${errors.redirectDomains ? html`<div class="error-message" id="partner-redirectDomains-hint" role="alert">${errors.redirectDomains}</div>`
            : html`<div class="hint" id="partner-redirectDomains-hint">One per line. Every redirect URI must be https and under one of these (the domain or a subdomain).</div>`}
        </div>
      </div>
      <div class="editor-box">
        <h3>How much</h3>
        ${this.field(2, 'maxClients', 'Clients *', 'How many clients the partner may own, waiting ones included.', { type: 'number' })}
        ${this.field(2, 'maxEdges', 'Edges in group *', 'How many edges the partner may enroll; it adds them itself.', { type: 'number' })}
        <div class="field-pair">
          ${this.field(2, 'accessTokenTtlMinutes', 'Access token, minutes *', 'The longest access token a partner client may ask for.', { type: 'number' })}
          ${this.field(2, 'refreshTokenTtlDays', 'Refresh token, days *', 'The longest refresh token.', { type: 'number' })}
        </div>
      </div>
      <div class="editor-box">
        <h3>How the clients authenticate</h3>
        <div class="hint">With the group certificate (<span class="mono">tls_client_auth</span>). It names the group, so every replica of the partner's edges authenticates as any of its clients. Rate limits are the partner's to set for its own edges.</div>
      </div>
      <div class="form-actions">
        <span class="wizard-actions-note">Step 2 of 4 · every limit can be changed later on the partner's record</span>
        <button type="button" class="btn btn-secondary" @click=${() => (this.step = 1)}>Back</button>
        <button type="button" class="btn btn-primary" @click=${() => this.next(2)}>Continue to edge &amp; console</button>
      </div>
    `;
  }

  private renderStep3() {
    const id = this.draft.id.trim() || '…';
    return html`
      ${this.head('The edge group, the console and the first edge', 'Created with the partner. The group and the console client are fixed by the partner ID.')}
      <div class="editor-box">
        <h3>Edge group</h3>
        <p class="editor-sub">Kind <span class="mono">partner</span>, never changes. Its name goes into every certificate of the partner's edges.</p>
        <div class="form-group">
          <label for="partner-groupId">Group ID</label>
          <input type="text" id="partner-groupId" class="compact-input mono" .value=${id} readonly aria-describedby="partner-groupId-hint" />
          <div class="hint" id="partner-groupId-hint">Certificate name <span class="mono">urn:versola:edge-group:${id}</span>. The partner's clients authenticate to auth with it.</div>
        </div>
      </div>
      <div class="editor-box">
        <h3>Console client</h3>
        <p class="editor-sub">The web client the partner's staff sign in with. Owned by the partner, edited only here.</p>
        <div class="form-group">
          <label for="partner-consoleClientId">Client ID</label>
          <input type="text" id="partner-consoleClientId" class="compact-input mono" .value=${`${id}-console`} readonly aria-describedby="partner-consoleClientId-hint" />
          <div class="hint" id="partner-consoleClientId-hint">Scopes <span class="mono">openid phone email</span>, consent off: staff of the partner, not its customers.</div>
        </div>
        ${this.field(3, 'consoleUrl', 'Partner console URL *', `Redirect URI becomes ${this.draft.consoleUrl.trim() ? this.consoleRedirectUri : '<URL>/complete'}. Under one of the redirect domains.`, { type: 'url', placeholder: 'https://login.pilotpay.example' })}
        <div class="plan">
          ${this.planLine('Flow: code + PKCE, PAR', 'Every partner client.')}
          ${this.planLine('Client authentication: tls_client_auth with the group certificate', 'The console client is served by the partner\'s own edges.')}
          ${this.planLine('Who may sign in: anyone of this tenant', 'The partner\'s edge decides who is staff; nothing about them is held here.')}
        </div>
      </div>
      <div class="editor-box">
        <h3>First edge</h3>
        <p class="editor-sub">The partner adds further edges itself, up to its limit.</p>
        ${this.prefixedEdgeField()}
        ${this.field(3, 'edgePublicUrl', 'Public URL *', 'Where users reach the edge; back-channel logout goes there too.', { type: 'url', placeholder: 'https://login.pilotpay.example' })}
        <div class="hint">The enrollment token is single-use and short-lived. It is shown once after this wizard, with its expiry; send it over a channel you trust.</div>
      </div>
      <div class="form-actions">
        <span class="wizard-actions-note">Step 3 of 4</span>
        <button type="button" class="btn btn-secondary" @click=${() => (this.step = 2)}>Back</button>
        <button type="button" class="btn btn-primary" @click=${() => this.next(3)}>Continue to review</button>
      </div>
    `;
  }

  private reviewGroup(title: string, step: Step, rows: Array<[string, unknown]>) {
    return html`
      <section class="review-group" aria-label=${title}>
        <div class="review-head"><h2>${title}</h2><button type="button" class="review-change" aria-label=${`Change ${title.toLowerCase()}`} @click=${() => (this.step = step)}>Change</button></div>
        ${rows.map(([label, value]) => html`<div class="review-row"><span class="review-label">${label}</span><span class="review-value">${value}</span></div>`)}
      </section>
    `;
  }

  private renderStep4() {
    const draft = this.draft;
    const limits = this.limits;
    const tags = (values: string[]) => html`<div class="tag-list">${values.map(value => html`<span class="tag">${value}</span>`)}</div>`;
    return html`
      ${this.head('Review', 'Everything that will be created when you add the partner.')}
      <div class="layout with-aside">
        <div>
          ${this.reviewGroup('Organisation', 1, [
            ['Partner', html`${draft.displayName} · <span class="mono">${draft.id}</span> · tenant <span class="mono">${this.tenantId}</span>`],
            ['Logo', draft.logoUri.trim() ? html`<span class="mono">${draft.logoUri.trim()}</span>` : html`<span class="muted">None</span>`],
            ['Contact', html`${draft.email}${draft.phone.trim() ? ` · ${draft.phone.trim()}` : ''}`],
            ['Review date', draft.reviewAt ? `${draft.reviewAt} (a reminder)` : html`<span class="muted">Not set</span>`],
          ])}
          ${this.reviewGroup('Limits', 2, [
            ['Scopes', tags(limits.scopes)],
            ['Redirect domains', tags(limits.redirectDomains)],
            ['Caps', `${limits.maxClients} clients · ${limits.maxEdges} edges · ${ttlCapsLabel(limits)}`],
            ['Client authentication', 'group certificate'],
            ['Consent', html`Always shown, names ${draft.displayName} ${statusIcon('lock', '', 12)}`],
          ])}
          ${this.reviewGroup('Edge & console', 3, [
            ['Edge group', html`<span class="mono">${draft.id}</span> · kind partner`],
            ['Console client', html`<span class="mono">${draft.id}-console</span> → <span class="mono">${this.consoleRedirectUri}</span>`],
            ['First edge', html`<span class="mono">${this.edgeId}</span> at <span class="mono">${draft.edgePublicUrl}</span>`],
          ])}
          ${this.submitError ? html`<div class="editor-error" role="alert">${this.submitError}</div>` : ''}
        </div>
        <div class="aside">
          <section class="review-group" aria-label="What the partner gets">
            <div class="review-head"><h2>What the partner gets</h2></div>
            <div class="review-row"><span>Enrollment token for <span class="mono">${this.edgeId}</span></span><span class="chip">shown once</span></div>
            <div class="review-row"><span>Partner console at its own domain</span><span class="chip ok">after enrollment</span></div>
            <div class="review-row"><span>Access to central or this console</span><span class="chip danger">never</span></div>
          </section>
        </div>
      </div>
      <div class="form-actions">
        <span class="wizard-actions-note">Step 4 of 4 · one request creates the partner, the group, the console client, the first edge and the enrollment token</span>
        <button type="button" class="btn btn-secondary" @click=${() => (this.step = 3)} ?disabled=${this.isSaving}>Back</button>
        <button type="button" class="btn btn-primary" @click=${this.handleSubmit} ?disabled=${this.isSaving}>${this.isSaving ? 'Adding…' : 'Add partner'}</button>
      </div>
    `;
  }

  render() {
    return html`
      <div class="form-header">
        <div style="display:flex;align-items:center;gap:var(--spacing-md);min-width:0">
          <versola-nav-toggle></versola-nav-toggle>
          <div>
            <h1 class="form-title">Add partner</h1>
            <div class="form-subtitle">Four steps. The partner ID, its edge group and its console client cannot be changed afterwards.</div>
          </div>
        </div>
      </div>
      <div class="card">
        ${this.renderStepper()}
        ${this.step === 1 ? this.renderStep1() : this.step === 2 ? this.renderStep2() : this.step === 3 ? this.renderStep3() : this.renderStep4()}
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-wizard': VersolaPartnerWizard;
  }
}
