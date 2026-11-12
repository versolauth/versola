import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../../styles/components';
import { statusIcon } from '../../components/status-icon';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from '../../components/data-table';
import { confirmDestructiveAction } from '../../utils/confirm-dialog';
import { confirmDiscardDraft } from '../../utils/draft-guard';
import { formatDate } from '../../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../../utils/record-route';
import { fetchStaff, inviteStaff, removeStaff, updateStaffLevel, writeErrorMessage } from '../partner-api';
import type { PartnerMe, StaffLevel, StaffMember } from '../partner-types';
import { partnerViewStyles } from '../partner-styles';
import '../../components/content-header';
import '../../components/danger-card';
import '../../components/data-table';
import '../../components/empty-state';
import '../../components/error-card';
import '../../components/filter-bar';
import '../../components/loading-cards';
import '../../components/record-card';
import '../../components/side-panel';

type Filter = 'all' | 'admin' | 'invited';

const LEVELS: Array<{ id: StaffLevel; text: string }> = [
  { id: 'admin', text: 'everything, including Team and Settings' },
  { id: 'developer', text: 'clients, resources, edges; no Team, no Settings, no revocation' },
  { id: 'viewer', text: 'read everything, change nothing' },
];

/**
 * Team: who at the partner may use this console, kept on the partner's edge only (#549 decision
 * 3). Invite by phone or email: nothing is looked up at the operator, the invite applies at the
 * first sign-in with a matching identifier. Change a level, remove a member; the signed-in admin
 * cannot remove themself.
 */
@customElement('versola-partner-team')
export class VersolaPartnerTeam extends LitElement {
  @property({ attribute: false }) me!: PartnerMe;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private staff: StaffMember[] = [];
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private searchQuery = '';
  @state() private filter: Filter = 'all';
  @state() private inviteIdentifier = '';
  @state() private inviteLevel: StaffLevel = 'developer';
  @state() private inviteDays = '7';
  @state() private levelDraft: StaffLevel = 'viewer';
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @query('versola-data-table') private table?: VersolaDataTable<StaffMember>;
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
      .radio-row .mono { font-size: var(--text-base); }
      .levels { margin-top: var(--spacing-lg); max-width: 46rem; }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('route') || changed.has('staff')) {
      const key = this.route.create ? 'create' : this.route.edit === 'level' ? `level:${this.route.id}` : '';
      if (key !== this.draftKey) {
        this.draftKey = key;
        this.inviteIdentifier = '';
        this.inviteLevel = 'developer';
        this.inviteDays = '7';
        this.levelDraft = this.openMember?.level ?? 'viewer';
        this.formError = '';
        this.submitted = false;
        this.isSaving = false;
      }
    }
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      this.staff = await fetchStaff();
    } catch (error) {
      this.staff = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load the team';
    } finally {
      this.isLoading = false;
    }
  }

  private get openMember(): StaffMember | null {
    return this.route.id ? this.staff.find(member => member.id === this.route.id) ?? null : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.openMember && this.route.edit === 'level' ? 'edit' : 'view';
  }

  private isSelf(member: StaffMember): boolean {
    return member.subject === this.me.staff.sub;
  }

  private openView(member: StaffMember) {
    dispatchRecordRoute(this, { id: member.id, edit: null, create: false });
  }

  private closePanel() {
    const key = this.route.id;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private async cancelEditor() {
    if (this.route.create && this.inviteIdentifier && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  private get identifierError(): string {
    const value = this.inviteIdentifier.trim();
    if (!value) return this.submitted ? 'A phone number or an email is required.' : '';
    if (value.includes('@')) return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value) ? '' : 'An email address, or a phone number in international format.';
    return /^\+?[0-9 ()-]{7,20}$/.test(value) ? '' : 'A phone number in international format (+7 …), or an email address.';
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.submitted = true;
    const mode = this.editorMode;
    if (mode === 'create' && this.identifierError) return;
    this.isSaving = true;
    this.formError = '';
    try {
      if (mode === 'create') {
        const member = await inviteStaff({ identifier: this.inviteIdentifier.trim(), level: this.inviteLevel, expiresInDays: Number(this.inviteDays) });
        this.staff = [...this.staff, member];
        dispatchRecordRoute(this, { id: member.id, edit: null, create: false });
      } else {
        const member = this.openMember!;
        const saved = await updateStaffLevel(member.id, this.levelDraft);
        this.staff = this.staff.map(candidate => candidate.id === member.id ? saved : candidate);
        dispatchRecordRoute(this, { edit: null, create: false });
      }
    } catch (error) {
      this.formError = writeErrorMessage(error);
    } finally {
      this.isSaving = false;
    }
  }

  private async handleRemove(member: StaffMember) {
    const invited = member.state === 'invited';
    const confirmed = await confirmDestructiveAction({
      title: invited ? 'Cancel invitation' : 'Remove member',
      messagePrefix: invited ? 'Cancel the invitation for ' : 'Remove ',
      messageSubject: member.display ?? member.identifier?.masked ?? member.id,
      messageSuffix: invited ? '? They will not become a member at sign-in.' : ' from the team? Their session on this console ends; their account with the operator is untouched.',
      confirmLabel: invited ? 'Cancel invitation' : 'Remove',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      await removeStaff(member.id);
      this.staff = this.staff.filter(candidate => candidate.id !== member.id);
      if (this.route.id === member.id) this.closePanel();
    } catch (error) {
      this.errorMessage = writeErrorMessage(error);
    }
  }

  // ── list ──

  private get filtered(): StaffMember[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.staff.filter(member => (this.filter === 'all' || (this.filter === 'admin' ? member.level === 'admin' : member.state === 'invited'))
      && (!query || `${member.display ?? ''} ${member.identifier?.masked ?? ''} ${member.subject ?? ''}`.toLowerCase().includes(query)));
  }

  private memberTitle(member: StaffMember): string {
    return member.display ?? member.identifier?.masked ?? member.id;
  }

  private subline(member: StaffMember): string {
    return member.display ? member.identifier?.masked ?? member.subject ?? '' : member.state === 'invited' ? 'invited' : member.subject ?? '';
  }

  private levelChip(level: StaffLevel) {
    return html`<span class="chip">${level}</span>`;
  }

  private renderTable() {
    const columns: DataTableColumn<StaffMember>[] = [
      { id: 'member', label: 'Member', cell: member => html`
        <button type="button" class="row-link" aria-label=${`Open member ${this.memberTitle(member)} ${this.subline(member)}`.trim()}>
          <span class="row-title">${this.memberTitle(member)}</span>
          <span class="row-id">${this.subline(member)}</span>
        </button>` },
      { id: 'level', label: 'Level', cell: member => html`<div class="chips">${this.levelChip(member.level)}</div>` },
      { id: 'state', label: 'State', cell: member => member.state === 'invited'
        ? html`<div class="cell-lines"><span class="chip warn">Invited</span><span class="muted">${member.expiresAt ? `expires ${formatDate(member.expiresAt)}` : ''}</span></div>`
        : html`<div class="cell-lines"><span class="chip ok">${statusIcon('check', 'icon', 14)}Member</span><span class="muted">since ${formatDate(member.addedAt)}</span></div>` },
      { id: 'added', label: 'Added', class: 'muted', cell: member => `${formatDate(member.addedAt)} · by ${member.addedBy}` },
    ];
    const menuItems = (member: StaffMember): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(member) },
      ...(this.canManage ? [{ label: member.state === 'invited' ? 'Cancel invitation' : 'Remove member', danger: true, ariaLabel: `${member.state === 'invited' ? 'Cancel invitation' : 'Remove member'} ${this.memberTitle(member)}`, disabledReason: this.isSelf(member) ? 'You cannot remove yourself' : undefined, onSelect: () => void this.handleRemove(member) }] : []),
    ];
    return html`
      <versola-data-table selectable .columns=${columns} .rows=${this.filtered} .rowKey=${(member: StaffMember) => member.id}
        .selectedKey=${this.editorMode === 'view' ? this.route.id : null} .menuItems=${menuItems} .menuLabel=${(member: StaffMember) => `Actions for ${this.memberTitle(member)}`}
        @row-select=${(e: CustomEvent<{ row: StaffMember }>) => this.openView(e.detail.row)}></versola-data-table>`;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private renderGroups(member: StaffMember) {
    const self = this.isSelf(member);
    const invited = member.state === 'invited';
    return html`
      <versola-record-card heading="Member" .body=${html`
        ${this.row('Display', member.display ?? html`<span class="muted">Unknown until the first sign-in</span>`)}
        ${member.identifier ? this.row(member.identifier.kind === 'email' ? 'Email' : 'Phone', member.identifier.masked, invited ? 'Stored hashed until the first sign-in' : '') : ''}
        ${member.subject ? this.row('Subject', html`<span class="mono">${member.subject}</span>`, 'From the operator') : ''}
        ${this.row('State', invited ? html`Invited · applies at the first sign-in${member.expiresAt ? `, expires ${formatDate(member.expiresAt)}` : ''}` : html`Member`)}
        ${this.row('Added', `${formatDate(member.addedAt)} by ${member.addedBy}`)}
      `}></versola-record-card>
      <versola-record-card heading="Level" ?editable=${this.canManage && !self} .body=${html`
        ${this.row('Level', html`${this.levelChip(member.level)} <span class="muted small">${LEVELS.find(level => level.id === member.level)?.text}</span>`, self ? 'Your own level' : '')}
      `} @edit=${() => dispatchRecordRoute(this, { edit: 'level', create: false })}></versola-record-card>
      ${this.canManage ? html`
        <versola-danger-card heading=${invited ? 'Cancel invitation' : 'Remove from the team'} text=${invited ? 'They will not become a member at sign-in.' : 'Their session on this console ends. Their account with the operator is untouched.'}
          actionLabel=${invited ? 'Cancel invitation' : 'Remove member'} .actionAriaLabel=${`${invited ? 'Cancel invitation' : 'Remove member'} ${this.memberTitle(member)}`}
          .disabledReason=${self ? 'You cannot remove yourself: ask another admin.' : ''} @action=${() => this.handleRemove(member)}></versola-danger-card>` : ''}
    `;
  }

  private renderEditor(member: StaffMember | null) {
    const create = this.editorMode === 'create';
    const error = this.submitted ? this.identifierError : '';
    return html`
      <form id="team-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${create ? html`
          <div class="banner info" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('info')}Nothing is sent and nothing is looked up</div><div>The person must already have an account with ${this.me.partner.operator.name}, and that phone number or email must be verified there (a sign-in by a code sent to it). The first time they sign in to this console with it, they become a member.</div></div></div>
          <div class="editor-box">
            <h3>Invite a member</h3>
            <div class="form-group">
              <label for="invite-identifier">Phone or email *</label>
              <input type="text" id="invite-identifier" class="compact-input ${error ? 'input-error' : ''}" .value=${this.inviteIdentifier} placeholder="+7 701 … or name@pilotpay.example"
                aria-invalid=${error ? 'true' : nothing} aria-describedby="invite-identifier-hint" @input=${(e: Event) => this.inviteIdentifier = (e.target as HTMLInputElement).value} />
              ${error ? html`<div class="error-message" id="invite-identifier-hint" role="alert">${error}</div>` : html`<div class="hint" id="invite-identifier-hint">Stored hashed on your edge until the first sign-in.</div>`}
            </div>
            <div class="form-group">
              <label for="invite-days">Expires</label>
              <select id="invite-days" class="compact-input" .value=${this.inviteDays} @change=${(e: Event) => this.inviteDays = (e.target as HTMLSelectElement).value}>
                <option value="7">in 7 days</option>
                <option value="30">in 30 days</option>
              </select>
            </div>
          </div>` : ''}
        <div class="editor-box">
          <h3>Level</h3>
          <p class="editor-sub">Three fixed levels; the operator's permissions are not involved.</p>
          <div class="checkbox-list" role="radiogroup" aria-label="Level">
            ${LEVELS.map(level => html`
              <label class="checkbox-row radio-row">
                <input type="radio" name="level" value=${level.id} .checked=${(create ? this.inviteLevel : this.levelDraft) === level.id}
                  @change=${() => { if (create) this.inviteLevel = level.id; else this.levelDraft = level.id; }} />
                <span><span class="mono">${level.id}</span> <span class="muted small">· ${level.text}</span></span>
              </label>`)}
          </div>
          ${member && this.levelDraft === 'admin' && member.level !== 'admin' ? html`<div class="hint">An admin can invite and remove members, change settings and end sessions.</div>` : ''}
        </div>
      </form>`;
  }

  private renderPanel(member: StaffMember | null) {
    const mode = this.editorMode;
    if (mode === 'view' && !member) return '';
    const title = member ? this.memberTitle(member) : '';
    return html`
      <versola-side-panel kind="Team" .heading=${mode === 'create' ? 'Invite a member' : mode === 'edit' ? 'Edit level' : title} .recordId=${member?.subject ?? ''} idLabel="subject" .mode=${mode}
        .parentLabel=${title} .showOpenPage=${false} .meta=${member && mode === 'view' ? html`${this.levelChip(member.level)}${member.state === 'invited' ? html`<span class="chip warn">Invited</span>` : ''}` : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()} @back=${() => this.cancelEditor()}>
        <div class="record-compact">${mode === 'view' ? this.renderGroups(member!) : this.renderEditor(member)}</div>
        ${mode !== 'view' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="team-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : mode === 'create' ? 'Create invitation' : 'Save level'}</button>
          </div>` : ''}
      </versola-side-panel>`;
  }

  render() {
    const me = this.me;
    const options: Array<{ id: Filter; label: string }> = [{ id: 'all', label: 'All' }, { id: 'admin', label: 'Admins' }, { id: 'invited', label: 'Invited' }];
    return html`
      <content-header title="Team" description=${`Who at ${me.partner.displayName} may use this console. Kept on your edge only: the operator never sees this list, only who acted in its audit log. Everyone signs in with their ${me.partner.operator.name} account.`}>
        ${this.canManage ? html`<button slot="actions" class="btn btn-primary" @click=${() => dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null })}>+ Invite</button>` : ''}
      </content-header>
      ${this.errorMessage && this.staff.length > 0 ? html`<div class="banner warn" role="alert"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}${this.errorMessage}</div></div></div>` : ''}
      ${this.isLoading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
        : this.errorMessage && this.staff.length === 0 ? html`<versola-error-card heading="Could not load the team" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>`
          : html`
            <div class="list-toolbar">
              <input class="search-input" type="search" aria-label="Search team" placeholder="Search by name, phone or email" .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
              <versola-filter-bar label="Filter team" .options=${options} .value=${this.filter} @change=${(e: CustomEvent<{ id: string }>) => this.filter = e.detail.id as Filter}></versola-filter-bar>
            </div>
            ${this.filtered.length === 0 ? html`<versola-empty-state heading="No members match"></versola-empty-state>` : this.renderTable()}
            <div class="levels">
              <versola-record-card heading="Levels" subheading="Three fixed levels; the operator's permissions are not involved." .body=${html`
                ${LEVELS.map(level => this.row(level.id, level.text))}
              `}></versola-record-card>
            </div>`}
      ${this.renderPanel(this.openMember)}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-team': VersolaPartnerTeam;
  }
}
