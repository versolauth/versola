import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles, wizardStyles } from '../../styles/components';
import { statusIcon } from '../../components/status-icon';
import { valueChips } from '../../components/value-chips';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from '../../components/data-table';
import { confirmDestructiveAction } from '../../utils/confirm-dialog';
import { confirmDiscardDraft } from '../../utils/draft-guard';
import { copyToClipboard, formatDate } from '../../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../../utils/record-route';
import { approvalFieldLabel, redirectUriWithinDomains, ttlCapsLabel, ttlLabel } from '../../utils/partners';
import { deleteClient, fetchClients, fetchSettings, isPartnerApiError, rotateClientKeys, updateClient, withdrawClient, writeErrorMessage } from '../partner-api';
import type { PartnerClient, PartnerMe, PartnerSettings } from '../partner-types';
import { partnerViewStyles } from '../partner-styles';
import { consentSketchStyles } from './partner-client-wizard';
import '../../components/content-header';
import '../../components/danger-card';
import '../../components/data-table';
import '../../components/empty-state';
import '../../components/error-card';
import '../../components/filter-bar';
import '../../components/loading-cards';
import '../../components/record-card';
import '../../components/record-header';
import '../../components/side-panel';
import './partner-client-wizard';

type Filter = 'all' | 'active' | 'pending' | 'rejected';
type EditTarget = 'general' | 'signin' | 'tokens';

type Draft = {
  clientName: string; logoUri: string; policyUri: string; tosUri: string;
  redirectUris: string; scope: string[]; backChannelLogoutUri: string;
  accessTokenTtlMinutes: string; refreshTokenTtlDays: string;
};

/**
 * Clients, the partner's: table + panel (or page), the record with an Integrate card for an active
 * client, the fields that wait for the operator marked on a pending one, the rows the operator fixed
 * with the lock mark, the editable rows with the limits as inline hints, rotate keys, withdraw and
 * delete. `&new=1` is the wizard.
 */
@customElement('versola-partner-clients')
export class VersolaPartnerClients extends LitElement {
  @property({ attribute: false }) me!: PartnerMe;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private clients: PartnerClient[] = [];
  @state() private settings: PartnerSettings | null = null;
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private notice: { clientId: string; text: string; tone: 'ok' | 'warn' } | null = null;
  @state() private searchQuery = '';
  @state() private filter: Filter = 'all';
  @state() private draft: Draft = VersolaPartnerClients.emptyDraft();
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @state() private copyFeedback = '';
  @query('versola-data-table') private table?: VersolaDataTable<PartnerClient>;
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
    iconButtonStyles,
    recordStyles,
    wizardStyles,
    partnerViewStyles,
    consentSketchStyles,
    css`
      .uri-list { display: grid; gap: 0.25rem; }
      .uri-item { font-family: var(--font-mono); font-size: var(--text-sm); overflow-wrap: anywhere; display: flex; gap: 0.5rem; flex-wrap: wrap; align-items: center; }
      .waiting-mark { color: var(--warning-text); font-size: var(--text-xs); font-weight: 600; display: inline-flex; gap: 0.25rem; align-items: center; }
      .field-pair { display: grid; grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr)); gap: var(--spacing-md); max-width: 22.8rem; }
      .field-pair input { max-width: none; }
    `,
  ];

  private static emptyDraft(): Draft {
    return { clientName: '', logoUri: '', policyUri: '', tosUri: '', redirectUris: '', scope: ['openid'], backChannelLogoutUri: '', accessTokenTtlMinutes: '5', refreshTokenTtlDays: '14' };
  }

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('route') || changed.has('clients')) {
      const mode = this.editorMode;
      const key = mode === 'view' ? '' : `${mode}:${this.route.id ?? ''}:${this.route.edit ?? ''}`;
      if (key !== this.draftKey) {
        this.draftKey = key;
        if (mode !== 'view') this.resetDraft();
      }
      if (changed.has('route') && this.notice && this.route.id !== this.notice.clientId) this.notice = null;
    }
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [clients, settings] = await Promise.all([fetchClients(), fetchSettings().catch(() => null)]);
      this.clients = clients;
      this.settings = settings;
    } catch (error) {
      this.clients = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load clients';
    } finally {
      this.isLoading = false;
    }
  }

  // ── routing ──

  private get openClient(): PartnerClient | null {
    return this.route.id ? this.clients.find(client => client.id === this.route.id) ?? null : null;
  }

  private get editTarget(): EditTarget | null {
    const edit = this.route.edit;
    return this.openClient && (edit === 'general' || edit === 'signin' || edit === 'tokens') ? edit : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.editTarget ? 'edit' : 'view';
  }

  private openView(client: PartnerClient) {
    dispatchRecordRoute(this, { id: client.id, edit: null, create: false });
  }

  private closePanel() {
    const key = this.route.id;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private openEditor(target: EditTarget) {
    dispatchRecordRoute(this, { edit: target, create: false });
  }

  private async cancelEditor() {
    if (JSON.stringify(this.draft) !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  private resetDraft() {
    const client = this.openClient;
    this.draft = client ? {
      clientName: client.clientName, logoUri: client.logoUri ?? '', policyUri: client.policyUri ?? '', tosUri: client.tosUri ?? '',
      redirectUris: client.redirectUris.join('\n'), scope: [...client.scope], backChannelLogoutUri: client.backChannelLogoutUri ?? '',
      accessTokenTtlMinutes: String(Math.max(1, Math.round(client.accessTokenTtl / 60))), refreshTokenTtlDays: String(Math.max(1, Math.round(client.refreshTokenTtl / 86_400))),
    } : VersolaPartnerClients.emptyDraft();
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
    this.draftBaseline = JSON.stringify(this.draft);
  }

  private get limits() {
    return this.me.partner.limits;
  }

  private get draftRedirects(): string[] {
    return this.draft.redirectUris.split('\n').map(line => line.trim()).filter(Boolean);
  }

  private get draftErrors(): Partial<Record<keyof Draft, string>> {
    const errors: Partial<Record<keyof Draft, string>> = {};
    const draft = this.draft;
    const target = this.editTarget;
    if (target === 'general') {
      if (!draft.clientName.trim()) errors.clientName = 'A display name is required.';
      if (draft.logoUri.trim() && !/^https:\/\/\S+$/.test(draft.logoUri.trim())) errors.logoUri = 'An https URL.';
    }
    if (target === 'signin') {
      const uris = this.draftRedirects;
      if (uris.length === 0) errors.redirectUris = 'At least one redirect URI.';
      else if (uris.some(uri => !/^https:\/\/\S+$/.test(uri))) errors.redirectUris = 'One https URL per line.';
      if (draft.backChannelLogoutUri.trim() && !/^https:\/\/\S+$/.test(draft.backChannelLogoutUri.trim())) errors.backChannelLogoutUri = 'An https URL.';
    }
    if (target === 'tokens') {
      const positive = (value: string) => /^\d+$/.test(value.trim()) && Number(value) > 0;
      if (!positive(draft.accessTokenTtlMinutes)) errors.accessTokenTtlMinutes = 'A whole number of minutes.';
      else if (Number(draft.accessTokenTtlMinutes) * 60 > this.limits.accessTokenTtlMax) errors.accessTokenTtlMinutes = `At most ${ttlLabel(this.limits.accessTokenTtlMax)}: the operator's cap.`;
      if (!positive(draft.refreshTokenTtlDays)) errors.refreshTokenTtlDays = 'A whole number of days.';
      else if (Number(draft.refreshTokenTtlDays) * 86_400 > this.limits.refreshTokenTtlMax) errors.refreshTokenTtlDays = `At most ${ttlLabel(this.limits.refreshTokenTtlMax)}: the operator's cap.`;
    }
    return errors;
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.submitted = true;
    const client = this.openClient;
    if (!client || Object.keys(this.draftErrors).length > 0) return;
    this.isSaving = true;
    this.formError = '';
    const draft = this.draft;
    try {
      const saved = await updateClient({
        id: client.id, kind: client.kind,
        clientName: draft.clientName.trim(), logoUri: draft.logoUri.trim() || null, policyUri: draft.policyUri.trim() || null, tosUri: draft.tosUri.trim() || null,
        redirectUris: this.draftRedirects, scope: [...new Set(['openid', ...draft.scope])], backChannelLogoutUri: draft.backChannelLogoutUri.trim() || null,
        accessTokenTtl: Number(draft.accessTokenTtlMinutes) * 60, refreshTokenTtl: Number(draft.refreshTokenTtlDays) * 86_400,
        postLogoutRedirectUris: client.postLogoutRedirectUris,
      });
      this.clients = this.clients.map(candidate => candidate.id === client.id ? saved : candidate);
      this.notice = saved.approval.status === 'pending' && saved.approval.waitingFields.length
        ? { clientId: client.id, tone: 'warn', text: `Saved. ${saved.approval.waitingFields.map(approvalFieldLabel).join(', ')} wait${saved.approval.waitingFields.length === 1 ? 's' : ''} for ${this.me.partner.operator.name}; the rest applies on your edges' next sync.` }
        : { clientId: client.id, tone: 'ok', text: 'Saved. Your edges apply it on their next sync.' };
      dispatchRecordRoute(this, { edit: null, create: false });
    } catch (error) {
      this.formError = writeErrorMessage(error);
    } finally {
      this.isSaving = false;
    }
  }

  private async handleWithdraw(client: PartnerClient) {
    const neverApproved = client.approval.kind === 'create';
    const confirmed = await confirmDestructiveAction({
      title: 'Withdraw the request',
      messagePrefix: 'Withdraw the request for ',
      messageSubject: client.id,
      messageSuffix: neverApproved ? '? The client is removed; you can create it again without the items that need the operator.' : '? The client stays as registered; the requested change is dropped.',
      confirmLabel: 'Withdraw',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      const result = await withdrawClient(client.id);
      if (result.approval.status === 'rejected') {
        this.clients = this.clients.filter(candidate => candidate.id !== client.id);
        this.closePanel();
      } else {
        this.clients = this.clients.map(candidate => candidate.id === client.id ? result : candidate);
      }
    } catch (error) {
      this.errorMessage = writeErrorMessage(error);
    }
  }

  private async handleDelete(client: PartnerClient) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete client',
      messagePrefix: 'Delete client ',
      messageSubject: client.id,
      messageSuffix: '? Its users are signed out and the operator is notified. This cannot be undone.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      await deleteClient(client.id);
      this.clients = this.clients.filter(candidate => candidate.id !== client.id);
      if (this.route.id === client.id) this.closePanel();
    } catch (error) {
      this.errorMessage = writeErrorMessage(error);
    }
  }

  private async handleRotate(client: PartnerClient) {
    const confirmed = await confirmDestructiveAction({
      title: 'Rotate keys',
      messagePrefix: 'Rotate the keys of ',
      messageSubject: client.id,
      messageSuffix: '? Your edges start signing with a new key; the old one stops verifying after their next sync.',
      confirmLabel: 'Rotate',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      const result = await rotateClientKeys(client.id);
      this.notice = { clientId: client.id, tone: 'ok', text: `Keys rotated at ${formatDate(result.rotatedAt)}. Your edges pick the new key up on their next sync.` };
    } catch (error) {
      this.errorMessage = writeErrorMessage(error);
    }
  }

  private handleCreated(event: CustomEvent<PartnerClient>) {
    this.clients = [event.detail, ...this.clients];
    dispatchRecordRoute(this, { id: event.detail.id, edit: null, create: false, layout: null });
  }

  private async copySnippet(client: PartnerClient) {
    const copied = await copyToClipboard(this.snippet(client));
    this.copyFeedback = copied ? 'Snippet copied.' : 'Could not copy the snippet.';
  }

  // ── derived ──

  private get filtered(): PartnerClient[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.clients.filter(client => {
      const status = client.approval.status;
      const matches = this.filter === 'all' || (this.filter === 'active' ? status === 'approved' : status === this.filter);
      return matches && (!query || client.id.toLowerCase().includes(query) || client.clientName.toLowerCase().includes(query));
    });
  }

  private approvalChip(client: PartnerClient) {
    const status = client.approval.status;
    if (status === 'pending') return html`<span class="chip warn">Awaiting approval</span>`;
    if (status === 'rejected') return html`<span class="chip danger">Rejected</span>`;
    return html`<span class="chip ok">${statusIcon('check', 'icon', 14)}Active</span>`;
  }

  private waits(client: PartnerClient, field: string): boolean {
    return client.approval.status === 'pending' && client.approval.waitingFields.includes(field);
  }

  private waitingMark(client: PartnerClient, field: string) {
    return this.waits(client, field) ? html`<span class="waiting-mark">${statusIcon('alert', 'icon', 12)}needs operator</span>` : '';
  }

  private snippet(client: PartnerClient): string {
    return `<a href="${client.loginUrl}">Sign in with ${this.me.partner.operator.name}</a>`;
  }

  // ── list ──

  private renderToolbar() {
    const options: Array<{ id: Filter; label: string }> = [{ id: 'all', label: 'All' }, { id: 'active', label: 'Active' }, { id: 'pending', label: 'Awaiting approval' }, { id: 'rejected', label: 'Rejected' }];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search clients" placeholder="Search clients by name or ID"
          .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
        <versola-filter-bar label="Filter clients" .options=${options} .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => this.filter = e.detail.id as Filter}></versola-filter-bar>
      </div>`;
  }

  private renderTable() {
    const columns: DataTableColumn<PartnerClient>[] = [
      { id: 'client', label: 'Client', cell: client => html`
        <button type="button" class="row-link" aria-label=${`Open client ${client.clientName} ${client.id}`}>
          <span class="row-title">${client.clientName}</span>
          <span class="row-id">${client.id}</span>
        </button>` },
      { id: 'kind', label: 'Kind', class: 'muted', cell: client => client.kind === 'native' ? 'Native' : 'Web' },
      { id: 'approval', label: 'Approval', cell: client => html`<div class="chips">${this.approvalChip(client)}</div>` },
      { id: 'redirects', label: 'Redirect URIs', cell: client => valueChips(client.redirectUris, { max: 1 }) },
      { id: 'scopes', label: 'Scopes', cell: client => valueChips(client.scope, { max: 3 }) },
    ];
    const menuItems = (client: PartnerClient): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(client) },
      { label: 'Copy client ID', onSelect: () => void copyToClipboard(client.id) },
      ...(this.canManage ? [{ label: 'Delete client', danger: true, ariaLabel: `Delete client ${client.id}`, onSelect: () => void this.handleDelete(client) }] : []),
    ];
    return html`
      <versola-data-table selectable .columns=${columns} .rows=${this.filtered} .rowKey=${(client: PartnerClient) => client.id}
        .selectedKey=${this.editorMode === 'view' ? this.route.id : null} .menuItems=${menuItems} .menuLabel=${(client: PartnerClient) => `Actions for ${client.id}`}
        @row-select=${(e: CustomEvent<{ row: PartnerClient }>) => this.openView(e.detail.row)}></versola-data-table>`;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private renderBanners(client: PartnerClient) {
    const me = this.me;
    const waiting = client.approval.waitingFields.map(approvalFieldLabel);
    return html`
      ${this.notice?.clientId === client.id ? html`<div class="banner ${this.notice.tone}" role="status"><div class="banner-text"><div class="banner-title">${statusIcon(this.notice.tone === 'ok' ? 'check' : 'alert')}${this.notice.text}</div></div></div>` : ''}
      ${client.approval.status === 'pending' ? html`
        <div class="banner warn" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('alert')}Waiting for ${me.partner.operator.name}${client.approval.requestedAt ? ` since ${formatDate(client.approval.requestedAt)}` : ''}</div>
            <div>${waiting.length ? `${waiting.join(', ')} ${waiting.length === 1 ? 'needs' : 'need'} the operator.` : 'The operator reviews it.'} ${this.canManage ? 'You can withdraw the request and resubmit without them.' : ''}</div>
          </div>
        </div>` : ''}
      ${client.approval.status === 'rejected' ? html`
        <div class="banner warn" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('x')}Rejected by ${me.partner.operator.name}</div>
            <div>${client.approval.note ?? 'No note was given.'} The client cannot sign anyone in; change it and the operator reviews it again, or delete it.</div>
          </div>
        </div>` : ''}
    `;
  }

  private renderGroups(client: PartnerClient) {
    const me = this.me;
    const limits = this.limits;
    const active = client.approval.status === 'approved';
    const edit = this.canManage;
    return html`
      ${active ? html`
        <versola-record-card heading="Integrate" subheading="Put the button on your site; your edge does the rest." .body=${html`
          <div class="record-list">
            <pre class="code-block">${this.snippet(client)}</pre>
            <div class="key-actions">
              <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.copySnippet(client)}>Copy snippet</button>
              ${this.copyFeedback ? html`<span class="copy-feedback" role="status">${this.copyFeedback}</span>` : ''}
            </div>
          </div>
          ${this.row('After sign-in', html`the browser returns to <span class="mono">${client.redirectUris[0] ?? '…'}</span> with the edge session cookie`)}
          ${this.row('Your APIs', html`behind the edge see the user at <span class="mono">${me.edge.publicUrl}/resources/&lt;resource&gt;/…</span>`)}
        `}></versola-record-card>` : ''}

      <versola-record-card heading="General" subheading="What users see on the consent screen. The operator approves the name and logo." ?editable=${edit} .body=${html`
        ${this.row('Display name', html`${client.clientName} ${this.waitingMark(client, 'clientName')}`)}
        ${this.row('Logo', html`${client.logoUri ? html`<span class="mono">${client.logoUri}</span>` : html`<span class="muted">None</span>`} ${this.waitingMark(client, 'logoUri')}`)}
        ${this.row('Policy · Terms', client.policyUri || client.tosUri ? html`<span class="mono">${client.policyUri ?? '—'} · ${client.tosUri ?? '—'}</span>` : html`<span class="muted">None</span>`)}
      `} @edit=${() => this.openEditor('general')}></versola-record-card>

      <versola-record-card heading="Sign-in" subheading="What the user goes through. The operator's rules (passkeys, OTP, step-up) apply on top." ?editable=${edit} .body=${html`
        ${this.row('Redirect URIs', html`<div class="uri-list">${client.redirectUris.map(uri => html`<div class="uri-item">${uri}${redirectUriWithinDomains(uri, limits.redirectDomains) ? '' : html`<span class="chip warn">outside your domains</span>`}</div>`)}</div>${this.waitingMark(client, 'redirectUris')}`)}
        ${this.row('Scopes', html`<div class="tag-list">${client.scope.map(scope => html`<span class="tag">${scope}</span>`)}</div>${this.waitingMark(client, 'scope')}`)}
        ${this.row('Back-channel logout', client.backChannelLogoutUri ? html`<span class="mono">${client.backChannelLogoutUri}</span>` : html`<span class="muted">None</span>`)}
        ${this.row('Flow', 'Authorization code + PKCE, PAR required', 'Operator')}
        ${this.row('Consent', `Always shown, names ${me.partner.displayName}`, 'Operator')}
      `} @edit=${() => this.openEditor('signin')}></versola-record-card>

      <versola-record-card heading="Credentials & tokens" subheading="How your edge authenticates as this client, and how long its tokens live." ?editable=${edit} .body=${html`
        ${this.row('Client authentication', client.fixed.authMethod === 'tls_client_auth' ? html`tls_client_auth with your group certificate <span class="mono">urn:versola:edge-group:${client.fixed.group}</span>` : 'private_key_jwt with the keys your edge holds', 'Operator')}
        ${this.row('Token lifetimes', html`access ${ttlLabel(client.accessTokenTtl)} · refresh ${ttlLabel(client.refreshTokenTtl)} <span class="muted small">(caps ${ttlCapsLabel(limits)})</span>${this.waitingMark(client, 'accessTokenTtl')}${this.waitingMark(client, 'refreshTokenTtl')}`)}
        ${this.row('Refresh rotation', 'On', 'Operator')}
        ${edit && client.fixed.authMethod === 'private_key_jwt' ? html`<versola-record-row .value=${html`<div class="key-actions"><button type="button" class="btn btn-secondary btn-sm" @click=${() => this.handleRotate(client)}>Rotate keys</button></div>`}></versola-record-row>` : ''}
      `} @edit=${() => this.openEditor('tokens')}></versola-record-card>

      <versola-record-card heading="Served by" .body=${html`
        ${this.row('Edges', html`the edges of group <span class="mono">${client.fixed.group}</span>`, 'Your group')}
        ${this.row('Created', formatDate(client.createdAt))}
      `}></versola-record-card>

      ${edit ? html`
        <versola-danger-card heading="Delete client" text="Users of this client are signed out; the operator is notified. This cannot be undone."
          actionLabel="Delete client" .actionAriaLabel=${`Delete client ${client.id}`} @action=${() => this.handleDelete(client)}></versola-danger-card>` : ''}
    `;
  }

  private renderMeta(client: PartnerClient) {
    return html`${this.approvalChip(client)}<span class="chip">${client.kind === 'native' ? 'Native' : 'Web'}</span>`;
  }

  private renderRecordPage(client: PartnerClient) {
    return html`
      <versola-record-header .crumbs=${[{ label: 'Clients', onSelect: () => this.closePanel() }, { label: client.clientName }]}
        .heading=${client.clientName} .recordId=${client.id} idLabel="client ID" .meta=${this.renderMeta(client)}></versola-record-header>
      ${this.renderBanners(client)}
      <div class="record-page">${this.renderGroups(client)}</div>
    `;
  }

  // ── editors ──

  private input(key: keyof Draft, label: string, hint: string, options: { type?: string; placeholder?: string } = {}) {
    const error = this.submitted ? this.draftErrors[key] : undefined;
    const id = `edit-${key}`;
    return html`
      <div class="form-group">
        <label for=${id}>${label}</label>
        <input type=${options.type ?? 'text'} id=${id} class="compact-input ${error ? 'input-error' : ''}" .value=${String(this.draft[key])} placeholder=${options.placeholder ?? ''}
          aria-invalid=${error ? 'true' : nothing} aria-describedby=${`${id}-hint`}
          @input=${(e: Event) => this.draft = { ...this.draft, [key]: (e.target as HTMLInputElement).value }} />
        ${error ? html`<div class="error-message" id=${`${id}-hint`} role="alert">${error}</div>` : html`<div class="hint" id=${`${id}-hint`}>${hint}</div>`}
      </div>`;
  }

  private renderEditor(client: PartnerClient) {
    const limits = this.limits;
    const target = this.editTarget;
    const errors = this.submitted ? this.draftErrors : {};
    const draft = this.draft;
    return html`
      <form id="client-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${target === 'general' ? html`
          <div class="editor-box">
            <h3>General</h3>
            <p class="editor-sub">A new name or logo goes to ${this.me.partner.operator.name} for approval; the links apply at once.</p>
            ${this.input('clientName', 'Display name *', 'Shown on the consent screen; the operator approves a change.')}
            ${this.input('logoUri', 'Logo URL', 'The operator approves a change. Optional.', { type: 'url', placeholder: 'https://' })}
            ${this.input('policyUri', 'Privacy policy URL', 'Optional.', { type: 'url' })}
            ${this.input('tosUri', 'Terms URL', 'Optional.', { type: 'url' })}
          </div>` : target === 'signin' ? html`
          <div class="editor-box">
            <h3>Sign-in</h3>
            <div class="form-group">
              <label for="edit-redirectUris">Redirect URIs *</label>
              <textarea id="edit-redirectUris" class="compact-input ${errors.redirectUris ? 'input-error' : ''}" rows="3" .value=${draft.redirectUris}
                aria-invalid=${errors.redirectUris ? 'true' : nothing} aria-describedby="edit-redirectUris-hint"
                @input=${(e: Event) => this.draft = { ...draft, redirectUris: (e.target as HTMLTextAreaElement).value }}></textarea>
              ${errors.redirectUris ? html`<div class="error-message" id="edit-redirectUris-hint" role="alert">${errors.redirectUris}</div>`
                : html`<div class="hint" id="edit-redirectUris-hint">One per line. https under ${limits.redirectDomains.join(', ')}; a URI elsewhere waits for the operator.</div>`}
            </div>
            <div class="form-group">
              <span class="checkbox-list" role="group" aria-labelledby="edit-scopes-label">
                <label id="edit-scopes-label">Scopes</label>
                ${[...new Set([...limits.scopes, ...draft.scope])].map(scope => html`
                  <label class="checkbox-row">
                    <input type="checkbox" .checked=${scope === 'openid' || draft.scope.includes(scope)} ?disabled=${scope === 'openid'}
                      @change=${(e: Event) => {
                        const checked = (e.target as HTMLInputElement).checked;
                        this.draft = { ...this.draft, scope: checked ? [...this.draft.scope, scope] : this.draft.scope.filter(candidate => candidate !== scope) };
                      }} />
                    <span class="mono">${scope}</span>${limits.scopes.includes(scope) ? '' : html`<span class="chip warn">outside your limits</span>`}
                  </label>`)}
              </span>
              <div class="hint">What the operator allowed you. openid is always present.</div>
            </div>
            ${this.input('backChannelLogoutUri', 'Back-channel logout URI', 'Where auth tells your edge that a user signed out everywhere.', { type: 'url' })}
          </div>` : html`
          <div class="editor-box">
            <h3>Token lifetimes</h3>
            <p class="editor-sub">Within the operator's caps: ${ttlCapsLabel(limits)}.</p>
            <div class="field-pair">
              ${this.input('accessTokenTtlMinutes', 'Access token, minutes *', `At most ${ttlLabel(limits.accessTokenTtlMax)}.`, { type: 'number' })}
              ${this.input('refreshTokenTtlDays', 'Refresh token, days *', `At most ${ttlLabel(limits.refreshTokenTtlMax)}.`, { type: 'number' })}
            </div>
            <div class="plan">
              <div class="plan-line"><span class="plan-mark">${statusIcon('lock', '', 14)}</span><span>Client authentication: ${client.fixed.authMethod}<div class="plan-why">Set by the operator for this client.</div></span></div>
            </div>
          </div>`}
      </form>`;
  }

  private renderPanel(client: PartnerClient | null) {
    const mode = this.editorMode;
    if (mode === 'create' || !client) return '';
    const target = this.editTarget;
    const heading = mode === 'edit' ? (target === 'general' ? 'Edit general' : target === 'signin' ? 'Edit sign-in' : 'Edit token lifetimes') : client.clientName;
    const submitLabel = target === 'general' ? 'Save general' : target === 'signin' ? 'Save sign-in' : 'Save token lifetimes';
    const pending = client.approval.status === 'pending';
    return html`
      <versola-side-panel kind="Client" .heading=${heading} .recordId=${client.id} idLabel="client ID" .mode=${mode} .parentLabel=${client.clientName}
        .meta=${mode === 'view' ? this.renderMeta(client) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()} @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">${mode === 'view' ? html`${this.renderBanners(client)}${this.renderGroups(client)}` : this.renderEditor(client)}</div>
        ${mode === 'edit' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="client-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : submitLabel}</button>
          </div>` : pending && this.canManage ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.handleWithdraw(client)}>Withdraw request</button>
            <button type="button" class="btn btn-primary" disabled title="The client cannot sign anyone in until the operator approves it">Sign in with this client</button>
          </div>` : ''}
      </versola-side-panel>`;
  }

  render() {
    if (this.route.create) {
      return html`<versola-partner-client-wizard .me=${this.me} .existingIds=${this.clients.map(client => client.id)} .defaults=${this.settings?.defaults ?? null}
        @created=${this.handleCreated} @cancel=${() => dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null })}></versola-partner-client-wizard>`;
    }
    const client = this.openClient;
    const panel = this.renderPanel(client);
    if (client && this.route.layout === 'page') {
      return html`${this.renderRecordPage(client)}${this.editorMode === 'view' ? '' : panel}`;
    }
    const atCap = this.clients.length >= this.limits.maxClients;
    return html`
      <content-header title="Clients" description="Your applications registered with the operator. A new client or a change outside your limits waits for the operator; everything else applies at once.">
        ${this.clients.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" ?disabled=${atCap} title=${atCap ? `The operator allows you ${this.limits.maxClients} clients` : ''}
            @click=${() => dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null })}>+ Create client</button>` : ''}
      </content-header>
      ${this.errorMessage && this.clients.length > 0 ? html`<div class="banner warn" role="alert"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}${this.errorMessage}</div></div></div>` : ''}
      ${this.isLoading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
        : this.errorMessage && this.clients.length === 0 ? html`<versola-error-card heading="Could not load clients" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>`
          : this.clients.length === 0 ? html`
            <versola-empty-state heading="No clients yet" text=${this.canManage ? 'Create your first client; the operator approves it before it can sign anyone in.' : 'No clients registered.'}>
              ${this.canManage ? html`<button class="btn btn-primary" @click=${() => dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null })}>+ Create client</button>` : ''}
            </versola-empty-state>`
            : html`${this.renderToolbar()}${this.filtered.length === 0 ? html`<versola-empty-state heading="No clients match"></versola-empty-state>` : this.renderTable()}`}
      ${panel}
    `;
  }
}

/** Shared by the specs: whether an error is the one a suspended partner gets. */
export const isSuspendedError = (error: unknown) => isPartnerApiError(error, 'partner_suspended');

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-clients': VersolaPartnerClients;
  }
}
