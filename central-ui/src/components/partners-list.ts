import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../styles/components';
import type { CreatedPartner, Edge, OAuthClient, OAuthScope, Partner, PartnerApproval, PartnerLimits } from '../types';
import {
  fetchAllClients, fetchEdges, fetchPartnerApprovals, fetchPartners, getScopes, resumePartner, suspendPartner, updatePartner, updatePartnerLimits,
} from '../utils/central-api';
import { copyToClipboard, formatDate, getLocalizedDescription } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { RATE_LIMIT_POLICY_OPTIONS, partnerActivity, reviewState, ttlCapsLabel } from '../utils/partners';
import { certificateChip, edgeCertificateState, partnerStatusChip } from './partner-chips';
import { statusIcon } from './status-icon';
import { valueChips } from './value-chips';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from './data-table';
import type { NavItem } from './navigation';
import './content-header';
import './danger-card';
import './data-table';
import './empty-state';
import './error-card';
import './filter-bar';
import './loading-cards';
import './record-card';
import './record-header';
import './side-panel';
import './tabs';
import './partner-approvals';
import './partner-wizard';

type PartnerFilter = 'all' | Partner['status'] | 'awaiting';
type EditTarget = 'organisation' | 'limits' | 'suspend';

type OrganisationDraft = { displayName: string; logoUri: string; email: string; phone: string; reviewAt: string };
type LimitsDraft = {
  scopes: string[]; redirectDomains: string; maxClients: string; maxEdges: string;
  accessTokenTtlMinutes: string; refreshTokenTtlDays: string; tlsClientAuth: boolean; privateKeyJwt: boolean; rateLimitPolicy: string;
};

/**
 * Partners (#549): the organisations that run their own edge and own clients inside this tenant.
 * Table + side panel, like Edges: the record's groups (organisation, limits, edge group, clients,
 * approvals, activity, Fixed) each with their editor in the panel, Suspend / Resume in the danger
 * card, and `?tab=approvals` for the queue of what partners asked for beyond their limits. The
 * `Add partner` wizard is a page (`&new=1`) that ends on the one-time token and bundle page.
 */
@customElement('versola-partners-list')
export class VersolaPartnersList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private partners: Partner[] = [];
  @state() private approvals: PartnerApproval[] = [];
  @state() private edges: Edge[] = [];
  @state() private clients: OAuthClient[] = [];
  @state() private scopes: OAuthScope[] = [];
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private searchQuery = '';
  @state() private filter: PartnerFilter = 'all';
  /** What the wizard created: the one-time token and bundle page, shown until the operator leaves it. */
  @state() private created: CreatedPartner | null = null;
  @state() private copyFeedback = '';
  @state() private organisationDraft: OrganisationDraft = { displayName: '', logoUri: '', email: '', phone: '', reviewAt: '' };
  @state() private limitsDraft: LimitsDraft = VersolaPartnersList.emptyLimitsDraft();
  @state() private suspendNote = '';
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @query('versola-data-table') private table?: VersolaDataTable<Partner>;
  private draftKey = '';
  private draftBaseline = '';
  private loadRequestId = 0;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    iconButtonStyles,
    recordStyles,
    css`
      :host { display: block; }

      .list-toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-md);
        align-items: center;
        margin-bottom: var(--spacing-lg);
      }

      .search-input {
        flex: 1 1 14rem;
        max-width: 28rem;
        min-width: 0;
      }

      versola-tabs { margin-bottom: var(--spacing-lg); }

      .muted { color: var(--text-secondary); }
      .small { font-size: var(--text-sm); }

      .mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
      }

      .row-title.mono { font-size: var(--text-base); }

      .cell-stack { display: grid; gap: 0.25rem; }

      .review-due { color: var(--warning-text); font-weight: 600; }
      .review-overdue { color: var(--danger-text); font-weight: 600; }

      .record-list { display: grid; gap: 0.5rem; padding: 0.75rem var(--spacing-lg); }
      .record-compact .record-list { padding: 0.625rem var(--spacing-md); }
      .edge-rows { display: grid; gap: 0.5rem; }
      .edge-row {
        display: flex;
        flex-wrap: wrap;
        gap: 0.375rem 0.75rem;
        align-items: center;
      }
      .edge-row .chip { margin-left: auto; }
      .chip.muted-chip { color: var(--text-secondary); }

      .timeline {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        gap: 0.5rem;
        font-size: var(--text-sm);
      }
      .timeline li {
        display: grid;
        grid-template-columns: 7.5rem 1fr;
        gap: 0.75rem;
      }
      .timeline .when { color: var(--text-secondary); white-space: nowrap; }
      .timeline .actor { font-family: var(--font-mono); }
      .timeline .operator { font-family: var(--font-family); font-weight: 600; }

      .secret-rows { display: grid; gap: 0.5rem; margin: 0.75rem 0; }
      .secret-label { font-size: var(--text-xs); color: var(--text-secondary); margin-bottom: 0.125rem; }
      .secret-value {
        margin: 0;
        padding: 0.625rem 0.75rem;
        background: var(--bg-page);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        line-height: 1.5;
        white-space: pre-wrap;
        word-break: break-all;
        max-height: 20rem;
        overflow: auto;
      }
      .banner-actions { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: center; }
      .copy-feedback { font-size: var(--text-sm); color: var(--success-text); }
      .key-banner { display: block; }
      .key-banner .banner-text { flex: 1 1 100%; }
      .result-page .record-card { margin-bottom: var(--spacing-md); }
      .record-page .record-card { margin-bottom: var(--spacing-md); }

      .key-actions { display: flex; flex-wrap: wrap; gap: 0.5rem; }

      .stops {
        margin: var(--spacing-sm) 0 var(--spacing-md);
        padding-left: 1.25rem;
        font-size: var(--text-sm);
        color: var(--text-secondary);
        display: grid;
        gap: 0.25rem;
      }

      .checkbox-list { display: grid; gap: 0.375rem; }
      .checkbox-row {
        display: flex;
        gap: 0.625rem;
        align-items: center;
        font-size: var(--text-base);
        color: var(--text-primary);
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
        margin: 0;
        cursor: pointer;
      }
      .checkbox-row input { width: 1rem; height: 1rem; margin: 0; accent-color: var(--accent); flex: none; }
      .checkbox-row input:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
      .checkbox-row .mono { font-size: var(--text-base); }

      .field-pair { display: grid; grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr)); gap: var(--spacing-md); max-width: 22.8rem; }
      .field-pair input { max-width: none; }
      input[type='date'] {
        width: 100%;
        max-width: min(100%, 22.8rem);
        padding: 0.6875rem 1rem;
        background: var(--field);
        border: 1.5px solid var(--border-input);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-family: var(--font-family);
        font-size: var(--text-base);
      }
      input[type='date']:focus { outline: 2px solid var(--focus-ring); outline-offset: 2px; background: var(--surface); border-color: var(--text-primary); }

      .link-button {
        background: none;
        border: 0;
        padding: 0;
        font: inherit;
        color: var(--accent);
        text-decoration: underline;
        cursor: pointer;
      }
      .link-button:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; border-radius: var(--radius-sm); }
    `,
  ];

  private static emptyLimitsDraft(): LimitsDraft {
    return { scopes: ['openid'], redirectDomains: '', maxClients: '10', maxEdges: '3', accessTokenTtlMinutes: '10', refreshTokenTtlDays: '30', tlsClientAuth: true, privateKeyJwt: false, rateLimitPolicy: 'partner-default' };
  }

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('beforeunload', this.handleBeforeUnload);
    void this.loadData();
  }

  disconnectedCallback() {
    window.removeEventListener('beforeunload', this.handleBeforeUnload);
    super.disconnectedCallback();
  }

  private readonly handleBeforeUnload = (event: BeforeUnloadEvent) => {
    if (this.created) event.preventDefault();
  };

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId') && changed.get('tenantId') !== undefined) void this.loadData();
    if (changed.has('route') || changed.has('partners')) {
      const mode = this.editorMode;
      const draftKey = mode === 'view' ? '' : `${mode}:${this.route.id ?? ''}:${this.route.edit ?? ''}`;
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (mode !== 'view') this.resetDraft();
      }
      // The token and bundle are for the partner just created; leaving its page is the one chance gone.
      if (this.created && changed.has('route') && this.route.id !== this.created.partner.id) this.created = null;
    }
  }

  private async loadData() {
    if (!this.tenantId) {
      this.partners = [];
      return;
    }
    const tenantId = this.tenantId;
    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [partners, approvals, edges, clients, scopes] = await Promise.all([
        fetchPartners(tenantId),
        fetchPartnerApprovals(tenantId).catch(() => [] as PartnerApproval[]),
        fetchEdges().catch(() => [] as Edge[]),
        fetchAllClients(tenantId).catch(() => [] as OAuthClient[]),
        getScopes(tenantId).catch(() => [] as OAuthScope[]),
      ]);
      if (requestId !== this.loadRequestId) return;
      this.partners = partners;
      this.approvals = approvals;
      this.edges = edges;
      this.clients = clients;
      this.scopes = scopes;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.partners = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load partners';
    } finally {
      if (requestId === this.loadRequestId) this.isLoading = false;
    }
  }

  /** Decisions in the queue change clients and partners too: everything is read again. */
  private async reloadAfterDecision() {
    await this.loadData();
  }

  // ── routing ──

  private get openPartner(): Partner | null {
    return this.route.id ? this.partners.find(partner => partner.id === this.route.id) ?? null : null;
  }

  private get editTarget(): EditTarget | null {
    const edit = this.route.edit;
    return this.openPartner && (edit === 'organisation' || edit === 'limits' || edit === 'suspend') ? edit : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.editTarget ? 'edit' : 'view';
  }

  private openView(partner: Partner) {
    dispatchRecordRoute(this, { id: partner.id, edit: null, create: false, tab: null });
  }

  private async closePanel() {
    const key = this.route.id;
    if (this.created && !await this.confirmLeaveBundle()) return;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private openEditor(target: EditTarget) {
    dispatchRecordRoute(this, { edit: target, create: false });
  }

  private async cancelEditor() {
    if (this.draftSnapshot !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  private handleCreateClick() {
    dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null, tab: null });
  }

  private openApprovals(approvalId: string | null = null) {
    dispatchRecordRoute(this, { id: approvalId, edit: null, create: false, layout: null, tab: 'approvals' });
  }

  private navigateTo(view: NavItem, id?: string) {
    this.dispatchEvent(new CustomEvent('navigate-view', { detail: { view, id }, bubbles: true, composed: true }));
  }

  private async confirmLeaveBundle(): Promise<boolean> {
    return confirmDestructiveAction({
      title: 'Leave without saving the bundle?',
      message: 'The enrollment token and the bootstrap bundle are shown only once. After you leave they cannot be retrieved; you would have to issue a new token from the edge.',
      confirmLabel: 'Leave',
      cancelLabel: 'Stay',
    });
  }

  // ── drafts ──

  private get draftSnapshot(): string {
    return JSON.stringify([this.organisationDraft, this.limitsDraft, this.suspendNote]);
  }

  private resetDraft() {
    const partner = this.openPartner;
    this.organisationDraft = {
      displayName: partner?.displayName ?? '',
      logoUri: partner?.logoUri ?? '',
      email: partner?.contact.email ?? '',
      phone: partner?.contact.phone ?? '',
      reviewAt: partner?.reviewAt ? partner.reviewAt.slice(0, 10) : '',
    };
    const limits = partner?.limits;
    this.limitsDraft = limits ? {
      scopes: [...limits.scopes],
      redirectDomains: limits.redirectDomains.join('\n'),
      maxClients: String(limits.maxClients),
      maxEdges: String(limits.maxEdges),
      accessTokenTtlMinutes: String(Math.round(limits.accessTokenTtlMax / 60)),
      refreshTokenTtlDays: String(Math.round(limits.refreshTokenTtlMax / 86_400)),
      tlsClientAuth: limits.clientAuthMethods.includes('tls_client_auth'),
      privateKeyJwt: limits.clientAuthMethods.includes('private_key_jwt'),
      rateLimitPolicy: limits.rateLimitPolicy,
    } : VersolaPartnersList.emptyLimitsDraft();
    this.suspendNote = '';
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
    this.draftBaseline = this.draftSnapshot;
  }

  private get organisationErrors(): Partial<Record<keyof OrganisationDraft, string>> {
    const errors: Partial<Record<keyof OrganisationDraft, string>> = {};
    const draft = this.organisationDraft;
    if (!draft.displayName.trim()) errors.displayName = 'A display name is required: users see it on the consent screen.';
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(draft.email.trim())) errors.email = 'A contact email is required.';
    if (draft.logoUri.trim() && !/^https:\/\/\S+$/.test(draft.logoUri.trim())) errors.logoUri = 'The logo is an https URL.';
    return errors;
  }

  private get limitsErrors(): Partial<Record<keyof LimitsDraft, string>> {
    const errors: Partial<Record<keyof LimitsDraft, string>> = {};
    const draft = this.limitsDraft;
    const domains = draft.redirectDomains.split('\n').map(line => line.trim()).filter(Boolean);
    if (domains.length === 0) errors.redirectDomains = 'At least one domain: a partner client cannot redirect anywhere otherwise.';
    else if (domains.some(domain => !/^(?!-)[a-z0-9-]+(\.[a-z0-9-]+)+$/i.test(domain))) errors.redirectDomains = 'One domain per line, such as pilotpay.example. No scheme, no path.';
    const positive = (value: string) => /^\d+$/.test(value.trim()) && Number(value) > 0;
    if (!positive(draft.maxClients)) errors.maxClients = 'A whole number above zero.';
    if (!positive(draft.maxEdges)) errors.maxEdges = 'A whole number above zero.';
    if (!positive(draft.accessTokenTtlMinutes) || Number(draft.accessTokenTtlMinutes) > 24 * 60) errors.accessTokenTtlMinutes = 'Between 1 minute and 24 hours.';
    if (!positive(draft.refreshTokenTtlDays) || Number(draft.refreshTokenTtlDays) > 365) errors.refreshTokenTtlDays = 'Between 1 and 365 days.';
    if (!draft.tlsClientAuth && !draft.privateKeyJwt) errors.tlsClientAuth = 'At least one method: the partner\'s clients must authenticate somehow.';
    return errors;
  }

  private limitsFromDraft(): PartnerLimits {
    const draft = this.limitsDraft;
    return {
      scopes: [...new Set(['openid', ...draft.scopes])],
      redirectDomains: draft.redirectDomains.split('\n').map(line => line.trim().toLowerCase()).filter(Boolean),
      maxClients: Number(draft.maxClients),
      maxEdges: Number(draft.maxEdges),
      accessTokenTtlMax: Number(draft.accessTokenTtlMinutes) * 60,
      refreshTokenTtlMax: Number(draft.refreshTokenTtlDays) * 86_400,
      clientAuthMethods: [...(draft.tlsClientAuth ? ['tls_client_auth' as const] : []), ...(draft.privateKeyJwt ? ['private_key_jwt' as const] : [])],
      rateLimitPolicy: draft.rateLimitPolicy,
    };
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.submitted = true;
    const partner = this.openPartner;
    const target = this.editTarget;
    if (!partner || !target) return;
    if (target === 'organisation' && Object.keys(this.organisationErrors).length > 0) return;
    if (target === 'limits' && Object.keys(this.limitsErrors).length > 0) return;
    if (target === 'suspend' && !this.suspendNote.trim()) return;

    this.isSaving = true;
    this.formError = '';
    try {
      if (target === 'organisation') {
        const draft = this.organisationDraft;
        const update = {
          id: partner.id,
          displayName: draft.displayName.trim(),
          logoUri: draft.logoUri.trim() || null,
          contact: { email: draft.email.trim(), phone: draft.phone.trim() || null },
          reviewAt: draft.reviewAt ? new Date(`${draft.reviewAt}T00:00:00Z`).toISOString() : null,
        };
        await updatePartner(update);
        this.partners = this.partners.map(candidate => candidate.id === partner.id ? { ...candidate, ...update } : candidate);
      } else if (target === 'limits') {
        const limits = this.limitsFromDraft();
        await updatePartnerLimits(partner.id, limits);
        this.partners = this.partners.map(candidate => candidate.id === partner.id ? { ...candidate, limits } : candidate);
      } else {
        await suspendPartner(partner.id, this.suspendNote.trim());
        this.partners = this.partners.map(candidate => candidate.id === partner.id ? { ...candidate, status: 'suspended' } : candidate);
      }
      dispatchRecordRoute(this, { edit: null, create: false });
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save the partner';
    } finally {
      this.isSaving = false;
    }
  }

  private async handleResume(partner: Partner) {
    const confirmed = await confirmDestructiveAction({
      title: 'Resume partner',
      messagePrefix: 'Resume ',
      messageSubject: partner.displayName,
      messageSuffix: '? Its clients are enabled again in auth, its edges may renew their certificates and call central, and its staff can sign in to the partner console. Users sign in again.',
      confirmLabel: 'Resume',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      await resumePartner(partner.id);
      this.partners = this.partners.map(candidate => candidate.id === partner.id ? { ...candidate, status: 'active' } : candidate);
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to resume the partner';
    }
  }

  private handleCreated(event: CustomEvent<CreatedPartner>) {
    const created = event.detail;
    this.created = created;
    this.copyFeedback = '';
    this.partners = [...this.partners.filter(partner => partner.id !== created.partner.id), created.partner].sort((a, b) => a.id.localeCompare(b.id));
    this.edges = [...this.edges.filter(edge => edge.id !== created.edge.id), created.edge];
    dispatchRecordRoute(this, { id: created.partner.id, edit: null, create: false, layout: null });
    void fetchAllClients(created.partner.tenantId).then(clients => { this.clients = clients; }).catch(() => undefined);
  }

  private async handleCopy(what: 'token' | 'bundle') {
    if (!this.created) return;
    const copied = await copyToClipboard(what === 'token' ? this.created.enrollmentToken : this.created.bundle);
    this.copyFeedback = copied ? `${what === 'token' ? 'Enrollment token' : 'Bootstrap bundle'} copied.` : `Could not copy the ${what}.`;
  }

  // ── derived ──

  private clientsOf(partner: Partner): OAuthClient[] {
    return this.clients.filter(client => client.partnerId === partner.id);
  }

  private edgesOf(partner: Partner): Edge[] {
    return this.edges.filter(edge => edge.partnerId === partner.id);
  }

  private waitingOf(partner: Partner): PartnerApproval[] {
    return this.approvals.filter(approval => approval.partnerId === partner.id && approval.status === 'waiting');
  }

  private matchesFilter(partner: Partner): boolean {
    switch (this.filter) {
      case 'all': return true;
      case 'awaiting': return this.waitingOf(partner).length > 0;
      default: return partner.status === this.filter;
    }
  }

  private get filteredPartners(): Partner[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.partners.filter(partner => this.matchesFilter(partner)
      && (!query || partner.id.toLowerCase().includes(query) || partner.displayName.toLowerCase().includes(query)));
  }

  private reviewCell(partner: Partner) {
    if (!partner.reviewAt) return html`<span class="muted">Not set</span>`;
    const state = reviewState(partner.reviewAt);
    const date = formatDate(partner.reviewAt);
    if (state === 'overdue') return html`<span class="review-overdue">${date} · overdue</span>`;
    if (state === 'due') return html`<span class="review-due">${date} · due</span>`;
    return html`${date}`;
  }

  // ── list ──

  private renderToolbar() {
    const options: Array<{ id: PartnerFilter; label: string }> = [
      { id: 'all', label: 'All' },
      { id: 'active', label: 'Active' },
      { id: 'onboarding', label: 'Onboarding' },
      { id: 'suspended', label: 'Suspended' },
      { id: 'awaiting', label: 'Awaiting approval' },
    ];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search partners" placeholder="Search partners by name or ID"
          .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
        <versola-filter-bar label="Filter partners" .options=${options} .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => this.filter = e.detail.id as PartnerFilter}></versola-filter-bar>
      </div>
    `;
  }

  private renderTable() {
    const columns: DataTableColumn<Partner>[] = [
      { id: 'partner', label: 'Partner', cell: partner => html`
        <button type="button" class="row-link" aria-label=${`Open partner ${partner.displayName} ${partner.id}`}>
          <span class="row-title">${partner.displayName}</span>
          <span class="row-id">${partner.id}</span>
        </button>` },
      { id: 'status', label: 'Status', cell: partner => html`<div class="chips">${partnerStatusChip(partner.status)}</div>` },
      { id: 'group', label: 'Edge group', cell: partner => html`<span class="mono">${partner.groupId}</span>` },
      { id: 'clients', label: 'Clients', cell: partner => {
        const waiting = this.waitingOf(partner).filter(approval => approval.kind === 'client_create').length;
        return html`<div class="cell-stack">
          ${valueChips(this.clientsOf(partner).filter(client => client.id !== partner.consoleClientId).map(client => client.id), { empty: 'None yet' })}
          ${waiting > 0 ? html`<span class="muted small">${waiting === 1 ? '1 waiting approval' : `${waiting} waiting approval`}</span>` : ''}
        </div>`;
      } },
      { id: 'edges', label: 'Edges', cell: partner => valueChips(this.edgesOf(partner).map(edge => edge.id), { empty: 'None enrolled' }) },
      { id: 'review', label: 'Review date', cell: partner => this.reviewCell(partner) },
    ];
    const menuItems = (partner: Partner): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(partner) },
      { label: 'Copy partner ID', onSelect: () => void copyToClipboard(partner.id) },
      ...(this.canManage && partner.status !== 'suspended' ? [{ label: 'Suspend partner', danger: true, ariaLabel: `Suspend partner ${partner.id}`, onSelect: () => { this.openView(partner); this.openEditor('suspend'); } }] : []),
    ];
    return html`
      <versola-data-table selectable
        .columns=${columns}
        .rows=${this.filteredPartners}
        .rowKey=${(partner: Partner) => partner.id}
        .selectedKey=${this.editorMode === 'view' ? this.route.id : null}
        .menuItems=${menuItems}
        .menuLabel=${(partner: Partner) => `Actions for ${partner.id}`}
        @row-select=${(e: CustomEvent<{ row: Partner }>) => this.openView(e.detail.row)}
      ></versola-data-table>
    `;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private tags(values: string[]) {
    return values.length ? html`<div class="tag-list">${values.map(value => html`<span class="tag">${value}</span>`)}</div>` : html`<span class="muted">None</span>`;
  }

  private approvalChip(client: OAuthClient) {
    if (client.approval === 'pending') return html`<span class="chip warn">Awaiting approval</span>`;
    if (client.approval === 'rejected') return html`<span class="chip danger">Rejected</span>`;
    return html`<span class="chip ok">${statusIcon('check', 'icon', 14)}Active</span>`;
  }


  private renderBanners(partner: Partner) {
    const waiting = this.waitingOf(partner);
    return html`
      ${this.created?.partner.id === partner.id ? this.renderBundleBanner(this.created) : ''}
      ${partner.status === 'suspended' ? html`
        <div class="banner warn" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('alert')}Suspended: every call of this partner is refused</div>
            <div>Its clients are disabled in auth, their refresh tokens revoked, and its edges cannot renew their certificates or reach central. Resume from the card at the bottom.</div>
          </div>
        </div>` : ''}
      ${partner.status === 'onboarding' ? html`
        <div class="banner info" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('info')}Onboarding: no edge has enrolled yet</div>
            <div>The partner becomes active when its first edge enrolls with the bootstrap bundle. The partner can sign in to its console only after that (the console client is served by its own edge), so expect no approval requests before.</div>
          </div>
        </div>` : ''}
      ${waiting.map(approval => html`
        <div class="banner warn" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('alert')}${approval.kind === 'profile_change' ? 'A name or logo change' : approval.kind === 'client_change' ? `A change to client ${approval.targetId}` : `Client ${approval.targetId}`} is waiting for your approval</div>
            <div>Since ${formatDate(approval.requestedAt)}. The partner cannot use it until you decide.
              <button type="button" class="link-button" @click=${() => this.openApprovals(approval.id)}>Review request</button></div>
          </div>
        </div>`)}
    `;
  }

  private renderBundleBanner(created: CreatedPartner) {
    return html`
      <div class="banner ok key-banner" role="status">
        <div class="banner-text">
          <div class="banner-title">${statusIcon('check')}Partner created. Save the enrollment token and the bootstrap bundle now.</div>
          <div>They are not shown again. The token is single-use and expires in 24 hours; the bundle goes next to the edge <span class="mono">${created.edge.id}</span> as <span class="mono">bootstrap.json</span>, started with <span class="mono">mode = "partner"</span>.</div>
          <div class="secret-rows">
            <div><div class="secret-label">Enrollment token</div><pre class="secret-value">${created.enrollmentToken}</pre></div>
            <div><div class="secret-label">Bootstrap bundle</div><pre class="secret-value" tabindex="0" role="region" aria-label="Bootstrap bundle">${created.bundle}</pre></div>
          </div>
          <div class="banner-actions">
            <button type="button" class="btn btn-primary btn-sm" @click=${() => this.handleCopy('token')}>Copy token</button>
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.handleCopy('bundle')}>Copy bundle</button>
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.created = null; }}>I've saved them</button>
            ${this.copyFeedback ? html`<span class="copy-feedback" role="status">${this.copyFeedback}</span>` : ''}
          </div>
        </div>
      </div>
    `;
  }

  private renderGroups(partner: Partner) {
    const limits = partner.limits;
    const owned = this.clientsOf(partner).filter(client => client.id !== partner.consoleClientId);
    const edges = this.edgesOf(partner);
    const waiting = this.waitingOf(partner);
    const activity = partnerActivity(partner, this.approvals, this.edges).slice(0, 6);
    const review = reviewState(partner.reviewAt);
    return html`
      <versola-record-card heading="Organisation" subheading="What users see on the consent screen and who to call." ?editable=${this.canManage} .body=${html`
        ${this.row('Display name', partner.displayName)}
        ${this.row('Logo', partner.logoUri ? html`<span class="mono">${partner.logoUri}</span>` : html`<span class="muted">None</span>`)}
        ${this.row('Contact', html`${partner.contact.email}${partner.contact.phone ? html` · ${partner.contact.phone}` : ''}`)}
        ${this.row('Review date', partner.reviewAt ? html`${formatDate(partner.reviewAt)} <span class="muted small">${review === 'overdue' ? '· overdue, a reminder only: nothing stops' : review === 'due' ? '· due, a reminder only: nothing stops' : '· a reminder only'}</span>` : html`<span class="muted">Not set</span>`)}
        ${this.row('Staff', html`<span class="muted">Managed by the partner on its edge. Central records only who acted, in the activity below.</span>`)}
      `} @edit=${() => this.openEditor('organisation')}></versola-record-card>

      <versola-record-card heading="Limits" subheading="What the partner may do by itself. Anything beyond comes to you as an approval request." ?editable=${this.canManage} .body=${html`
        ${this.row('Scopes', this.tags(limits.scopes))}
        ${this.row('Redirect domains', this.tags(limits.redirectDomains))}
        ${this.row('Clients', `Up to ${limits.maxClients}`)}
        ${this.row('Edges in group', `Up to ${limits.maxEdges}`)}
        ${this.row('Token lifetimes', ttlCapsLabel(limits))}
        ${this.row('Client authentication', limits.clientAuthMethods.map(method => method === 'tls_client_auth' ? 'Group certificate (tls_client_auth)' : 'private_key_jwt').join(' or '))}
        ${this.row('Rate limits', html`<span class="mono">${limits.rateLimitPolicy}</span>`)}
        ${this.row('Consent', 'Always shown, names the partner', 'Every partner client')}
      `} @edit=${() => this.openEditor('limits')}></versola-record-card>

      <versola-record-card heading=${`Edge group ${partner.groupId}`} subheading="Partner kind. Its edges get a 14-day certificate that names the group and renew it themselves while the partner is active." .body=${html`
        ${edges.length ? html`<div class="record-list">${edges.map(edge => html`
          <div class="edge-row">
            <button type="button" class="link-button mono" @click=${() => this.navigateTo('edges', edge.id)}>${edge.id}</button>
            <span class="muted small">${edge.publicUrl ? html`${edge.publicUrl} · ` : ''}${edge.certificateNotAfter ? `certificate to ${formatDate(edge.certificateNotAfter)}` : 'token issued, not enrolled'}${edge.lastRenewedAt ? ` · renewed ${formatDate(edge.lastRenewedAt)}` : ''}</span>
            ${certificateChip(edgeCertificateState(edge, partner))}
          </div>`)}</div>` : this.row('Edges', html`<span class="muted">No edge enrolled yet.</span>`)}
        ${this.row('Policies', html`Rate limits <span class="mono">${limits.rateLimitPolicy}</span>; overload and access log are set per edge under Edges.`)}
      `}></versola-record-card>

      <versola-record-card heading="Clients" subheading="Owned by the partner, inside this tenant. Open one to see every setting." .body=${html`
        ${owned.length ? html`<div class="record-list">${owned.map(client => html`
          <div class="edge-row">
            <button type="button" class="link-button mono" @click=${() => this.navigateTo('clients', client.id)}>${client.id}</button>
            <span class="muted small">${getLocalizedDescription(client.clientName)} · ${client.clientType === 'native' ? 'Native' : 'Web'}</span>
            ${this.approvalChip(client)}
          </div>`)}</div>` : this.row('Clients', html`<span class="muted">None yet. The partner creates them from its console; each one waits for your approval.</span>`)}
        ${this.row('Console client', html`<button type="button" class="link-button mono" @click=${() => this.navigateTo('clients', partner.consoleClientId)}>${partner.consoleClientId}</button> <span class="muted small">how the partner's staff sign in</span>`)}
      `}></versola-record-card>

      <versola-record-card heading="Waiting approvals" subheading="What the partner asked for beyond its limits." .body=${html`
        ${waiting.length ? html`<div class="record-list">${waiting.map(approval => html`
          <div class="edge-row">
            <button type="button" class="link-button" @click=${() => this.openApprovals(approval.id)}>${approval.kind === 'profile_change' ? 'Name or logo change' : `${approval.kind === 'client_create' ? 'New client' : 'Change to'} ${approval.targetId}`}</button>
            <span class="muted small">${formatDate(approval.requestedAt)} · <span class="mono">${approval.requestedBy}</span></span>
          </div>`)}</div>` : this.row('Queue', html`<span class="muted">Nothing is waiting.</span>`)}
        <versola-record-row .value=${html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => this.openApprovals()}>Open the approvals queue</button>`}></versola-record-row>
      `}></versola-record-card>

      <versola-record-card heading="Activity" subheading="Partner actions carry the staff member's sub from the forwarded token." .body=${html`
        <div class="record-list">${activity.length ? html`<ul class="timeline">${activity.map(event => html`
          <li><span class="when">${formatDate(event.at)}</span><span><span class=${event.byOperator ? 'operator' : 'actor'}>${event.actor}</span> ${event.text}</span></li>`)}</ul>` : html`<span class="muted">Nothing yet.</span>`}</div>
      `}></versola-record-card>

      <versola-record-card heading="Fixed" fixedLabel="Fixed" .body=${html`
        ${this.row('Partner ID', html`<span class="mono">${partner.id}</span>`, 'Set when added')}
        ${this.row('Edge group', html`<span class="mono">${partner.groupId}</span>`, 'Created with the partner')}
        ${this.row('Console client', html`<span class="mono">${partner.consoleClientId}</span>`, 'Created with the partner')}
        ${this.row('Added', formatDate(partner.createdAt))}
      `}></versola-record-card>

      ${this.canManage ? (partner.status === 'suspended' ? html`
        <versola-record-card heading="Resume partner" subheading="Enables its clients in auth again, lets its edges renew and call central, and lets its staff sign in to the partner console." .body=${html`
          <versola-record-row .value=${html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => this.handleResume(partner)}>Resume partner</button>`}></versola-record-row>
        `}></versola-record-card>` : html`
        <versola-danger-card heading="Suspend partner"
          text="Immediately: its clients are disabled in auth (no tokens), their refresh tokens revoked and back-channel logout sent, every call of its edges refused, certificate renewal included. Reversible."
          actionLabel="Suspend partner" .actionAriaLabel=${`Suspend partner ${partner.id}`}
          @action=${() => this.openEditor('suspend')}></versola-danger-card>`) : ''}
    `;
  }

  private renderMeta(partner: Partner) {
    const waiting = this.waitingOf(partner).length;
    return html`${partnerStatusChip(partner.status)}${waiting ? html`<span class="chip warn">Approval waiting</span>` : ''}`;
  }

  private renderRecordPage(partner: Partner) {
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Partners', onSelect: () => this.closePanel() }, { label: partner.displayName }]}
        .heading=${partner.displayName} .recordId=${partner.id} idLabel="partner ID"
        .meta=${html`<span class="chip">Partner</span>${this.renderMeta(partner)}`}
      ></versola-record-header>
      ${this.renderBanners(partner)}
      <div class="record-page">${this.renderGroups(partner)}</div>
    `;
  }

  // ── editors ──

  private renderOrganisationEditor() {
    const draft = this.organisationDraft;
    const errors = this.submitted ? this.organisationErrors : {};
    const field = (key: keyof OrganisationDraft, label: string, hint: string, type = 'text', placeholder = '') => html`
      <div class="form-group">
        <label for=${`partner-${key}`}>${label}</label>
        <input type=${type} id=${`partner-${key}`} class="compact-input ${errors[key] ? 'input-error' : ''}" .value=${draft[key]}
          aria-invalid=${errors[key] ? 'true' : nothing} aria-describedby=${`partner-${key}-hint`} placeholder=${placeholder}
          @input=${(e: Event) => this.organisationDraft = { ...draft, [key]: (e.target as HTMLInputElement).value }} />
        ${errors[key] ? html`<div class="error-message" id=${`partner-${key}-hint`} role="alert">${errors[key]}</div>` : html`<div class="hint" id=${`partner-${key}-hint`}>${hint}</div>`}
      </div>`;
    return html`
      <div class="editor-box">
        <h3>Organisation</h3>
        <p class="editor-sub">The name and logo are what users see on the consent screen of every client of this partner.</p>
        ${field('displayName', 'Display name *', 'Shown on the consent screen: "<name> wants to sign you in".')}
        ${field('logoUri', 'Logo URL', 'An https URL of an SVG or PNG. Shown next to the name.', 'url', 'https://')}
        ${field('email', 'Contact email *', 'Who the operator reaches about incidents and the review date.', 'email')}
        ${field('phone', 'Contact phone', 'Optional.', 'text', '+7 …')}
        <div class="form-group">
          <label for="partner-reviewAt">Review date</label>
          <input type="date" id="partner-reviewAt" .value=${draft.reviewAt} aria-describedby="partner-reviewAt-hint"
            @input=${(e: Event) => this.organisationDraft = { ...draft, reviewAt: (e.target as HTMLInputElement).value }} />
          <div class="hint" id="partner-reviewAt-hint">A reminder to look at the partner again. Nothing stops on that date.</div>
        </div>
      </div>
    `;
  }

  private renderLimitsEditor() {
    const draft = this.limitsDraft;
    const errors = this.submitted ? this.limitsErrors : {};
    const number = (key: 'maxClients' | 'maxEdges' | 'accessTokenTtlMinutes' | 'refreshTokenTtlDays', label: string, hint: string) => html`
      <div class="form-group">
        <label for=${`limit-${key}`}>${label}</label>
        <input type="number" id=${`limit-${key}`} class="compact-input ${errors[key] ? 'input-error' : ''}" .value=${draft[key]} min="1"
          aria-invalid=${errors[key] ? 'true' : nothing} aria-describedby=${`limit-${key}-hint`}
          @input=${(e: Event) => this.limitsDraft = { ...draft, [key]: (e.target as HTMLInputElement).value }} />
        ${errors[key] ? html`<div class="error-message" id=${`limit-${key}-hint`} role="alert">${errors[key]}</div>` : html`<div class="hint" id=${`limit-${key}-hint`}>${hint}</div>`}
      </div>`;
    const scopeIds = [...new Set(['openid', ...this.scopes.map(scope => scope.id), ...draft.scopes])];
    return html`
      <div class="editor-box">
        <h3>What a partner client may request</h3>
        <p class="editor-sub">A client outside these waits for your approval; a change inside them applies at once.</p>
        <div class="form-group">
          <span class="checkbox-list" role="group" aria-labelledby="limit-scopes-label">
            <label id="limit-scopes-label">Scopes</label>
            ${scopeIds.map(scope => html`
              <label class="checkbox-row">
                <input type="checkbox" .checked=${scope === 'openid' || draft.scopes.includes(scope)} ?disabled=${scope === 'openid'}
                  @change=${(e: Event) => {
                    const checked = (e.target as HTMLInputElement).checked;
                    this.limitsDraft = { ...draft, scopes: checked ? [...draft.scopes, scope] : draft.scopes.filter(candidate => candidate !== scope) };
                  }} />
                <span class="mono">${scope}</span>${scope === 'openid' ? html`<span class="muted small">always</span>` : ''}
              </label>`)}
          </span>
          <div class="hint">Caps what any client of the partner may ask users for. Scopes outside the list need your approval.</div>
        </div>
        <div class="form-group">
          <label for="limit-redirectDomains">Redirect domains *</label>
          <textarea id="limit-redirectDomains" class="compact-input ${errors.redirectDomains ? 'input-error' : ''}" rows="3" .value=${draft.redirectDomains}
            aria-invalid=${errors.redirectDomains ? 'true' : nothing} aria-describedby="limit-redirectDomains-hint" placeholder="pilotpay.example"
            @input=${(e: Event) => this.limitsDraft = { ...draft, redirectDomains: (e.target as HTMLTextAreaElement).value }}></textarea>
          ${errors.redirectDomains ? html`<div class="error-message" id="limit-redirectDomains-hint" role="alert">${errors.redirectDomains}</div>`
            : html`<div class="hint" id="limit-redirectDomains-hint">One per line. Every redirect URI must be https and under one of these (the domain or a subdomain).</div>`}
        </div>
      </div>
      <div class="editor-box">
        <h3>How much</h3>
        ${number('maxClients', 'Clients *', 'Caps how many clients the partner may own, waiting ones included.')}
        ${number('maxEdges', 'Edges in group *', 'Caps how many edges the partner may enroll in its group; it adds them itself.')}
        <div class="field-pair">
          ${number('accessTokenTtlMinutes', 'Access token, minutes *', 'The longest access token a partner client may ask for.')}
          ${number('refreshTokenTtlDays', 'Refresh token, days *', 'The longest refresh token a partner client may ask for.')}
        </div>
      </div>
      <div class="editor-box">
        <h3>How the clients authenticate and how much they may send</h3>
        <div class="form-group">
          <span class="checkbox-list" role="group" aria-labelledby="limit-auth-label">
            <label id="limit-auth-label">Client authentication *</label>
            <label class="checkbox-row"><input type="checkbox" .checked=${draft.tlsClientAuth} aria-describedby="limit-auth-hint"
              @change=${(e: Event) => this.limitsDraft = { ...draft, tlsClientAuth: (e.target as HTMLInputElement).checked }} /><span>Group certificate (<span class="mono">tls_client_auth</span>)</span></label>
            <label class="checkbox-row"><input type="checkbox" .checked=${draft.privateKeyJwt}
              @change=${(e: Event) => this.limitsDraft = { ...draft, privateKeyJwt: (e.target as HTMLInputElement).checked }} /><span><span class="mono">private_key_jwt</span></span></label>
          </span>
          ${errors.tlsClientAuth ? html`<div class="error-message" id="limit-auth-hint" role="alert">${errors.tlsClientAuth}</div>`
            : html`<div class="hint" id="limit-auth-hint">The group certificate names the group (<span class="mono">urn:versola:edge-group:${this.openPartner?.groupId ?? '…'}</span>), so every replica of the partner's edges authenticates as any of its clients.</div>`}
        </div>
        <div class="form-group">
          <label for="limit-rateLimitPolicy">Rate-limit policy</label>
          <select id="limit-rateLimitPolicy" class="compact-input" .value=${draft.rateLimitPolicy} aria-describedby="limit-rateLimitPolicy-hint"
            @change=${(e: Event) => this.limitsDraft = { ...draft, rateLimitPolicy: (e.target as HTMLSelectElement).value }}>
            ${RATE_LIMIT_POLICY_OPTIONS.map(option => html`<option value=${option.id} ?selected=${option.id === draft.rateLimitPolicy}>${option.label}</option>`)}
          </select>
          <div class="hint" id="limit-rateLimitPolicy-hint">Applied to every edge of the group. The partner sees it and cannot change it.</div>
        </div>
      </div>
    `;
  }

  private renderSuspendEditor(partner: Partner) {
    const error = this.submitted && !this.suspendNote.trim() ? 'A note is required: the partner sees it.' : '';
    return html`
      <div class="editor-box">
        <h3>Suspend ${partner.displayName}</h3>
        <p class="editor-sub">Immediately, and until you resume it:</p>
        <ul class="stops">
          <li>auth refuses tokens for every client of the partner, and their refresh tokens are revoked with a back-channel logout;</li>
          <li>its edges cannot renew their certificates, which expire within 14 days;</li>
          <li>every management call from the partner console is refused;</li>
          <li>its staff see "suspended" in the partner console.</li>
        </ul>
        <div class="form-group">
          <label for="suspend-note">Note *</label>
          <textarea id="suspend-note" class="compact-input ${error ? 'input-error' : ''}" rows="3" .value=${this.suspendNote}
            aria-invalid=${error ? 'true' : nothing} aria-describedby="suspend-note-hint" placeholder="Why, and what the partner should do"
            @input=${(e: Event) => this.suspendNote = (e.target as HTMLTextAreaElement).value}></textarea>
          ${error ? html`<div class="error-message" id="suspend-note-hint" role="alert">${error}</div>`
            : html`<div class="hint" id="suspend-note-hint">Shown to the partner's staff in their console and kept in the audit log.</div>`}
        </div>
      </div>
    `;
  }

  private renderEditor(partner: Partner) {
    return html`
      <form id="partner-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${this.editTarget === 'organisation' ? this.renderOrganisationEditor()
          : this.editTarget === 'limits' ? this.renderLimitsEditor()
            : this.renderSuspendEditor(partner)}
      </form>
    `;
  }

  private renderPanel(partner: Partner | null) {
    const mode = this.editorMode;
    if (mode !== 'view' && mode !== 'edit') return '';
    if (!partner) return '';
    const target = this.editTarget;
    const heading = mode === 'edit' ? (target === 'organisation' ? 'Edit organisation' : target === 'limits' ? 'Edit limits' : 'Suspend partner') : partner.displayName;
    const submitLabel = target === 'organisation' ? 'Save organisation' : target === 'limits' ? 'Save limits' : 'Suspend partner';
    return html`
      <versola-side-panel kind="Partner" .heading=${heading} .recordId=${partner.id} idLabel="partner ID" .mode=${mode} ?wide=${target === 'limits'}
        .parentLabel=${partner.displayName}
        .meta=${mode === 'view' ? this.renderMeta(partner) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()}
        @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">
          ${mode === 'view' ? html`${this.renderBanners(partner)}${this.renderGroups(partner)}` : this.renderEditor(partner)}
        </div>
        ${mode === 'edit' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="partner-editor" class="btn ${target === 'suspend' ? 'btn-danger' : 'btn-primary'}" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : submitLabel}</button>
          </div>` : ''}
      </versola-side-panel>
    `;
  }

  private renderTabs() {
    const value = this.route.tab === 'approvals' ? 'approvals' : 'partners';
    return html`
      <versola-tabs label="Partners sections" .tabs=${[{ id: 'partners', label: 'Partners' }, { id: 'approvals', label: 'Approvals' }]} .value=${value}
        @tab-change=${(e: CustomEvent<{ id: string }>) => dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null, tab: e.detail.id === 'approvals' ? 'approvals' : null })}>
      </versola-tabs>
    `;
  }

  render() {
    if (!this.tenantId) {
      return html`
        <content-header title="Partners" description="Organisations that run their own edge and offer “Sign in with you” on their sites and apps."></content-header>
        <versola-empty-state heading="Select a tenant" text="A partner's clients live in a tenant. Pick one in the sidebar."></versola-empty-state>
      `;
    }

    if (this.route.create) {
      return html`<versola-partner-wizard .tenantId=${this.tenantId} .scopes=${this.scopes} .existingPartnerIds=${this.partners.map(partner => partner.id)}
        .existingEdgeIds=${this.edges.map(edge => edge.id)}
        @created=${this.handleCreated}
        @cancel=${() => dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null })}></versola-partner-wizard>`;
    }

    const partner = this.openPartner;
    if (this.route.tab === 'approvals') {
      return html`
        <content-header title="Partners" description="What partners asked for beyond their limits. A request is a diff against what is registered; approving applies it, rejecting leaves the client as it was."></content-header>
        ${this.renderTabs()}
        <versola-partner-approvals .tenantId=${this.tenantId} .partners=${this.partners} .approvals=${this.approvals} .clients=${this.clients}
          .canManage=${this.canManage} .route=${this.route} ?loading=${this.isLoading}
          @decided=${() => this.reloadAfterDecision()}></versola-partner-approvals>
      `;
    }

    const panel = this.renderPanel(partner);
    if (partner && this.route.layout === 'page') {
      return html`${this.renderRecordPage(partner)}${this.editorMode === 'view' ? '' : panel}`;
    }

    return html`
      <content-header title="Partners" description="Organisations that run their own edge on their own infrastructure and offer “Sign in with you” on their sites and apps. A partner is a set of clients inside this tenant, never a tenant of its own.">
        ${this.partners.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick}>+ Add partner</button>` : ''}
      </content-header>
      ${this.renderTabs()}
      ${this.errorMessage && !this.isLoading && this.partners.length > 0 ? html`
        <div class="banner warn" role="alert"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}${this.errorMessage}</div></div></div>` : ''}
      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage && this.partners.length === 0 ? html`
        <versola-error-card heading="Could not load partners" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.partners.length === 0 ? html`
        <versola-empty-state heading="No partners yet" text=${this.canManage ? 'Add a partner to create its edge group, console client and first edge in one go.' : 'No partners in this tenant.'}>
          ${this.canManage ? html`<button class="btn btn-primary" @click=${this.handleCreateClick}>+ Add partner</button>` : ''}
        </versola-empty-state>
      ` : html`
        ${this.renderToolbar()}
        ${this.filteredPartners.length === 0 ? html`<versola-empty-state heading="No partners match"></versola-empty-state>` : this.renderTable()}
      `}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partners-list': VersolaPartnersList;
  }
}
