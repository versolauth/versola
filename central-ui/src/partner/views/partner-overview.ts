import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, recordStyles, tagStyles } from '../../styles/components';
import { statusIcon } from '../../components/status-icon';
import { formatDate } from '../../utils/helpers';
import { relativeTime } from '../../utils/user-display';
import { ttlCapsLabel } from '../../utils/partners';
import { fetchAudit, fetchClients, fetchEdges, fetchStaff } from '../partner-api';
import type { AuditEventRecord, PartnerClient, PartnerEdge, PartnerMe, StaffMember } from '../partner-types';
import { partnerViewStyles } from '../partner-styles';
import type { PartnerNavItem } from '../partner-navigation';
import '../../components/content-header';
import '../../components/record-card';
import '../../components/loading-cards';

/**
 * Overview: the link to the operator, the edges and their certificates, what needs attention,
 * the limits the operator set, and the last things that happened. Each card loads on its own
 * and fails on its own.
 */
@customElement('versola-partner-overview')
export class VersolaPartnerOverview extends LitElement {
  @property({ attribute: false }) me!: PartnerMe;
  @state() private clients: PartnerClient[] | null = null;
  @state() private edges: PartnerEdge[] | null = null;
  @state() private staff: StaffMember[] | null = null;
  @state() private events: AuditEventRecord[] | null = null;
  @state() private failed = false;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    recordStyles,
    partnerViewStyles,
    css`
      .tiles { display: grid; grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr)); gap: var(--spacing-md); margin-bottom: var(--spacing-lg); }
      .tile {
        background: var(--surface);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-card);
        padding: var(--spacing-md) var(--spacing-lg);
        display: grid;
        gap: 0.375rem;
        align-content: start;
      }
      .tile .kicker { font-size: var(--text-xs); font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; color: var(--text-secondary); }
      .tile .big { font-size: var(--text-lg); font-weight: 600; display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
      .tile .sub { font-size: var(--text-sm); color: var(--text-secondary); }
      .tile ul { margin: 0; padding: 0; list-style: none; font-size: var(--text-sm); display: grid; gap: 0.375rem; text-align: left; }
      .tile ul .link-button { font-size: var(--text-sm); display: block; width: 100%; }
      .tile .edge-ids { gap: 0.25rem; flex-direction: column; align-items: flex-start; font-size: var(--text-base); font-weight: 400; }
      .timeline { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.5rem; font-size: var(--text-sm); }
      .timeline li { display: grid; grid-template-columns: 7.5rem 1fr; gap: 0.75rem; }
      .timeline .when { color: var(--text-secondary); white-space: nowrap; }
      .timeline b { font-weight: 600; }
      .grid-2 { margin-bottom: var(--spacing-md); }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.load();
  }

  private async load() {
    const [clients, edges, staff, audit] = await Promise.allSettled([fetchClients(), fetchEdges(), fetchStaff(), fetchAudit({})]);
    this.clients = clients.status === 'fulfilled' ? clients.value : null;
    this.edges = edges.status === 'fulfilled' ? edges.value : null;
    this.staff = staff.status === 'fulfilled' ? staff.value : null;
    this.events = audit.status === 'fulfilled' ? audit.value.events : null;
    this.failed = [clients, edges, staff, audit].every(result => result.status === 'rejected');
  }

  private go(view: PartnerNavItem, id?: string, create = false) {
    this.dispatchEvent(new CustomEvent('navigate-view', { detail: { view, id, create }, bubbles: true, composed: true }));
  }

  private get attention(): Array<{ text: string; view: PartnerNavItem; id?: string }> {
    const items: Array<{ text: string; view: PartnerNavItem; id?: string }> = [];
    for (const client of this.clients ?? []) {
      if (client.approval.status === 'pending') items.push({ text: `Client ${client.id} is waiting for the operator${client.approval.requestedAt ? ` since ${formatDate(client.approval.requestedAt)}` : ''}`, view: 'clients', id: client.id });
      if (client.approval.status === 'rejected') items.push({ text: `Client ${client.id} was rejected by the operator`, view: 'clients', id: client.id });
    }
    for (const edge of this.edges ?? []) {
      if (edge.status === 'suspended') items.push({ text: `Edge ${edge.id}: certificate renewal refused, the partner is suspended`, view: 'edges', id: edge.id });
      if (edge.status === 'waiting') items.push({ text: `Edge ${edge.id} has not enrolled yet${edge.pendingToken ? ` (token expires ${relativeTime(edge.pendingToken.expiresAt)})` : ''}`, view: 'edges', id: edge.id });
    }
    const soon = Date.now() + 3 * 86_400_000;
    for (const member of this.staff ?? []) {
      if (member.state === 'invited' && member.expiresAt && Date.parse(member.expiresAt) < soon) items.push({ text: `Invite for ${member.identifier?.masked ?? 'a member'} expires ${relativeTime(member.expiresAt)}`, view: 'team', id: member.id });
    }
    return items;
  }

  private renderTiles() {
    const me = this.me;
    const suspended = me.partner.status === 'suspended';
    const central = me.edge.central === 'ok' && !suspended;
    const edges = this.edges ?? [];
    const enrolled = edges.filter(edge => edge.status !== 'waiting');
    const nextRenewal = enrolled.map(edge => edge.certificateNotAfter).filter((value): value is string => !!value).sort()[0];
    const attention = this.attention;
    return html`
      <div class="tiles">
        <div class="tile">
          <span class="kicker">Link to the operator</span>
          <span class="big">${suspended
            ? html`<span class="chip danger">Refused: suspended</span>`
            : central
              ? html`<span class="chip ok">${statusIcon('check', 'icon', 14)}Connected</span>`
              : html`<span class="chip danger">Central unavailable</span>`}</span>
          <span class="sub">${suspended ? `${me.partner.operator.name} answers every call of your edges with 403, certificate renewal included, until it resumes you.` : central ? `central over mTLS · auth ${me.partner.operator.issuer}` : 'Your edges serve from their last snapshot; changes cannot be saved until central is back.'}</span>
        </div>
        <div class="tile">
          <span class="kicker">Your edges</span>
          <span class="big edge-ids">${this.edges === null ? html`<span class="muted">…</span>` : edges.length ? edges.map(edge => html`<span class="mono">${edge.id}</span>`) : html`<span class="muted">None enrolled</span>`}</span>
          <span class="sub">${suspended ? `renewals are refused while you are suspended${nextRenewal ? `; the first certificate expires on ${formatDate(nextRenewal)}` : ''}` : nextRenewal ? `next certificate renewal before ${formatDate(nextRenewal)}; they renew by themselves every 14 days` : 'certificates renew by themselves every 14 days once enrolled'}</span>
        </div>
        <div class="tile">
          <span class="kicker">Needs attention</span>
          ${attention.length ? html`<ul>${attention.map(item => html`<li><button type="button" class="link-button" @click=${() => this.go(item.view, item.id)}>${item.text}</button></li>`)}</ul>`
            : html`<span class="big">Nothing</span><span class="sub">no client waiting, no invite expiring, no renewal refused</span>`}
        </div>
      </div>
    `;
  }

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private approvalChip(client: PartnerClient) {
    if (client.approval.status === 'pending') return html`<span class="chip warn">Awaiting approval</span>`;
    if (client.approval.status === 'rejected') return html`<span class="chip danger">Rejected</span>`;
    return html`<span class="chip ok">${statusIcon('check', 'icon', 14)}Active</span>`;
  }

  render() {
    const me = this.me;
    const limits = me.partner.limits;
    const tags = (values: string[]) => html`<div class="tag-list">${values.map(value => html`<span class="tag">${value}</span>`)}</div>`;
    return html`
      <content-header title="Overview" description=${`${me.partner.displayName} on the Versola installation of ${me.partner.operator.name}. Your users are the operator's users; the operator sets how they sign in, you decide what your apps and APIs do with the session.`}>
        ${me.staff.level !== 'viewer' && me.partner.status !== 'suspended' ? html`<button slot="actions" class="btn btn-primary" @click=${() => this.go('clients', undefined, true)}>+ Create client</button>` : ''}
      </content-header>
      ${this.failed ? html`<div class="banner warn" role="alert"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}Could not read from the edge</div><div>The cards below are empty. Reload to try again.</div></div></div>` : ''}
      ${this.renderTiles()}
      <div class="grid-2">
        <versola-record-card heading="Clients" subheading="Your applications that sign the operator's users in." .body=${html`
          ${this.clients === null ? html`<versola-loading-cards .count=${1}></versola-loading-cards>` : this.clients.length ? html`<div class="record-list">${this.clients.map(client => html`
            <div class="list-row">
              <button type="button" class="link-button mono" @click=${() => this.go('clients', client.id)}>${client.id}</button>
              <span class="muted small">${client.clientName} · ${client.kind === 'native' ? 'Native' : 'Web'}</span>
              ${this.approvalChip(client)}
            </div>`)}</div>` : this.row('Clients', html`<span class="muted">None yet.</span>`)}
          <versola-record-row .value=${html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => this.go('clients')}>All clients</button>`}></versola-record-row>
        `}></versola-record-card>
        <versola-record-card heading="What the operator allows you" subheading=${`Set by ${me.partner.operator.name}. Ask them to change it; requests beyond these become approval requests.`} .body=${html`
          ${this.row('Scopes', tags(limits.scopes))}
          ${this.row('Redirect domains', tags(limits.redirectDomains))}
          ${this.row('Clients · edges', `up to ${limits.maxClients} · up to ${limits.maxEdges}`)}
          ${this.row('Token lifetimes', ttlCapsLabel(limits))}
          ${this.row('Consent', `Always shown, names ${me.partner.displayName}`, 'Operator')}
          ${me.partner.reviewAt ? this.row('Review date', formatDate(me.partner.reviewAt)) : ''}
        `}></versola-record-card>
      </div>
      <versola-record-card heading="Recent activity" subheading="Your team's actions and the operator's decisions about you. Everything here is also in the operator's audit log." .body=${html`
        <div class="record-list">${this.events === null ? html`<span class="muted">…</span>` : this.events.length ? html`<ul class="timeline">${this.events.slice(0, 6).map(event => html`
          <li><span class="when">${formatDate(event.at)}</span><span><b>${event.actor.display ?? event.actor.sub}</b> ${event.action} · <span class="mono">${event.target}</span></span></li>`)}</ul>` : html`<span class="muted">Nothing yet.</span>`}</div>
        <versola-record-row .value=${html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => this.go('audit')}>Open audit</button>`}></versola-record-row>
      `}></versola-record-card>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-overview': VersolaPartnerOverview;
  }
}
