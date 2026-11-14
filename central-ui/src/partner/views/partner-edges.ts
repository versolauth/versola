import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../../styles/components';
import { statusIcon } from '../../components/status-icon';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from '../../components/data-table';
import { confirmDestructiveAction } from '../../utils/confirm-dialog';
import { confirmDiscardDraft } from '../../utils/draft-guard';
import { copyToClipboard, formatDate } from '../../utils/helpers';
import { relativeTime } from '../../utils/user-display';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../../utils/record-route';
import { validateEdgeId } from '../../utils/validators';
import { createEdge, deleteEdge, fetchEdges, issueEnrollmentToken, revokeEnrollmentToken, writeErrorMessage, readErrorMessage } from '../partner-api';
import type { EnrollmentToken, PartnerEdge, PartnerMe } from '../partner-types';
import { partnerViewStyles } from '../partner-styles';
import '../../components/content-header';
import '../../components/danger-card';
import '../../components/data-table';
import '../../components/empty-state';
import '../../components/error-card';
import '../../components/loading-cards';
import '../../components/record-card';
import '../../components/record-header';
import '../../components/side-panel';

type Issued = EnrollmentToken & { edgeId: string };

/**
 * Edges, the partner's own deployments in its group: the table (id, public URL, status,
 * certificate), Add edge up to `maxEdges`, which ends on the new record with the
 * enrollment token shown once; the record with the link to the operator,
 * the certificate and the operator's policies read-only; Revoke an unused token.
 */
@customElement('versola-partner-edges')
export class VersolaPartnerEdges extends LitElement {
  @property({ attribute: false }) me!: PartnerMe;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private edges: PartnerEdge[] = [];
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private draftId = '';
  @state() private draftUrl = '';
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @state() private issued: Issued | null = null;
  @state() private copyFeedback = '';
  @query('versola-data-table') private table?: VersolaDataTable<PartnerEdge>;
  private draftKey = '';

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
    partnerViewStyles,
    css`
      .input-with-prefix { display: flex; align-items: stretch; max-width: min(100%, 22.8rem); }
      .input-prefix { display: flex; align-items: center; padding: 0 0.75rem; background: var(--surface-inset); border: 1.5px solid var(--border-input); border-right: 0; border-radius: var(--radius-md) 0 0 var(--radius-md); color: var(--text-secondary); font-family: var(--font-mono); font-size: var(--text-base); user-select: none; }
      .input-with-prefix input { border-radius: 0 var(--radius-md) var(--radius-md) 0; flex: 1; min-width: 0; max-width: none; }
      .uri-list { display: grid; gap: 0.25rem; }
    `,
  ];

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
    if (this.issued) event.preventDefault();
  };

  updated(changed: Map<string, unknown>) {
    if (changed.has('route')) {
      const key = this.route.create ? 'create' : '';
      if (key !== this.draftKey) {
        this.draftKey = key;
        this.draftId = '';
        this.draftUrl = '';
        this.formError = '';
        this.submitted = false;
      }
      if (this.issued && this.route.id !== this.issued.edgeId) this.issued = null;
    }
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      this.edges = await fetchEdges();
    } catch (error) {
      this.edges = [];
      this.errorMessage = readErrorMessage(error, 'Failed to load edges');
    } finally {
      this.isLoading = false;
    }
  }

  private get openEdge(): PartnerEdge | null {
    return this.route.id ? this.edges.find(edge => edge.id === this.route.id) ?? null : null;
  }

  private get edgeId(): string {
    return `edge-${this.draftId.trim()}`;
  }

  private get idError(): string {
    if (!this.draftId.trim()) return this.submitted ? 'An edge ID is required.' : '';
    if (!validateEdgeId(this.edgeId)) return 'Lowercase letters, digits and dashes.';
    if (this.edges.some(edge => edge.id === this.edgeId)) return 'You already have an edge with this ID.';
    return '';
  }

  private get urlError(): string {
    const url = this.draftUrl.trim();
    if (!url) return this.submitted ? 'The public URL is required.' : '';
    if (!/^https:\/\/[^\s/]+\/?$/.test(url)) return 'An https origin, such as https://login.pilotpay.example.';
    return '';
  }

  private openView(edge: PartnerEdge) {
    dispatchRecordRoute(this, { id: edge.id, edit: null, create: false });
  }

  private async closePanel() {
    const key = this.route.id;
    if (this.issued && !await this.confirmLeaveToken()) return;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private confirmLeaveToken(): Promise<boolean> {
    return confirmDestructiveAction({
      title: 'Leave without saving the token?',
      message: 'The enrollment token is shown only once. After you leave you would have to revoke the token and issue a new one.',
      confirmLabel: 'Leave',
      cancelLabel: 'Stay',
    });
  }

  private async cancelEditor() {
    if ((this.draftId || this.draftUrl) && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.submitted = true;
    if (this.idError || this.urlError) return;
    this.isSaving = true;
    this.formError = '';
    try {
      const { edge, ...token } = await createEdge({ id: this.edgeId, publicUrl: this.draftUrl.trim().replace(/\/$/, '') });
      this.edges = [...this.edges, edge];
      this.issued = { ...token, edgeId: edge.id };
      this.copyFeedback = '';
      dispatchRecordRoute(this, { id: edge.id, edit: null, create: false });
    } catch (error) {
      this.formError = writeErrorMessage(error);
    } finally {
      this.isSaving = false;
    }
  }

  private async handleIssueToken(edge: PartnerEdge) {
    this.errorMessage = '';
    try {
      const token = await issueEnrollmentToken(edge.id);
      this.issued = { ...token, edgeId: edge.id };
      this.copyFeedback = '';
    } catch (error) {
      this.errorMessage = writeErrorMessage(error);
    }
  }

  private async handleRevokeToken(edge: PartnerEdge) {
    const confirmed = await confirmDestructiveAction({
      title: 'Revoke enrollment token',
      messagePrefix: 'Revoke the unused enrollment token of ',
      messageSubject: edge.id,
      messageSuffix: '? Whoever holds it can no longer enroll with it. Issue a new one when you are ready.',
      confirmLabel: 'Revoke token',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      await revokeEnrollmentToken(edge.id);
      if (this.issued?.edgeId === edge.id) this.issued = null;
    } catch (error) {
      this.errorMessage = writeErrorMessage(error);
    }
  }

  private async handleDelete(edge: PartnerEdge) {
    const confirmed = await confirmDestructiveAction({
      title: 'Remove edge',
      messagePrefix: 'Remove edge ',
      messageSubject: edge.id,
      messageSuffix: '? The operator stops renewing its certificate and refuses it. Sessions already open on it are not ended by this: stop the edge itself.',
      confirmLabel: 'Remove',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      await deleteEdge(edge.id);
      this.edges = this.edges.filter(candidate => candidate.id !== edge.id);
      if (this.route.id === edge.id) dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    } catch (error) {
      this.errorMessage = writeErrorMessage(error);
    }
  }

  private async copy() {
    if (!this.issued) return;
    const copied = await copyToClipboard(this.issued.token);
    this.copyFeedback = copied ? 'Token copied.' : 'Could not copy the token.';
  }

  // ── list ──

  private statusChip(edge: PartnerEdge) {
    if (edge.status === 'enrolled') return html`<span class="chip ok">${statusIcon('check', 'icon', 14)}Enrolled</span>`;
    if (edge.status === 'suspended') return html`<span class="chip danger">Suspended</span>`;
    return html`<span class="chip">Waiting for enrollment</span>`;
  }

  private certificateCell(edge: PartnerEdge) {
    if (edge.status === 'waiting') return html`<span class="muted">not enrolled</span>`;
    if (edge.status === 'suspended') return html`<div class="cell-lines"><span>to ${edge.certificateNotAfter ? formatDate(edge.certificateNotAfter) : '—'}</span><div class="chips"><span class="chip danger">Renewal refused</span></div></div>`;
    return html`<div class="cell-lines"><span>valid to ${edge.certificateNotAfter ? formatDate(edge.certificateNotAfter) : '—'}</span><span class="muted">${edge.lastRenewedAt ? `renewed ${formatDate(edge.lastRenewedAt)}` : ''}</span></div>`;
  }

  private renderTable() {
    const columns: DataTableColumn<PartnerEdge>[] = [
      { id: 'edge', label: 'Edge', cell: edge => html`<button type="button" class="row-link" aria-label=${`Open edge ${edge.id}`}><span class="row-title">${edge.id}</span></button>` },
      { id: 'url', label: 'Public URL', cell: edge => html`<span class="cell-line">${edge.publicUrl}</span>` },
      { id: 'status', label: 'Status', cell: edge => html`<div class="chips">${this.statusChip(edge)}</div>` },
      { id: 'certificate', label: 'Certificate', cell: edge => this.certificateCell(edge) },
    ];
    const menuItems = (edge: PartnerEdge): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(edge) },
      { label: 'Copy edge ID', onSelect: () => void copyToClipboard(edge.id) },
      ...(this.canManage && edge.id !== this.me.edge.id ? [{ label: 'Remove edge', danger: true, ariaLabel: `Remove edge ${edge.id}`, onSelect: () => void this.handleDelete(edge) }] : []),
    ];
    return html`
      <versola-data-table selectable .columns=${columns} .rows=${this.edges} .rowKey=${(edge: PartnerEdge) => edge.id}
        .selectedKey=${this.route.create ? null : this.route.id} .menuItems=${menuItems} .menuLabel=${(edge: PartnerEdge) => `Actions for ${edge.id}`}
        @row-select=${(e: CustomEvent<{ row: PartnerEdge }>) => this.openView(e.detail.row)}></versola-data-table>`;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  /** What is shown once after a token is issued: the token, nothing else. The edge makes its own keys and asks the operator for a certificate with it. */
  private renderIssued(edge: PartnerEdge, issued: Issued) {
    const until = issued.expiresAt ? html` until ${formatDate(issued.expiresAt)} (${relativeTime(issued.expiresAt)})` : '';
    return html`
      <div class="banner ok key-banner" role="status">
        <div class="banner-text">
          <div class="banner-title">${statusIcon('check')}Enrollment token created. It is shown once.</div>
          <div>The token is good for one enrollment${until}. Put it in a secret and start the edge <span class="mono">${edge.id}</span> with it.</div>
          <div class="secret-rows">
            <div><div class="secret-label">Enrollment token</div><pre class="secret-value" tabindex="0" role="region" aria-label="Enrollment token">${issued.token}</pre></div>
          </div>
          <div class="banner-actions">
            <button type="button" class="btn btn-primary btn-sm" @click=${() => this.copy()}>Copy token</button>
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.issued = null; }}>Done</button>
            ${this.copyFeedback ? html`<span class="copy-feedback" role="status">${this.copyFeedback}</span>` : ''}
          </div>
        </div>
      </div>`;
  }

  private renderBanners(edge: PartnerEdge) {
    const issued = this.issued?.edgeId === edge.id ? this.issued : null;
    return html`
      ${issued ? this.renderIssued(edge, issued) : ''}
      ${edge.status === 'suspended' ? html`
        <div class="banner warn" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}Certificate renewal refused: ${this.me.partner.operator.name} has suspended you</div><div>The certificate expires on ${edge.certificateNotAfter ? formatDate(edge.certificateNotAfter) : '…'}; the edge renews again once the operator resumes you.</div></div></div>` : ''}
    `;
  }

  private policyRows(edge: PartnerEdge) {
    const rateLimits = (edge.policies.rateLimits as { policies?: Array<{ name: string; limits?: Array<{ limit: number; windowSeconds: number }>; by?: string[] }> }).policies ?? [];
    const overload = edge.policies.overload as { inbound?: { mode?: string }; outbound?: { retryBudgetPercent?: number } };
    const accessLog = edge.policies.accessLog as { mode?: string };
    return html`
      ${this.row('Rate limits', rateLimits.length ? html`<div class="uri-list">${rateLimits.map(policy => html`<div><span class="mono">${policy.name}</span> · ${(policy.limits ?? []).map(limit => `${limit.limit} / ${limit.windowSeconds} s`).join(', ')}${policy.by?.length ? ` per ${policy.by.join(', ')}` : ''}</div>`)}</div>` : html`<span class="muted">None set</span>`, 'Operator')}
      ${this.row('Overload', html`${overload.inbound?.mode ? `inbound ${overload.inbound.mode}` : 'deployment default'}${overload.outbound?.retryBudgetPercent != null ? ` · retry budget ${overload.outbound.retryBudgetPercent} %` : ''}`, 'Operator')}
      ${this.row('Access log', accessLog.mode ?? 'deployment default', 'Operator')}
    `;
  }

  private renderGroups(edge: PartnerEdge) {
    const me = this.me;
    const edit = this.canManage;
    const central = me.edge.central === 'ok';
    return html`
      <versola-record-card heading="Link to the operator" .body=${html`
        ${this.row('central-mtls', html`${me.partner.operator.centralMtlsUrl ? html`<span class="mono">${me.partner.operator.centralMtlsUrl}</span> · ` : ''}${edge.status === 'enrolled' ? (central ? html`<span class="chip ok">${statusIcon('check', 'icon', 14)}connected</span>` : html`<span class="chip danger">unavailable</span>`) : html`<span class="muted">not enrolled</span>`}`)}
        ${this.row('id-mtls (auth)', html`${me.partner.operator.mtlsUrl ? html`<span class="mono">${me.partner.operator.mtlsUrl}</span> · ` : ''}as your clients`)}
        ${this.row('If central is unreachable', 'this page cannot be read or changed: its calls answer 503 until central is back')}
      `}></versola-record-card>

      <versola-record-card heading="Certificate" subheading="Issued by the operator's edge CA, renewed by this edge with a new key every 14 days." .body=${html`
        ${edge.status === 'waiting' ? html`
          ${this.row('Status', html`Not enrolled · <span class="muted">a token is shown once, when it is issued</span>`)}
          ${edit ? html`<versola-record-row .value=${html`<div class="key-actions">
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.handleRevokeToken(edge)}>Revoke token</button>
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.handleIssueToken(edge)}>Issue enrollment token</button>
          </div>`}></versola-record-row>` : ''}` : html`
          ${this.row('Valid', html`${edge.lastRenewedAt ? `${formatDate(edge.lastRenewedAt)} → ` : ''}${edge.certificateNotAfter ? formatDate(edge.certificateNotAfter) : '—'}`)}
          ${this.row('Names', html`<span class="mono">urn:versola:edge:${edge.id}</span><br><span class="mono">urn:versola:edge-group:${me.partner.id}</span>`)}
          ${this.row('Renewal', edge.status === 'suspended' ? html`<span class="chip danger">Refused: suspended</span>` : html`<span class="chip ok">${statusIcon('check', 'icon', 14)}By itself</span>`)}`}
      `}></versola-record-card>

      <versola-record-card heading="Address" .body=${html`
        ${this.row('Public URL', edge.publicUrl ? html`<span class="mono">${edge.publicUrl}</span>` : html`<span class="muted">None registered</span>`)}
      `}></versola-record-card>

      <versola-record-card heading="The operator's policies" subheading=${`Set by ${me.partner.operator.name} for your group. Read-only.`} .body=${this.policyRows(edge)}></versola-record-card>

      ${edit && edge.id !== me.edge.id ? html`
        <versola-danger-card heading="Remove edge" text="The operator stops renewing its certificate and refuses it. Sessions already open on it are not ended by this: stop the edge itself."
          actionLabel="Remove edge" .actionAriaLabel=${`Remove edge ${edge.id}`} @action=${() => this.handleDelete(edge)}></versola-danger-card>` : ''}
    `;
  }

  private renderMeta(edge: PartnerEdge) {
    return html`${this.statusChip(edge)}<span class="chip">group ${this.me.partner.id}</span>`;
  }

  private renderEditor() {
    const idError = this.idError;
    const urlError = this.urlError;
    return html`
      <form id="edge-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        <div class="editor-box">
          <h3>New edge</h3>
          <p class="editor-sub">Registered in your group with the operator; the enrollment token is shown once on the record.</p>
          <div class="form-group">
            <label for="edge-id">Edge ID *</label>
            <div class="input-with-prefix">
              <span class="input-prefix" aria-hidden="true">edge-</span>
              <input type="text" id="edge-id" class="compact-input mono ${idError ? 'input-error' : ''}" .value=${this.draftId} placeholder="pilot-us-1"
                aria-invalid=${idError ? 'true' : nothing} aria-describedby="edge-id-hint" @input=${(e: Event) => this.draftId = (e.target as HTMLInputElement).value} />
            </div>
            ${idError ? html`<div class="error-message" id="edge-id-hint" role="alert">${idError}</div>` : html`<div class="hint" id="edge-id-hint">Registered as <span class="mono">${this.edgeId}</span>. Lowercase letters, digits and dashes.</div>`}
          </div>
          <div class="form-group">
            <label for="edge-url">Public URL *</label>
            <input type="url" id="edge-url" class="compact-input ${urlError ? 'input-error' : ''}" .value=${this.draftUrl} placeholder="https://login-us.pilotpay.example"
              aria-invalid=${urlError ? 'true' : nothing} aria-describedby="edge-url-hint" @input=${(e: Event) => this.draftUrl = (e.target as HTMLInputElement).value} />
            ${urlError ? html`<div class="error-message" id="edge-url-hint" role="alert">${urlError}</div>` : html`<div class="hint" id="edge-url-hint">Where users reach it; back-channel logout from the operator goes there too.</div>`}
          </div>
        </div>
      </form>`;
  }

  private renderPanel(edge: PartnerEdge | null) {
    const create = this.route.create;
    if (!create && !edge) return '';
    return html`
      <versola-side-panel kind="Edge" .heading=${create ? 'Add edge' : edge!.id} .recordId=${edge?.id ?? ''} idLabel="edge ID" .mode=${create ? 'create' : 'view'}
        .subtitle=${create ? 'The ID is fixed once registered.' : ''} .meta=${edge && !create ? this.renderMeta(edge) : null}
        @close=${() => create ? this.cancelEditor() : this.closePanel()} @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">${create ? this.renderEditor() : html`${this.renderBanners(edge!)}${this.renderGroups(edge!)}`}</div>
        ${create ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="edge-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Adding…' : 'Add edge'}</button>
          </div>` : ''}
      </versola-side-panel>`;
  }

  render() {
    const me = this.me;
    const edge = this.openEdge;
    const panel = this.renderPanel(edge);
    if (edge && this.route.layout === 'page' && !this.route.create) {
      return html`
        <versola-record-header .crumbs=${[{ label: 'Edges', onSelect: () => this.closePanel() }, { label: edge.id }]} .heading=${edge.id} .recordId=${edge.id} idLabel="edge ID" .meta=${this.renderMeta(edge)}></versola-record-header>
        ${this.renderBanners(edge)}
        <div class="record-page">${this.renderGroups(edge)}</div>`;
    }
    const atCap = this.edges.length >= me.partner.limits.maxEdges;
    return html`
      <content-header title="Edges" description=${`Your edge deployments, in your group ${me.partner.id}. Each one holds its own key and certificate from the operator's edge CA, renews it by itself and is turned away the moment the operator suspends you.`}>
        ${this.edges.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" ?disabled=${atCap} title=${atCap ? `The operator allows you ${me.partner.limits.maxEdges} edges` : ''}
            @click=${() => dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null })}>+ Add edge</button>` : ''}
      </content-header>
      <div class="banner info" role="status">
        <div class="banner-text">
          <div class="banner-title">${statusIcon('info')}Certificates renew by themselves</div>
          <div>Each edge asks the operator for a new 14-day certificate with a new key when a third of the lifetime is left. Nothing to schedule; renewals stop only if the operator suspends ${me.partner.displayName}.${atCap ? ` You have the ${me.partner.limits.maxEdges} edges the operator allows.` : ''}</div>
        </div>
      </div>
      ${this.errorMessage && this.edges.length > 0 ? html`<div class="banner warn" role="alert"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}${this.errorMessage}</div></div></div>` : ''}
      ${this.isLoading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
        : this.errorMessage && this.edges.length === 0 ? html`<versola-error-card heading="Could not load edges" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>`
          : this.edges.length === 0 ? html`
            <versola-empty-state heading="No edges yet" text=${this.canManage ? 'Add your first edge to get its enrollment token.' : 'No edges in your group.'}>
              ${this.canManage ? html`<button class="btn btn-primary" @click=${() => dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null })}>+ Add edge</button>` : ''}
            </versola-empty-state>`
            : this.renderTable()}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-edges': VersolaPartnerEdges;
  }
}
