import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, recordStyles, segmentedStyles, tagStyles } from '../../styles/components';
import { statusIcon } from '../../components/status-icon';
import type { DataTableColumn } from '../../components/data-table';
import { confirmDestructiveAction } from '../../utils/confirm-dialog';
import { formatDate } from '../../utils/helpers';
import { fetchRevocations, fetchStaff, revoke, writeErrorMessage } from '../partner-api';
import type { PartnerMe, Revocation, RevocationBy, StaffMember } from '../partner-types';
import { partnerViewStyles } from '../partner-styles';
import '../../components/content-header';
import '../../components/data-table';
import '../../components/record-card';
import '../../components/loading-cards';

const BY_LABELS: Record<RevocationBy, { label: string; field: string; hint: string; placeholder: string; what: string }> = {
  user: { label: 'A user', field: 'User (sub)', hint: 'The sub as it appears in your tokens and /userinfo. Every session of the user on this edge.', placeholder: 'u_7f3a9c2e', what: 'the sessions of user' },
  sid: { label: 'A session', field: 'Session (sid)', hint: 'One session, as the sid claim names it.', placeholder: 'sid_…', what: 'session' },
  fam: { label: 'A refresh family', field: 'Family (fam_id)', hint: 'One refresh-token family, as the fam_id claim names it. Your edge stops using it and the session that holds it ends.', placeholder: 'fam_…', what: 'refresh family' },
};

/**
 * Sessions & revocation: end sessions held by the edge that serves this console. The tokens live in those
 * sessions, so ending one discards them; the operator's auth is never contacted and central is not needed.
 * Admins only; others see the list.
 */
@customElement('versola-partner-revocation')
export class VersolaPartnerRevocation extends LitElement {
  @property({ attribute: false }) me!: PartnerMe;
  @property({ type: Boolean }) canManage = false;

  @state() private team: StaffMember[] = [];
  @state() private recent: Revocation[] | null = null;
  @state() private by: RevocationBy = 'user';
  @state() private value = '';
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
    const [recent, team] = await Promise.allSettled([fetchRevocations(), fetchStaff()]);
    this.team = team.status === 'fulfilled' ? team.value : [];
    this.recent = recent.status === 'fulfilled' ? recent.value : [];
  }

  private get valueError(): string {
    return this.value.trim() ? '' : 'A value is required.';
  }

  private async submit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.submitted = true;
    if (this.valueError) return;
    const spec = BY_LABELS[this.by];
    const what = `${spec.what} ${this.value.trim()}`;
    const confirmed = await confirmDestructiveAction({
      title: 'End sessions',
      message: `End ${what} on this edge? The users are signed out and sign in again on their next visit. The operator's auth is not contacted: tokens already issued stay valid there until they expire, but they are held only in the sessions you end.`,
      confirmLabel: 'End sessions',
    });
    if (!confirmed) return;
    this.isSaving = true;
    this.formError = '';
    try {
      const response = await revoke({ by: this.by, value: this.value.trim() });
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
    return html`
      <form novalidate @submit=${this.submit}>
        <div class="card record-card">
          <div class="record-card-head"><div><h2>End sessions</h2><div class="record-card-sub">On the edge that serves this console, <span class="mono">${this.me.edge.id}</span>. The user is named by the same identifier you got in the token.</div></div></div>
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
            <div class="form-group">
              <label for="revoke-value">${spec.field} *</label>
              <input type="text" id="revoke-value" class="compact-input mono ${valueError ? 'input-error' : ''}" .value=${this.value} placeholder=${spec.placeholder} ?disabled=${!this.canManage}
                aria-invalid=${valueError ? 'true' : nothing} aria-describedby="revoke-value-hint" @input=${(e: Event) => this.value = (e.target as HTMLInputElement).value} />
              ${valueError ? html`<div class="error-message" id="revoke-value-hint" role="alert">${valueError}</div>` : html`<div class="hint" id="revoke-value-hint">${spec.hint}</div>`}
            </div>
            <div class="danger-actions"><button type="submit" class="btn btn-danger" ?disabled=${!this.canManage || this.isSaving}>${this.isSaving ? 'Ending…' : 'End sessions'}</button></div>
          </div>
        </div>
      </form>`;
  }

  private renderRecent() {
    const columns: DataTableColumn<Revocation>[] = [
      { id: 'when', label: 'When', class: 'muted', cell: item => formatDate(item.at) },
      { id: 'who', label: 'By', cell: item => this.team.find(member => member.subject === item.by_staff)?.display ?? item.by_staff },
      { id: 'what', label: 'What', cell: item => html`<span class="cell-line">${[BY_LABELS[item.by]?.label.toLowerCase(), item.value].filter(Boolean).join(' · ')}</span>` },
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
      <content-header title="Sessions & revocation" description="End sessions held by the edge that serves this console: every session of a user, one session, or one refresh family. The tokens live in those sessions and are discarded with them. The operator's auth is not contacted, so a token already issued stays valid there until it expires; users of other partners are never visible here."></content-header>
      <div class="grid-2">
        ${this.renderForm()}
        <versola-record-card heading="What it does" .body=${html`
          <versola-record-row .label=${'this edge'} .value=${html`ends the sessions and discards the tokens they hold <span class="chip ok">immediately</span>`}></versola-record-row>
          <versola-record-row .label=${'the user'} .value=${html`signs in again on the next visit`}></versola-record-row>
          <versola-record-row .label=${"operator's auth"} .value=${html`not contacted; a token already issued stays valid there <span class="chip">until it expires</span>`}></versola-record-row>
          <versola-record-row .label=${'your other edges'} .value=${html`unaffected: each keeps its own sessions`}></versola-record-row>
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
