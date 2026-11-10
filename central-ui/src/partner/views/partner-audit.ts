import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, formStyles, recordStyles, tagStyles } from '../../styles/components';
import { statusIcon } from '../../components/status-icon';
import { resultBadgeStyles } from '../../components/activity-shared';
import { formatDate } from '../../utils/helpers';
import { EMPTY_RECORD_ROUTE, type RecordRoute } from '../../utils/record-route';
import { fetchAudit } from '../partner-api';
import type { AuditEventRecord, PartnerMe } from '../partner-types';
import { partnerViewStyles } from '../partner-styles';
import '../../components/content-header';
import '../../components/empty-state';
import '../../components/error-card';
import '../../components/filter-bar';
import '../../components/loading-cards';

type ActorFilter = 'all' | 'team' | 'operator' | 'edges';
type KindFilter = '' | 'clients' | 'resources' | 'edges' | 'team' | 'revocations' | 'security';

/**
 * Audit: everything done in this console, every decision the operator made about the partner,
 * and what its edges did. A table filtered by actor and kind, paged by the API's cursor, exported
 * as CSV from what is loaded.
 */
@customElement('versola-partner-audit')
export class VersolaPartnerAudit extends LitElement {
  @property({ attribute: false }) me!: PartnerMe;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private events: AuditEventRecord[] = [];
  @state() private next: string | null = null;
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private actor: ActorFilter = 'all';
  @state() private kind: KindFilter = '';
  @state() private query = '';
  private queryTimer: ReturnType<typeof setTimeout> | undefined;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
    recordStyles,
    resultBadgeStyles,
    partnerViewStyles,
    css`
      :host { container: audit / inline-size; }
      .table-card { padding: 0; overflow: auto; }
      table { width: 100%; border-collapse: collapse; }
      th { text-align: left; font-size: var(--text-xs); font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; color: var(--text-secondary); padding: 0.75rem var(--spacing-md); border-bottom: 1px solid var(--border-default); white-space: nowrap; }
      td { padding: 0.75rem var(--spacing-md); border-bottom: 1px solid var(--border-subtle); vertical-align: top; font-size: var(--text-base); }
      tbody tr:last-child td { border-bottom: 0; }
      td.when { white-space: nowrap; font-family: var(--font-mono); font-size: var(--text-sm); color: var(--text-secondary); }
      td.who { white-space: nowrap; }
      .chip.operator { border-color: var(--accent-border); background: var(--accent-subtle); color: var(--accent-text); }
      .pager { display: flex; justify-content: center; padding: var(--spacing-md); border-top: 1px solid var(--border-default); }
      select.kind { width: auto; max-width: 16rem; padding-block: 0.5rem; }
      .footnote { display: flex; gap: 0.5rem; align-items: flex-start; margin-top: var(--spacing-md); }
      @container audit (max-width: 720px) {
        thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
        tr { display: grid; gap: 0.25rem; padding: 0.75rem var(--spacing-md); border-bottom: 1px solid var(--border-subtle); }
        tbody tr:last-child { border-bottom: 0; }
        td { display: block; padding: 0; border: 0; white-space: normal; }
        td::before { content: attr(data-label); display: block; font-size: var(--text-xs); font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; color: var(--text-secondary); }
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.load();
  }

  private async load(append = false) {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const page = await fetchAudit({ cursor: append ? this.next : null, actor: this.actor === 'all' ? undefined : this.actor, kind: this.kind || undefined, ...(this.query.trim() ? { q: this.query.trim() } : {}) });
      this.events = append ? [...this.events, ...page.events] : page.events;
      this.next = page.next ?? null;
    } catch (error) {
      if (!append) this.events = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load the audit log';
    } finally {
      this.isLoading = false;
    }
  }

  private setActor(actor: ActorFilter) {
    this.actor = actor;
    void this.load();
  }

  private setKind(kind: KindFilter) {
    this.kind = kind;
    void this.load();
  }

  private setQuery(query: string) {
    this.query = query;
    clearTimeout(this.queryTimer);
    this.queryTimer = setTimeout(() => void this.load(), 250);
  }

  private exportCsv() {
    const escape = (value: string) => `"${value.replace(/"/g, '""')}"`;
    const lines = [['at', 'actor', 'sub', 'action', 'target', 'outcome', 'from'].join(','),
      ...this.events.map(event => [event.at, event.actor.display ?? '', event.actor.sub, event.action, event.target, event.outcome, event.from ?? ''].map(escape).join(','))];
    const blob = new Blob([lines.join('\n')], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${this.me.partner.id}-audit.csv`;
    link.click();
    URL.revokeObjectURL(url);
  }

  private who(event: AuditEventRecord) {
    if (event.actor.byOperator) return html`<span class="chip operator">operator</span>`;
    if (event.actor.sub.startsWith('edge-')) return html`<span class="mono">${event.actor.sub}</span>`;
    if (event.actor.sub === 'anonymous') return html`<span class="chip warn">security</span>`;
    return html`${event.actor.display ?? event.actor.sub}`;
  }

  private outcome(event: AuditEventRecord) {
    const tone = event.outcome === 'ok' ? 'ok' : event.outcome === 'refused' ? 'warn' : 'danger';
    return html`<span class="result ${tone}">${event.outcome}</span>`;
  }

  render() {
    const actors: Array<{ id: ActorFilter; label: string }> = [{ id: 'all', label: 'All' }, { id: 'team', label: 'Team' }, { id: 'operator', label: 'Operator' }, { id: 'edges', label: 'Edges' }];
    return html`
      <content-header title="Audit" description="Everything done in this console, every decision the operator made about you, and what your edges did. The same events exist in the operator's log under your partner ID.">
        <button slot="actions" class="btn btn-secondary" ?disabled=${this.events.length === 0} @click=${() => this.exportCsv()}>Export CSV</button>
      </content-header>
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search audit" placeholder="Search by client, member or event" .value=${this.query} @input=${(e: Event) => this.setQuery((e.target as HTMLInputElement).value)} />
        <versola-filter-bar label="Filter by actor" .options=${actors} .value=${this.actor} @change=${(e: CustomEvent<{ id: string }>) => this.setActor(e.detail.id as ActorFilter)}></versola-filter-bar>
        <select class="kind" aria-label="Kind of event" .value=${this.kind} @change=${(e: Event) => this.setKind((e.target as HTMLSelectElement).value as KindFilter)}>
          <option value="">Every kind</option>
          <option value="clients">Clients</option>
          <option value="resources">Resources</option>
          <option value="edges">Edges</option>
          <option value="team">Team</option>
          <option value="revocations">Revocations</option>
          <option value="security">Security</option>
        </select>
      </div>
      ${this.errorMessage ? html`<versola-error-card heading="Could not load the audit log" .message=${this.errorMessage} @retry=${() => this.load()}></versola-error-card>`
        : this.isLoading && this.events.length === 0 ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
          : this.events.length === 0 ? html`<versola-empty-state heading="No events match"></versola-empty-state>`
            : html`
              <div class="card table-card">
                <table aria-label="Audit events">
                  <thead><tr><th>When</th><th>Who</th><th>Event</th><th>Target</th><th>Outcome</th><th>From</th></tr></thead>
                  <tbody>${this.events.map(event => html`
                    <tr>
                      <td class="when" data-label="When">${formatDate(event.at)}</td>
                      <td class="who" data-label="Who">${this.who(event)}</td>
                      <td data-label="Event">${event.action}</td>
                      <td data-label="Target"><span class="mono">${event.target}</span></td>
                      <td data-label="Outcome">${this.outcome(event)}</td>
                      <td data-label="From" class="muted">${event.from ?? '—'}</td>
                    </tr>`)}</tbody>
                </table>
                ${this.next ? html`<div class="pager"><button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.isLoading} @click=${() => this.load(true)}>${this.isLoading ? 'Loading…' : 'Older events'}</button></div>` : ''}
              </div>`}
      <p class="muted small footnote">${statusIcon('info', 'icon', 14)}<span>Team and edge events are written by your edge; operator decisions and client changes come from central with your partner ID. Retention follows the operator's audit policy.</span></p>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-audit': VersolaPartnerAudit;
  }
}
