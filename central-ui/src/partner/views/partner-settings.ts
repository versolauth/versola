import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, recordStyles, tagStyles } from '../../styles/components';
import { statusIcon } from '../../components/status-icon';
import { confirmDiscardDraft } from '../../utils/draft-guard';
import { formatDate } from '../../utils/helpers';
import { partnerAccessTtlError, ttlCapsLabel, ttlLabel } from '../../utils/partners';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../../utils/record-route';
import { recallProfileApproval, rememberProfileApproval } from '../pending-profile';
import { fetchProfileApproval, fetchSettings, requestProfileChange, updateSettings, writeErrorMessage, readErrorMessage } from '../partner-api';
import type { PartnerMe, PartnerSettings, ProfileApproval } from '../partner-types';
import { partnerViewStyles } from '../partner-styles';
import '../../components/content-header';
import '../../components/loading-cards';
import '../../components/error-card';
import '../../components/record-card';
import '../../components/nav-toggle';

type EditTarget = 'profile' | 'contacts' | 'defaults';
type Draft = { displayName: string; logoUri: string; email: string; phone: string; backChannelLogoutUri: string; accessTokenTtlMinutes: string; refreshTokenTtlDays: string };

/**
 * Settings: the organisation as the operator and its users see it (a change goes to the operator
 * for approval), contacts, the defaults new clients start from, the agreement the operator set
 * (read-only) and the operator's endpoints for the firewall.
 */
@customElement('versola-partner-settings')
export class VersolaPartnerSettings extends LitElement {
  @property({ attribute: false }) me!: PartnerMe;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private settings: PartnerSettings | null = null;
  @state() private approval: ProfileApproval | null = null;
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private notice = '';
  @state() private draft: Draft = { displayName: '', logoUri: '', email: '', phone: '', backChannelLogoutUri: '', accessTokenTtlMinutes: '5', refreshTokenTtlDays: '14' };
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  private draftKey = '';
  private draftBaseline = '';

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    recordStyles,
    partnerViewStyles,
    css`
      .form-header { display: flex; align-items: center; gap: var(--spacing-md); margin-bottom: var(--spacing-xl); }
      .form-title { font-size: var(--text-4xl); font-weight: 800; letter-spacing: -0.02em; margin: 0; }
      .editor-card { max-width: 46rem; }
      .field-pair { display: grid; grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr)); gap: var(--spacing-md); max-width: 22.8rem; }
      .field-pair input { max-width: none; }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('route') || changed.has('settings')) {
      const key = this.editTarget ?? '';
      if (key !== this.draftKey) {
        this.draftKey = key;
        if (key) this.resetDraft();
      }
    }
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [settings, approval] = await Promise.all([fetchSettings(), fetchProfileApproval().catch(() => null)]);
      this.settings = settings;
      this.approval = approval ?? recallProfileApproval(this.me.partner);
    } catch (error) {
      this.errorMessage = readErrorMessage(error, 'Failed to load the settings');
    } finally {
      this.isLoading = false;
    }
  }

  private get editTarget(): EditTarget | null {
    const edit = this.route.edit;
    return edit === 'profile' || edit === 'contacts' || edit === 'defaults' ? edit : null;
  }

  private resetDraft() {
    const me = this.me;
    const settings = this.settings;
    this.draft = {
      displayName: me.partner.displayName, logoUri: me.partner.logoUri ?? '',
      email: settings?.contact.email ?? '', phone: settings?.contact.phone ?? '',
      backChannelLogoutUri: settings?.defaults.backChannelLogoutUri ?? '',
      accessTokenTtlMinutes: String(Math.max(1, Math.round((settings?.defaults.accessTokenTtl || 300) / 60))),
      refreshTokenTtlDays: String(Math.max(1, Math.round((settings?.defaults.refreshTokenTtl || 14 * 86_400) / 86_400))),
    };
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
    this.draftBaseline = JSON.stringify(this.draft);
  }

  private get errors(): Partial<Record<keyof Draft, string>> {
    const errors: Partial<Record<keyof Draft, string>> = {};
    const draft = this.draft;
    const limits = this.me.partner.limits;
    if (this.editTarget === 'profile') {
      if (!draft.displayName.trim()) errors.displayName = 'A display name is required.';
      if (draft.logoUri.trim() && !/^https:\/\/\S+$/.test(draft.logoUri.trim())) errors.logoUri = 'An https URL.';
    }
    if (this.editTarget === 'contacts' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(draft.email.trim())) errors.email = 'A contact email is required.';
    if (this.editTarget === 'defaults') {
      if (draft.backChannelLogoutUri.trim() && !/^https:\/\/\S+$/.test(draft.backChannelLogoutUri.trim())) errors.backChannelLogoutUri = 'An https URL.';
      const positive = (value: string) => /^\d+$/.test(value.trim()) && Number(value) > 0;
      if (!positive(draft.accessTokenTtlMinutes) || partnerAccessTtlError(Number(draft.accessTokenTtlMinutes) * 60, limits.accessTokenTtlMax, false)) errors.accessTokenTtlMinutes = `Between 1 minute and ${ttlLabel(Math.min(limits.accessTokenTtlMax, 86_400))}, the operator's cap and central's limit.`;
      if (!positive(draft.refreshTokenTtlDays) || Number(draft.refreshTokenTtlDays) * 86_400 > limits.refreshTokenTtlMax) errors.refreshTokenTtlDays = `Between 1 day and ${ttlLabel(limits.refreshTokenTtlMax)}, the operator's cap.`;
    }
    return errors;
  }

  private async cancel() {
    if (JSON.stringify(this.draft) !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.submitted = true;
    if (Object.keys(this.errors).length > 0) return;
    this.isSaving = true;
    this.formError = '';
    const draft = this.draft;
    try {
      if (this.editTarget === 'profile') {
        const { approval } = await requestProfileChange({ displayName: draft.displayName.trim(), logoUri: draft.logoUri.trim() || null });
        this.approval = approval;
        rememberProfileApproval(this.me.partner.id, approval);
        this.notice = `Sent to ${this.me.partner.operator.name} for approval. Users keep seeing the current name and logo until it is approved.`;
      } else {
        const settings: PartnerSettings = {
          contact: this.editTarget === 'contacts' ? { email: draft.email.trim(), phone: draft.phone.trim() || null } : this.settings!.contact,
          defaults: this.editTarget === 'defaults'
            ? { backChannelLogoutUri: draft.backChannelLogoutUri.trim(), accessTokenTtl: Number(draft.accessTokenTtlMinutes) * 60, refreshTokenTtl: Number(draft.refreshTokenTtlDays) * 86_400 }
            : this.settings!.defaults,
        };
        await updateSettings(settings);
        this.settings = settings;
        this.notice = 'Saved.';
      }
      dispatchRecordRoute(this, { edit: null, create: false });
    } catch (error) {
      this.formError = writeErrorMessage(error);
    } finally {
      this.isSaving = false;
    }
  }

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private field(key: keyof Draft, label: string, hint: string, options: { type?: string; placeholder?: string } = {}) {
    const error = this.submitted ? this.errors[key] : undefined;
    const id = `settings-${key}`;
    return html`
      <div class="form-group">
        <label for=${id}>${label}</label>
        <input type=${options.type ?? 'text'} id=${id} class="compact-input ${error ? 'input-error' : ''}" .value=${this.draft[key]} placeholder=${options.placeholder ?? ''}
          aria-invalid=${error ? 'true' : nothing} aria-describedby=${`${id}-hint`}
          @input=${(e: Event) => this.draft = { ...this.draft, [key]: (e.target as HTMLInputElement).value }} />
        ${error ? html`<div class="error-message" id=${`${id}-hint`} role="alert">${error}</div>` : html`<div class="hint" id=${`${id}-hint`}>${hint}</div>`}
      </div>`;
  }

  private renderEditor(target: EditTarget) {
    const me = this.me;
    const titles: Record<EditTarget, string> = { profile: 'Request a change of name or logo', contacts: 'Edit contacts', defaults: 'Edit defaults for new clients' };
    const submits: Record<EditTarget, string> = { profile: 'Send for approval', contacts: 'Save contacts', defaults: 'Save defaults' };
    return html`
      <div class="form-header">
        <versola-nav-toggle></versola-nav-toggle>
        <h1 class="form-title">${titles[target]}</h1>
      </div>
      <form class="card editor-card" novalidate @submit=${this.handleSubmit}>
        <div class="card-body">
          ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
          ${target === 'profile' ? html`
            <div class="banner info" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('info')}${me.partner.operator.name} approves the name and the logo</div><div>They appear on the consent screen of every client of yours. Users keep seeing the current ones until the operator approves the change.</div></div></div>
            ${this.field('displayName', 'Display name *', 'Shown on the consent screen: "<name> wants to sign you in".')}
            ${this.field('logoUri', 'Logo URL', 'An https URL of an SVG or PNG. Optional.', { type: 'url', placeholder: 'https://' })}
          ` : target === 'contacts' ? html`
            ${this.field('email', 'Security contact *', 'Who the operator reaches about incidents and the review date.', { type: 'email' })}
            ${this.field('phone', 'Phone', 'Optional.', { placeholder: '+7 …' })}
          ` : html`
            ${this.field('backChannelLogoutUri', 'Back-channel logout URI', 'Prefilled for every new client; usually your edge\'s logout endpoint.', { type: 'url' })}
            <div class="field-pair">
              ${this.field('accessTokenTtlMinutes', 'Access token, minutes *', `At most ${ttlLabel(me.partner.limits.accessTokenTtlMax)}.`, { type: 'number' })}
              ${this.field('refreshTokenTtlDays', 'Refresh token, days *', `At most ${ttlLabel(me.partner.limits.refreshTokenTtlMax)}.`, { type: 'number' })}
            </div>
          `}
          <div class="form-actions">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancel()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : submits[target]}</button>
          </div>
        </div>
      </form>`;
  }

  render() {
    const me = this.me;
    const target = this.editTarget;
    if (target && this.settings) return this.renderEditor(target);
    const limits = me.partner.limits;
    const settings = this.settings;
    const tags = (values: string[]) => html`<div class="tag-list">${values.map(value => html`<span class="tag">${value}</span>`)}</div>`;
    const waitingChip = html`<span class="chip warn">waiting for the operator</span>`;
    const edit = this.canManage;
    const host = (url: string | null) => (url ?? '').replace(/^https?:\/\//, '');
    return html`
      <content-header title="Settings" description="Your organisation as the operator and its users see it, and the defaults your clients start from."></content-header>
      ${this.notice ? html`<div class="banner ok" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('check')}${this.notice}</div></div><button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.notice = ''; }}>Dismiss</button></div>` : ''}
      ${this.isLoading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
        : this.errorMessage && !settings ? html`<versola-error-card heading="Could not load the settings" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>`
          : html`
            <div class="grid-2">
              <div>
                <versola-record-card heading="Organisation" subheading="Shown on the consent screen. Changes go to the operator for approval." ?editable=${edit} editLabel="Request change" .body=${html`
                  ${this.row('Display name', html`${me.partner.displayName}${this.approval ? html` → ${this.approval.requested.displayName} ${waitingChip}` : ''}`, this.approval ? '' : 'Operator approves')}
                  ${this.row('Logo', html`${me.partner.logoUri ? html`<span class="mono">${me.partner.logoUri}</span>` : html`<span class="muted">None</span>`}${this.approval && this.approval.requested.logoUri !== (me.partner.logoUri ?? null) ? html` → <span class="mono">${this.approval.requested.logoUri ?? 'none'}</span> ${waitingChip}` : ''}`, this.approval ? '' : 'Operator approves')}
                  ${this.approval ? this.row('Requested', `${formatDate(this.approval.requestedAt)}; users keep seeing the current name until the operator decides`) : ''}
                  ${this.row('Partner ID', html`<span class="mono">${me.partner.id}</span>`, 'Fixed')}
                `} @edit=${() => dispatchRecordRoute(this, { edit: 'profile', create: false })}></versola-record-card>
                <versola-record-card heading="Contacts" subheading="Who the operator reaches about incidents and the review date." ?editable=${edit} .body=${html`
                  ${this.row('Security', settings?.contact.email ?? '—')}
                  ${this.row('Phone', settings?.contact.phone ?? html`<span class="muted">None</span>`)}
                `} @edit=${() => dispatchRecordRoute(this, { edit: 'contacts', create: false })}></versola-record-card>
                <versola-record-card heading="Defaults for new clients" ?editable=${edit} .body=${html`
                  ${this.row('Back-channel logout URI', settings?.defaults.backChannelLogoutUri ? html`<span class="mono">${settings.defaults.backChannelLogoutUri}</span>` : html`<span class="muted">None</span>`)}
                  ${this.row('Token lifetimes', settings ? `access ${settings.defaults.accessTokenTtl ? ttlLabel(settings.defaults.accessTokenTtl) : 'not set'} · refresh ${settings.defaults.refreshTokenTtl ? ttlLabel(settings.defaults.refreshTokenTtl) : 'not set'}` : '—')}
                `} @edit=${() => dispatchRecordRoute(this, { edit: 'defaults', create: false })}></versola-record-card>
              </div>
              <div>
                <versola-record-card heading=${`Your agreement with ${me.partner.operator.name}`} subheading="Set by the operator. Ask them to change it." .body=${html`
                  ${this.row('Status', html`<span class="chip ${me.partner.status === 'active' ? 'ok' : me.partner.status === 'suspended' ? 'danger' : ''}">${me.partner.status}</span>`)}
                  ${this.row('Review date', me.partner.reviewAt ? formatDate(me.partner.reviewAt) : html`<span class="muted">Not set</span>`)}
                  ${this.row('Scopes allowed', tags(limits.scopes))}
                  ${this.row('Redirect domains', tags(limits.redirectDomains))}
                  ${this.row('Caps', `${limits.maxClients} clients · ${limits.maxEdges} edges · ${ttlCapsLabel(limits)}`)}
                  ${this.row('Client authentication', 'group certificate')}
                  ${this.row('Consent', `always shown, names ${me.partner.displayName}`, 'Operator')}
                `}></versola-record-card>
                <versola-record-card heading="Operator endpoints" subheading="What your edges talk to. For your firewall." .body=${html`
                  ${this.row('Issuer', me.partner.operator.issuer ? html`<span class="mono">${me.partner.operator.issuer}</span>` : html`<span class="muted">Not known yet</span>`)}
                  ${this.row('auth mTLS', me.partner.operator.mtlsUrl ? html`<span class="mono">${host(me.partner.operator.mtlsUrl)}:443</span>` : html`<span class="muted">Not known yet</span>`)}
                  ${this.row('central mTLS', me.partner.operator.centralMtlsUrl ? html`<span class="mono">${host(me.partner.operator.centralMtlsUrl)}:443</span>` : html`<span class="muted">Not known yet</span>`)}
                  ${this.row('Enrollment', me.partner.operator.centralMtlsUrl ? html`<span class="mono">${host(me.partner.operator.centralMtlsUrl).replace('central-mtls.', 'central.')}:443</span> <span class="muted small">once per edge</span>` : html`<span class="muted">Not known yet</span>`)}
                  ${this.row('Inbound from the operator', 'back-channel logout to your edges')}
                `}></versola-record-card>
              </div>
            </div>`}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-settings': VersolaPartnerSettings;
  }
}
