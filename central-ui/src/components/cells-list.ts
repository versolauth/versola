import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../styles/components';
import type { Cell, CellState, CellStatus, Edge, Partition, Region } from '../types';
import { createCell, createPartition, createRegion, deleteCell, deleteRegion, fetchEdges, fetchTopology, setEdgeRegion, updateCell } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { formatDateTime, localZone } from '../utils/date-format';
import { copyToClipboard } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { statusIcon } from './status-icon';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from './data-table';
import './content-header';
import './danger-card';
import './data-table';
import './empty-state';
import './error-card';
import './filter-bar';
import './loading-cards';
import './record-card';
import './record-header';
import './side-panel';
import { ID_RULE, PUBLIC_KEYS_HINT, addressError, certificatesOf, parsePublicKeys, publicKeysText, validId } from '../utils/topology-input';
import type { ApiError } from '../utils/central-api';
import './user-moves-list';

type CellFilter = 'all' | CellState;
type CellGroup = 'placement' | 'addresses' | 'keys';

/** What each state means for new users; a cell in any state still serves the users it holds. */
const STATES: Array<{ id: CellState; label: string; text: string }> = [
  { id: 'open', label: 'Open', text: 'New users are placed here, by the weight among the open cells of the region.' },
  { id: 'closed', label: 'Closed', text: 'No new users. The users the cell holds are served as before.' },
  { id: 'draining', label: 'Draining', text: 'No new users, and the users it holds are being moved to other cells.' },
];

const NEW_PARTITION = '__new__';

type DeleteRefusal = { kind: 'users' | 'move' | 'tokens' | 'other'; message: string; tokens: number | null };

/** Which refusal of `DELETE /configuration/cells` this is, from the text central answers with (all of them are `409`). */
function deleteRefusal(error: unknown): DeleteRefusal {
  const text = (error as ApiError | undefined)?.bodyText ?? (error instanceof Error ? error.message : '');
  const status = (error as ApiError | undefined)?.status;
  const fallback = error instanceof Error ? error.message : 'Failed to delete the cell';
  if (status !== 409) return { kind: 'other', message: fallback, tokens: null };
  const held = /still holds (\d+) user/.exec(text);
  if (held) {
    return { kind: 'users', tokens: null, message: `The cell still holds ${held[1]} user(s) in the directory. Move them out first: open a user and choose Move to another cell, or set the cell to Draining. If the cell is gone for good, delete it as lost.` };
  }
  if (/move of a user that is not finished/.test(text)) {
    return { kind: 'move', tokens: null, message: 'A move from or to this cell is not finished. Wait until it is done or has failed (see User moves below).' };
  }
  if (/force=true/.test(text)) {
    const count = /answers for (\d+) refresh token/.exec(text);
    return { kind: 'tokens', tokens: count ? Number(count[1]) : null, message: text };
  }
  return { kind: 'other', message: fallback, tokens: null };
}

/**
 * Cells and regions: the table of every cell and, for the one selected, the side panel with its placement (state and
 * weight), its addresses and pinned certificates, the Fixed group (the ID and the region) and the danger card; below the
 * table, the regions with the cells and the edge sites in each. Everything shown comes from `GET /configuration/topology`
 * and `GET /configuration/edges`: the screen is the registry the edges read their cells from.
 */
@customElement('versola-cells-list')
export class VersolaCellsList extends LitElement {
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private cells: CellStatus[] = [];
  @state() private regions: Region[] = [];
  @state() private partitions: Partition[] = [];
  @state() private edges: Edge[] = [];
  @state() private searchQuery = '';
  @state() private filter: CellFilter = 'all';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private actionError = '';
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @state() private draftId = '';
  @state() private draftRegionId = '';
  @state() private draftState: CellState = 'closed';
  @state() private draftWeight = '100';
  @state() private draftInternalUrl = '';
  @state() private draftMtlsUrl = '';
  @state() private draftCertificates = '';
  @state() private draftPublicKeys = '';
  @state() private draftPartitionId = '';
  @state() private draftNewPartitionId = '';
  @state() private draftSites: string[] = [];
  @query('versola-data-table') private table?: VersolaDataTable<CellStatus>;
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
    css`
      :host {
        display: block;
      }

      .list-toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-md);
        align-items: center;
        margin-bottom: var(--spacing-lg);
      }

      .search-input {
        flex: 1 1 14rem;
        max-width: 28rem;
        min-width: 0;
      }

      .muted {
        color: var(--text-secondary);
      }

      .mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
      }

      .record-page .record-card,
      .regions {
        margin-bottom: var(--spacing-md);
      }

      .regions {
        margin-top: var(--spacing-xl);
      }

      .region-actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
      }

      .drain-warning {
        margin-bottom: var(--spacing-md);
      }

      .check-list {
        display: grid;
        gap: var(--spacing-sm);
      }

      /* A row of the list is a label for its checkbox, not a field caption: the form's caption style does not apply. */
      .check-row {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        min-height: 24px;
        margin: 0;
        font-weight: 400;
        letter-spacing: normal;
        text-transform: none;
        color: var(--text-primary);
      }

      textarea.compact-input {
        min-height: 9rem;
        font-family: var(--font-mono);
        font-size: var(--text-sm);
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  updated(changed: Map<string, unknown>) {
    const dataChanged = changed.has('cells') || changed.has('regions') || changed.has('partitions') || changed.has('edges');
    if (changed.has('route') || dataChanged) {
      const mode = this.editorMode;
      const draftKey = mode === 'view' ? '' : `${mode}:${this.route.id ?? ''}:${this.route.edit ?? ''}`;
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (mode !== 'view') this.resetDraft();
      } else if (mode !== 'view' && dataChanged && this.draftSnapshot === this.draftBaseline) {
        // The editor was opened before the topology arrived (a reload on a create link): an untouched draft is built again
        // from what is known now, so the region and partition it shows are the ones it submits.
        this.resetDraft();
      }
    }
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      let edgesFailed = false;
      const [topology, edges] = await Promise.all([
        fetchTopology(),
        fetchEdges().catch(() => {
          edgesFailed = true;
          return [] as Edge[];
        }),
      ]);
      if (edgesFailed) this.actionError = 'The edge sites could not be loaded, so they cannot be edited until the page is reloaded.';
      this.cells = topology.cells;
      this.regions = topology.regions;
      this.partitions = topology.partitions;
      this.edges = edges;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load cells';
      this.cells = [];
      this.regions = [];
      this.partitions = [];
      this.edges = [];
    } finally {
      this.isLoading = false;
    }
  }

  // ── routing ──

  private get openCell(): CellStatus | null {
    return this.route.id ? this.cells.find(cell => cell.id === this.route.id) ?? null : null;
  }

  /** The region an `edit=sites.<region>` editor is about. */
  private get sitesRegion(): Region | null {
    const id = this.route.edit?.startsWith('sites.') ? this.route.edit.slice('sites.'.length) : null;
    return id ? this.regions.find(region => region.id === id) ?? null : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' | 'region' | 'sites' {
    if (this.route.create) return 'create';
    if (this.route.edit === 'region') return 'region';
    if (this.sitesRegion) return 'sites';
    return this.editGroup && this.openCell ? 'edit' : 'view';
  }

  private get editGroup(): CellGroup | null {
    return this.route.edit === 'placement' || this.route.edit === 'addresses' || this.route.edit === 'keys' ? this.route.edit : null;
  }

  private openView(cell: CellStatus) {
    dispatchRecordRoute(this, { id: cell.id, edit: null, create: false });
  }

  private closePanel() {
    const key = this.route.id;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private get draftSnapshot(): string {
    return JSON.stringify([
      this.draftId, this.draftRegionId, this.draftState, this.draftWeight, this.draftInternalUrl, this.draftMtlsUrl,
      this.draftCertificates, this.draftPublicKeys, this.draftPartitionId, this.draftNewPartitionId, [...this.draftSites].sort(),
    ]);
  }

  /** Closing an editor with unsaved changes asks first; the draft is dropped only on a yes. */
  private async cancelEditor() {
    if (this.draftSnapshot !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  // ── draft ──

  private resetDraft() {
    const cell = this.editorMode === 'edit' ? this.openCell : null;
    const region = this.sitesRegion;
    this.draftId = '';
    this.draftRegionId = cell?.regionId ?? this.regions[0]?.id ?? '';
    // A new cell takes no users until it is opened: it is registered before anything is checked against it.
    this.draftState = cell?.state ?? 'closed';
    this.draftWeight = String(cell?.weight ?? 100);
    this.draftInternalUrl = cell?.internalUrl ?? '';
    this.draftMtlsUrl = cell?.mtlsUrl ?? '';
    this.draftCertificates = (cell?.trustedCertificates ?? []).join('\n');
    this.draftPublicKeys = publicKeysText(cell?.publicKeys ?? []);
    this.draftPartitionId = this.partitions[0]?.id ?? NEW_PARTITION;
    this.draftNewPartitionId = '';
    this.draftSites = region ? this.edges.filter(edge => edge.regionId === region.id).map(edge => edge.id) : [];
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
    this.draftBaseline = this.draftSnapshot;
  }

  private get idError(): string {
    const id = this.draftId.trim();
    if (!id) return this.submitted ? 'An ID is required.' : '';
    if (!validId(id)) return ID_RULE;
    if (this.editorMode === 'create' && this.cells.some(cell => cell.id === id)) return 'A cell with this ID already exists.';
    if (this.editorMode === 'region' && this.regions.some(region => region.id === id)) return 'A region with this ID already exists.';
    return '';
  }

  private get weightError(): string {
    return /^\d+$/.test(this.draftWeight.trim()) ? '' : 'A whole number, 0 or more.';
  }

  private get internalUrlError(): string {
    if (!this.draftInternalUrl.trim()) return this.submitted ? 'An address is required.' : '';
    return addressError(this.draftInternalUrl.trim(), false);
  }

  private get mtlsUrlError(): string {
    return this.draftMtlsUrl.trim() ? addressError(this.draftMtlsUrl.trim(), true) : '';
  }

  private get certificatesError(): string {
    const text = this.draftCertificates.trim();
    if (!text) return '';
    const certificates = certificatesOf(text);
    if (certificates.length === 0 || text.replace(/-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g, '').trim()) {
      return 'PEM certificates only: each from -----BEGIN CERTIFICATE----- to -----END CERTIFICATE-----.';
    }
    return new Set(certificates).size === certificates.length ? '' : 'The same certificate is pasted twice.';
  }

  /** Empty until submitted (a new cell has none yet), then the first thing wrong with the pasted keys. */
  private get publicKeysError(): string {
    if (!this.draftPublicKeys.trim() && !this.submitted) return '';
    return parsePublicKeys(this.draftPublicKeys).error;
  }

  private get newPartitionError(): string {
    if (this.draftPartitionId !== NEW_PARTITION) return '';
    const id = this.draftNewPartitionId.trim();
    if (!id) return this.submitted ? 'A partition ID is required.' : '';
    if (!validId(id)) return ID_RULE;
    return this.partitions.some(partition => partition.id === id) ? 'A partition with this ID already exists.' : '';
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.submitted = true;
    this.actionError = '';
    const mode = this.editorMode;
    if (mode === 'region') return this.saveRegion();
    if (mode === 'sites') return this.saveSites();

    const group = this.editGroup;
    const existing = this.openCell;
    const placement = mode === 'create' || group === 'placement';
    const addresses = mode === 'create' || group === 'addresses';
    const keys = mode === 'create' || group === 'keys';
    if (mode === 'create' && !this.draftRegionId) {
      this.formError = 'Choose a region.';
      return;
    }
    if (mode === 'create' && (this.idError || !this.draftId.trim())) return;
    if (placement && this.weightError) return;
    if (keys && parsePublicKeys(this.draftPublicKeys).error) return;
    if (addresses && (this.internalUrlError || !this.draftInternalUrl.trim() || this.mtlsUrlError || this.certificatesError)) return;

    const next: Cell = {
      id: mode === 'create' ? this.draftId.trim() : existing!.id,
      regionId: mode === 'create' ? this.draftRegionId : existing!.regionId,
      state: placement ? this.draftState : existing!.state,
      weight: placement ? Number(this.draftWeight.trim()) : existing!.weight,
      internalUrl: addresses ? this.draftInternalUrl.trim() : existing!.internalUrl,
      mtlsUrl: addresses ? this.draftMtlsUrl.trim() || null : existing!.mtlsUrl ?? null,
      trustedCertificates: addresses ? certificatesOf(this.draftCertificates) : existing!.trustedCertificates,
      publicKeys: keys ? parsePublicKeys(this.draftPublicKeys).keys! : existing!.publicKeys,
    };

    this.isSaving = true;
    this.formError = '';
    try {
      if (mode === 'create') {
        await createCell(next);
        this.cells = [...this.cells, { ...next, users: 0, moving: 0 }].sort((a, b) => a.id.localeCompare(b.id));
        dispatchRecordRoute(this, { id: next.id, edit: null, create: false });
      } else {
        await updateCell(next);
        this.cells = this.cells.map(cell => cell.id === next.id ? { ...cell, ...next } : cell);
        dispatchRecordRoute(this, { edit: null, create: false });
      }
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save the cell';
    } finally {
      this.isSaving = false;
    }
  }

  private async saveRegion() {
    if (this.idError || !this.draftId.trim() || this.newPartitionError) return;
    const fresh = this.draftPartitionId === NEW_PARTITION;
    const partitionId = fresh ? this.draftNewPartitionId.trim() : this.draftPartitionId;
    if (!partitionId) return;
    const id = this.draftId.trim();
    this.isSaving = true;
    this.formError = '';
    try {
      if (fresh) {
        await createPartition(partitionId);
        this.partitions = [...this.partitions, { id: partitionId }].sort((a, b) => a.id.localeCompare(b.id));
        this.draftPartitionId = partitionId;
      }
      await createRegion(id, partitionId);
      this.regions = [...this.regions, { id, partitionId }].sort((a, b) => a.id.localeCompare(b.id));
      dispatchRecordRoute(this, { edit: null, create: false });
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save the region';
    } finally {
      this.isSaving = false;
    }
  }

  /** One request per edge whose region changes: put in this region, or taken out of it. */
  private async saveSites() {
    const region = this.sitesRegion!;
    const wanted = new Set(this.draftSites);
    const changes = this.ownEdges
      .map(edge => ({ edge, regionId: wanted.has(edge.id) ? region.id : edge.regionId === region.id ? null : edge.regionId ?? null }))
      .filter(change => (change.edge.regionId ?? null) !== change.regionId);
    this.isSaving = true;
    this.formError = '';
    let failing = '';
    try {
      for (const change of changes) {
        failing = change.edge.id;
        await setEdgeRegion(change.edge.id, change.regionId);
        this.edges = this.edges.map(edge => edge.id === change.edge.id ? { ...edge, regionId: change.regionId } : edge);
      }
      dispatchRecordRoute(this, { edit: null, create: false });
    } catch (error) {
      const reason = error instanceof Error ? error.message : 'Failed to save the edges of the region';
      // The edges before the failing one are already moved; saying which is the one that was not tells the operator what to retry.
      this.formError = failing ? `Edge ${failing} was not changed: ${reason}` : reason;
    } finally {
      this.isSaving = false;
    }
  }

  private async handleDeleteCell(cell: CellStatus) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete cell',
      messagePrefix: 'Delete cell ',
      messageSubject: cell.id,
      messageSuffix: '? Its ID is in every value it issued, so it should never be registered again for another cell.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    await this.deleteWith(cell, {});
  }

  /** The cell is gone for good with its users: central deletes it although the directory still places users in it. */
  private async handleDeleteLostCell(cell: CellStatus) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete a lost cell',
      messagePrefix: 'Delete cell ',
      messageSubject: cell.id,
      messageSuffix: ` as lost? Use this only when the cell is gone for good, with its database. The ${cell.users} user(s) the directory still places in it stay in the directory and cannot sign in until they are deleted. A working cell is retired by moving its users out instead.`,
      confirmLabel: 'Delete as lost',
    });
    if (!confirmed) return;
    await this.deleteWith(cell, { lost: true });
  }

  private async deleteWith(cell: CellStatus, options: { lost?: boolean; force?: boolean }) {
    this.actionError = '';
    try {
      await deleteCell(cell.id, options);
      this.cells = this.cells.filter(candidate => candidate.id !== cell.id);
      if (this.route.id === cell.id) dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    } catch (error) {
      const refusal = deleteRefusal(error);
      if (refusal.kind === 'tokens' && !options.force) {
        // The only refusal `force` lifts, so it is offered here and nowhere else, with what it costs.
        const forced = await confirmDestructiveAction({
          title: 'Delete the cell and invalidate refresh tokens?',
          messagePrefix: 'Cell ',
          messageSubject: cell.id,
          messageSuffix: ` ${refusal.tokens === null ? 'did not say whether it still answers for refresh tokens of users moved out of it' : `still answers for ${refusal.tokens} refresh token(s) of users moved out of it`}. `
            + 'The edge does not know a deleted cell, so deleting it now makes those refresh tokens invalid: the apps that hold them have to sign in again.',
          confirmLabel: 'Delete and invalidate',
        });
        if (forced) await this.deleteWith(cell, { ...options, force: true });
        return;
      }
      this.actionError = refusal.message;
    }
  }

  private async handleDeleteRegion(region: Region) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete region',
      messagePrefix: 'Delete region ',
      messageSubject: region.id,
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    this.actionError = '';
    try {
      await deleteRegion(region.id);
      this.regions = this.regions.filter(candidate => candidate.id !== region.id);
    } catch (error) {
      this.actionError = error instanceof Error ? error.message : 'Failed to delete the region';
    }
  }

  // ── list ──

  /** The operator's own edges: a partner's edge is in no region. */
  private get ownEdges(): Edge[] {
    return this.edges.filter(edge => edge.groupKind !== 'partner');
  }

  private get filteredCells(): CellStatus[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.cells.filter(cell => (this.filter === 'all' || cell.state === this.filter)
      && (!query || cell.id.toLowerCase().includes(query) || cell.regionId.toLowerCase().includes(query)));
  }

  private stateChip(cell: CellStatus) {
    switch (cell.state) {
      case 'open': return html`<span class="chip ok">${statusIcon('check', 'icon', 14)}Open</span>`;
      case 'draining': return html`<span class="chip warn">Draining</span>${cell.drainBlocked ? html`<span class="chip warn">Blocked</span>` : ''}`;
      default: return html`<span class="chip">Closed</span>`;
    }
  }

  private partitionOf(regionId: string): string {
    return this.regions.find(region => region.id === regionId)?.partitionId ?? '';
  }

  private renderToolbar() {
    const options: Array<{ id: CellFilter; label: string }> = [{ id: 'all', label: 'All' }, ...STATES.map(option => ({ id: option.id, label: option.label }))];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search cells" placeholder="Cell ID or region"
          .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
        <versola-filter-bar label="Filter cells"
          .options=${options}
          .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => this.filter = e.detail.id as CellFilter}></versola-filter-bar>
      </div>
    `;
  }

  private renderTable() {
    const columns: DataTableColumn<CellStatus>[] = [
      { id: 'cell', label: 'Cell', cell: cell => html`
        <button type="button" class="row-link" aria-label=${`Open cell ${cell.id}`}>
          <span class="row-title">${cell.id}</span>
        </button>` },
      { id: 'region', label: 'Region', cell: cell => html`<div class="chips"><span class="chip">${cell.regionId}</span></div>` },
      { id: 'state', label: 'State', cell: cell => html`<div class="chips">${this.stateChip(cell)}</div>` },
      { id: 'weight', label: 'Weight', class: 'muted', cell: cell => String(cell.weight) },
      { id: 'users', label: 'Users', class: 'muted', cell: cell => String(cell.users) },
      { id: 'moving', label: 'Moving', class: 'muted', cell: cell => String(cell.moving) },
      { id: 'address', label: 'Address', cell: cell => html`<span class="mono">${cell.internalUrl}</span>` },
    ];
    const menuItems = (cell: CellStatus): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(cell) },
      { label: 'Copy cell ID', onSelect: () => void copyToClipboard(cell.id) },
      ...(this.canManage && cell.state === 'closed' ? [{ label: 'Delete cell', danger: true, ariaLabel: `Delete cell ${cell.id}`, onSelect: () => void this.handleDeleteCell(cell) }] : []),
    ];
    return html`
      <versola-data-table selectable
        .columns=${columns}
        .rows=${this.filteredCells}
        .rowKey=${(cell: CellStatus) => cell.id}
        .selectedKey=${this.editorMode === 'view' ? this.route.id : null}
        .menuItems=${menuItems}
        .menuLabel=${(cell: CellStatus) => `Actions for ${cell.id}`}
        @row-select=${(e: CustomEvent<{ row: CellStatus }>) => this.openView(e.detail.row)}
      ></versola-data-table>
    `;
  }

  private renderRegions() {
    return html`
      <div class="regions">
        ${this.regions.length === 0 ? html`
          <versola-record-card heading="Regions" subheading="A region is one metro: a cell and an edge site are each in one. Add a region before the first cell." .body=${html`
            <versola-record-row .value=${this.canManage
              ? html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => dispatchRecordRoute(this, { id: null, edit: 'region', create: false })}>Add region</button>`
              : html`<span class="muted">No regions yet.</span>`}></versola-record-row>
          `}></versola-record-card>
        ` : this.regions.map(region => {
          const cells = this.cells.filter(cell => cell.regionId === region.id);
          const sites = this.edges.filter(edge => edge.regionId === region.id);
          const empty = cells.length === 0 && sites.length === 0;
          return html`
            <versola-record-card heading=${`Region ${region.id}`} subheading=${`Partition ${region.partitionId}`} .body=${html`
              ${this.row('Cells', cells.length ? html`<div class="tag-list">${cells.map(cell => html`<span class="tag">${cell.id}</span>`)}</div>` : html`<span class="muted">None</span>`)}
              ${this.row('Edge sites', sites.length ? html`<div class="tag-list">${sites.map(edge => html`<span class="tag">${edge.id}</span>`)}</div>` : html`<span class="muted">None</span>`)}
              ${this.canManage ? html`<versola-record-row .value=${html`<div class="region-actions">
                <button type="button" class="btn btn-secondary btn-sm" aria-label=${`Edit edge sites of ${region.id}`}
                  @click=${() => dispatchRecordRoute(this, { id: null, edit: `sites.${region.id}`, create: false })}>Edit edge sites</button>
                ${empty ? html`<button type="button" class="btn btn-secondary btn-sm" aria-label=${`Delete region ${region.id}`}
                  @click=${() => this.handleDeleteRegion(region)}>Delete region</button>` : ''}
              </div>`}></versola-record-row>` : ''}
            `}></versola-record-card>`;
        })}
        ${this.regions.length > 0 && this.canManage ? html`
          <button type="button" class="btn btn-secondary" @click=${() => dispatchRecordRoute(this, { id: null, edit: 'region', create: false })}>Add region</button>` : ''}
      </div>
    `;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private tokensText(cell: CellStatus) {
    // Absent is a cell that did not answer in time: not known, which is not the same as none.
    if (cell.tokens === undefined) return html`<span class="muted">Unknown: the cell did not answer</span>`;
    if (cell.tokens === 0) return html`0`;
    return html`${cell.tokens}${cell.tokensUntil ? html`<span class="muted"> · the last expires ${formatDateTime(cell.tokensUntil, localZone())}</span>` : ''}`;
  }

  private renderUsersCard(cell: CellStatus) {
    const draining = cell.state === 'draining';
    return html`
      <versola-record-card heading=${draining ? 'Drain progress' : 'Users and moves'}
        subheading=${draining ? 'The users of this cell are being moved to the other cells of its partition.' : 'The users the directory places in this cell, and the moves that leave it.'} .body=${html`
        ${draining && cell.drainBlocked ? html`<div class="banner warn drain-warning" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}The drain is waiting</div>
          <div>No open cell of the partition with a weight above 0 can take these users. Nothing is moved until there is one: open a cell or give an open cell a weight.</div></div></div>` : ''}
        ${this.row(draining ? 'Users left' : 'Users', html`${cell.users}`)}
        ${this.row('Moves in progress', html`${cell.moving}`)}
        ${this.row('Carried refresh tokens', html`${this.tokensText(cell)}<div class="hint">Refresh tokens of users moved out of this cell that it still answers for, so the app is sent on to the new cell. While there are any, deleting the cell needs force and makes them invalid.</div>`)}
      `}></versola-record-card>`;
  }

  private renderGroups(cell: CellStatus) {
    const state = STATES.find(option => option.id === cell.state)!;
    return html`
      <versola-record-card heading="Placement" subheading="Whether new users are placed in this cell, and its share of them." ?editable=${this.canManage} .body=${html`
        ${this.row('State', html`<div class="chips">${this.stateChip(cell)}</div><div class="hint">${state.text}</div>`)}
        ${this.row('Weight', html`${cell.weight}<div class="hint">Its share of new users among the open cells of ${cell.regionId}.</div>`)}
      `} @edit=${() => dispatchRecordRoute(this, { edit: 'placement', create: false })}></versola-record-card>

      ${this.renderUsersCard(cell)}

      <versola-record-card heading="Addresses" subheading="Where an edge reaches this cell's auth, and the certificates it is pinned by." ?editable=${this.canManage} .body=${html`
        ${this.row('Internal URL', html`<span class="mono">${cell.internalUrl}</span>`)}
        ${this.row('Mutual-TLS URL', cell.mtlsUrl ? html`<span class="mono">${cell.mtlsUrl}</span>` : html`<span class="muted">None</span>`)}
        ${this.row('Pinned certificates', cell.trustedCertificates.length
          ? html`<div class="tag-list">${cell.trustedCertificates.map((_, index) => html`<span class="tag">Certificate ${index + 1}</span>`)}</div>`
          : html`<span class="muted">None: the endpoints are not pinned</span>`)}
      `} @edit=${() => dispatchRecordRoute(this, { edit: 'addresses', create: false })}></versola-record-card>

      <versola-record-card heading="Public keys" subheading="The keys the cell's auth signs its calls to central with." ?editable=${this.canManage} .body=${html`
        ${cell.publicKeys.length ? cell.publicKeys.map((key, index) => this.row(index === 0 ? 'Key 1 (encrypted to)' : `Key ${index + 1}`,
          html`<span class="mono">${key.kid}</span><div class="hint">RSA</div>`)) : this.row('Keys', html`<span class="muted">None: this cell cannot call central</span>`)}
      `} @edit=${() => dispatchRecordRoute(this, { edit: 'keys', create: false })}></versola-record-card>

      <versola-record-card heading="Fixed" fixedLabel="Fixed" .body=${html`
        ${this.row('Cell ID', html`<span class="mono">${cell.id}</span>`, 'Written in front of every value the cell issues')}
        ${this.row('Region', html`<span class="mono">${cell.regionId}</span>`, 'A cell is where its database is')}
        ${this.row('Partition', html`<span class="mono">${this.partitionOf(cell.regionId)}</span>`, 'The partition of the region')}
      `}></versola-record-card>

      ${this.canManage ? html`
        <versola-danger-card heading="Delete cell" text="Removes the cell from the registry. This can't be undone."
          actionLabel="Delete cell" .actionAriaLabel=${`Delete cell ${cell.id}`}
          .disabledReason=${cell.state === 'closed' ? '' : 'Only a closed cell is deleted: close it first, once it holds no users.'}
          @action=${() => this.handleDeleteCell(cell)}></versola-danger-card>
        <versola-danger-card heading="The cell is lost" text="The cell is gone for good with its database. Deletes it although the directory still places users in it: they stay in the directory and cannot sign in until they are deleted. Not the way to retire a working cell."
          actionLabel="Delete as lost" .actionAriaLabel=${`Delete cell ${cell.id} as lost`}
          .disabledReason=${cell.state === 'closed' ? '' : 'Only a closed cell is deleted: close it first.'}
          @action=${() => this.handleDeleteLostCell(cell)}></versola-danger-card>` : ''}
    `;
  }

  private renderRecordPage(cell: CellStatus) {
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Cells', onSelect: () => this.closePanel() }, { label: cell.id }]}
        .heading=${cell.id} .recordId=${cell.id} idLabel="cell ID"
        .meta=${html`<span class="chip">Cell</span>${this.stateChip(cell)}`}
      ></versola-record-header>
      ${this.actionError ? html`<div class="banner warn" role="alert"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}${this.actionError}</div></div></div>` : ''}
      <div class="record-page">${this.renderGroups(cell)}</div>
    `;
  }

  // ── editors ──

  private field(id: string, label: string, value: string, error: string, onInput: (value: string) => void, options: { placeholder?: string; hint?: string; mono?: boolean } = {}) {
    return html`
      <div class="form-group">
        <label for=${id}>${label}</label>
        <input type="text" id=${id} class="compact-input ${options.mono ? 'mono' : ''} ${error ? 'input-error' : ''}" .value=${value} autocomplete="off" spellcheck="false"
          aria-invalid=${error ? 'true' : nothing} aria-describedby=${error ? `${id}-error` : options.hint ? `${id}-hint` : nothing}
          placeholder=${options.placeholder ?? ''} @input=${(e: Event) => onInput((e.target as HTMLInputElement).value)} />
        ${error ? html`<div class="error-message" id="${id}-error" role="alert">${error}</div>` : options.hint ? html`<div class="hint" id="${id}-hint">${options.hint}</div>` : ''}
      </div>
    `;
  }

  private renderPlacementFields() {
    const text = STATES.find(option => option.id === this.draftState)!.text;
    return html`
      <div class="form-group">
        <label for="cell-state">State</label>
        <select id="cell-state" class="compact-input" .value=${this.draftState} aria-describedby="cell-state-hint"
          @change=${(e: Event) => this.draftState = (e.target as HTMLSelectElement).value as CellState}>
          ${STATES.map(option => html`<option value=${option.id} ?selected=${option.id === this.draftState}>${option.label}</option>`)}
        </select>
        <div class="hint" id="cell-state-hint">${text}</div>
      </div>
      ${this.field('cell-weight', 'Weight *', this.draftWeight, this.weightError, value => this.draftWeight = value,
        { hint: 'The share of new users among the open cells of the region. 0 takes none.' })}
    `;
  }

  private renderAddressFields() {
    const error = this.certificatesError;
    return html`
      ${this.field('cell-internal-url', 'Internal URL *', this.draftInternalUrl, this.internalUrlError, value => this.draftInternalUrl = value,
        { placeholder: 'http://auth-kz-1.internal:9003', hint: 'Where an edge sends the cell\'s token, PAR and userinfo calls.', mono: true })}
      ${this.field('cell-mtls-url', 'Mutual-TLS URL (Optional)', this.draftMtlsUrl, this.mtlsUrlError, value => this.draftMtlsUrl = value,
        { placeholder: 'https://auth-kz-1.internal:9008', hint: 'The cell\'s mutual-TLS listener, for the calls that present a client certificate.', mono: true })}
      <div class="form-group">
        <label for="cell-certificates">Pinned certificates (Optional)</label>
        <textarea id="cell-certificates" class="compact-input ${error ? 'input-error' : ''}" spellcheck="false" .value=${this.draftCertificates}
          aria-invalid=${error ? 'true' : nothing} aria-describedby=${error ? 'cell-certificates-error' : 'cell-certificates-hint'}
          placeholder="-----BEGIN CERTIFICATE-----" @input=${(e: Event) => this.draftCertificates = (e.target as HTMLTextAreaElement).value}></textarea>
        ${error ? html`<div class="error-message" id="cell-certificates-error" role="alert">${error}</div>`
          : html`<div class="hint" id="cell-certificates-hint">The leaf certificates of the cell's endpoints, PEM, one after another. Paste the new one beside the old one to replace a certificate without a gap.</div>`}
      </div>
    `;
  }

  private renderKeyFields() {
    const error = this.publicKeysError;
    return html`
      <div class="form-group">
        <label for="cell-public-keys">Public keys *</label>
        <textarea id="cell-public-keys" class="compact-input ${error ? 'input-error' : ''}" spellcheck="false" .value=${this.draftPublicKeys}
          aria-invalid=${error ? 'true' : nothing} aria-describedby=${error ? 'cell-public-keys-error' : 'cell-public-keys-hint'}
          placeholder='{"kty":"RSA","kid":"kz-1","n":"...","e":"AQAB"}' @input=${(e: Event) => this.draftPublicKeys = (e.target as HTMLTextAreaElement).value}></textarea>
        ${error ? html`<div class="error-message" id="cell-public-keys-error" role="alert">${error}</div>`
          : html`<div class="hint" id="cell-public-keys-hint">${PUBLIC_KEYS_HINT}</div>`}
      </div>
    `;
  }

  private renderCellEditor() {
    const mode = this.editorMode;
    return html`
      <form id="cell-editor" @submit=${this.handleSubmit} novalidate>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${mode === 'create' ? html`
          <div class="editor-box">
            <h3>Basics</h3>
            <p class="editor-sub">The ID is written in front of every value the cell issues, and the region is where its database is. Neither changes later.</p>
            ${this.field('cell-id', 'Cell ID *', this.draftId, this.idError, value => this.draftId = value, { placeholder: 'e.g. kz-1', hint: ID_RULE, mono: true })}
            <div class="form-group">
              <label for="cell-region">Region *</label>
              <select id="cell-region" class="compact-input" .value=${this.draftRegionId}
                @change=${(e: Event) => this.draftRegionId = (e.target as HTMLSelectElement).value}>
                ${this.regions.map(region => html`<option value=${region.id} ?selected=${region.id === this.draftRegionId}>${region.id} (partition ${region.partitionId})</option>`)}
              </select>
            </div>
          </div>
          <div class="editor-box">
            <h3>Addresses</h3>
            <p class="editor-sub">Where an edge reaches this cell's auth.</p>
            ${this.renderAddressFields()}
          </div>
          <div class="editor-box">
            <h3>Public keys</h3>
            <p class="editor-sub">Register the cell here before its auth is started: central takes a call to be the cell's only when it verifies with one of these keys.</p>
            ${this.renderKeyFields()}
          </div>
          <div class="editor-box">
            <h3>Placement</h3>
            <p class="editor-sub">A new cell starts closed: open it when it is ready to take users.</p>
            ${this.renderPlacementFields()}
          </div>
        ` : this.editGroup === 'placement' ? html`
          <div class="editor-box">
            <h3>Placement</h3>
            <p class="editor-sub">A cell in any state still serves the users it holds.</p>
            ${this.renderPlacementFields()}
          </div>
        ` : this.editGroup === 'keys' ? html`
          <div class="editor-box">
            <h3>Public keys</h3>
            <p class="editor-sub">The whole set the cell has from now on, not an addition: replace a key by pasting both for a while, then only the new one.</p>
            ${this.renderKeyFields()}
          </div>
        ` : html`
          <div class="editor-box">
            <h3>Addresses</h3>
            <p class="editor-sub">An edge picks the change up on its next configuration sync.</p>
            ${this.renderAddressFields()}
          </div>
        `}
      </form>
    `;
  }

  private renderRegionEditor() {
    return html`
      <form id="cell-editor" @submit=${this.handleSubmit} novalidate>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        <div class="editor-box">
          <h3>Region</h3>
          <p class="editor-sub">One metro, a failure domain: the cells and the edge sites of one place. The ID does not change later.</p>
          ${this.field('region-id', 'Region ID *', this.draftId, this.idError, value => this.draftId = value, { placeholder: 'e.g. almaty', hint: ID_RULE, mono: true })}
          ${this.partitions.length ? html`
            <div class="form-group">
              <label for="region-partition">Partition *</label>
              <select id="region-partition" class="compact-input" .value=${this.draftPartitionId} aria-describedby="region-partition-hint"
                @change=${(e: Event) => this.draftPartitionId = (e.target as HTMLSelectElement).value}>
                ${this.partitions.map(partition => html`<option value=${partition.id} ?selected=${partition.id === this.draftPartitionId}>${partition.id}</option>`)}
                <option value=${NEW_PARTITION} ?selected=${this.draftPartitionId === NEW_PARTITION}>New partition…</option>
              </select>
              <div class="hint" id="region-partition-hint">A partition is one jurisdiction, with its own issuer, keys and directory. A deployment has one.</div>
            </div>` : ''}
          ${this.draftPartitionId === NEW_PARTITION ? this.field('region-new-partition', 'Partition ID *', this.draftNewPartitionId, this.newPartitionError,
            value => this.draftNewPartitionId = value, { placeholder: 'e.g. kz', hint: 'One jurisdiction, with its own issuer, keys and directory. Created with the region.', mono: true }) : ''}
        </div>
      </form>
    `;
  }

  private renderSitesEditor(region: Region) {
    const edges = this.ownEdges;
    return html`
      <form id="cell-editor" @submit=${this.handleSubmit} novalidate>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        <div class="editor-box">
          <h3>Edge sites</h3>
          <p class="editor-sub">Your own edges that run in ${region.id}. An edge looks for an open cell in its own region first. A partner's edge is in no region.</p>
          ${edges.length === 0 ? html`<p class="muted">No edges registered yet.</p>` : html`
            <div class="check-list" role="group" aria-label=${`Edges in ${region.id}`}>
              ${edges.map(edge => {
                const elsewhere = edge.regionId && edge.regionId !== region.id ? edge.regionId : '';
                return html`
                  <label class="check-row">
                    <input type="checkbox" .checked=${this.draftSites.includes(edge.id)}
                      @change=${(e: Event) => this.draftSites = (e.target as HTMLInputElement).checked ? [...this.draftSites, edge.id] : this.draftSites.filter(id => id !== edge.id)} />
                    <span class="mono">${edge.id}</span>
                    ${elsewhere ? html`<span class="muted">now in ${elsewhere}</span>` : ''}
                  </label>`;
              })}
            </div>`}
        </div>
      </form>
    `;
  }

  private renderPanel(cell: CellStatus | null) {
    const mode = this.editorMode;
    if (mode === 'view' && !cell) return '';
    const region = this.sitesRegion;
    const heading = mode === 'create' ? 'Register cell' : mode === 'region' ? 'Add region' : mode === 'sites' ? 'Edit edge sites'
      : mode === 'edit' ? (this.editGroup === 'placement' ? 'Edit placement' : this.editGroup === 'keys' ? 'Edit public keys' : 'Edit addresses') : cell!.id;
    const submitLabel = mode === 'create' ? 'Register cell' : mode === 'region' ? 'Add region' : mode === 'sites' ? 'Save edge sites'
      : this.editGroup === 'placement' ? 'Save placement' : this.editGroup === 'keys' ? 'Save public keys' : 'Save addresses';
    const parent = mode === 'edit' ? cell!.id : mode === 'sites' ? `Region ${region!.id}` : '';
    return html`
      <versola-side-panel open ?modal=${mode !== 'view'} .mode=${mode === 'view' ? 'view' : mode === 'create' || mode === 'region' ? 'create' : 'edit'}
        .heading=${heading} .kind=${mode === 'view' ? 'Cell' : ''} .recordId=${mode === 'view' ? cell!.id : ''} idLabel="cell ID"
        .parentLabel=${parent} .subtitle=${mode === 'create' ? 'The ID and the region are fixed once registered.' : ''}
        .meta=${cell && mode === 'view' ? this.stateChip(cell) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()}
        @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">
          ${mode === 'view' ? this.renderGroups(cell!) : mode === 'region' ? this.renderRegionEditor() : mode === 'sites' ? this.renderSitesEditor(region!) : this.renderCellEditor()}
        </div>
        ${mode !== 'view' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="cell-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : submitLabel}</button>
          </div>` : ''}
      </versola-side-panel>
    `;
  }

  private handleCreateClick() {
    dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null });
  }

  render() {
    const cell = this.openCell;
    const panel = this.renderPanel(cell);
    if (cell && this.route.layout === 'page') {
      return html`${this.renderRecordPage(cell)}${this.editorMode === 'view' ? '' : panel}`;
    }

    const canRegister = this.canManage && this.regions.length > 0;
    return html`
      <content-header title="Cells" description="A cell is one auth deployment with its own database, holding a fixed set of users. Regions say where the cells and your edge sites are.">
        ${this.cells.length > 0 && canRegister ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick}>+ Register Cell</button>` : ''}
      </content-header>

      ${this.actionError ? html`<div class="banner warn" role="alert"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}${this.actionError}</div></div></div>` : ''}

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load cells" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : html`
        ${this.cells.length === 0 ? html`
          <versola-empty-state heading="No cells registered"
            text=${this.regions.length === 0 ? 'This installation runs as one auth deployment. Add a region below, then register the first cell.' : 'This installation runs as one auth deployment. Register a cell to describe it.'}>
            ${canRegister ? html`<button class="btn btn-primary" @click=${this.handleCreateClick}>+ Register Cell</button>` : ''}
          </versola-empty-state>
        ` : html`
          ${this.renderToolbar()}
          ${this.filteredCells.length === 0 ? html`<versola-empty-state heading="No cells match"></versola-empty-state>` : this.renderTable()}
        `}
        ${this.cells.length > 0 ? html`<versola-user-moves .cells=${this.cells}></versola-user-moves>` : ''}
        ${this.renderRegions()}
      `}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-cells-list': VersolaCellsList;
  }
}
