import { LitElement, html, css, nothing, type PropertyValues } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, formStyles, tagStyles } from '../styles/components';
import type { FormPreview, FormRecord, Locale, OAuthClient, ThemePreset, ThemeRecord } from '../types';
import { fetchAllClients, fetchFormPreviews, fetchForms, fetchLocales, fetchThemePresets, fetchThemes } from '../utils/central-api';
import { copyToClipboard } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { clientsUsingTheme, joinNames, resolveTokens, themeDetailText } from '../utils/theme-summary';
import { themeThumb } from './theme-thumb';
import type { ThemeSaved } from './theme-record';
import type { DataTableColumn, RowMenuItem } from './data-table';
import './content-header';
import './data-table';
import './empty-state';
import './error-card';
import './filter-bar';
import './loading-cards';
import './theme-record';

type ThemeFilter = 'all' | 'tenant';

/**
 * Themes: the table of the themes a tenant can use (base and overrides, the look as a thumbnail and the
 * brand colour, the clients that use it; a theme stored with CSS and no values is listed as "Versola + custom CSS"), the theme's own page (`?view=themes&id=<id>`)
 * which is also its editor, and the create form (`&new=1`). "Used by" is computed here from the
 * tenant's clients, which the screen loads: central has no usage endpoint, and a global theme may
 * also be used by clients of other tenants, which the delete (refused by central) reports.
 */
@customElement('versola-themes-list')
export class VersolaThemesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private themes: ThemeRecord[] = [];
  /** The built-in themes a theme can be based on; empty from a central that has none. */
  @state() private presets: ThemePreset[] = [];
  @state() private clients: OAuthClient[] = [];
  /** What the editor's preview renders: the stored forms, the states central lists for them, the languages. */
  @state() private forms: FormRecord[] = [];
  @state() private previews: FormPreview[] = [];
  @state() private locales: Locale[] = [];
  @state() private searchQuery = '';
  @state() private filter: ThemeFilter = 'all';
  @state() private loading = false;
  @state() private error = '';
  @state() private notice = '';
  /** The theme just stored, said once on its page. */
  @state() private justSaved: { id: string; kind: 'created' | 'saved' } | null = null;

  private loadSeq = 0;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
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
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  updated(changed: PropertyValues) {
    if (changed.has('tenantId') && changed.get('tenantId') !== undefined) void this.loadData();
    if (changed.has('route')) {
      const previous = changed.get('route') as RecordRoute | undefined;
      if (previous?.id && previous.id !== this.route.id) this.justSaved = null;
      if (this.route.id || this.route.create) this.notice = '';
    }
  }

  private async loadData() {
    const seq = ++this.loadSeq;
    this.loading = true;
    this.error = '';
    try {
      const tenantId = this.tenantId;
      const [themes, presets, forms, previews, locales, clients] = await Promise.all([
        tenantId ? fetchThemes(tenantId) : Promise.resolve([]),
        fetchThemePresets(),
        fetchForms(),
        fetchFormPreviews(),
        fetchLocales(),
        tenantId ? fetchAllClients(tenantId) : Promise.resolve([]),
      ]);
      if (seq !== this.loadSeq) return;
      this.themes = themes;
      this.presets = presets;
      this.forms = forms;
      this.previews = previews;
      this.locales = locales;
      this.clients = clients;
    } catch (error) {
      if (seq !== this.loadSeq) return;
      this.error = error instanceof Error ? error.message : 'Failed to load themes';
    } finally {
      if (seq === this.loadSeq) this.loading = false;
    }
  }

  // ── data ────────────────────────────────────────────────────────────────

  private usedBy(themeId: string): string[] {
    return clientsUsingTheme(this.clients, themeId);
  }

  private get filteredThemes(): ThemeRecord[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.themes.filter(record => {
      if (query && !record.id.toLowerCase().includes(query)) return false;
      switch (this.filter) {
        case 'tenant': return !!record.tenantId;
        default: return true;
      }
    });
  }

  private get openTheme(): ThemeRecord | null {
    return this.route.id ? this.themes.find(record => record.id === this.route.id) ?? null : null;
  }

  private open(record: ThemeRecord) {
    dispatchRecordRoute(this, { id: record.id, edit: null, create: false });
  }

  private handleSaved(e: CustomEvent<ThemeSaved>) {
    const { theme: saved, created } = e.detail;
    this.themes = this.themes.some(record => record.id === saved.id)
      ? this.themes.map(record => (record.id === saved.id ? saved : record))
      : [...this.themes, saved];
    this.justSaved = { id: saved.id, kind: created ? 'created' : 'saved' };
    if (created) dispatchRecordRoute(this, { id: saved.id, create: false, edit: null });
  }

  private handleDeleted(id: string) {
    this.themes = this.themes.filter(record => record.id !== id);
    this.notice = `Deleted theme ${id}.`;
    dispatchRecordRoute(this, { id: null, edit: null, create: false });
  }

  // ── rendering ───────────────────────────────────────────────────────────

  private renderToolbar() {
    const options: Array<{ id: ThemeFilter; label: string }> = [
      { id: 'all', label: 'All' },
      { id: 'tenant', label: 'This tenant' },
    ];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search themes" placeholder="Search themes by ID"
          .value=${this.searchQuery} @input=${(e: Event) => { this.searchQuery = (e.target as HTMLInputElement).value; }} />
        <versola-filter-bar label="Filter themes" .options=${options} .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => { this.filter = e.detail.id as ThemeFilter; }}></versola-filter-bar>
      </div>`;
  }

  private renderTable() {
    const columns: DataTableColumn<ThemeRecord>[] = [
      { id: 'theme', label: 'Theme', cell: record => html`
        <button type="button" class="row-link" aria-label=${`Open theme ${record.id}`}><span class="row-title mono">${record.id}</span></button>
        <div class="muted">${record.tenantId ? 'This tenant' : 'Global'}</div>` },
      { id: 'base', label: 'Base', cell: record => {
        if (!record.tokens) return html`<div class="cell-lines"><strong>Versola + custom CSS</strong><span class="muted">No values saved yet</span></div>`;
        const base = this.presets.find(preset => preset.id === record.tokens!.base);
        return html`<div class="cell-lines"><strong>${base?.title.en ?? record.tokens.base}</strong><span class="muted">${themeDetailText(record)}</span></div>`;
      } },
      { id: 'look', label: 'Look', cell: record => {
        const tokens = resolveTokens(record, this.presets);
        if (!tokens) return html`<span class="muted">${record.tokens ? 'Base theme not found' : 'Set by its CSS'}</span>`;
        return html`<span style="display:inline-flex;align-items:center;gap:var(--spacing-sm)">${themeThumb(tokens, { small: true })}<span style=${`width:0.75rem;height:0.75rem;border-radius:50%;flex:none;background:${tokens.brand}`}></span><code style="font-family:var(--font-mono);font-size:var(--text-sm)">${tokens.brand}</code></span>`;
      } },
      { id: 'used', label: 'Used by', cell: record => {
        const users = this.usedBy(record.id);
        if (users.length === 0) return html`<span class="muted">No clients</span>`;
        const rest = users.slice(2);
        return html`<div class="tag-list">${users.slice(0, 2).map(id => html`<span class="tag" title=${id}>${id}</span>`)}${rest.length ? html`<span class="chip more" title=${rest.join(', ')}>+${rest.length}</span>` : nothing}</div>`;
      } },
    ];
    const menuItems = (record: ThemeRecord): RowMenuItem[] => {
      const users = this.usedBy(record.id);
      const reason = record.id === 'default' ? 'The default theme cannot be deleted' : users.length ? `Used by ${joinNames(users)}` : '';
      return [
        { label: 'Open', onSelect: () => this.open(record) },
        { label: 'Copy ID', ariaLabel: `Copy theme ID ${record.id}`, onSelect: () => void copyToClipboard(record.id) },
        ...(this.canManage ? [{ label: 'Delete theme', danger: true, ariaLabel: `Delete theme ${record.id}`, disabledReason: reason || undefined, onSelect: () => this.open(record) }] : []),
      ];
    };
    return html`
      <versola-data-table
        .columns=${columns}
        .rows=${this.filteredThemes}
        .rowKey=${(record: ThemeRecord) => record.id}
        .menuItems=${menuItems}
        .menuLabel=${(record: ThemeRecord) => `Actions for ${record.id}`}
        selectable
        @row-select=${(e: CustomEvent<{ row: ThemeRecord }>) => this.open(e.detail.row)}
      ></versola-data-table>`;
  }

  private renderList() {
    const create = () => dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null });
    return html`
      <content-header title="Themes" description="How sign-in, verification and consent screens look. A client picks one theme.">
        ${this.canManage && !this.loading && !this.error ? html`<button slot="actions" class="btn btn-primary" @click=${create}>+ Create theme</button>` : nothing}
      </content-header>

      ${this.notice ? html`
        <div class="banner ok" role="status">
          <div class="banner-text">${this.notice}</div>
          <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.notice = ''; }}>Dismiss</button>
        </div>` : nothing}

      ${this.loading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
      : this.error ? html`<versola-error-card heading="Could not load themes" .message=${this.error} @retry=${() => this.loadData()}></versola-error-card>`
      : this.themes.length === 0 ? html`
        <versola-empty-state heading="No themes yet" text=${this.canManage ? 'Create your first theme to get started' : 'No themes available.'}>
          ${this.canManage ? html`<button class="btn btn-primary" @click=${create}>+ Create theme</button>` : nothing}
        </versola-empty-state>`
      : html`
        ${this.renderToolbar()}
        ${this.filteredThemes.length === 0
          ? html`<versola-empty-state heading="No themes match" text="Adjust the search or the filter."></versola-empty-state>`
          : this.renderTable()}`}`;
  }

  private renderRecord(record: ThemeRecord, isNew: boolean) {
    return html`
      <versola-theme-record
        .theme=${record}
        .isNew=${isNew}
        .canManage=${this.canManage}
        .tenantId=${this.tenantId}
        .presets=${this.presets}
        .forms=${this.forms}
        .previews=${this.previews}
        .locales=${this.locales}
        .usedBy=${isNew ? [] : this.usedBy(record.id)}
        .notice=${!isNew && this.justSaved?.id === record.id ? this.justSaved.kind : ''}
        @theme-saved=${(e: CustomEvent<ThemeSaved>) => this.handleSaved(e)}
        @theme-deleted=${(e: CustomEvent<{ id: string }>) => this.handleDeleted(e.detail.id)}
      ></versola-theme-record>`;
  }

  private readonly blankTheme: ThemeRecord = { id: '', css: '', tenantId: null };

  render() {
    if (!this.tenantId) return html`<div class="card"><div style="padding: var(--spacing-lg); color: var(--text-secondary)">Select a tenant to manage themes.</div></div>`;
    if (this.route.create && this.canManage) {
      return this.loading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>` : this.renderRecord(this.blankTheme, true);
    }
    if (this.route.id) {
      if (this.loading) return html`<versola-loading-cards .count=${3}></versola-loading-cards>`;
      if (this.error) return html`<versola-error-card heading="Could not load theme" .message=${this.error} @retry=${() => this.loadData()}></versola-error-card>`;
      const record = this.openTheme;
      if (!record) {
        return html`
          <versola-empty-state heading="Theme not found" text="No theme has this ID for this tenant. It may have been deleted.">
            <button type="button" class="btn btn-secondary" @click=${() => dispatchRecordRoute(this, { id: null, edit: null })}>Back to themes</button>
          </versola-empty-state>`;
      }
      return this.renderRecord(record, false);
    }
    return this.renderList();
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-themes-list': VersolaThemesList;
  }
}
