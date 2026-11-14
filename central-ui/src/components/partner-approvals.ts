import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../styles/components';
import type { FormPreviewScenario, FormRecord, Locale, OAuthClient, Partner, PartnerApproval, ThemeRecord } from '../types';
import { approvePartnerApproval, fetchFormPreviews, fetchForms, fetchLocales, fetchThemes, rejectPartnerApproval } from '../utils/central-api';
import { pickConsentScenario } from '../utils/consent-preview';
import { formatDate, getLocalizedDescription } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { APPROVAL_KIND_LABELS, APPROVAL_STATUS_LABELS, approvalDiff, approvalFieldLabel, approvalValueLines, changesConsentScreen, type ApprovalDiffRow } from '../utils/partners';
import { statusIcon } from './status-icon';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from './data-table';
import './client-consent-preview';
import './data-table';
import './empty-state';
import './filter-bar';
import './loading-cards';
import './record-card';
import './side-panel';
import './typeahead-select';

type StatusFilter = 'waiting' | 'decided' | 'all';

/**
 * The approvals queue (#549): what partners asked for beyond their limits, waiting first. The
 * record is a two-column diff, requested against registered, with the rows outside the partner's
 * limits marked; a consent preview when the request changes what users see; and the decision
 * (Approve, Approve with overrides, Reject) with a note the partner reads. A decided request shows
 * who decided, when and the note.
 */
@customElement('versola-partner-approvals')
export class VersolaPartnerApprovals extends LitElement {
  @property({ type: String }) tenantId = '';
  @property({ attribute: false }) partners: Partner[] = [];
  @property({ attribute: false }) approvals: PartnerApproval[] = [];
  @property({ attribute: false }) clients: OAuthClient[] = [];
  @property({ type: Boolean }) canManage = false;
  @property({ type: Boolean }) loading = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private statusFilter: StatusFilter = 'waiting';
  @state() private partnerFilter = '';
  @state() private note = '';
  @state() private overriding = false;
  @state() private overrides: Record<string, string> = {};
  @state() private isDeciding = false;
  @state() private decisionError = '';
  @state() private submitted = false;
  @state() private previewForm: FormRecord | null = null;
  @state() private previewScenario: FormPreviewScenario | null = null;
  @state() private previewTheme: ThemeRecord | null = null;
  @state() private locales: Locale[] = [];
  private previewLoaded = false;
  private draftFor = '';
  @query('versola-data-table') private table?: VersolaDataTable<PartnerApproval>;

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

      .list-toolbar { display: flex; flex-wrap: wrap; gap: var(--spacing-md); align-items: center; margin-bottom: var(--spacing-lg); }
      .muted { color: var(--text-secondary); }
      .small { font-size: var(--text-sm); }
      .mono { font-family: var(--font-mono); font-size: var(--text-sm); overflow-wrap: anywhere; }

      .diff { display: grid; grid-template-columns: 9rem 1fr 1fr; border: 1px solid var(--border-default); border-radius: var(--radius-inner); font-size: var(--text-sm); }
      .diff-row { display: contents; }
      /* The cells of a row are children of .diff-row (display: contents), not of .diff: a rule on .diff > div reaches only the header. */
      .diff > .h, .diff-row > div { padding: 0.5rem 0.75rem; min-width: 0; overflow-wrap: anywhere; }
      .diff-row > div { border-top: 1px solid var(--border-subtle); }
      .diff > .h { background: var(--surface-inset); font-size: var(--text-xs); font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; color: var(--text-secondary); }
      .diff .field { color: var(--text-secondary); }
      .diff .requested { background: var(--success-subtle); color: var(--text-primary); }
      .diff .requested.outside { background: var(--warning-subtle); }
      .diff .current { color: var(--text-secondary); }
      .diff .line { display: block; }
      .diff .mark { display: inline-flex; align-items: center; gap: 0.25rem; margin-top: 0.25rem; font-size: var(--text-xs); font-weight: 600; }
      .diff .mark.outside { color: var(--warning-text); }
      .diff .mark.within { color: var(--success-text); }
      .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
      @container (max-width: 480px) {
        .diff { grid-template-columns: 1fr; }
        .diff > .h { display: none; }
        /* One separator per row, above its label, not one above each of the three stacked cells. */
        .diff-row > div { border-top: 0; }
        .diff-row > .field { border-top: 1px solid var(--border-subtle); }
        .diff .field { padding-bottom: 0; font-weight: 600; }
        .diff .current::before { content: 'Registered: '; font-weight: 600; }
        .diff .requested::before { content: 'Requested: '; font-weight: 600; }
      }
      .record-compact { container-type: inline-size; }
      .record-list { padding: 0.75rem 0; }

      .consent-note { font-size: var(--text-sm); color: var(--text-secondary); }
      .consent-fallback { border: 1px dashed var(--border-strong); border-radius: var(--radius-inner); padding: var(--spacing-md); display: grid; gap: 0.5rem; max-width: 24rem; }
      .consent-fallback h3 { margin: 0; font-size: var(--text-base); }
      .consent-fallback ul { margin: 0; padding-left: 1.25rem; font-size: var(--text-sm); }
      .consent-logo { width: 2.5rem; height: 2.5rem; border-radius: var(--radius-md); background: var(--accent-subtle); color: var(--accent-text); display: inline-flex; align-items: center; justify-content: center; font-weight: 800; }

      .decided { display: grid; gap: 0.25rem; font-size: var(--text-sm); }
      .footer-reject { margin-right: auto; }
      .override-list { display: grid; gap: var(--spacing-md); }
      textarea.compact-input { max-width: none; }
    `,
  ];

  updated(changed: Map<string, unknown>) {
    if (changed.has('route')) {
      const key = this.route.id ?? '';
      if (key !== this.draftFor) {
        this.draftFor = key;
        this.note = '';
        this.overriding = false;
        this.overrides = {};
        this.decisionError = '';
        this.submitted = false;
      }
      if (this.route.id && !this.previewLoaded) void this.loadPreviewParts();
    }
  }

  /** The consent form, its scenario and the tenant's theme, for the preview; a central without them leaves the fallback card. */
  private async loadPreviewParts() {
    this.previewLoaded = true;
    const [forms, previews, themes, locales] = await Promise.all([
      fetchForms().catch(() => [] as FormRecord[]),
      fetchFormPreviews().catch(() => []),
      fetchThemes(this.tenantId).catch(() => [] as ThemeRecord[]),
      fetchLocales().catch(() => [] as Locale[]),
    ]);
    this.previewForm = forms.find(form => form.id === 'consent' && form.active && form.jsCompiled) ?? null;
    this.previewScenario = pickConsentScenario(previews.find(preview => preview.formId === 'consent')?.scenarios ?? []);
    this.previewTheme = themes.find(theme => theme.id === 'default') ?? themes[0] ?? null;
    this.locales = locales;
  }

  private get openApproval(): PartnerApproval | null {
    return this.route.id ? this.approvals.find(approval => approval.id === this.route.id) ?? null : null;
  }

  private partnerOf(approval: PartnerApproval): Partner | undefined {
    return this.partners.find(partner => partner.id === approval.partnerId);
  }

  private clientOf(approval: PartnerApproval): OAuthClient | undefined {
    return approval.kind === 'profile_change' ? undefined : this.clients.find(client => client.id === approval.targetId);
  }

  private requestTitle(approval: PartnerApproval): string {
    if (approval.kind === 'profile_change') return `Name or logo of ${this.partnerOf(approval)?.displayName ?? approval.partnerId}`;
    const name = typeof approval.requested.clientName === 'string' ? approval.requested.clientName : this.clientOf(approval) ? getLocalizedDescription(this.clientOf(approval)!.clientName) : approval.targetId;
    return approval.kind === 'client_create' ? `New client “${name}”` : `Change to ${name}`;
  }

  private get filtered(): PartnerApproval[] {
    const rank = (approval: PartnerApproval) => (approval.status === 'waiting' ? 0 : 1);
    return this.approvals
      .filter(approval => this.statusFilter === 'all' || (this.statusFilter === 'waiting' ? approval.status === 'waiting' : approval.status !== 'waiting'))
      .filter(approval => !this.partnerFilter || approval.partnerId === this.partnerFilter)
      .sort((a, b) => rank(a) - rank(b) || Date.parse(b.requestedAt) - Date.parse(a.requestedAt));
  }

  private statusChip(approval: PartnerApproval) {
    const label = APPROVAL_STATUS_LABELS[approval.status];
    if (approval.status === 'waiting') return html`<span class="chip warn">${label} since ${formatDate(approval.requestedAt)}</span>`;
    if (approval.status === 'approved') return html`<span class="chip ok">${statusIcon('check', 'icon', 14)}${label}</span>`;
    if (approval.status === 'rejected') return html`<span class="chip danger">${label}</span>`;
    return html`<span class="chip">${label}</span>`;
  }

  private open(approval: PartnerApproval) {
    dispatchRecordRoute(this, { id: approval.id, edit: null, create: false, tab: 'approvals' });
  }

  private async close() {
    const key = this.route.id;
    if ((this.note.trim() || this.overriding) && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null, tab: 'approvals' });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private overrideValue(row: ApprovalDiffRow): string {
    return this.overrides[row.field] ?? row.requested.join('\n');
  }

  private overridesPayload(approval: PartnerApproval): Record<string, unknown> {
    const payload: Record<string, unknown> = {};
    // The rows the override form renders: the name and logo are among them even when they are not in `outsideLimits`.
    for (const { field } of approvalDiff(approval).filter(row => row.outside)) {
      const text = this.overrides[field];
      if (text === undefined) continue;
      const original = approval.requested[field];
      if (Array.isArray(original)) payload[field] = text.split('\n').map(line => line.trim()).filter(Boolean);
      else if (typeof original === 'number') payload[field] = Number(text);
      else payload[field] = text.trim();
    }
    return payload;
  }

  private async decide(approval: PartnerApproval, decision: 'approve' | 'override' | 'reject') {
    if (this.isDeciding) return;
    this.submitted = true;
    if (decision === 'reject' && !this.note.trim()) return;
    this.isDeciding = true;
    this.decisionError = '';
    try {
      if (decision === 'reject') await rejectPartnerApproval(approval.id, this.note.trim());
      else await approvePartnerApproval(approval.id, this.note.trim(), decision === 'override' ? this.overridesPayload(approval) : undefined);
      this.note = '';
      this.overriding = false;
      this.overrides = {};
      this.dispatchEvent(new CustomEvent('decided', { detail: { id: approval.id, decision }, bubbles: true, composed: true }));
    } catch (error) {
      this.decisionError = error instanceof Error ? error.message : 'Failed to record the decision';
    } finally {
      this.isDeciding = false;
    }
  }

  // ── list ──

  private renderToolbar() {
    const options: Array<{ id: StatusFilter; label: string }> = [{ id: 'waiting', label: 'Waiting' }, { id: 'decided', label: 'Decided' }, { id: 'all', label: 'All' }];
    return html`
      <div class="list-toolbar">
        <versola-filter-bar label="Filter approvals" .options=${options} .value=${this.statusFilter}
          @change=${(e: CustomEvent<{ id: string }>) => this.statusFilter = e.detail.id as StatusFilter}></versola-filter-bar>
        <versola-typeahead-select label="Partner" placeholder="Any partner" .value=${this.partnerFilter}
          .options=${this.partners.map(partner => ({ id: partner.id, label: `${partner.displayName} (${partner.id})` }))}
          @change=${(e: CustomEvent<{ id: string }>) => this.partnerFilter = e.detail.id}></versola-typeahead-select>
      </div>
    `;
  }

  private renderTable() {
    const columns: DataTableColumn<PartnerApproval>[] = [
      { id: 'request', label: 'Request', cell: approval => html`
        <button type="button" class="row-link" aria-label=${`Open request ${this.requestTitle(approval)} ${approval.targetId}`}>
          <span class="row-title">${this.requestTitle(approval)}</span>
          <span class="row-id">${approval.targetId}</span>
        </button>` },
      { id: 'partner', label: 'Partner', cell: approval => this.partnerOf(approval)?.displayName ?? approval.partnerId },
      { id: 'requested', label: 'Requested', class: 'muted', cell: approval => html`${formatDate(approval.requestedAt)} · <span class="mono">${approval.requestedBy}</span>` },
      { id: 'kind', label: 'Kind', cell: approval => html`<span class="chip">${APPROVAL_KIND_LABELS[approval.kind]}</span>` },
      { id: 'status', label: 'Status', cell: approval => html`<div class="chips">${this.statusChip(approval)}</div>` },
    ];
    const menuItems = (approval: PartnerApproval): RowMenuItem[] => [{ label: 'Open', onSelect: () => this.open(approval) }];
    return html`
      <versola-data-table selectable .columns=${columns} .rows=${this.filtered} .rowKey=${(approval: PartnerApproval) => approval.id}
        .selectedKey=${this.route.id} .menuItems=${menuItems} .menuLabel=${(approval: PartnerApproval) => `Actions for ${approval.id}`}
        @row-select=${(e: CustomEvent<{ row: PartnerApproval }>) => this.open(e.detail.row)}></versola-data-table>
    `;
  }

  // ── record ──

  private renderDiff(approval: PartnerApproval) {
    const rows = approvalDiff(approval);
    return html`
      <div class="diff" role="group" aria-label="Requested against registered">
        <div class="h" aria-hidden="true">Field</div><div class="h" aria-hidden="true">Registered</div><div class="h" aria-hidden="true">Requested</div>
        ${rows.map(row => html`
          <div class="diff-row" data-field=${row.field}>
            <div class="field">${row.label}</div>
            <div class="current"><span class="sr-only">Registered: </span>${row.current.length ? row.current.map(line => html`<span class="line">${line}</span>`) : '—'}</div>
            <div class="requested ${row.outside ? 'outside' : ''}">
              <span class="sr-only">Requested: </span>${row.requested.length ? row.requested.map(line => html`<span class="line">${line}</span>`) : html`<span class="muted">removed</span>`}
              ${row.outside ? html`<span class="mark outside">${statusIcon('alert', 'icon', 12)}needs operator</span>` : html`<span class="mark within">${statusIcon('check', 'icon', 12)}within limits</span>`}
            </div>
          </div>`)}
      </div>
    `;
  }

  private renderConsentPreview(approval: PartnerApproval) {
    const partner = this.partnerOf(approval);
    const client = this.clientOf(approval);
    const name = typeof approval.requested.clientName === 'string' ? approval.requested.clientName
      : typeof approval.requested.displayName === 'string' ? approval.requested.displayName
        : client ? getLocalizedDescription(client.clientName) : partner?.displayName ?? '';
    const logoUri = typeof approval.requested.logoUri === 'string' ? approval.requested.logoUri : client?.logoUri ?? partner?.logoUri ?? '';
    const scopes = Array.isArray(approval.requested.scope) ? approval.requested.scope as string[] : client?.scope ?? ['openid'];
    if (this.previewForm && this.previewScenario) {
      return html`<versola-client-consent-preview .form=${this.previewForm} .scenario=${this.previewScenario}
        .tokens=${this.previewTheme?.tokens ?? { base: 'versola' }} .themeCss=${this.previewTheme?.css ?? ''} .overrides=${client?.themeOverrides ?? {}}
        .clientName=${{ en: name }} .logoUri=${logoUri} .locales=${this.locales}></versola-client-consent-preview>`;
    }
    return html`
      <div class="consent-fallback" aria-label="Consent screen, as text">
        <span class="consent-logo" aria-hidden="true">${(name || '?').slice(0, 1).toUpperCase()}</span>
        <h3>${name || 'The app'} wants to sign you in</h3>
        <ul>${scopes.filter(scope => scope !== 'openid').map(scope => html`<li>${scope}</li>`)}</ul>
        <span class="consent-note">${name || 'The app'} is a partner, not the operator. Users can revoke this later in their account.</span>
      </div>
      <p class="consent-note">Central has no consent form to render, so the screen is described instead of drawn.</p>
    `;
  }

  /** What the operator registered instead of what was asked for: only the fields the overrides changed. */
  private renderApplied(approval: PartnerApproval) {
    const applied = approval.applied;
    if (!applied) return '';
    const changed = Object.keys(applied).filter(field => JSON.stringify(applied[field]) !== JSON.stringify(approval.requested[field]));
    if (changed.length === 0) return '';
    return html`<versola-record-row .label=${'Registered instead'} .value=${html`<div class="record-list-plain">${changed.map(field => html`<div><b>${approvalFieldLabel(field)}</b>: ${approvalValueLines(field, applied[field]).join(', ') || '—'}</div>`)}</div>`}></versola-record-row>`;
  }

  private renderDecision(approval: PartnerApproval) {
    if (approval.status !== 'waiting') {
      return html`
        <versola-record-card heading="Decision" .body=${html`
          <versola-record-row .label=${'Outcome'} .value=${this.statusChip(approval)}></versola-record-row>
          <versola-record-row .label=${'Decided by'} .value=${approval.decidedBy ? html`<span class="mono">${approval.decidedBy}</span>` : html`<span class="muted">the partner</span>`}></versola-record-row>
          <versola-record-row .label=${'When'} .value=${approval.decidedAt ? formatDate(approval.decidedAt) : '—'}></versola-record-row>
          <versola-record-row .label=${'Note'} .value=${approval.note ? approval.note : html`<span class="muted">No note</span>`}></versola-record-row>
          ${this.renderApplied(approval)}
        `}></versola-record-card>
      `;
    }
    if (!this.canManage) {
      return html`<div class="banner info" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('info')}Read-only: deciding needs partners:manage</div></div></div>`;
    }
    const rows = approvalDiff(approval).filter(row => row.outside);
    const noteError = this.submitted && !this.note.trim() ? 'A note is required when rejecting: the partner reads it.' : '';
    return html`
      <form id="approval-decision" novalidate @submit=${(e: Event) => e.preventDefault()}>
        ${this.decisionError ? html`<div class="editor-error" role="alert">${this.decisionError}</div>` : ''}
        <div class="editor-box">
          <h3>Decision</h3>
          <p class="editor-sub">Approve applies the request as it is; approve with overrides lets you change the rows that need you first; reject leaves everything as registered.</p>
          <div class="form-group">
            <label for="decision-note">Note to the partner</label>
            <textarea id="decision-note" class="compact-input ${noteError ? 'input-error' : ''}" rows="3" .value=${this.note}
              aria-invalid=${noteError ? 'true' : nothing} aria-describedby="decision-note-hint"
              placeholder="e.g. checkout.pilot-partner.net is not in your redirect domains; add it to the limits first or drop it"
              @input=${(e: Event) => this.note = (e.target as HTMLTextAreaElement).value}></textarea>
            ${noteError ? html`<div class="error-message" id="decision-note-hint" role="alert">${noteError}</div>`
              : html`<div class="hint" id="decision-note-hint">Shown on the request in the partner console. Required when rejecting.</div>`}
          </div>
          ${this.overriding ? html`
            <div class="override-list" role="group" aria-label="Overrides">
              ${rows.map(row => html`
                <div class="form-group">
                  <label for=${`override-${row.field}`}>${row.label}</label>
                  ${row.requested.length > 1 || Array.isArray(approval.requested[row.field]) ? html`
                    <textarea id=${`override-${row.field}`} class="compact-input" rows="3" .value=${this.overrideValue(row)} aria-describedby=${`override-${row.field}-hint`}
                      @input=${(e: Event) => this.overrides = { ...this.overrides, [row.field]: (e.target as HTMLTextAreaElement).value }}></textarea>` : html`
                    <input type="text" id=${`override-${row.field}`} class="compact-input" .value=${this.overrideValue(row)} aria-describedby=${`override-${row.field}-hint`}
                      @input=${(e: Event) => this.overrides = { ...this.overrides, [row.field]: (e.target as HTMLInputElement).value }} />`}
                  <div class="hint" id=${`override-${row.field}-hint`}>What is registered instead of what was asked for. One value per line.</div>
                </div>`)}
            </div>` : ''}
        </div>
      </form>
    `;
  }

  private renderRecord(approval: PartnerApproval) {
    const partner = this.partnerOf(approval);
    const outside = approvalDiff(approval).filter(row => row.outside).map(row => row.label.toLowerCase());
    return html`
      ${approval.status === 'waiting' ? html`
        <div class="banner info" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('info')}${outside.length ? `${outside.length === 1 ? 'One item needs' : `${outside.length} items need`} you: ${outside.join(', ')}` : 'Everything is inside the partner\'s limits'}</div>
            <div>${outside.length ? 'Everything else is inside the partner\'s limits and would apply at once.' : 'Approve to register it; a new client always waits for the operator.'}</div>
          </div>
        </div>` : ''}
      <versola-record-card heading="What the partner asked for" subheading="Requested against what is registered. Rows outside the limits are marked." .body=${html`
        <div class="record-list">${this.renderDiff(approval)}</div>
        <versola-record-row .label=${'Requested by'} .value=${html`<span class="mono">${approval.requestedBy}</span> <span class="muted small">(staff of ${partner?.displayName ?? approval.partnerId}, from the forwarded token)</span>`}></versola-record-row>
        <versola-record-row .label=${'When'} .value=${formatDate(approval.requestedAt)}></versola-record-row>
      `}></versola-record-card>
      ${changesConsentScreen(approval) ? html`
        <versola-record-card heading="How users will see it" subheading="The consent screen auth will show. Approving the name approves this." .body=${html`
          <div class="record-list">${this.renderConsentPreview(approval)}</div>
        `}></versola-record-card>` : ''}
      ${this.renderDecision(approval)}
    `;
  }

  private renderPanel() {
    const approval = this.openApproval;
    if (!approval) return '';
    const partner = this.partnerOf(approval);
    const waiting = approval.status === 'waiting' && this.canManage;
    return html`
      <versola-side-panel kind=${`Approval request · ${partner?.displayName ?? approval.partnerId}`} .heading=${this.requestTitle(approval)} .recordId=${approval.id} idLabel="request ID"
        mode="view" wide .showOpenPage=${false}
        .meta=${html`<span class="chip">${APPROVAL_KIND_LABELS[approval.kind]}</span>${this.statusChip(approval)}`}
        @close=${() => this.close()}>
        <div class="record-compact">${this.renderRecord(approval)}</div>
        ${waiting ? html`
          <div slot="footer">
            <button type="button" class="btn btn-danger-secondary footer-reject" ?disabled=${this.isDeciding} @click=${() => this.decide(approval, 'reject')}>Reject</button>
            ${approval.outsideLimits.length ? (this.overriding
              ? html`<button type="button" class="btn btn-primary" ?disabled=${this.isDeciding} @click=${() => this.decide(approval, 'override')}>Approve with overrides</button>`
              : html`<button type="button" class="btn btn-secondary" ?disabled=${this.isDeciding} @click=${() => { this.overriding = true; }}>Approve with overrides…</button>`) : ''}
            ${this.overriding ? html`<button type="button" class="btn btn-secondary" ?disabled=${this.isDeciding} @click=${() => { this.overriding = false; this.overrides = {}; }}>Cancel overrides</button>`
              : html`<button type="button" class="btn btn-primary" ?disabled=${this.isDeciding} @click=${() => this.decide(approval, 'approve')}>${this.isDeciding ? 'Saving…' : 'Approve as requested'}</button>`}
          </div>` : ''}
      </versola-side-panel>
    `;
  }

  render() {
    return html`
      ${this.loading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
        : this.approvals.length === 0 ? html`<versola-empty-state heading="No requests" text="Partners have asked for nothing beyond their limits."></versola-empty-state>`
          : html`
            ${this.renderToolbar()}
            ${this.filtered.length === 0 ? html`<versola-empty-state heading="No requests match"></versola-empty-state>` : this.renderTable()}`}
      ${this.renderPanel()}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-approvals': VersolaPartnerApprovals;
  }
}
