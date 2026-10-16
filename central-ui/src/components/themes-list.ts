import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles, iconActionStyles } from '../styles/components';
import type { FormPreview, FormRecord, Locale, ThemePreset, ThemeRecord } from '../types';
import {
  fetchFormPreviews,
  fetchForms,
  fetchLocales,
  fetchThemePresets,
  fetchThemes,
} from '../utils/central-api';
import './content-header';
import './error-card';
import './loading-cards';
import './theme-edit';

@customElement('versola-themes-list')
export class VersolaThemesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;

  @state() private themes: ThemeRecord[] = [];
  // The built-in themes a theme can be based on; empty from a central that has none.
  @state() private presets: ThemePreset[] = [];
  // What the editor's preview renders: the stored forms, the states central lists for them, the languages.
  @state() private forms: FormRecord[] = [];
  @state() private previews: FormPreview[] = [];
  @state() private locales: Locale[] = [];

  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private editingTheme: ThemeRecord | null = null;

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId') && changed.get('tenantId') !== undefined) void this.loadData();
  }

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    iconActionStyles,
    css`
      :host { display: block; }
      .group { margin-bottom: var(--spacing-lg); }
      .group-title { font-size: 0.8125rem; font-weight: 600; color: var(--text-secondary); margin: 0 0 var(--spacing-sm); }
      .theme-card {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
        background: var(--surface);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-lg);
        padding: var(--spacing-md) var(--spacing-lg);
        margin-bottom: var(--spacing-sm);
        transition: border-color var(--transition-base);
      }
      .theme-card:hover { border-color: var(--accent); }
      .theme-id { font-family: var(--font-mono); font-size: 1.0625rem; font-weight: 600; color: var(--accent); }
      .theme-kind { font-size: 0.6875rem; font-weight: 600; color: var(--text-secondary); border: 1px solid var(--border-default); border-radius: 999px; padding: 0.0625rem 0.5rem; }
      .swatch { width: 1rem; height: 1rem; border-radius: 50%; border: 1px solid var(--border-default); flex: none; }
      .theme-card .icon-action { margin-left: auto; }
      .empty-state { text-align: center; padding: 3rem; color: var(--text-secondary); }
    `,
  ];

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [themes, presets, forms, previews, locales] = await Promise.all([
        this.tenantId ? fetchThemes(this.tenantId) : Promise.resolve([]),
        fetchThemePresets(),
        fetchForms(),
        fetchFormPreviews(),
        fetchLocales(),
      ]);
      this.themes = themes;
      this.presets = presets;
      this.forms = forms;
      this.previews = previews;
      this.locales = locales;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load themes';
    } finally {
      this.isLoading = false;
    }
  }

  private handleSubmit(e: CustomEvent<{ theme: ThemeRecord }>) {
    const updated = e.detail.theme;
    this.themes = this.themes.some(t => t.id === updated.id)
      ? this.themes.map(t => (t.id === updated.id ? updated : t))
      : [...this.themes, updated];
    this.editingTheme = null;
  }

  private handleDelete(e: CustomEvent<{ id: string }>) {
    this.themes = this.themes.filter(t => t.id !== e.detail.id);
    this.editingTheme = null;
  }

  /** The brand colour a visual theme ends up with: its own override, else its base's. */
  private brandOf(t: ThemeRecord): string | undefined {
    if (!t.tokens) return undefined;
    return t.tokens.overrides?.brand ?? this.presets.find(p => p.id === t.tokens!.base)?.tokens.brand;
  }

  private renderRow(t: ThemeRecord) {
    const overrides = t.tokens?.overrides ? Object.keys(t.tokens.overrides).length : 0;
    const brand = this.brandOf(t);
    return html`
      <div class="theme-card">
        ${brand ? html`<span class="swatch" style=${`background:${brand}`}></span>` : nothing}
        <span class="theme-id">${t.id}</span>
        ${t.tokens
          ? html`<span class="theme-kind" title="Built from a base theme and overrides">${t.tokens.base}${overrides > 0 ? ` +${overrides}` : ''}</span>`
          : html`<span class="theme-kind" title="A stylesheet">CSS</span>`}
        <button class="icon-action" title="Edit theme" aria-label=${`Edit theme ${t.id}`}
          @click=${() => { this.editingTheme = { ...t }; }}>✎</button>
      </div>
    `;
  }

  render() {
    if (this.editingTheme !== null) {
      return html`
        <versola-theme-edit
          .theme=${this.editingTheme}
          .tenantId=${this.tenantId}
          .presets=${this.presets}
          .forms=${this.forms}
          .previews=${this.previews}
          .locales=${this.locales}
          @cancel=${() => { this.editingTheme = null; }}
          @submit=${(e: CustomEvent<{ theme: ThemeRecord }>) => this.handleSubmit(e)}
          @delete=${(e: CustomEvent<{ id: string }>) => this.handleDelete(e)}
        ></versola-theme-edit>
      `;
    }

    const global = this.themes.filter(t => !t.tenantId);
    const tenant = this.themes.filter(t => !!t.tenantId);
    return html`
      <content-header title="Themes" description="How sign-in, verification and consent screens look.">
        ${this.canManage ? html`
          <button slot="actions" class="btn btn-primary" ?disabled=${this.isLoading} @click=${() => { this.editingTheme = { id: '', css: '', tenantId: null }; }}>
            + New Theme
          </button>
        ` : nothing}
      </content-header>

      ${this.isLoading ? html`
        <versola-loading-cards count="2"></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load themes" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.themes.length === 0 ? html`
        <div class="empty-state">No themes configured yet.</div>
      ` : html`
        ${global.length > 0 ? html`
          <div class="group"><div class="group-title">Global</div>${global.map(t => this.renderRow(t))}</div>
        ` : nothing}
        ${tenant.length > 0 ? html`
          <div class="group"><div class="group-title">This tenant</div>${tenant.map(t => this.renderRow(t))}</div>
        ` : nothing}
      `}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-themes-list': VersolaThemesList;
  }
}
