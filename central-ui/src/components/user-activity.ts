import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, iconButtonStyles, segmentedStyles } from '../styles/components';
import { formatShortDateTime, offsetLabel } from '../utils/date-format';
import { countryName, describeCountry } from '../utils/country-flag';
import { copyToClipboard } from '../utils/helpers';
import { dispatchRecordRoute } from '../utils/record-route';
import { fetchUserAudit, UserAuditError } from '../utils/users-api';
import {
  NO_FILTERS,
  adminActionEvent,
  attentionEvents,
  currentCursor,
  describeEvent,
  describeUserAgent,
  pageRangeLabel,
  parseActivityParams,
  popCursor,
  presetWindow,
  pushCursor,
  serializeActivityParams,
  summaryCards,
  type ActivityView,
  type AuditEvent,
  type Filters,
  type RangeKind,
  type SummaryCard,
  type UserAuditReport,
} from '../utils/user-audit';
import { effectiveZoneChoice, getProfileZone, getStoredZoneChoice, onProfileZoneChange, resolveZone, setStoredZoneChoice, type ZoneChoice } from '../utils/zone-preference';
import { resultBadge, resultBadgeStyles, countryLabel } from './activity-shared';
import { statusIcon } from './status-icon';
import type { VersolaActivityTimeline, TimelineState } from './activity-timeline';
import './activity-event-panel';
import './activity-range';
import './activity-timeline';
import './empty-state';

const stable = (params: Record<string, string>) => JSON.stringify(Object.entries(params).sort(([a], [b]) => a.localeCompare(b)));

/**
 * The Activity tab of the user record (`?view=users&id=<id>&tab=activity`), reading central's audit report
 * (`GET /users/audit`, permission `audit:read`). The record renders it only while the tab is open, so nothing is asked
 * for before. The period control and the summary of the first page (cards, what needs attention, addresses,
 * browsers), then the timeline with its filters and pager, and the event panel. The view (interval, filters, page, open
 * event) lives in the URL (`params`), so Copy link reproduces it.
 */
@customElement('versola-user-activity')
export class VersolaUserActivity extends LitElement {
  @property({ type: String }) userId = '';
  @property({ type: Boolean }) canReadAudit = false;
  /** The page's URL parameters, owned by `admin-app`. */
  @property({ attribute: false }) params: Record<string, string> = {};

  @state() private view: ActivityView = parseActivityParams({}, new Date());
  /** The page on screen. */
  @state() private report: UserAuditReport | null = null;
  /** The first page of the interval: where the summary and the cards come from. */
  @state() private first: UserAuditReport | null = null;
  @state() private loadState: TimelineState = 'loading';
  @state() private errorMessage = '';
  @state() private forbidden = false;
  /** What this browser remembers of an explicit switch; `null` leaves the choice to the profile zone. */
  @state() private storedZoneChoice: ZoneChoice | null = getStoredZoneChoice();
  /** The administrator's own zone (the `zoneinfo` of their profile), `null` when there is none. */
  @state() private profileZone: string | null = getProfileZone();
  private stopProfileZone?: () => void;
  @state() private updatedAt: Date | null = null;
  @state() private rangeOpen = false;
  @state() private rangeProblem = '';
  @state() private announcement = '';
  @state() private linkCopied = false;
  @query('versola-activity-timeline') private timelineEl?: VersolaActivityTimeline;
  @query('#custom-range-button') private customButton?: HTMLButtonElement;

  private seq = 0;
  private lastSynced = '';
  private lastGood: ActivityView | null = null;
  private announceNext = false;
  private opener: { from: 'timeline' | 'attention'; id: string } | null = null;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    bannerStyles,
    iconButtonStyles,
    segmentedStyles,
    resultBadgeStyles,
    css`
      :host { display: block; }

      .period-bar { position: relative; display: flex; flex-wrap: wrap; align-items: center; gap: var(--spacing-sm) var(--spacing-md); margin-bottom: var(--spacing-lg); }
      .period-bar .seg-btn { min-height: 2.25rem; padding: 0 0.875rem; }
      .period-note { font-size: var(--text-sm); color: var(--text-secondary); }
      .period-bar .spacer { flex: 1; }
      .icon-button.copy-link { min-width: 2.25rem; min-height: 2.25rem; border: 1.5px solid var(--border-input); border-radius: var(--radius-pill); color: var(--text-primary); }
      .icon-button.copy-link:hover { border-color: var(--text-primary); color: var(--text-primary); }
      versola-activity-range { position: absolute; top: calc(100% + 0.5rem); left: 0; z-index: var(--z-dropdown); }

      .stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--spacing-md); margin-bottom: var(--spacing-lg); }
      .stat {
        display: block;
        box-sizing: border-box;
        width: 100%;
        padding: var(--spacing-md) var(--spacing-lg);
        text-align: left;
        font: inherit;
        color: var(--text-primary);
        background: var(--surface);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-card);
      }
      button.stat { cursor: pointer; transition: border-color var(--transition-fast); }
      button.stat:hover { border-color: var(--border-strong); }
      button.stat:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
      .stat-top { display: flex; justify-content: space-between; align-items: baseline; gap: var(--spacing-sm); font-size: var(--text-base); font-weight: 600; color: var(--text-secondary); }
      .stat-view { font-size: var(--text-sm); font-weight: 600; color: var(--accent); white-space: nowrap; }
      .stat-value { display: block; margin: 0.25rem 0; font-size: var(--text-3xl); font-weight: 800; letter-spacing: -0.02em; line-height: 1.2; }
      .stat-detail { display: block; font-size: var(--text-sm); color: var(--text-secondary); overflow-wrap: anywhere; }

      .card { padding: 0; margin-bottom: var(--spacing-lg); overflow: hidden; }
      .card-head { padding: var(--spacing-lg) var(--spacing-lg) var(--spacing-md); }
      .card-head h2 { margin: 0; font-size: var(--text-xl); font-weight: 700; color: var(--text-primary); }
      .card-head p { margin: 0.25rem 0 0; font-size: var(--text-sm); color: var(--text-secondary); }
      .none { margin: 0; padding: var(--spacing-md) var(--spacing-lg) var(--spacing-lg); font-size: var(--text-base); color: var(--text-secondary); border-top: 1px solid var(--border-default); }

      ul.attention { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--border-default); }
      ul.attention li + li { border-top: 1px solid var(--border-subtle); }
      .attn-row {
        display: grid;
        grid-template-columns: 9.5rem minmax(0, 1fr) auto 1rem;
        align-items: center;
        gap: var(--spacing-md);
        box-sizing: border-box;
        width: 100%;
        padding: 0.875rem var(--spacing-lg);
        border: 0;
        background: none;
        font: inherit;
        text-align: left;
        color: var(--text-primary);
        cursor: pointer;
      }
      .attn-row:hover { background: var(--surface-inset); }
      .attn-row:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: -2px; }
      .attn-time { font-size: var(--text-sm); color: var(--text-secondary); font-variant-numeric: tabular-nums; }
      .attn-title { display: block; font-weight: 700; }
      .attn-sub { display: block; font-size: var(--text-sm); color: var(--text-secondary); overflow-wrap: anywhere; }
      .attn-chevron { color: var(--text-secondary); }

      .pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--spacing-lg); }
      .pair .card { margin-bottom: var(--spacing-lg); }
      .table-wrap { overflow-x: auto; border-top: 1px solid var(--border-default); }
      .table-wrap:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: -2px; }
      table.seen { width: 100%; min-width: 30rem; border-collapse: collapse; font-size: var(--text-base); }
      table.seen th { padding: 0.875rem 1rem; text-align: left; font-size: var(--text-xs); font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--text-secondary); white-space: nowrap; }
      table.seen td { padding: 0.625rem 1rem; border-top: 1px solid var(--border-subtle); vertical-align: top; overflow-wrap: anywhere; }
      table.seen th:first-child, table.seen td:first-child { padding-left: var(--spacing-lg); }
      table.seen th:last-child, table.seen td:last-child { padding-right: var(--spacing-lg); }
      table.seen td.when, table.seen td.nowrap { white-space: nowrap; font-variant-numeric: tabular-nums; }

      .skeleton { display: block; border-radius: var(--radius-lg); border: 1px solid var(--border-default); background: var(--surface); box-shadow: var(--shadow-card); padding: var(--spacing-lg); }
      .bar { display: block; height: 0.75rem; border-radius: 999px; background: linear-gradient(90deg, var(--surface-inset), var(--accent-subtle), var(--surface-inset)); background-size: 200% 100%; animation: shimmer 1.4s infinite; }
      .bar + .bar { margin-top: 0.75rem; }
      .bar.short { width: 40%; }
      .bar.medium { width: 65%; }
      .bar.block { height: 1.75rem; width: 30%; }
      @keyframes shimmer { from { background-position: 200% 0; } to { background-position: -200% 0; } }

      .footnote { margin: var(--spacing-md) 0 var(--spacing-xl); font-size: var(--text-sm); color: var(--text-secondary); }
      .footnote a { color: var(--accent); }
      .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }

      @media (max-width: 1100px) { .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } .pair { grid-template-columns: minmax(0, 1fr); } }
      @media (max-width: 720px) {
        .stats { grid-template-columns: minmax(0, 1fr); }
        .attn-row { grid-template-columns: minmax(0, 1fr) auto; }
        .attn-time { grid-column: 1 / -1; }
        .attn-chevron { display: none; }
      }
    `,
  ];

  // ── lifecycle ─────────────────────────────────────────────────────────

  willUpdate(changed: Map<PropertyKey, unknown>) {
    if (changed.has('userId') || changed.has('canReadAudit')) {
      this.start();
    } else if (changed.has('params') && Object.keys(this.params).length > 0 && stable(this.params) !== this.lastSynced) {
      // The URL changed under us (Back, a pasted link): take its view.
      this.view = parseActivityParams(this.params, new Date());
      this.lastSynced = stable(serializeActivityParams(this.view));
      this.first = null;
      void this.load();
    }
  }

  private start() {
    this.view = parseActivityParams(this.params, new Date());
    this.report = null;
    this.first = null;
    this.forbidden = false;
    if (!this.canReadAudit) return;
    this.syncUrl();
    void this.load();
  }

  private syncUrl() {
    const next = serializeActivityParams(this.view);
    this.lastSynced = stable(next);
    if (stable(this.params) === this.lastSynced) return;
    dispatchRecordRoute(this, { tab: 'activity', params: next });
  }

  private async load() {
    const seq = ++this.seq;
    const view = this.view;
    this.loadState = 'loading';
    this.errorMessage = '';
    try {
      const query = { from: view.from, to: view.to, cursor: currentCursor(view.cursors), limit: view.limit };
      const report = await fetchUserAudit(this.userId, query);
      if (seq !== this.seq) return;
      this.report = report;
      if (!query.cursor) this.first = report;
      else if (!this.first?.summary) void this.loadSummary(seq);
      this.updatedAt = new Date();
      this.loadState = 'ready';
      this.rangeProblem = '';
      this.lastGood = view;
      if (view.event && !this.findEvent(view.event)) this.setView({ event: '' });
      this.afterLoad();
    } catch (error) {
      if (seq !== this.seq) return;
      if (error instanceof UserAuditError && error.status === 403) {
        this.forbidden = true;
      } else if (error instanceof UserAuditError && error.status === 400 && this.lastGood && this.lastGood !== view) {
        // The server refused this window or cursor: go back to the view that worked and say why.
        this.rangeProblem = error.message;
        this.view = this.lastGood;
        this.syncUrl();
        this.loadState = 'ready';
        this.announceNext = false;
        return;
      } else {
        this.errorMessage = error instanceof Error ? error.message : 'Failed to load activity';
      }
      this.loadState = 'error';
      this.announceNext = false;
    }
  }

  /** The summary belongs to the first page; a link that opens at a later one asks for it with a one-event page. */
  private async loadSummary(seq: number) {
    try {
      const report = await fetchUserAudit(this.userId, { from: this.view.from, to: this.view.to, limit: 1 });
      if (seq === this.seq) this.first = report;
    } catch {
      // The cards stay unavailable; the page itself is shown.
    }
  }

  private afterLoad() {
    if (!this.announceNext || !this.report) return;
    this.announceNext = false;
    const label = pageRangeLabel(this.view.cursors, this.view.limit, this.report.events.length);
    this.announcement = `${label}.${this.report.nextCursor ? '' : ' Beginning of history.'}`;
    void this.updateComplete.then(() => this.timelineEl?.scrollToTop());
  }

  // ── view changes ──────────────────────────────────────────────────────

  private setView(change: Partial<ActivityView>) {
    this.view = { ...this.view, ...change };
    this.syncUrl();
  }

  private reload(change: Partial<ActivityView> = {}, options: { resetSummary?: boolean } = {}) {
    this.view = { ...this.view, cursors: [], ...change };
    if (options.resetSummary) this.first = null;
    this.announceNext = true;
    this.syncUrl();
    void this.load();
  }

  private chooseRange(range: Exclude<RangeKind, 'custom'>) {
    this.rangeOpen = false;
    this.rangeProblem = '';
    this.reload({ range, ...presetWindow(range, new Date()), event: '' }, { resetSummary: true });
  }

  private applyCustom(event: CustomEvent<{ from: string; to: string }>) {
    this.rangeOpen = false;
    this.rangeProblem = '';
    this.reload({ range: 'custom', from: event.detail.from, to: event.detail.to, event: '' }, { resetSummary: true });
    void this.updateComplete.then(() => this.customButton?.focus());
  }

  private closeRange() {
    this.rangeOpen = false;
    void this.updateComplete.then(() => this.customButton?.focus());
  }

  private refresh() {
    const range = this.view.range;
    // A preset ends now: refreshing moves its end to now. A custom range stays what it was.
    this.reload(range === 'custom' ? {} : presetWindow(range, new Date()));
  }

  private older() {
    const next = this.report?.nextCursor;
    if (!next) return;
    this.reload({ cursors: pushCursor(this.view.cursors, next), event: '' });
  }

  private newer() {
    this.reload({ cursors: popCursor(this.view.cursors), event: '' });
  }

  private setFilters(filters: Filters) {
    this.setView({ filters });
  }

  private setZone(choice: ZoneChoice) {
    this.storedZoneChoice = choice;
    setStoredZoneChoice(choice);
  }

  private get zoneChoice(): ZoneChoice {
    return effectiveZoneChoice(this.storedZoneChoice, this.profileZone);
  }

  override connectedCallback() {
    super.connectedCallback();
    // The zone saved under Preferences shows without a reload, and so does a zone cleared there.
    this.stopProfileZone = onProfileZoneChange(() => {
      this.profileZone = getProfileZone();
      this.storedZoneChoice = getStoredZoneChoice();
    });
    this.profileZone = getProfileZone();
  }

  override disconnectedCallback() {
    this.stopProfileZone?.();
    super.disconnectedCallback();
  }

  private applyCard(card: SummaryCard) {
    this.setView({ filters: { ...NO_FILTERS, ...card.filters } });
    void this.updateComplete.then(() => {
      this.timelineEl?.scrollToTop();
      this.timelineEl?.focusTitle();
    });
  }

  private async copyLink() {
    if (await copyToClipboard(window.location.href)) {
      this.linkCopied = true;
      window.setTimeout(() => { this.linkCopied = false; }, 2000);
    }
  }

  // ── the event panel ───────────────────────────────────────────────────

  /** An event of the loaded page, of the high-risk list, or an administrator action of the summary. */
  private findEvent(id: string): AuditEvent | undefined {
    const summary = this.first?.summary;
    return this.report?.events.find(event => event.id === id)
      ?? summary?.high.find(event => event.id === id)
      ?? summary?.adminActions.map(action => adminActionEvent(action, this.userId)).find(event => event.id === id);
  }

  private openEvent(id: string, from: 'timeline' | 'attention') {
    this.opener = { from, id };
    this.setView({ event: id });
  }

  /** An attention row for an administrator action opens the same row of the timeline when it holds one. */
  private openAttention(event: AuditEvent) {
    const match = event.id.startsWith('admin-action:')
      ? this.report?.events.find(candidate => candidate.kind === event.kind && candidate.at === event.at)
      : undefined;
    this.openEvent(match?.id ?? event.id, 'attention');
  }

  private closeEvent() {
    const opener = this.opener;
    this.setView({ event: '' });
    void this.updateComplete.then(() => {
      if (opener?.from === 'attention') {
        const row = [...this.renderRoot.querySelectorAll<HTMLElement>('.attn-row')].find(node => node.dataset.id === opener.id);
        if (row) { row.focus(); return; }
      }
      if (opener) this.timelineEl?.focusRow(opener.id);
    });
  }

  private showRequest(event: CustomEvent<{ traceId: string }>) {
    this.opener = null;
    this.setView({ filters: { ...NO_FILTERS, trace: event.detail.traceId }, event: '' });
    void this.updateComplete.then(() => {
      this.timelineEl?.scrollToTop();
      this.timelineEl?.focusTitle();
    });
  }

  // ── drawing ───────────────────────────────────────────────────────────

  /** Which zone the times are in and why, for the line under the report. */
  private get zoneNote(): string {
    switch (this.zoneChoice) {
      case 'utc': return 'Times are shown in UTC.';
      case 'zone': return `Times are shown in your time zone from Preferences (${this.zone}).`;
      default: return `Times are shown in your browser's time zone (${this.zone}).`;
    }
  }

  private get zone(): string {
    return resolveZone(this.zoneChoice, this.profileZone);
  }

  private when(at: string): string {
    return formatShortDateTime(at, this.zone);
  }

  /** First and last seen: to the minute, like the mockup; the title carries the seconds and both zones. */
  private seen(at: string): string {
    return formatShortDateTime(at, this.zone, { seconds: false });
  }

  private bothZones(at: string): string {
    return this.zone === 'UTC' ? `${formatShortDateTime(at, 'UTC')} UTC` : `${formatShortDateTime(at, this.zone)} (${offsetLabel(at, this.zone)})\n${formatShortDateTime(at, 'UTC')} UTC`;
  }

  private renderPeriod() {
    const { view } = this;
    const window = this.report?.window;
    const to = window?.to ?? view.to;
    const from = window?.from ?? view.from;
    const end = view.range === 'custom' ? formatShortDateTime(to, this.zone, { seconds: false }) : 'now';
    const zoneName = offsetLabel(to, this.zone);
    const pill = (range: Exclude<RangeKind, 'custom'>, label: string) => html`
      <button type="button" class="seg-btn ${view.range === range ? 'seg-active' : ''}" aria-pressed=${view.range === range}
        @click=${() => this.chooseRange(range)}>${label}</button>`;
    return html`
      <div class="period-bar">
        <div class="seg-control" role="group" aria-label="Period">
          ${pill('24h', '24 hours')}
          ${pill('7d', '7 days')}
          <button type="button" id="custom-range-button" class="seg-btn ${view.range === 'custom' ? 'seg-active' : ''}" aria-pressed=${view.range === 'custom'}
            aria-haspopup="dialog" aria-expanded=${this.rangeOpen} @click=${() => { this.rangeOpen = !this.rangeOpen; }}>Custom range</button>
        </div>
        <span class="period-note">Showing ${formatShortDateTime(from, this.zone, { seconds: false })} – ${end} (${zoneName})</span>
        <span class="spacer"></span>
        <button type="button" class="icon-button copy-link" aria-label="Copy link to this view" title="Copy link to this view" @click=${this.copyLink}>${statusIcon(this.linkCopied ? 'check' : 'link', '', 18)}</button>
        <span class="sr-only" role="status" aria-live="polite">${this.linkCopied ? 'Link copied' : ''}</span>
        <versola-activity-range ?open=${this.rangeOpen} .zone=${this.zone} .from=${from} .to=${to} .retentionFrom=${window?.retentionFrom ?? ''}
          .serverProblem=${this.rangeProblem}
          @apply=${this.applyCustom} @cancel=${this.closeRange}></versola-activity-range>
      </div>`;
  }

  private renderCards(cards: SummaryCard[]) {
    return html`
      <div class="stats" role="group" aria-label="Summary of this period">
        ${cards.map(card => {
          const body = html`
            <span class="stat-top"><span>${card.label}</span>${card.value === null ? nothing : html`<span class="stat-view" aria-hidden="true">View ›</span>`}</span>
            <span class="stat-value">${card.value === null ? html`<span aria-hidden="true">—</span>` : card.value}</span>
            <span class="stat-detail">${card.detail}</span>`;
          return card.value === null
            ? html`<div class="stat">${body}</div>`
            : html`<button type="button" class="stat" title=${`Show these events in the timeline`} @click=${() => this.applyCard(card)}>${body}</button>`;
        })}
      </div>`;
  }

  private attentionSubtitle(event: AuditEvent): string {
    const { subtitle } = describeEvent(event);
    const country = describeCountry(event.country);
    return [subtitle, event.clientId, event.ip && country ? `${event.ip} · ${country.name}` : event.ip, describeUserAgent(event.userAgent)].filter(Boolean).join(' · ');
  }

  private renderAttention(events: AuditEvent[]) {
    return html`
      <section class="card" aria-labelledby="attention-title">
        <div class="card-head"><h2 id="attention-title">Needs attention</h2><p>Events rated high risk and administrator actions in this period</p></div>
        ${events.length === 0 ? html`<p class="none">Nothing needs attention in this period.</p>` : html`
          <ul class="attention">
            ${events.map(event => html`
              <li><button type="button" class="attn-row" data-id=${event.id} aria-haspopup="dialog" @click=${() => this.openAttention(event)}>
                <span class="attn-time" title=${this.bothZones(event.at)}>${this.when(event.at)}</span>
                <span><span class="attn-title">${describeEvent(event).title}</span><span class="attn-sub">${this.attentionSubtitle(event)}</span></span>
                ${resultBadge(event)}
                <span class="attn-chevron" aria-hidden="true">›</span>
              </button></li>`)}
          </ul>`}
      </section>`;
  }

  private renderSeen(report: UserAuditReport) {
    const summary = report.summary!;
    const names = summary.countries.map(entry => countryName(entry.country) ?? entry.country);
    return html`
      <div class="pair">
        <section class="card" aria-labelledby="addresses-title">
          <div class="card-head"><h2 id="addresses-title">Addresses</h2><p>Where the activity came from${names.length ? `: ${names.join(', ')}` : ''}</p></div>
          ${summary.ips.length === 0 ? html`<p class="none">No addresses recorded in this period.</p>` : html`
            <div class="table-wrap" tabindex="0" role="group" aria-labelledby="addresses-title"><table class="seen">
              <thead><tr><th scope="col">Address</th><th scope="col">Country</th><th scope="col">First seen</th><th scope="col">Last seen</th></tr></thead>
              <tbody>${summary.ips.map(entry => html`<tr>
                <td class="mono nowrap">${entry.ip}</td>
                <td class="nowrap">${entry.country ? countryLabel(entry.country) : html`<span class="muted">—</span>`}</td>
                <td class="when" title=${this.bothZones(entry.firstSeen)}>${this.seen(entry.firstSeen)}</td>
                <td class="when" title=${this.bothZones(entry.lastSeen)}>${this.seen(entry.lastSeen)}</td></tr>`)}</tbody>
            </table></div>`}
        </section>
        <section class="card" aria-labelledby="browsers-title">
          <div class="card-head"><h2 id="browsers-title">Browsers and apps</h2><p>What the person used</p></div>
          ${summary.userAgents.length === 0 ? html`<p class="none">No browsers or apps recorded in this period.</p>` : html`
            <div class="table-wrap" tabindex="0" role="group" aria-labelledby="browsers-title"><table class="seen">
              <thead><tr><th scope="col">Name</th><th scope="col">First seen</th><th scope="col">Last seen</th></tr></thead>
              <tbody>${summary.userAgents.map(entry => html`<tr>
                <td title=${entry.userAgent}>${describeUserAgent(entry.userAgent)}</td>
                <td class="when" title=${this.bothZones(entry.firstSeen)}>${this.seen(entry.firstSeen)}</td>
                <td class="when" title=${this.bothZones(entry.lastSeen)}>${this.seen(entry.lastSeen)}</td></tr>`)}</tbody>
            </table></div>`}
        </section>
      </div>`;
  }

  private renderSkeletons() {
    return html`
      <div class="stats" aria-hidden="true">
        ${[0, 1, 2, 3].map(() => html`<span class="skeleton"><span class="bar short"></span><span class="bar block" style="margin-top:0.75rem"></span><span class="bar medium" style="margin-top:0.75rem"></span></span>`)}
      </div>
      <span class="skeleton" aria-hidden="true" style="margin-bottom: var(--spacing-lg)"><span class="bar short"></span><span class="bar"></span><span class="bar medium"></span></span>`;
  }

  private renderDenied() {
    return html`<versola-empty-state heading="No access to Activity" text="Reading a user's activity needs the audit:read permission. Ask an administrator who can grant it."></versola-empty-state>`;
  }

  render() {
    if (!this.canReadAudit || this.forbidden) return this.renderDenied();

    const { report, first, view } = this;
    const summary = first?.summary ?? null;
    const partial = Boolean(report?.partial.includes('auth') || first?.partial.includes('auth'));
    const firstPageEvents = first?.events ?? [];
    const cards = summaryCards(summary, firstPageEvents, first?.truncated ?? false, at => this.when(at));
    const hasAnything = firstPageEvents.length > 0 || (summary?.adminActions.length ?? 0) > 0;
    const showSummary = Boolean(first) && hasAnything;
    const selected = view.event ? this.findEvent(view.event) : undefined;
    const showsCountry = Boolean(report?.events.some(event => event.country) || summary?.ips.some(entry => entry.country));

    return html`
      ${this.renderPeriod()}
      ${this.rangeProblem && !this.rangeOpen ? html`<div class="banner warn" role="alert"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}That period was not accepted</div><div>${this.rangeProblem}</div></div>
        <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.rangeProblem = ''; }}>Dismiss</button></div>` : nothing}
      ${partial ? html`<div class="banner warn" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}Sign-in history is unavailable right now</div>
        <div>The authentication service did not answer, so only administrator actions are shown.</div></div>
        <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.refresh()}>Try again</button></div>` : nothing}

      ${!first && this.loadState === 'loading' ? this.renderSkeletons() : nothing}
      ${showSummary ? html`
        ${this.renderCards(cards)}
        ${this.renderAttention(attentionEvents(summary, this.userId))}
        ${first && summary?.auth ? this.renderSeen(first) : nothing}` : nothing}

      <versola-activity-timeline
        .events=${report?.events ?? []}
        .state=${this.loadState}
        .errorMessage=${this.errorMessage}
        .zone=${this.zone}
        .zoneChoice=${this.zoneChoice}
        .profileZone=${this.profileZone}
        .filters=${view.filters}
        .limit=${view.limit}
        .cursors=${view.cursors}
        .hasNext=${Boolean(report?.nextCursor)}
        .selectedId=${selected?.id ?? ''}
        .updatedLabel=${this.updatedAt ? formatShortDateTime(this.updatedAt, this.zone).split(', ')[1] : ''}
        .canShowLonger=${view.range !== '7d'}
        .announcement=${this.announcement}
        @filters-change=${(event: CustomEvent<Filters>) => this.setFilters(event.detail)}
        @refresh=${() => this.refresh()}
        @zone-change=${(event: CustomEvent<{ choice: ZoneChoice }>) => this.setZone(event.detail.choice)}
        @page-older=${() => this.older()}
        @page-newer=${() => this.newer()}
        @limit-change=${(event: CustomEvent<{ limit: number }>) => this.reload({ limit: event.detail.limit as ActivityView['limit'], event: '' })}
        @open-event=${(event: CustomEvent<{ id: string }>) => this.openEvent(event.detail.id, 'timeline')}
        @show-week=${() => this.chooseRange('7d')}
      ></versola-activity-timeline>

      <p class="footnote">
        ${this.zoneNote} Hover a time to see both.
        Opening this report is recorded in the audit log.${showsCountry ? html` Countries are looked up when the report is opened. IP Geolocation by <a href="https://db-ip.com" target="_blank" rel="noopener noreferrer">DB-IP</a>.` : nothing}
      </p>

      ${selected ? html`<versola-activity-event-panel .event=${selected} .zone=${this.zone}
        @close=${() => this.closeEvent()} @show-request=${this.showRequest}></versola-activity-event-panel>` : nothing}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-user-activity': VersolaUserActivity;
  }
}
