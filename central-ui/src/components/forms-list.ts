import { LitElement, html, css, nothing, type PropertyValues } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, chipStyles, formStyles } from '../styles/components';
import type { FormPreview, FormRecord, Locale, ThemeRecord } from '../types';
import { fetchFormPreviews, fetchForms, fetchLocales, fetchThemes } from '../utils/central-api';
import { isBuiltInForm, localeSummary, namesWithOverflow } from '../utils/form-texts';
import { copyToClipboard } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { valueChips } from './value-chips';
import type { DataTableColumn, RowMenuItem } from './data-table';
import type { FormSaved } from './form-record';
import './content-header';
import './data-table';
import './empty-state';
import './error-card';
import './filter-bar';
import './form-record';
import './loading-cards';

type FormFilter = 'all' | 'built-in' | 'custom' | 'missing';
type FormRow = { id: string; versions: FormRecord[]; active?: FormRecord; latest: FormRecord; builtIn: boolean; complete: string[]; missing: string[] };

/**
 * Forms: the table of every form (its kind, the languages it is complete in or missing, the active
 * version and whether a newer one is stored), the record page of the one opened
 * (`?view=forms&id=<id>`, `&edit=<tab>`) and the create form (`&new=1`). The record is the editor;
 * this component loads what it shares (versions, languages, themes, the built-in forms' states).
 */
@customElement('versola-forms-list')
export class VersolaFormsList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private forms: FormRecord[] = [];
  @state() private locales: Locale[] = [];
  @state() private themes: ThemeRecord[] = [];
  /** The states central lists for the built-in forms; empty from a central that does not list them. */
  @state() private previews: FormPreview[] = [];
  @state() private searchQuery = '';
  @state() private filter: FormFilter = 'all';
  @state() private loading = false;
  @state() private error = '';
  @state() private saved: { id: string; version: number } | null = null;

  private loadSeq = 0;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
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
    void this.loadData(false);
  }

  updated(changed: PropertyValues) {
    if (changed.has('tenantId') && changed.get('tenantId') !== undefined) void this.loadData(false);
    if (changed.has('route')) {
      const previous = changed.get('route') as RecordRoute | undefined;
      if (previous && previous.id !== this.route.id) this.saved = null;
    }
  }

  /** `quiet` reloads after a save without swapping the page for the loading cards. */
  private async loadData(quiet: boolean) {
    const seq = ++this.loadSeq;
    if (!quiet) this.loading = true;
    this.error = '';
    try {
      const [forms, locales, themes, previews] = await Promise.all([
        fetchForms(),
        fetchLocales(),
        this.tenantId ? fetchThemes(this.tenantId) : Promise.resolve([]),
        fetchFormPreviews(),
      ]);
      if (seq !== this.loadSeq) return;
      this.forms = forms.sort((a, b) => a.id.localeCompare(b.id) || b.version - a.version);
      this.locales = locales;
      this.themes = themes;
      this.previews = previews;
    } catch (error) {
      if (seq !== this.loadSeq) return;
      this.error = error instanceof Error ? error.message : 'Failed to load forms';
    } finally {
      if (seq === this.loadSeq) this.loading = false;
    }
  }

  // ── data ────────────────────────────────────────────────────────────────

  private get rows(): FormRow[] {
    const groups = new Map<string, FormRecord[]>();
    for (const form of this.forms) groups.set(form.id, [...(groups.get(form.id) ?? []), form]);
    return [...groups.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([id, versions]) => {
        const latest = versions[0];
        const active = versions.find(version => version.active);
        // What users see is the active version's languages; a form with none is judged by its newest.
        const { complete, missing } = localeSummary((active ?? latest).localizations, this.locales);
        return { id, versions, active, latest, builtIn: isBuiltInForm(id, this.previews), complete, missing };
      });
  }

  private get filteredRows(): FormRow[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.rows.filter(row => {
      if (query && !row.id.toLowerCase().includes(query)) return false;
      switch (this.filter) {
        case 'built-in': return row.builtIn;
        case 'custom': return !row.builtIn;
        case 'missing': return row.missing.length > 0;
        default: return true;
      }
    });
  }

  private get openVersions(): FormRecord[] {
    return this.route.id ? this.forms.filter(form => form.id === this.route.id) : [];
  }

  private open(row: FormRow) {
    dispatchRecordRoute(this, { id: row.id, edit: null, create: false });
  }

  private async handleSaved(e: CustomEvent<FormSaved>) {
    const { id } = e.detail;
    await this.loadData(true);
    const version = Math.max(0, ...this.forms.filter(form => form.id === id).map(form => form.version));
    this.saved = { id, version };
    if (this.route.create) dispatchRecordRoute(this, { id, create: false, edit: 'versions' });
  }

  // ── rendering ───────────────────────────────────────────────────────────

  private renderToolbar() {
    const options: Array<{ id: FormFilter; label: string }> = [
      { id: 'all', label: 'All' },
      { id: 'built-in', label: 'Built-in' },
      { id: 'custom', label: 'Custom' },
      { id: 'missing', label: 'Missing translations' },
    ];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search forms" placeholder="Search forms by ID"
          .value=${this.searchQuery} @input=${(e: Event) => { this.searchQuery = (e.target as HTMLInputElement).value; }} />
        <versola-filter-bar label="Filter forms" .options=${options} .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => { this.filter = e.detail.id as FormFilter; }}></versola-filter-bar>
      </div>`;
  }

  private renderTable() {
    const columns: DataTableColumn<FormRow>[] = [
      { id: 'form', label: 'Form', cell: row => html`
        <button type="button" class="row-link" aria-label=${`Open form ${row.id}`}><span class="row-title mono">${row.id}</span></button>` },
      { id: 'kind', label: 'Kind', cell: row => html`<span class="chip ${row.builtIn ? '' : 'value'}">${row.builtIn ? 'Built-in' : 'Custom'}</span>` },
      { id: 'locales', label: 'Locales', cell: row => row.complete.length === 0 && row.missing.length === 0 ? html`<span class="muted">—</span>`
        : valueChips(row.complete, { mono: false, extra: row.missing.length ? html`<span class="chip warn">Missing: ${namesWithOverflow(row.missing)}</span>` : nothing }) },
      { id: 'active', label: 'Active version', cell: row => {
        const pending = row.active ? row.latest.version > row.active.version : true;
        return html`<div class="cell-lines">
          ${row.active ? html`<span>v${row.active.version}</span>` : html`<span class="chip warn">No active version</span>`}
          ${row.active && pending ? html`<span class="chip warn">v${row.latest.version} not active</span>` : nothing}
        </div>`;
      } },
    ];
    const menuItems = (row: FormRow): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.open(row) },
      { label: 'Copy ID', ariaLabel: `Copy form ID ${row.id}`, onSelect: () => void copyToClipboard(row.id) },
    ];
    return html`
      <versola-data-table
        .columns=${columns}
        .rows=${this.filteredRows}
        .rowKey=${(row: FormRow) => row.id}
        .menuItems=${menuItems}
        .menuLabel=${(row: FormRow) => `Actions for ${row.id}`}
        selectable
        @row-select=${(e: CustomEvent<{ row: FormRow }>) => this.open(e.detail.row)}
      ></versola-data-table>`;
  }

  private renderList() {
    const create = () => dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null });
    return html`
      <content-header title="Forms" description="The screens users see while signing in. Built-in forms ship with Versola; custom ones are yours.">
        ${this.canManage && !this.loading && !this.error ? html`<button slot="actions" class="btn btn-primary" @click=${create}>+ Create form</button>` : nothing}
      </content-header>

      ${this.loading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
      : this.error ? html`<versola-error-card heading="Could not load forms" .message=${this.error} @retry=${() => this.loadData(false)}></versola-error-card>`
      : this.forms.length === 0 ? html`
        <versola-empty-state heading="No forms yet" text=${this.canManage ? 'Create your first form to get started' : 'No forms available.'}>
          ${this.canManage ? html`<button class="btn btn-primary" @click=${create}>+ Create form</button>` : nothing}
        </versola-empty-state>`
      : html`
        ${this.renderToolbar()}
        ${this.filteredRows.length === 0
          ? html`<versola-empty-state heading="No forms match" text="Adjust the search or the filter."></versola-empty-state>`
          : this.renderTable()}`}`;
  }

  private renderRecord(isNew: boolean) {
    const versions = isNew ? [] : this.openVersions;
    return html`
      <versola-form-record
        .versions=${versions}
        .isNew=${isNew}
        .canManage=${this.canManage}
        .tab=${this.route.edit}
        .locales=${this.locales}
        .themes=${this.themes}
        .previews=${this.previews}
        .saved=${this.saved}
        @form-saved=${(e: CustomEvent<FormSaved>) => void this.handleSaved(e)}
        @forms-changed=${() => void this.loadData(true)}
      ></versola-form-record>`;
  }

  render() {
    if (this.route.create && this.canManage) {
      return this.loading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>` : this.renderRecord(true);
    }
    if (this.route.id) {
      if (this.loading) return html`<versola-loading-cards .count=${3}></versola-loading-cards>`;
      if (this.error) return html`<versola-error-card heading="Could not load forms" .message=${this.error} @retry=${() => this.loadData(false)}></versola-error-card>`;
      if (this.openVersions.length === 0) {
        return html`
          <versola-empty-state heading="Form not found" text="No form has this ID. It may never have been stored.">
            <button type="button" class="btn btn-secondary" @click=${() => dispatchRecordRoute(this, { id: null, edit: null })}>Back to forms</button>
          </versola-empty-state>`;
      }
      return this.renderRecord(false);
    }
    return this.renderList();
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-forms-list': VersolaFormsList;
  }
}
