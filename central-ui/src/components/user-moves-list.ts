import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, chipStyles, formStyles } from '../styles/components';
import type { Cell, UserMove, UserMovePhase } from '../types';
import { fetchUserMoves } from '../utils/users-api';
import { formatDateTime, localZone } from '../utils/date-format';
import { MOVE_PHASES, errorLabel, phaseLabel, reasonLabel } from '../utils/user-moves';
import { statusIcon } from './status-icon';
import type { DataTableColumn } from './data-table';
import './data-table';
import './empty-state';
import './error-card';
import './filter-bar';
import './loading-cards';

type PhaseFilter = 'all' | UserMovePhase;

/**
 * The moves of users between cells, one per user (the latest): a move an administrator asked for, or one the drain of a cell made.
 * Filter by phase and by cell. A move that stays in `flipped` is the one to look at: the directory names the new cell but the old
 * one has not finished, so it does not serve that user until it does.
 */
@customElement('versola-user-moves')
export class VersolaUserMoves extends LitElement {
  /** The registered cells, for the cell filter. */
  @property({ attribute: false }) cells: Cell[] = [];

  @state() private moves: UserMove[] = [];
  @state() private phase: PhaseFilter = 'all';
  @state() private cell = '';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  private loadSeq = 0;

  static styles = [
    theme,
    buttonStyles,
    chipStyles,
    bannerStyles,
    formStyles,
    css`
      :host {
        display: block;
        margin-top: var(--spacing-xl);
      }

      h2 {
        margin: 0 0 var(--spacing-xs);
        font-size: var(--text-lg);
      }

      .sub {
        margin: 0 0 var(--spacing-md);
        color: var(--text-secondary);
      }

      .toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-md);
        align-items: center;
        margin-bottom: var(--spacing-lg);
      }

      .toolbar select {
        width: auto;
        min-width: 10rem;
      }

      .mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
      }

      .muted {
        color: var(--text-secondary);
      }

      .note {
        display: block;
        margin-top: var(--spacing-xs);
        color: var(--text-secondary);
        font-size: var(--text-sm);
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.load();
  }

  private async load() {
    const seq = ++this.loadSeq;
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const moves = await fetchUserMoves({ limit: 100, phase: this.phase === 'all' ? undefined : this.phase, cell: this.cell || undefined });
      if (seq === this.loadSeq) this.moves = moves;
    } catch (error) {
      if (seq !== this.loadSeq) return;
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load moves';
      this.moves = [];
    } finally {
      if (seq === this.loadSeq) this.isLoading = false;
    }
  }

  private phaseChip(move: UserMove) {
    switch (move.phase) {
      case 'done': return html`<span class="chip ok">${statusIcon('check', 'icon', 14)}Done</span>`;
      case 'failed': return html`<span class="chip danger">Failed</span>`;
      case 'flipped': return html`<span class="chip warn">Flipped</span>`;
      default: return html`<span class="chip">${phaseLabel(move.phase)}</span>`;
    }
  }

  private renderTable() {
    const zone = localZone();
    const columns: DataTableColumn<UserMove>[] = [
      { id: 'user', label: 'User', cell: move => html`<span class="row-id" title=${move.userId}>${move.userId}</span>` },
      { id: 'route', label: 'From → to', cell: move => html`${move.from} → ${move.to}` },
      { id: 'phase', label: 'Phase', cell: move => html`<div class="chips">${this.phaseChip(move)}</div>
        ${move.phase === 'flipped' ? html`<div class="muted">The old cell does not serve this user until the move completes.</div>` : nothing}
        ${move.error ? html`<div class="muted">${errorLabel(move.error)}${move.attempts > 0 ? ` (${move.attempts} failed attempt${move.attempts === 1 ? '' : 's'})` : ''}</div>` : nothing}` },
      { id: 'reason', label: 'Reason', class: 'muted', cell: move => reasonLabel(move.reason) },
      { id: 'started', label: 'Started', class: 'muted', cell: move => formatDateTime(move.startedAt, zone) },
      { id: 'updated', label: 'Updated', class: 'muted', cell: move => formatDateTime(move.updatedAt, zone) },
    ];
    return html`
      <versola-data-table
        .columns=${columns}
        .rows=${this.moves}
        .rowKey=${(move: UserMove) => move.userId}
      ></versola-data-table>`;
  }

  render() {
    const options = [{ id: 'all', label: 'All' }, ...MOVE_PHASES.map(phase => ({ id: phase.id, label: phase.label }))];
    const flipped = this.moves.filter(move => move.phase === 'flipped');
    return html`
      <section aria-labelledby="moves-title">
        <h2 id="moves-title">User moves</h2>
        <p class="sub">The latest move of each user between cells, newest first. A finished or failed move is replaced by the next one of the user.</p>
        <div class="toolbar">
          <versola-filter-bar label="Filter moves by phase" .options=${options} .value=${this.phase}
            @change=${(e: CustomEvent<{ id: string }>) => { this.phase = e.detail.id as PhaseFilter; void this.load(); }}></versola-filter-bar>
          <select aria-label="Filter moves by cell" .value=${this.cell}
            @change=${(e: Event) => { this.cell = (e.target as HTMLSelectElement).value; void this.load(); }}>
            <option value="" ?selected=${this.cell === ''}>Any cell</option>
            ${this.cells.map(cell => html`<option value=${cell.id} ?selected=${cell.id === this.cell}>${cell.id}</option>`)}
          </select>
          <button type="button" class="btn btn-secondary" ?disabled=${this.isLoading} @click=${() => this.load()}>Refresh</button>
        </div>
        ${flipped.length ? html`<div class="banner warn" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}${flipped.length} move${flipped.length === 1 ? ' is' : 's are'} flipped but not complete</div>
          <div>The directory names the new cell, so the move is never rolled back, but the old cell does not serve these users until it completes. Central retries; one that stays here needs attention.</div></div></div>` : nothing}
        ${this.isLoading ? html`<versola-loading-cards .count=${1}></versola-loading-cards>`
          : this.errorMessage ? html`<versola-error-card heading="Could not load moves" .message=${this.errorMessage} @retry=${() => this.load()}></versola-error-card>`
          : this.moves.length === 0 ? html`<versola-empty-state heading="No moves" text="No user has been moved between cells${this.phase === 'all' && !this.cell ? '' : ' with this filter'}."></versola-empty-state>`
          : this.renderTable()}
      </section>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-user-moves': VersolaUserMoves;
  }
}
