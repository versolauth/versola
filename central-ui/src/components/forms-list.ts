import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles, iconActionStyles } from '../styles/components';
import type { BackendProperty, Locale, FormRecord, ThemeRecord } from '../types';
import {
  fetchForms,
  fetchLocales,
  fetchThemes,
  setActiveFormVersion,
} from '../utils/central-api';
import { buildPreviewSrcdoc } from '../utils/preview';
import { humanizeLabel, toggleAnyOf, exclusiveAnyOfValues } from '../utils/helpers';
import './content-header';
import './error-card';
import './loading-cards';
import './code-editor';
import './form-edit';
import './theme-edit';

@customElement('versola-forms-list')
export class VersolaFormsList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;

  @state() private forms: FormRecord[] = [];
  @state() private locales: Locale[] = [];
  @state() private themes: ThemeRecord[] = [];

  @state() private isLoading = false;
  @state() private errorMessage = '';

  @state() private editingForm: FormRecord | null = null;
  @state() private expandedForms: Set<string> = new Set();
  @state() private expandedVersions: Set<string> = new Set();
  @state() private previewOpen: Set<string> = new Set();
  @state() private previewLocale: Record<string, string> = {};
  @state() private previewProps: Record<string, Record<string, string | boolean | number | string[]>> = {};
  @state() private previewWidth: Record<string, number> = {};
  @state() private previewTheme: Record<string, string> = {};

  private static readonly WIDTH_PRESETS = [
    { label: 'Mobile', width: 390 },
    { label: 'Desktop', width: 1280 },
  ];

  @state() private formsExpanded = true;
  @state() private themesExpanded = false;

  @state() private editingTheme: ThemeRecord | null = null;

  private readonly handleIframeMessage = (e: MessageEvent) => {
    if (
      e.data?.type === 'versola:locale-change' &&
      typeof e.data.locale === 'string' &&
      typeof e.data.previewId === 'string'
    ) {
      this.previewLocale = { ...this.previewLocale, [e.data.previewId]: e.data.locale };
    }
  };

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('message', this.handleIframeMessage);
    void this.loadData();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    window.removeEventListener('message', this.handleIframeMessage);
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId')) {
      void this.loadThemes();
    }
  }

  private async loadThemes() {
    if (!this.tenantId) {
      this.themes = [];
      return;
    }
    try {
      this.themes = await fetchThemes(this.tenantId);
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load themes';
    }
  }

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    iconActionStyles,
    css`
      :host { display: block; }

      .form-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        margin-bottom: var(--spacing-md);
        transition: border-color var(--transition-base);
      }
      .form-card:hover { border-color: var(--accent); }

      .form-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-lg);
        cursor: pointer;
        user-select: none;
      }
      .form-id {
        font-family: var(--font-mono);
        font-size: 1.125rem;
        font-weight: 600;
        color: var(--accent);
      }
      .form-body { border-top: 1px solid var(--border-dark); padding: var(--spacing-lg); }

      .form-title {
        font-size: 1.625rem;
        font-weight: 700;
        letter-spacing: -0.025em;
        color: var(--text-primary);
        margin: 0;
      }

      .field-label {
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--text-secondary);
        margin-bottom: 0.5rem;
      }
      .form-actions {
        display: flex;
        align-items: center;
        gap: 1rem;
        justify-content: flex-end;
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-dark);
      }
      .form-actions-row {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
      }
      .save-msg { font-size: 0.875rem; }
      .save-msg.success { color: var(--success, #3fb950); }
      .save-msg.error { color: var(--danger); }

      .hint { font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 0.5rem; }
      .section-gap { margin-top: var(--spacing-xl); }
      .preview-iframe {
        width: 100%;
        height: 100%;
        border: none;
        background: #fff;
        display: block;
      }

      /* Controls toolbar */
      .controls-toolbar {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: var(--spacing-xl);
        padding: var(--spacing-md) var(--spacing-lg);
      }
      .ctrl-group {
        display: inline-flex;
        flex-direction: column;
        gap: 0.25rem;
      }
      .ctrl-label {
        font-size: 0.625rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: var(--text-secondary);
      }


      /* Toggle switch */
      .toggle-wrap {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        height: 28px;
      }
      .toggle {
        position: relative;
        display: inline-block;
        width: 34px;
        height: 18px;
        flex-shrink: 0;
        cursor: pointer;
      }
      .toggle input {
        opacity: 0;
        position: absolute;
        width: 0;
        height: 0;
      }
      .toggle::before {
        content: '';
        position: absolute;
        inset: 0;
        background: rgba(255,255,255,0.12);
        border: 1px solid var(--border-dark);
        border-radius: 9999px;
        transition: background 0.2s, border-color 0.2s;
      }
      .toggle:has(input:checked)::before {
        background: var(--accent);
        border-color: var(--accent);
      }
      .toggle::after {
        content: '';
        position: absolute;
        top: 2px;
        left: 2px;
        width: 14px;
        height: 14px;
        background: rgba(255,255,255,0.5);
        border-radius: 50%;
        transition: transform 0.18s, background 0.18s;
      }
      .toggle:has(input:checked)::after {
        transform: translateX(16px);
        background: #fff;
      }
      .toggle-val {
        font-size: 0.8125rem;
        color: var(--text-secondary);
        min-width: 2rem;
        transition: color var(--transition-fast);
      }
      .toggle:has(input:checked) + .toggle-val { color: var(--text-primary); }

      /* Segmented control for string arrays */
      .seg-control {
        display: inline-flex;
        height: 28px;
      }
      .seg-btn {
        padding: 0 0.875rem;
        background: transparent;
        border: 1px solid var(--border-dark);
        border-left-width: 0;
        color: var(--text-secondary);
        font-size: 0.8125rem;
        font-weight: 500;
        cursor: pointer;
        white-space: nowrap;
        transition: background 0.15s, color 0.15s;
        border-radius: 0;
      }
      .seg-btn:first-child {
        border-left-width: 1px;
        border-radius: var(--radius-md) 0 0 var(--radius-md);
      }
      .seg-btn:last-child {
        border-radius: 0 var(--radius-md) var(--radius-md) 0;
      }
      .seg-btn:hover:not(.seg-active) {
        background: rgba(255,255,255,0.05);
        color: var(--text-primary);
      }
      .seg-btn:disabled {
        cursor: default;
      }
      .seg-btn.seg-active {
        background: var(--accent);
        border-color: var(--accent);
        color: #fff;
        font-weight: 600;
      }

      /* Preview size presets */
      .preview-sizes {
        display: flex;
        gap: var(--spacing-sm);
        margin-bottom: var(--spacing-md);
      }
      .size-btn {
        padding: 0.25rem 0.75rem;
        background: transparent;
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        color: var(--text-secondary);
        font-size: 0.75rem;
        font-weight: 500;
        cursor: pointer;
        transition: all var(--transition-fast);
      }
      .size-btn:hover { color: var(--text-primary); border-color: var(--accent); }
      .size-btn.active {
        background: rgba(88, 166, 255, 0.1);
        border-color: var(--accent);
        color: var(--accent);
      }
      .preview-resizer {
        resize: horizontal;
        overflow: hidden;
        box-sizing: border-box;
        height: 600px;
        max-width: 100%;
        margin: 0 auto;
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        background: #fff;
      }
      .version-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        margin-bottom: var(--spacing-sm);
        transition: border-color var(--transition-fast);
      }
      .version-card:hover {
        border-color: var(--accent);
        cursor: pointer;
      }
      .version-header {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
        padding: var(--spacing-md) var(--spacing-lg);
        user-select: none;
      }
      .version-badge {
        font-family: var(--font-mono);
        font-weight: 600;
        color: var(--text-primary);
        font-size: 0.9375rem;
      }
      .active-bubble {
        display: inline-flex;
        align-items: center;
        padding: 0.125rem 0.625rem;
        border-radius: 9999px;
        background: rgba(63, 185, 80, 0.15);
        border: 1px solid rgba(63, 185, 80, 0.4);
        color: var(--success, #3fb950);
        font-size: 0.6875rem;
        font-weight: 600;
        letter-spacing: 0.04em;
        text-transform: uppercase;
      }
      .version-actions {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        margin-left: auto;
      }
      .version-body {
        border-top: 1px solid var(--border-dark);
      }
      .preview-bar {
        display: flex;
        justify-content: flex-start;
        padding: var(--spacing-md) var(--spacing-lg);
        border-top: 1px solid var(--border-dark);
      }
      .preview-bar .preview-toggle-btn {
        padding: 0 0.875rem;
        height: 28px;
        background: var(--accent);
        border: 1px solid var(--accent);
        border-radius: var(--radius-md);
        color: #fff;
        font-size: 0.8125rem;
        font-weight: 600;
        cursor: pointer;
        transition: opacity var(--transition-fast);
      }
      .preview-bar .preview-toggle-btn:hover { opacity: 0.85; }
      .preview-area {
        padding: var(--spacing-md) var(--spacing-lg) var(--spacing-lg);
        border-top: 1px solid var(--border-dark);
      }
      .btn-active-set {
        font-size: 0.75rem;
        padding: 0.25rem 0.625rem;
      }
      .kv-grid {
        display: grid;
        grid-template-columns: minmax(120px, 200px) 1fr;
        gap: 0.375rem 0.75rem;
        align-items: center;
      }
      .kv-key {
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--text-secondary);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .kv-value {
        font-size: 0.8125rem;
        color: var(--text-primary);
        background: rgba(0,0,0,0.2);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-sm);
        padding: 0.25rem 0.5rem;
      }
      .kv-value[type="number"]::-webkit-inner-spin-button,
      .kv-value[type="number"]::-webkit-outer-spin-button {
        -webkit-appearance: none;
        margin: 0;
      }
      .kv-value[type="number"] {
        -moz-appearance: textfield;
        appearance: textfield;
      }

      .new-theme-card {
        background: var(--bg-dark-card);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        padding: var(--spacing-lg);
        margin-bottom: var(--spacing-lg);
      }
      .new-theme-card .panel-title {
        font-size: 0.9375rem;
        font-weight: 600;
        color: var(--text-primary);
        margin: 0 0 var(--spacing-md);
      }
      .form-control {
        width: 100%;
        padding: 0.5rem var(--spacing-md);
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-family: var(--font-family);
        font-size: 0.875rem;
        box-sizing: border-box;
        transition: border-color var(--transition-fast);
      }
      .form-control::placeholder { color: var(--text-secondary); }
      .form-control:focus {
        outline: none;
        border-color: var(--accent);
        box-shadow: 0 0 0 2px rgba(88, 166, 255, 0.15);
      }
      .locale-list {
        display: flex;
        flex-direction: column;
      }
      .locale-row {
        display: flex;
        align-items: center;
        gap: var(--spacing-md);
        padding: 0.5rem 0;
        border-bottom: 1px solid var(--border-dark);
      }
      .locale-row:last-child { border-bottom: none; }
      .locale-code {
        font-family: var(--font-mono);
        font-weight: 600;
        color: var(--accent);
        min-width: 4rem;
      }
      .locale-name { color: var(--text-primary); }
      .locale-row .icon-action { margin-left: auto; }

      .empty-state {
        text-align: center;
        padding: 3rem;
        color: var(--text-secondary);
      }
    `,
  ];

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [forms, locales, themes] = await Promise.all([
        fetchForms(),
        fetchLocales(),
        this.tenantId ? fetchThemes(this.tenantId) : Promise.resolve([]),
      ]);
      // Sort by id and version desc
      this.forms = forms.sort((a, b) => a.id.localeCompare(b.id) || b.version - a.version);
      this.locales = locales;
      this.themes = themes;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load forms';
    } finally {
      this.isLoading = false;
    }
  }

  private get groupedForms() {
    const groups = new Map<string, FormRecord[]>();
    for (const f of this.forms) {
      const g = groups.get(f.id) || [];
      g.push(f);
      groups.set(f.id, g);
    }
    return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b));
  }

  private async handleSetActiveVersion(id: string, version: number) {
    try {
      await setActiveFormVersion(id, version);
      this.forms = this.forms.map(f => f.id === id ? { ...f, active: f.version === version } : f);
    } catch (error) {
      console.error('Failed to set active version', error);
    }
  }

  private handleNewVersion(latestForm: FormRecord) {
    this.editingForm = latestForm;
  }

  private handleCreateForm() {
    this.editingForm = { id: '', version: 0, active: false, style: '', jsSource: null, jsCompiled: null, localizations: {}, properties: [] };
  }

  private toggleExpand(formId: string) {
    const next = new Set(this.expandedForms);
    next.has(formId) ? next.delete(formId) : next.add(formId);
    this.expandedForms = next;
  }

  private toggleVersionExpand(key: string) {
    const next = new Set(this.expandedVersions);
    next.has(key) ? next.delete(key) : next.add(key);
    this.expandedVersions = next;
  }

  private togglePreview(key: string) {
    const next = new Set(this.previewOpen);
    next.has(key) ? next.delete(key) : next.add(key);
    this.previewOpen = next;
  }

  private setPreviewProp(key: string, name: string, value: string | boolean | number | string[]) {
    const current = this.previewProps[key] ?? {};
    this.previewProps = { ...this.previewProps, [key]: { ...current, [name]: value } };
  }

  private setPreviewWidth(key: string, width: number) {
    this.previewWidth = { ...this.previewWidth, [key]: width };
  }

  private setPreviewTheme(key: string, themeId: string) {
    this.previewTheme = { ...this.previewTheme, [key]: themeId };
  }

  private effectivePropValue(key: string, prop: BackendProperty): string | boolean | number | string[] {
    const stored = this.previewProps[key]?.[prop.name];
    if (stored !== undefined) return stored;
    if (prop.type === 'StringArrayProperty') return prop.allowedValues;
    if (prop.type === 'NumberProperty') return prop.default;
    return false;
  }

  private buildPreviewSrcdoc(form: FormRecord, key: string): string {
    const themeId = this.previewTheme[key] ?? this.themes.find(t => t.id === 'default')?.id ?? this.themes[0]?.id ?? '';
    return buildPreviewSrcdoc({
      formId: form.id,
      properties: form.properties,
      getValue: p => this.effectivePropValue(key, p),
      localizations: form.localizations,
      locale: this.previewLocale[key] || this.locales[0]?.code || 'en',
      locales: this.locales.map(l => l.code),
      themeCss: this.themes.find(t => t.id === themeId)?.css ?? '',
      style: form.style,
      jsCompiled: form.jsCompiled ?? '',
      previewId: key,
    });
  }

  private renderVersionCard(form: FormRecord) {
    const key = `${form.id}:${form.version}`;
    const isExpanded = this.expandedVersions.has(key);
    const isPreviewOpen = this.previewOpen.has(key);
    const width = this.previewWidth[key] ?? 0;
    const selectedTheme = this.previewTheme[key] ?? this.themes.find(t => t.id === 'default')?.id ?? this.themes[0]?.id ?? '';

    return html`
      <div class="version-card" @click=${() => this.toggleVersionExpand(key)}>
        <div class="version-header">
          <span class="version-badge">v${form.version}</span>
          ${form.active ? html`<span class="active-bubble">Active</span>` : nothing}
          <div class="version-actions" @click=${(e: Event) => e.stopPropagation()}>
            ${!form.active ? html`
              <button class="btn btn-secondary btn-sm btn-active-set"
                @click=${() => this.handleSetActiveVersion(form.id, form.version)}>
                Set Active
              </button>
            ` : nothing}
            <span style="color: var(--text-secondary); pointer-events: none">${isExpanded ? '▲' : '▼'}</span>
          </div>
        </div>

        ${isExpanded ? html`
          <div class="version-body" @click=${(e: Event) => e.stopPropagation()}>
            <div class="controls-toolbar">


              ${this.themes.length > 0 ? html`
                <div class="ctrl-group">
                  <span class="ctrl-label">Theme</span>
                  <div class="seg-control">
                    ${this.themes.map(t => html`
                      <button class="seg-btn ${selectedTheme === t.id ? 'seg-active' : ''}"
                        @click=${() => this.setPreviewTheme(key, t.id)}>${t.id}</button>
                    `)}
                  </div>
                </div>
              ` : nothing}

              ${form.properties.map(prop => {
                const val = this.effectivePropValue(key, prop);
                if (prop.type === 'BooleanProperty') {
                  return html`
                    <div class="ctrl-group">
                      <span class="ctrl-label">${humanizeLabel(prop.name)}</span>
                      <div class="toggle-wrap">
                        <label class="toggle">
                          <input type="checkbox" .checked=${!!val}
                            @change=${(e: Event) => this.setPreviewProp(key, prop.name, (e.target as HTMLInputElement).checked)} />
                        </label>
                        <span class="toggle-val">${val ? 'on' : 'off'}</span>
                      </div>
                    </div>`;
                } else if (prop.type === 'NumberProperty') {
                  return html`
                    <div class="ctrl-group">
                      <span class="ctrl-label">${humanizeLabel(prop.name)}</span>
                      <input type="number" class="kv-value" style="width:5rem" .value=${String(val)}
                        min=${prop.min !== undefined ? prop.min : nothing}
                        max=${prop.max !== undefined ? prop.max : nothing}
                        @change=${(e: Event) => this.setPreviewProp(key, prop.name, Number((e.target as HTMLInputElement).value))} />
                    </div>`;
                } else {
                  const selected = Array.isArray(val) ? val : [];
                  const exclusive = exclusiveAnyOfValues(prop.name);
                  return html`
                    <div class="ctrl-group">
                      <span class="ctrl-label">${humanizeLabel(prop.name)}</span>
                      <div class="seg-control">
                        ${prop.allowedValues.map(v => {
                          const isSelected = selected.includes(v);
                          const isLast = isSelected && selected.length === 1;
                          return html`
                          <button class="seg-btn ${isSelected ? 'seg-active' : ''}" ?disabled=${isLast}
                            @click=${() => { const next = toggleAnyOf(selected, v, exclusive); if (next) this.setPreviewProp(key, prop.name, next); }}>${v}</button>
                        `;
                        })}
                      </div>
                    </div>`;
                }
              })}

            </div>

            <div class="preview-bar">
              <button class="preview-toggle-btn" @click=${() => this.togglePreview(key)}>
                ${isPreviewOpen ? 'Hide preview' : 'Preview'}
              </button>
            </div>

            ${isPreviewOpen ? html`
              <div class="preview-area">
                <div class="preview-sizes">
                  ${VersolaFormsList.WIDTH_PRESETS.map(p => html`
                    <button class="size-btn ${width === p.width ? 'active' : ''}"
                      @click=${() => this.setPreviewWidth(key, p.width)}>${p.label}</button>
                  `)}
                </div>
                <div class="preview-resizer" style=${width ? `width: ${width}px` : 'width: 100%'}>
                  <iframe class="preview-iframe" .srcdoc=${this.buildPreviewSrcdoc(form, key)} sandbox="allow-same-origin allow-scripts"></iframe>
                </div>
              </div>
            ` : nothing}
          </div>
        ` : nothing}
      </div>
    `;
  }

  private handleFormEditCancel() {
    this.editingForm = null;
  }

  private async handleFormEditSubmit(_e: CustomEvent<{ form: FormRecord }>) {
    this.editingForm = null;
    await this.loadData();
  }

  private toggleThemesExpand() {
    this.themesExpanded = !this.themesExpanded;
  }

  private toggleFormsExpand() {
    this.formsExpanded = !this.formsExpanded;
  }

  private handleCreateTheme() {
    this.editingTheme = { id: '', css: '', tenantId: null };
  }

  private handleThemeEdit(t: ThemeRecord) {
    this.editingTheme = { ...t };
  }

  private handleThemeEditCancel() {
    this.editingTheme = null;
  }

  private handleThemeEditSubmit(e: CustomEvent<{ theme: ThemeRecord }>) {
    const updated = e.detail.theme;
    if (this.themes.some(t => t.id === updated.id)) {
      this.themes = this.themes.map(t => t.id === updated.id ? updated : t);
    } else {
      this.themes = [...this.themes, updated];
    }
    this.editingTheme = null;
  }

  private handleThemeDelete(e: CustomEvent<{ id: string }>) {
    this.themes = this.themes.filter(t => t.id !== e.detail.id);
    this.editingTheme = null;
  }

  private renderThemesSection() {
    const globalThemes = this.themes.filter(t => !t.tenantId);
    const tenantThemes = this.themes.filter(t => !!t.tenantId);
    const renderThemeRow = (t: ThemeRecord) => html`
      <div class="locale-row">
        <span class="form-id" style="font-size:0.9375rem">${t.id}</span>
        <span style="margin-left:auto">
          <button class="icon-action" title="Edit theme" aria-label=${`Edit theme ${t.id}`}
            @click=${() => this.handleThemeEdit(t)}>✎</button>
        </span>
      </div>
    `;
    return html`
      <div class="form-card">
        <div class="form-header" @click=${() => this.toggleThemesExpand()}>
          <div class="form-id">Themes</div>
          <div style="display:flex;align-items:center;gap:var(--spacing-sm)" @click=${(e: Event) => e.stopPropagation()}>
            ${this.themesExpanded && this.canManage ? html`
              <button class="icon-action" title="New Theme" aria-label="New Theme"
                @click=${() => this.handleCreateTheme()}>＋</button>
            ` : nothing}
            <span style="color:var(--text-secondary)">${this.themesExpanded ? '▲' : '▼'}</span>
          </div>
        </div>
        ${this.themesExpanded ? html`
          <div class="form-body">
            ${this.themes.length === 0 ? html`<div class="hint">No themes configured yet.</div>` : html`
              <div class="locale-list">
                ${globalThemes.length > 0 ? html`
                  <div class="locale-row" style="font-size:0.8125rem;color:var(--text-secondary);font-weight:600;padding-bottom:0">Global</div>
                  ${globalThemes.map(renderThemeRow)}
                ` : nothing}
                ${tenantThemes.length > 0 ? html`
                  <div class="locale-row" style="font-size:0.8125rem;color:var(--text-secondary);font-weight:600;padding-bottom:0;margin-top:var(--spacing-sm)">This tenant</div>
                  ${tenantThemes.map(renderThemeRow)}
                ` : nothing}
              </div>
            `}
          </div>
        ` : nothing}
      </div>
    `;
  }

  render() {
    if (this.editingForm) {
      return html`
        <versola-form-edit
          .form=${this.editingForm}
          .locales=${this.locales}
          .themes=${this.themes}
          @cancel=${() => this.handleFormEditCancel()}
          @submit=${(e: CustomEvent<{ form: FormRecord }>) => this.handleFormEditSubmit(e)}
        ></versola-form-edit>
      `;
    }

    if (this.editingTheme !== null) {
      return html`
        <versola-theme-edit
          .theme=${this.editingTheme}
          .tenantId=${this.tenantId}
          @cancel=${() => this.handleThemeEditCancel()}
          @submit=${(e: CustomEvent<{ theme: ThemeRecord }>) => this.handleThemeEditSubmit(e)}
          @delete=${(e: CustomEvent<{ id: string }>) => this.handleThemeDelete(e)}
        ></versola-theme-edit>
      `;
    }

    return html`
      <content-header title="Forms"></content-header>

      ${this.isLoading ? html`
        <versola-loading-cards count="2"></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load forms" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : html`
        <div class="form-card">
          <div class="form-header" @click=${() => this.toggleFormsExpand()}>
            <div class="form-id">Forms</div>
            <div style="display:flex;align-items:center;gap:var(--spacing-sm)" @click=${(e: Event) => e.stopPropagation()}>
              ${this.formsExpanded && this.canManage ? html`
                <button class="icon-action" title="New Form" aria-label="New Form"
                  @click=${() => this.handleCreateForm()}>＋</button>
              ` : nothing}
              <span style="color:var(--text-secondary)">${this.formsExpanded ? '▲' : '▼'}</span>
            </div>
          </div>
          ${this.formsExpanded ? html`
          <div class="form-body">
            ${this.forms.length === 0 ? html`<div class="hint">No forms available.</div>` : html`
              ${this.groupedForms.map(([id, versions]) => {
                const isExpanded = this.expandedForms.has(id);
                return html`
                  <div class="form-card">
                    <div class="form-header" @click=${() => this.toggleExpand(id)}>
                      <div class="form-id">${id}</div>
                      <div style="display:flex;align-items:center;gap:var(--spacing-sm)" @click=${(e: Event) => e.stopPropagation()}>
                        ${isExpanded && this.canManage ? html`
                          <button class="icon-action" title="New Version" aria-label="New Version"
                            @click=${() => this.handleNewVersion(versions[0])}>＋</button>
                        ` : nothing}
                        <span style="color:var(--text-secondary)">${isExpanded ? '▲' : '▼'}</span>
                      </div>
                    </div>
                    ${isExpanded ? html`
                      <div class="form-body">
                        ${versions.map(v => this.renderVersionCard(v))}
                      </div>
                    ` : nothing}
                  </div>
                `;
              })}
            `}
          </div>
          ` : nothing}
        </div>

        ${this.renderThemesSection()}
      `}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-forms-list': VersolaFormsList;
  }
}
