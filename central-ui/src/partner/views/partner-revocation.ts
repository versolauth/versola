import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, recordStyles, segmentedStyles, tagStyles } from '../../styles/components';
import { statusIcon } from '../../components/status-icon';
import type { DataTableColumn } from '../../components/data-table';
import { confirmDestructiveAction } from '../../utils/confirm-dialog';
import { formatDate } from '../../utils/helpers';
import { fetchClients, fetchEdges, fetchRevocations, revoke, writeErrorMessage } from '../partner-api';
import type { PartnerClient, PartnerEdge, PartnerMe, Revocation, RevocationBy } from '../partner-types';
import { partnerViewStyles } from '../partner-styles';
import '../../components/content-header';
import '../../components/data-table';
import '../../components/record-card';
import '../../components/loading-cards';

const BY_LABELS: Record<RevocationBy, { label: string; field: string; hint: string; placeholder: string; what: string }> = {
  user: { label: 'A user', field: 'User (sub)', hint: 'The sub as it appears in your tokens and /userinfo. Every session of the user on the clients chosen below.', placeholder: 'u_7f3a9c2e', what: 'the sessions of user' },
  sid: { label: 'A session', field: 'Session (sid)', hint: 'One session, as the sid claim names it.', placeholder: 'sid_…', what: 'session' },
  client: { label: 'A client', field: 'Client', hint: 'Every session of the client chosen below.', placeholder: '', what: 'every session of' },
  fam: { label: 'A refresh family', field: 'Family (fam_id)', hint: 'One refresh-token family, as the fam_id claim names it. Your edge stops using it and the session that holds it ends.', placeholder: 'fam_…', what: 'refresh family' },
};

/**
 * Sessions & revocation: end sessions on the partner's own edges. The tokens live in those
 * sessions, so ending one discards them; the operator's auth is never contacted and central is
 * not needed. Scope is the partner's clients and the edges chosen. Admins only; others see the list.
 */
@customElement('versola-partner-revocation')
export class VersolaPartnerRevocation extends LitElement {
  @property({ attribute: false }) me!: PartnerMe;
  @property({ type: Boolean }) canManage = false;

  @state() private clients: PartnerClient[] = [];
  @state() private edges: PartnerEdge[] = [];
  @state() private selectedEdges: string[] = [];
  @state() private recent: Revocation[] | null = null;
  @state() private by: RevocationBy = 'user';
  @state() private value = '';
  @state() private selected: string[] = [];
  @state() private submitted = false;
  @state() private isSaving = false;
  @state() private formError = '';
  @state() private result: { ended: number; what: string } | null = null;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    segmentedStyles,
    recordStyles,
    partnerViewStyles,
    css`
      .seg-control { margin-bottom: var(--spacing-md); }
      .danger-actions { display: flex; justify-content: flex-end; }
      .revoke-body { padding: var(--spacing-lg); color: var(--text-primary); }
      .ctrl-group { display: grid; gap: 0.5rem; margin-bottom: var(--spacing-md); }
      .seg-control { margin-bottom: 0; }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.load();
  }

  private async load() {
    const [clients, edges, recent] = await Promise.allSettled([fetchClients(), fetchEdges(), fetchRevocations()]);
    this.clients = clients.status === 'fulfilled' ? clients.value.filter(client => client.approval.status === 'approved') : [];
    this.selected = this.clients.map(client => client.id);
    this.edges = edges.status === 'fulfilled' ? edges.value.filter(edge => edge.status === 'enrolled') : [];
    this.selectedEdges = this.edges.map(edge => edge.id);
    this.recent = recent.status === 'fulfilled' ? recent.value : [];
  }

  private get valueError(): string {
    if (this.by === 'client') return this.selected.length ? '' : 'Pick at least one client.';
    if (!this.value.trim()) return 'A value is required.';
    return '';
  }

  private get clientsError(): string {
    return this.selected.length ? '' : 'Pick at least one client.';
  }

  private get edgesError(): string {
    return this.selectedEdges.length ? '' : this.edges.length ? 'Pick at least one edge.' : 'No enrolled edge to end sessions on.';
  }

  private async submit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.submitted = true;
    if (this.valueError || this.clientsError || this.edgesError) return;
    const spec = BY_LABELS[this.by];
    const what = this.by === 'client' ? `${spec.what} ${this.selected.join(', ')}` : `${spec.what} ${this.value.trim()} on ${this.selected.join(', ')}`;
    const where = this.selectedEdges.join(', ');
    const confirmed = await confirmDestructiveAction({
      title: 'End sessions',
      message: `End ${what} on ${where}? The users are signed out and sign in again on their next visit. The operator's auth is not contacted: tokens already issued stay valid there until they expire, but they are held only in the sessions you end.`,
      confirmLabel: 'End sessions',
    });
    if (!confirmed) return;
    this.isSaving = true;
    this.formError = '';
    try {
      const response = await revoke({ by: this.by, value: this.by === 'client' ? this.selected.join(',') : this.value.trim(), clients: this.selected, edges: this.selectedEdges });
      this.result = { ended: response.ended, what };
      this.value = '';
      this.submitted = false;
      this.recent = await fetchRevocations().catch(() => this.recent);
    } catch (error) {
      this.formError = writeErrorMessage(error);
    } finally {
      this.isSaving = false;
    }
  }

  private renderForm() {
    const spec = BY_LABELS[this.by];
    const valueError = this.submitted ? this.valueError : '';
    const clientsError = this.submitted ? this.clientsError : '';
    const edgesError = this.submitted ? this.edgesError : '';
    return html`
      <form novalidate @submit=${this.submit}>
        <div class="card record-card">
          <div class="record-card-head"><div><h2>End sessions</h2><div class="record-card-sub">On your own edges, for your clients only. The user is named by the same identifier you got in the token.</div></div></div>
          <div class="revoke-body">
            ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
            ${this.result ? html`<div class="banner ok" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('check')}Ended ${this.result.ended} ${this.result.ended === 1 ? 'session' : 'sessions'}</div><div>${this.result.what}. The tokens they held are discarded.</div></div></div>` : ''}
            ${this.canManage ? '' : html`<div class="banner info" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('info')}Admins end sessions</div><div>Your level is ${this.me.staff.level}: you can see the recent ones, not end one.</div></div></div>`}
            <div class="ctrl-group">
              <span class="ctrl-label" id="revoke-by-label">What to end</span>
              <div class="seg-control" role="group" aria-labelledby="revoke-by-label">
                ${(Object.keys(BY_LABELS) as RevocationBy[]).map(by => html`
                  <button type="button" class="seg-btn ${this.by === by ? 'seg-active' : ''}" aria-pressed=${this.by === by} ?disabled=${!this.canManage}
                    @click=${() => { this.by = by; this.value = ''; this.submitted = false; }}>${BY_LABELS[by].label}</button>`)}
              </div>
            </div>
            ${this.by === 'client' ? '' : html`
              <div class="form-group">
                <label for="revoke-value">${spec.field} *</label>
                <input type="text" id="revoke-value" class="compact-input mono ${valueError ? 'input-error' : ''}" .value=${this.value} placeholder=${spec.placeholder} ?disabled=${!this.canManage}
                  aria-invalid=${valueError ? 'true' : nothing} aria-describedby="revoke-value-hint" @input=${(e: Event) => this.value = (e.target as HTMLInputElement).value} />
                ${valueError ? html`<div class="error-message" id="revoke-value-hint" role="alert">${valueError}</div>` : html`<div class="hint" id="revoke-value-hint">${spec.hint}</div>`}
              </div>`}
            <div class="form-group">
              <span class="checkbox-list" role="group" aria-labelledby="revoke-clients-label">
                <label id="revoke-clients-label">Clients *</label>
                ${this.clients.length ? this.clients.map(client => html`
                  <label class="checkbox-row">
                    <input type="checkbox" .checked=${this.selected.includes(client.id)} ?disabled=${!this.canManage}
                      @change=${(e: Event) => {
                        const checked = (e.target as HTMLInputElement).checked;
                        this.selected = checked ? [...this.selected, client.id] : this.selected.filter(id => id !== client.id);
                      }} />
                    <span class="mono">${client.id}</span><span class="muted small">${client.clientName}</span>
                  </label>`) : html`<span class="muted small">No approved clients yet.</span>`}
              </span>
              ${clientsError ? html`<div class="error-message" role="alert">${clientsError}</div>` : html`<div class="hint">Your clients only; users of other partners are never visible here.</div>`}
            </div>
            <div class="form-group">
              <span class="checkbox-list" role="group" aria-labelledby="revoke-edges-label">
                <label id="revoke-edges-label">Edges *</label>
                ${this.edges.length ? this.edges.map(edge => html`
                  <label class="checkbox-row">
                    <input type="checkbox" .checked=${this.selectedEdges.includes(edge.id)} ?disabled=${!this.canManage}
                      @change=${(e: Event) => {
                        const checked = (e.target as HTMLInputElement).checked;
                        this.selectedEdges = checked ? [...this.selectedEdges, edge.id] : this.selectedEdges.filter(id => id !== edge.id);
                      }} />
                    <span class="mono">${edge.id}</span><span class="muted small">${edge.publicUrl.replace(/^https:\/\//, '')}</span>
                  </label>`) : html`<span class="muted small">No enrolled edge yet.</span>`}
              </span>
              ${edgesError ? html`<div class="error-message" role="alert">${edgesError}</div>` : html`<div class="hint">Sessions live on your edges; the operator's auth is not contacted.</div>`}
            </div>
            <div class="danger-actions"><button type="submit" class="btn btn-danger" ?disabled=${!this.canManage || this.isSaving}>${this.isSaving ? 'Ending…' : 'End sessions'}</button></div>
          </div>
        </div>
      </form>`;
  }

  private renderRecent() {
    const columns: DataTableColumn<Revocation>[] = [
      { id: 'when', label: 'When', class: 'muted', cell: item => formatDate(item.at) },
      { id: 'who', label: 'By', cell: item => item.by_staff },
      { id: 'what', label: 'What', cell: item => html`<span class="cell-line">${BY_LABELS[item.by].label.toLowerCase()} · ${item.value}</span>` },
      { id: 'clients', label: 'Clients', cell: item => html`<div class="tag-list">${item.clients.map(id => html`<span class="tag">${id}</span>`)}</div>` },
      { id: 'edges', label: 'Edges', cell: item => html`<div class="tag-list">${item.edges.map(id => html`<span class="tag">${id}</span>`)}</div>` },
      { id: 'ended', label: 'Ended', class: 'muted', cell: item => `${item.ended} ${item.ended === 1 ? 'session' : 'sessions'}` },
    ];
    return html`
      <versola-record-card heading="Recently ended" .body=${html`
        <div class="record-list bleed">
          ${this.recent === null ? html`<versola-loading-cards .count=${1}></versola-loading-cards>` : this.recent.length
            ? html`<versola-data-table flush .columns=${columns} .rows=${this.recent} .rowKey=${(item: Revocation) => item.id}></versola-data-table>`
            : html`<span class="muted">Nothing ended yet.</span>`}
        </div>
      `}></versola-record-card>`;
  }

  render() {
    return html`
      <content-header title="Sessions & revocation" description="End sessions on your own edges: a user's, one session, a client's, or one refresh family. The tokens live in those sessions and are discarded with them. The operator's auth is not contacted, so a token already issued stays valid there until it expires; users of other partners are never visible here."></content-header>
      <div class="grid-2">
        ${this.renderForm()}
        <versola-record-card heading="What it does" .body=${html`
          <versola-record-row .label=${'your edges'} .value=${html`end the sessions and discard the tokens they hold <span class="chip ok">immediately</span>`}></versola-record-row>
          <versola-record-row .label=${'the user'} .value=${html`signs in again on the next visit`}></versola-record-row>
          <versola-record-row .label=${"operator's auth"} .value=${html`not contacted; a token already issued stays valid there <span class="chip">until it expires</span>`}></versola-record-row>
        `}></versola-record-card>
      </div>
      <div style="margin-top:var(--spacing-md)">${this.renderRecent()}</div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-revocation': VersolaPartnerRevocation;
  }
}
