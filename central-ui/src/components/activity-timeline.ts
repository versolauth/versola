import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, chipStyles, formStyles, iconButtonStyles, segmentedStyles } from '../styles/components';
import { dayKey, formatDate, formatTime, formatWithOffset, localZone, offsetLabel } from '../utils/date-format';
import { countryName } from '../utils/country-flag';
import {
  CATEGORY_LABELS,
  PAGE_SIZES,
  RESULT_LABELS,
  clientsOf,
  describeEvent,
  filterChips,
  filterEvents,
  hasActiveFilters,
  pageRangeLabel,
  type AuditEvent,
  type CursorStack,
  type Filters,
  type PageSize,
} from '../utils/user-audit';
import { resultBadgeStyles, resultBadge, countryLabel } from './activity-shared';
import { statusIcon } from './status-icon';
import type { ZoneChoice } from '../utils/zone-preference';
import './filter-bar';

export type TimelineState = 'loading' | 'ready' | 'error';

/**
 * The Timeline card of the Activity screen: the title with a refresh button and when the page was read, the Local / UTC
 * switch, the filter bar (search, Result, Client, category pills) with its chips, the table grouped by day, and the pager
 * (page size, Newer, Older). It draws the page of events it is given and fires what the operator asks for; the screen
 * loads. Filtering is of the loaded page only: the report takes no filter parameters.
 */
@customElement('versola-activity-timeline')
export class VersolaActivityTimeline extends LitElement {
  @property({ attribute: false }) events: AuditEvent[] = [];
  @property({ type: String }) state: TimelineState = 'loading';
  @property({ type: String }) errorMessage = '';
  @property({ type: String }) zone = 'UTC';
  @property({ type: String }) zoneChoice: ZoneChoice = 'local';
  /** The zone of the administrator's profile; offered as its own button when it is not the browser's. */
  @property({ attribute: false }) profileZone: string | null = null;
  @property({ attribute: false }) filters!: Filters;
  @property({ type: Number }) limit: PageSize = 100;
  @property({ attribute: false }) cursors: CursorStack = [];
  @property({ type: Boolean }) hasNext = false;
  @property({ type: String }) selectedId = '';
  @property({ type: String }) updatedLabel = '';
  /** Whether a longer period than the one on screen exists to offer when it is empty. */
  @property({ type: Boolean }) canShowLonger = false;
  /** What the page change says to a screen reader. */
  @property({ type: String }) announcement = '';

  @query('.timeline-title') private titleEl?: HTMLElement;
  @query('.table-wrap') private tableWrap?: HTMLElement;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    formStyles,
    iconButtonStyles,
    segmentedStyles,
    resultBadgeStyles,
    css`
      :host { display: block; }

      .card { padding: 0; overflow: hidden; }

      .head {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
        padding: var(--spacing-lg) var(--spacing-lg) var(--spacing-md);
      }

      .head-main { display: flex; flex-wrap: wrap; align-items: center; column-gap: var(--spacing-sm); row-gap: 0.25rem; }
      .timeline-title { margin: 0; font-size: var(--text-2xl); font-weight: 700; color: var(--text-primary); }
      .timeline-title:focus { outline: none; }
      .subtitle { flex-basis: 100%; margin: 0; font-size: var(--text-sm); color: var(--text-secondary); }
      .updated { font-size: var(--text-sm); color: var(--text-secondary); }

      .icon-button.refresh {
        min-width: 2rem;
        min-height: 2rem;
        border: 1.5px solid var(--border-input);
        border-radius: var(--radius-pill);
        color: var(--text-primary);
      }

      .icon-button.refresh:hover { border-color: var(--text-primary); color: var(--text-primary); }
      .icon-button.refresh:disabled { color: var(--text-muted); border-color: var(--border-default); cursor: default; }

      .filters {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-sm) var(--spacing-md);
        padding: var(--spacing-md) var(--spacing-lg);
        border-top: 1px solid var(--border-default);
      }

      .filter-fields { display: flex; flex: 1 1 auto; flex-wrap: wrap; align-items: center; gap: var(--spacing-sm); min-width: 0; }

      .search { position: relative; flex: 0 1 19rem; min-width: 12rem; }
      .search .status-icon { position: absolute; left: 0.875rem; top: 50%; transform: translateY(-50%); color: var(--text-secondary); pointer-events: none; }
      .search input[type='search'] { padding: 0.5rem 1rem 0.5rem 2.5rem; font-size: var(--text-sm); }

      .select { flex: 0 1 auto; }
      .select select {
        width: auto;
        min-width: 0;
        padding: 0.5rem 2.25rem 0.5rem 0.875rem;
        background-position: calc(100% - 0.875rem) 50%;
        border-radius: var(--radius-pill);
        font-size: var(--text-sm);
      }

      .active-filters {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--spacing-sm);
        padding: 0 var(--spacing-lg) var(--spacing-md);
      }

      .chip.removable { background: var(--accent-subtle); color: var(--accent-text); border-color: var(--accent-border); padding-right: 0.25rem; }
      .chip.removable button {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 1.5rem;
        min-height: 1.5rem;
        padding: 0;
        border: 0;
        border-radius: var(--radius-pill);
        background: none;
        color: inherit;
        cursor: pointer;
      }
      .chip.removable button:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 1px; }

      .link-button {
        min-height: 1.5rem;
        padding: 0.125rem 0.25rem;
        border: 0;
        background: none;
        font: inherit;
        font-size: var(--text-sm);
        color: var(--accent);
        text-decoration: underline;
        cursor: pointer;
        border-radius: var(--radius-sm);
      }
      .link-button:hover { color: var(--accent-hover); }
      .link-button:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }

      .table-wrap { overflow-x: auto; border-top: 1px solid var(--border-default); }
      /* Below its natural width the table scrolls inside its card instead of squeezing the columns. */
      table { width: 100%; min-width: 52rem; border-collapse: collapse; font-size: var(--text-base); }
      th[scope='col'] {
        padding: 0.875rem 1rem;
        text-align: left;
        font-size: var(--text-xs);
        font-weight: 700;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        color: var(--text-secondary);
        white-space: nowrap;
      }
      thead th:first-child, td:first-child { padding-left: var(--spacing-lg); }
      thead th:last-child, td:last-child { padding-right: var(--spacing-lg); }

      th.day {
        padding: 0.5rem var(--spacing-lg);
        text-align: left;
        font-size: var(--text-xs);
        font-weight: 700;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        color: var(--text-secondary);
        background: var(--surface-inset);
        border-top: 1px solid var(--border-default);
      }

      td { padding: 0.75rem 1rem; border-top: 1px solid var(--border-subtle); vertical-align: top; color: var(--text-primary); }
      th.col-event, td.col-event { min-width: 14rem; }
      td.time { white-space: nowrap; font-variant-numeric: tabular-nums; }
      td.address { white-space: nowrap; }
      td.address .country { margin-left: 0.25rem; color: var(--text-secondary); font-size: var(--text-sm); }
      tr.event-row { cursor: pointer; transition: background-color var(--transition-fast); }
      tr.event-row:hover td { background: var(--surface-inset); }
      tr.event-row[aria-selected='true'] td { background: var(--accent-subtle); }
      tr.event-row[aria-selected='true'] td:first-child { box-shadow: inset 3px 0 0 var(--accent); }

      .row-link {
        display: block;
        padding: 0;
        border: 0;
        background: none;
        font: inherit;
        text-align: left;
        color: inherit;
        cursor: pointer;
        border-radius: var(--radius-sm);
      }
      .row-link:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
      .row-title { display: block; font-weight: 700; color: var(--text-primary); }
      .row-sub { display: block; font-size: var(--text-sm); color: var(--text-secondary); overflow-wrap: break-word; }

      .skeleton-row td { padding: 1rem; }
      .bar {
        display: block;
        height: 0.75rem;
        border-radius: 999px;
        background: linear-gradient(90deg, var(--surface-inset), var(--accent-subtle), var(--surface-inset));
        background-size: 200% 100%;
        animation: shimmer 1.4s infinite;
      }
      .bar.short { width: 5rem; }
      .bar.medium { width: 8rem; }
      .bar.thin { height: 0.5rem; width: 5rem; margin-top: 0.5rem; }
      @keyframes shimmer { from { background-position: 200% 0; } to { background-position: -200% 0; } }

      .state { padding: 3rem var(--spacing-lg); text-align: center; color: var(--text-secondary); border-top: 1px solid var(--border-default); }
      .state h3 { margin: 0; font-size: var(--text-xl); font-weight: 600; color: var(--text-primary); }
      .state p { margin: 0.5rem auto 0; max-width: 34rem; font-size: var(--text-base); }
      .state .btn { margin-top: 1rem; }

      .pager {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-sm) var(--spacing-md);
        padding: var(--spacing-md) var(--spacing-lg);
        border-top: 1px solid var(--border-default);
      }
      .pager-info { display: flex; flex-wrap: wrap; align-items: center; gap: var(--spacing-sm); font-size: var(--text-sm); color: var(--text-secondary); }
      .pager-info select { width: auto; padding-top: 0.25rem; padding-bottom: 0.25rem; border-radius: var(--radius-pill); font-size: var(--text-sm); }
      .pager-buttons { display: flex; align-items: center; gap: var(--spacing-sm); }
      .history-start { font-size: var(--text-sm); color: var(--text-secondary); }

      .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }

      @media (max-width: 720px) {
        .search { max-width: none; }
      }
    `,
  ];

  /** Moves focus back to the button of an event's row, after its panel closed. */
  focusRow(id: string) {
    void this.updateComplete.then(() => {
      for (const row of this.renderRoot.querySelectorAll<HTMLElement>('tr.event-row')) {
        if (row.dataset.id === id) row.querySelector<HTMLElement>('.row-link')?.focus();
      }
    });
  }

  focusTitle() {
    void this.updateComplete.then(() => this.titleEl?.focus());
  }

  /** The top of the table in view, as it should be after a page change. */
  scrollToTop() {
    this.scrollIntoView({ block: 'start', behavior: 'instant' as ScrollBehavior });
    if (this.tableWrap) this.tableWrap.scrollLeft = 0;
  }

  private fire<T>(name: string, detail?: T) {
    this.dispatchEvent(new CustomEvent<T>(name, { detail, bubbles: true, composed: true }));
  }

  private setFilter(change: Partial<Filters>) {
    this.fire('filters-change', { ...this.filters, ...change });
  }

  private renderFilters(clients: string[]) {
    const { filters } = this;
    const chips = filterChips(filters);
    return html`
      <div class="filters">
        <div class="filter-fields">
          <div class="search">
            ${statusIcon('search')}
            <input type="search" aria-label="Search events, clients and addresses" placeholder="Search events, clients or addresses"
              .value=${filters.q} @input=${(event: Event) => this.setFilter({ q: (event.target as HTMLInputElement).value })} />
          </div>
          <div class="select">
            <select aria-label="Result" .value=${filters.result}
              @change=${(event: Event) => this.setFilter({ result: (event.target as HTMLSelectElement).value as Filters['result'] })}>
              <option value="">Result: All results</option>
              ${(Object.keys(RESULT_LABELS) as (keyof typeof RESULT_LABELS)[]).map(key => html`<option value=${key} ?selected=${filters.result === key}>Result: ${RESULT_LABELS[key]}</option>`)}
            </select>
          </div>
          <div class="select">
            <select aria-label="Client" .value=${filters.client}
              @change=${(event: Event) => this.setFilter({ client: (event.target as HTMLSelectElement).value })}>
              <option value="">Client: All clients</option>
              ${clients.map(client => html`<option value=${client} ?selected=${filters.client === client}>Client: ${client}</option>`)}
            </select>
          </div>
        </div>
        <versola-filter-bar label="Category" .value=${filters.category || 'all'}
          .options=${[{ id: 'all', label: 'All' }, ...Object.entries(CATEGORY_LABELS).map(([id, label]) => ({ id, label }))]}
          @change=${(event: CustomEvent<{ id: string }>) => this.setFilter({ category: event.detail.id === 'all' ? '' : (event.detail.id as Filters['category']) })}></versola-filter-bar>
      </div>
      ${hasActiveFilters(filters) ? html`
        <div class="active-filters" role="group" aria-label="Active filters">
          ${chips.map(chip => html`<span class="chip removable">${chip.label}
            <button type="button" aria-label=${`Remove filter ${chip.label}`} title="Remove filter" @click=${() => this.setFilter({ [chip.key]: '' })}>${statusIcon('x', '', 14)}</button></span>`)}
          <button type="button" class="link-button" @click=${() => this.fire('filters-change', { q: '', result: '', client: '', category: '', trace: '' })}>Clear all</button>
        </div>` : nothing}
    `;
  }

  private timeTitle(at: string): string {
    const local = formatWithOffset(at, this.zone);
    return this.zone === 'UTC' ? local : `${local}\n${formatWithOffset(at, 'UTC')}`;
  }

  private openEvent(event: AuditEvent) {
    this.fire('open-event', { id: event.id });
  }

  private renderRow(event: AuditEvent) {
    const { title, subtitle } = describeEvent(event);
    const selected = event.id === this.selectedId;
    return html`
      <tr class="event-row" data-id=${event.id} aria-selected=${selected ? 'true' : 'false'} @click=${() => this.openEvent(event)}>
        <td class="time"><time datetime=${event.at} title=${this.timeTitle(event.at)}>${formatTime(event.at, this.zone)}</time></td>
        <td class="col-event">
          <button type="button" class="row-link" aria-haspopup="dialog" @click=${(click: Event) => { click.stopPropagation(); this.openEvent(event); }}>
            <span class="row-title">${title}</span>
            ${subtitle ? html`<span class="row-sub">${subtitle}</span>` : nothing}
          </button>
        </td>
        <td>${resultBadge(event)}</td>
        <td>${event.clientId ?? html`<span class="muted">—</span>`}</td>
        <td class="address">${event.ip ? html`<span class="mono">${event.ip}</span>${event.country ? html` ${countryLabel(event.country, 'short')}` : nothing}` : html`<span class="muted">—</span>`}</td>
      </tr>`;
  }

  private renderSkeleton() {
    return html`
      <tbody>
        ${Array.from({ length: 8 }, () => html`
          <tr class="skeleton-row" aria-hidden="true">
            <td><span class="bar short"></span></td>
            <td><span class="bar medium"></span><span class="bar thin"></span></td>
            <td><span class="bar short"></span></td>
            <td><span class="bar short"></span></td>
            <td><span class="bar medium"></span></td>
          </tr>`)}
      </tbody>`;
  }

  private renderRows(visible: AuditEvent[]) {
    const days: { key: string; at: string; events: AuditEvent[] }[] = [];
    for (const event of visible) {
      const key = dayKey(event.at, this.zone);
      const last = days[days.length - 1];
      if (last?.key === key) last.events.push(event);
      else days.push({ key, at: event.at, events: [event] });
    }
    return days.map(day => html`
      <tbody>
        <tr><th class="day" scope="rowgroup" colspan="5">${formatDate(day.at, this.zone)}</th></tr>
        ${day.events.map(event => this.renderRow(event))}
      </tbody>`);
  }

  private renderEmpty(filtered: boolean) {
    if (filtered) {
      return html`<div class="state"><h3>No events match these filters</h3><p>Filters apply to the events of this page only.</p>
        <button type="button" class="btn btn-secondary" @click=${() => this.fire('filters-change', { q: '', result: '', client: '', category: '', trace: '' })}>Clear all</button></div>`;
    }
    return html`<div class="state"><h3>No activity in this period</h3>
      <p>Events appear here when this user signs in or an administrator changes their account.${this.canShowLonger ? ' Try a longer period.' : ''}</p>
      ${this.canShowLonger ? html`<button type="button" class="btn btn-secondary" @click=${() => this.fire('show-week')}>Show 7 days</button>` : nothing}</div>`;
  }

  private renderPager(visibleCount: number) {
    const loaded = this.events.length;
    const filtered = hasActiveFilters(this.filters);
    const busy = this.state === 'loading';
    const label = busy && loaded === 0 ? 'Loading events' : pageRangeLabel(this.cursors, this.limit, loaded);
    return html`
      <div class="pager">
        <div class="pager-info">
          <span>${label}${filtered && loaded > 0 ? ` · ${visibleCount} shown by filters` : ''}</span>
          <span aria-hidden="true">·</span>
          <select aria-label="Events per page" .value=${String(this.limit)}
            @change=${(event: Event) => this.fire('limit-change', { limit: Number((event.target as HTMLSelectElement).value) })}>
            ${PAGE_SIZES.map(size => html`<option value=${String(size)} ?selected=${size === this.limit}>${size} per page</option>`)}
          </select>
          ${!this.hasNext && loaded > 0 && !busy ? html`<span class="history-start">Beginning of history</span>` : nothing}
        </div>
        <div class="pager-buttons">
          <button type="button" class="btn btn-secondary" ?disabled=${busy || this.cursors.length === 0} @click=${() => this.fire('page-newer')}>Newer</button>
          <button type="button" class="btn btn-secondary" ?disabled=${busy || !this.hasNext} @click=${() => this.fire('page-older')}>Older</button>
        </div>
      </div>`;
  }

  render() {
    const clients = clientsOf(this.events, this.filters.client);
    const visible = filterEvents(this.events, this.filters, code => countryName(code) ?? code);
    const filtered = hasActiveFilters(this.filters);
    const loading = this.state === 'loading';
    const zoneHeader = this.zoneChoice === 'utc' || this.zone === 'UTC' ? 'UTC' : `${this.zone}, ${offsetLabel(this.events[0]?.at ?? Date.now(), this.zone)}`;
    return html`
      <section class="card" aria-labelledby="timeline-title" aria-busy=${loading ? 'true' : 'false'}>
        <div class="head">
          <div class="head-main">
            <h2 class="timeline-title" id="timeline-title" tabindex="-1">Timeline</h2>
            <button type="button" class="icon-button refresh" aria-label="Refresh timeline" title="Refresh: reload the first page" ?disabled=${loading}
              @click=${() => this.fire('refresh')}>${statusIcon('refresh', '', 18)}</button>
            <span class="updated">${loading ? 'Updating…' : this.updatedLabel ? `Updated ${this.updatedLabel}` : ''}</span>
            <p class="subtitle">Newest first</p>
          </div>
          <div class="ctrl-group" role="group" aria-label="Time zone">
            <span class="ctrl-label" aria-hidden="true">Time zone</span>
            <div class="seg-control">
              ${this.profileZone && this.profileZone !== localZone() ? html`
                <button type="button" class="seg-btn ${this.zoneChoice === 'zone' ? 'seg-active' : ''}" aria-pressed=${this.zoneChoice === 'zone'} title=${this.profileZone}
                  @click=${() => this.fire('zone-change', { choice: 'zone' })}>My zone</button>` : nothing}
              <button type="button" class="seg-btn ${this.zoneChoice === 'local' ? 'seg-active' : ''}" aria-pressed=${this.zoneChoice === 'local'} title=${localZone()}
                @click=${() => this.fire('zone-change', { choice: 'local' })}>Local</button>
              <button type="button" class="seg-btn ${this.zoneChoice === 'utc' ? 'seg-active' : ''}" aria-pressed=${this.zoneChoice === 'utc'}
                @click=${() => this.fire('zone-change', { choice: 'utc' })}>UTC</button>
            </div>
          </div>
        </div>
        ${this.renderFilters(clients)}
        ${this.state === 'error' ? html`
          <div class="state" role="alert"><h3>Could not load activity</h3><p>${this.errorMessage}</p>
            <button type="button" class="btn btn-secondary" @click=${() => this.fire('refresh')}>Retry</button></div>`
        : !loading && visible.length === 0 ? this.renderEmpty(filtered && this.events.length > 0)
        : html`
          <div class="table-wrap">
            <table>
              <caption class="sr-only">Events, newest first</caption>
              <thead>
                <tr>
                  <th scope="col">Time (${zoneHeader})</th>
                  <th scope="col" class="col-event">Event</th>
                  <th scope="col">Result</th>
                  <th scope="col">Client</th>
                  <th scope="col">IP address</th>
                </tr>
              </thead>
              ${loading ? this.renderSkeleton() : this.renderRows(visible)}
            </table>
          </div>`}
        ${this.renderPager(visible.length)}
        <div class="sr-only" role="status" aria-live="polite">${this.announcement}</div>
      </section>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-activity-timeline': VersolaActivityTimeline;
  }
}
