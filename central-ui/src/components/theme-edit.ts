import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import type {
  FormPreview,
  FormRecord,
  Locale,
  ResolvedThemeTokens,
  ThemeOverrides,
  ThemePreset,
  ThemeRecord,
} from '../types';
import { createTheme, updateTheme, deleteTheme, renderTheme } from '../utils/central-api';
import { buildPreviewSrcdoc, localeNamesOf } from '../utils/preview';
import { pickScenario, renderScenarioControl } from './preview-scenarios';
import './code-editor';
import './theme-token-controls';
import type { OverridesChange } from './theme-token-controls';
import './nav-toggle';

@customElement('versola-theme-edit')
export class VersolaThemeEdit extends LitElement {
  @property({ type: Object }) theme!: ThemeRecord;
  @property({ type: String }) tenantId: string | null = null;
  // The built-in themes; empty from a central that has none, which leaves only themes written as CSS.
  @property({ type: Array }) presets: ThemePreset[] = [];
  // Every stored form version, and the states central lists for the built-in forms: the preview renders them.
  @property({ type: Array }) forms: FormRecord[] = [];
  @property({ type: Array }) previews: FormPreview[] = [];
  @property({ type: Array }) locales: Locale[] = [];

  @state() private editId = '';
  @state() private editCss = '';
  @state() private scope: 'global' | 'tenant' = 'global';
  @state() private kind: 'tokens' | 'css' = 'css';
  @state() private base = '';
  @state() private overrides: ThemeOverrides = {};
  // The colour field holds text that is not a #rrggbb colour (reported by the token controls).
  @state() private brandInvalid = false;
  @state() private advancedOpen = false;

  @state() private renderedCss = '';
  @state() private renderError = '';
  // True from an edit until central answers with the stylesheet, so the preview is not mistaken for the current one.
  @state() private rendering = false;
  @state() private previewFormId = '';
  @state() private previewScenarioId = '';
  @state() private previewWidth = 390;
  @state() private previewDark = false;

  @state() private saving = false;
  @state() private saveError = '';
  @state() private saveSuccess = '';

  private renderTimer: ReturnType<typeof setTimeout> | undefined;
  // Bumped by every edit, so an answer to an earlier request is dropped, however it arrives.
  private renderSeq = 0;
  // Whether the person picked Visual or Stylesheet; until then the default follows what central offers.
  private kindChosen = false;

  willUpdate(changed: Map<string, unknown>) {
    if (changed.has('theme') && this.theme) {
      this.editId = this.theme.id;
      this.editCss = this.theme.css;
      this.kindChosen = false;
      this.scope = this.theme.tenantId ? 'tenant' : 'global';
      const isNew = this.theme.id === '';
      this.kind = this.theme.tokens || (isNew && this.presets.length > 0) ? 'tokens' : 'css';
      this.base = this.theme.tokens?.base ?? this.presets.find(p => p.id === 'versola')?.id ?? this.presets[0]?.id ?? '';
      this.overrides = { ...(this.theme.tokens?.overrides ?? {}) };
      this.brandInvalid = false;
      this.advancedOpen = this.kind === 'tokens' && this.editCss.trim() !== '';
      this.scheduleRender();
    } else if (changed.has('presets') && this.theme) {
      // The built-in themes arrived after the theme was opened (a new theme opened while the page was still loading):
      // pick the default base, and start a new theme as a visual one unless the person already chose.
      if (this.base === '' || !this.basePreset) {
        this.base = this.presets.find(p => p.id === 'versola')?.id ?? this.presets[0]?.id ?? '';
      }
      if (this.theme.id === '' && !this.kindChosen && this.presets.length > 0) this.kind = 'tokens';
      this.scheduleRender();
    }
    if (changed.has('forms') || changed.has('previews')) {
      if (!this.previewFormId || !this.previewable.some(f => f.id === this.previewFormId)) {
        this.previewFormId = this.previewable.find(f => f.id === 'credential')?.id ?? this.previewable[0]?.id ?? '';
      }
    }
  }

  disconnectedCallback() {
    clearTimeout(this.renderTimer);
    super.disconnectedCallback();
  }

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    css`
      :host { display: block; }
      .form-title { font-size: 1.625rem; font-weight: 700; letter-spacing: -0.025em; color: var(--text-primary); margin: 0; }
      .title-stack { display: flex; flex-direction: column; gap: 0.5rem; }
      .field-label { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-secondary); margin-bottom: 0.5rem; }
      .hint { font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 0.5rem; }
      .kv-value { width: 100%; padding: 0.5rem var(--spacing-md); background: rgba(0,0,0,0.25); border: 1px solid var(--border-dark); border-radius: var(--radius-md); font-size: 0.8125rem; color: var(--text-primary); box-sizing: border-box; }
      .kv-value:focus { outline: none; border-color: var(--accent); }
      .form-actions { display: flex; align-items: center; gap: 1rem; justify-content: flex-end; margin-top: var(--spacing-xl); padding-top: var(--spacing-xl); border-top: 1px solid var(--border-dark); }
      .form-actions-left { margin-right: auto; }
      .save-msg { font-size: 0.875rem; }
      .save-msg.success { color: var(--success, #3fb950); }
      .save-msg.error { color: var(--danger); }

      .editor { display: grid; grid-template-columns: minmax(0, 30rem) minmax(0, 1fr); gap: var(--spacing-lg); align-items: start; margin-top: var(--spacing-lg); }
      @media (max-width: 1100px) { .editor { grid-template-columns: minmax(0, 1fr); } }
      .controls { display: flex; flex-direction: column; gap: var(--spacing-lg); }
      .reset { font: inherit; font-size: 0.75rem; color: var(--accent); background: none; border: 0; cursor: pointer; padding: 0; text-decoration: underline; }
      .summary { display: flex; align-items: center; justify-content: space-between; gap: var(--spacing-sm); font-size: 0.8125rem; color: var(--text-secondary); }

      .seg-control { display: inline-flex; flex-wrap: wrap; gap: 0.25rem; }
      .seg-btn { padding: 0.375rem 0.75rem; font: inherit; font-size: 0.8125rem; color: var(--text-secondary); background: transparent; border: 1px solid var(--border-dark); border-radius: var(--radius-md); cursor: pointer; }
      .seg-btn:hover { color: var(--text-primary); }
      .seg-btn.seg-active { color: var(--text-primary); border-color: var(--accent); background: color-mix(in srgb, var(--accent) 14%, transparent); }
      .seg-btn:disabled { opacity: 0.45; cursor: default; }
      .ctrl-group { display: flex; align-items: center; gap: var(--spacing-sm); flex-wrap: wrap; }
      .ctrl-label { font-size: 0.75rem; font-weight: 600; color: var(--text-secondary); }

      .presets { display: grid; grid-template-columns: repeat(auto-fill, minmax(8rem, 1fr)); gap: var(--spacing-sm); }
      .preset { text-align: left; padding: 0.5rem; font: inherit; color: var(--text-primary); background: transparent; border: 1px solid var(--border-dark); border-radius: var(--radius-md); cursor: pointer; }
      .preset:hover { border-color: var(--text-secondary); }
      .preset.sel { border-color: var(--accent); background: color-mix(in srgb, var(--accent) 10%, transparent); }
      .preset b { display: block; font-size: 0.8125rem; margin-top: 0.375rem; }
      /* The tiles repeat the page backgrounds of ThemeCss (util) and common.css: bold = brand 24% into #0c1110, soft = brand 14% into #f7f4ee, neutral = #ecebe7. Keep them in step when those change. */
      .th { display: grid; place-items: center; height: 4.5rem; border-radius: 6px; overflow: hidden; background: color-mix(in oklab, var(--b) 24%, #0c1110); }
      .th[data-surface='soft'] { background: color-mix(in oklab, var(--b) 14%, #f7f4ee); }
      .th[data-surface='neutral'] { background: #ecebe7; }
      .th i { display: block; }
      .th-sheet { width: 58%; height: 78%; background: #fffdf9; border-radius: 8px; display: flex; flex-direction: column; gap: 3px; padding: 6px 5px; box-shadow: 0 3px 8px rgba(0,0,0,0.2); }
      .th[data-shape='soft'] .th-sheet { border-radius: 5px; }
      .th[data-shape='sharp'] .th-sheet { border-radius: 1px; }
      .th-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--b); }
      .th-line { width: 50%; height: 3px; background: #1c1b19; border-radius: 2px; }
      .th-field { height: 6px; background: #efeae0; border-radius: 4px; }
      .th-btn { height: 7px; background: #1c1b19; border-radius: 5px; margin-top: auto; }

      details.advanced { border-top: 1px solid var(--border-dark); padding-top: var(--spacing-md); }
      details.advanced summary { cursor: pointer; font-weight: 600; font-size: 0.875rem; }

      .kind-toggle { display: inline-flex; gap: 0.25rem; }

      .preview { position: sticky; top: var(--spacing-md); }
      .preview-bar { display: flex; flex-wrap: wrap; align-items: center; gap: var(--spacing-md); margin-bottom: var(--spacing-md); }
      .preview-stage { background: rgba(0,0,0,0.2); border: 1px solid var(--border-dark); border-radius: var(--radius-md); padding: var(--spacing-sm); display: flex; justify-content: center; overflow: auto; }
      .preview-iframe { border: 0; border-radius: var(--radius-md); background: #fff; height: 40rem; max-width: 100%; }
      .render-error { color: var(--danger); font-size: 0.8125rem; margin-bottom: var(--spacing-sm); }
    `,
  ];

  // ─── data ───────────────────────────────────────────────────────────────────

  private get basePreset(): ThemePreset | undefined {
    return this.presets.find(p => p.id === this.base);
  }

  /** Every token with its value: the base's, with this theme's overrides laid over it. */
  private get resolved(): ResolvedThemeTokens | undefined {
    const base = this.basePreset?.tokens;
    if (!base) return undefined;
    return {
      brand: this.overrides.brand ?? base.brand,
      shape: this.overrides.shape ?? base.shape,
      surface: this.overrides.surface ?? base.surface,
      mode: this.overrides.mode ?? base.mode,
    };
  }

  /** The active version of each form, which is what a preview renders. */
  private get previewable(): FormRecord[] {
    return this.forms.filter(f => f.active && f.jsCompiled);
  }

  private get overrideCount(): number {
    return Object.keys(this.overrides).length;
  }

  private onOverridesChange(change: OverridesChange) {
    this.overrides = change.overrides;
    this.brandInvalid = change.brandInvalid;
    this.scheduleRender();
  }

  private pickBase(id: string) {
    this.base = id;
    // As in setOverride: an override equal to the new base's value is no override, so the theme follows that base.
    const values = this.basePreset?.tokens;
    if (values) {
      const next: ThemeOverrides = { ...this.overrides };
      for (const key of Object.keys(next) as Array<keyof ThemeOverrides>) {
        if (next[key] === values[key]) delete next[key];
      }
      this.overrides = next;
    }
    this.scheduleRender();
  }

  private scheduleRender() {
    clearTimeout(this.renderTimer);
    // Any request still in flight is for an older state of the editor; its answer must not be applied.
    this.renderSeq++;
    // A fixed scheme has no dark preview to switch on.
    if (this.kind === 'tokens' && (this.resolved?.mode ?? 'auto') !== 'auto') this.previewDark = false;
    if (this.kind !== 'tokens' || !this.base) {
      this.renderedCss = this.editCss;
      this.renderError = '';
      this.rendering = false;
      return;
    }
    this.rendering = true;
    this.renderTimer = setTimeout(() => void this.renderNow(), 200);
  }

  private async renderNow() {
    const seq = this.renderSeq;
    try {
      const rendered = await renderTheme({ base: this.base, overrides: this.overrides }, this.editCss);
      if (seq !== this.renderSeq) return;
      this.renderedCss = rendered.css;
      this.renderError = '';
      this.rendering = false;
    } catch (error) {
      if (seq !== this.renderSeq) return;
      // The preview keeps the last stylesheet that rendered; it is shown as out of date next to the reason.
      this.renderError = error instanceof Error ? error.message : 'Could not render the theme';
      this.rendering = false;
    }
  }

  // ─── actions ────────────────────────────────────────────────────────────────

  private get tokens() {
    return this.kind === 'tokens' ? { base: this.base, ...(this.overrideCount > 0 ? { overrides: this.overrides } : {}) } : null;
  }

  private async handleSave() {
    this.saving = true;
    this.saveSuccess = '';
    this.saveError = '';
    try {
      const isNew = this.theme.id === '';
      const tenantId = isNew
        ? (this.scope === 'tenant' ? this.tenantId : null)
        : this.theme.tenantId;
      const record: ThemeRecord = {
        id: this.editId.trim(),
        css: this.editCss,
        tenantId,
        tokens: this.tokens,
      };
      if (isNew) {
        await createTheme(record);
      } else {
        await updateTheme(record);
      }
      this.saveSuccess = isNew ? 'Created' : 'Saved';
      setTimeout(() => { this.saveSuccess = ''; }, 2000);
      this.dispatchEvent(new CustomEvent('submit', { detail: { theme: record }, bubbles: true, composed: true }));
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'Save failed';
    } finally {
      this.saving = false;
    }
  }

  private async handleDelete() {
    try {
      await deleteTheme(this.theme.id);
      this.dispatchEvent(new CustomEvent('delete', { detail: { id: this.theme.id }, bubbles: true, composed: true }));
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'Delete failed';
    }
  }

  private handleCancel() {
    this.dispatchEvent(new CustomEvent('cancel', { bubbles: true, composed: true }));
  }

  private setKind(kind: 'tokens' | 'css') {
    this.kind = kind;
    this.kindChosen = true;
    this.scheduleRender();
  }

  // ─── preview ────────────────────────────────────────────────────────────────

  private get previewForm(): FormRecord | undefined {
    return this.previewable.find(f => f.id === this.previewFormId);
  }

  private get scenarios() {
    return this.previews.find(p => p.formId === this.previewFormId)?.scenarios ?? [];
  }

  private previewSrcdoc(): string {
    const form = this.previewForm;
    if (!form) return '';
    const scheme = this.previewDark && (this.resolved?.mode ?? 'auto') === 'auto' ? ':root{color-scheme:dark}' : '';
    return buildPreviewSrcdoc({
      formId: form.id,
      properties: form.properties,
      getValue: prop => (prop.type === 'StringArrayProperty' ? prop.allowedValues : prop.type === 'NumberProperty' ? prop.default : false),
      scenario: pickScenario(this.scenarios, this.previewScenarioId),
      localizations: form.localizations,
      locale: this.locales[0]?.code ?? 'en',
      locales: this.locales.map(l => l.code),
      localeNames: localeNamesOf(this.locales),
      themeCss: `${this.renderedCss}\n${scheme}`,
      style: form.style,
      jsCompiled: form.jsCompiled ?? '',
    });
  }

  // ─── render ─────────────────────────────────────────────────────────────────

  private renderPresets() {
    return html`
      <section class="card">
        <div class="field-label">Base theme</div>
        <div class="hint">Start from a built-in theme. What you change below is kept as overrides, so switching the base keeps them.</div>
        <div class="presets" role="group" aria-label="Base theme">
          ${this.presets.map(p => html`
            <button class="preset ${this.base === p.id ? 'sel' : ''}" aria-pressed=${this.base === p.id ? 'true' : 'false'}
              @click=${() => this.pickBase(p.id)}>
              <span class="th" style=${`--b:${p.tokens.brand}`} data-surface=${p.tokens.surface} data-shape=${p.tokens.shape}>
                <i class="th-sheet"><i class="th-dot"></i><i class="th-line"></i><i class="th-field"></i><i class="th-field"></i><i class="th-btn"></i></i>
              </span>
              <b>${p.title.en ?? p.id}</b>
            </button>
          `)}
        </div>
      </section>
    `;
  }

  private renderTokens() {
    const resolved = this.resolved;
    const base = this.basePreset?.tokens;
    if (!resolved || !base) return nothing;
    return html`
      <section class="card">
        <div class="summary" style="margin-bottom: var(--spacing-md)">
          <span>${this.overrideCount === 0 ? 'No overrides: this theme is the base theme.' : `${this.overrideCount} ${this.overrideCount === 1 ? 'override' : 'overrides'} on ${this.basePreset?.title.en ?? this.base}`}</span>
          ${this.overrideCount > 0 ? html`<button class="reset" @click=${() => { this.overrides = {}; this.brandInvalid = false; this.scheduleRender(); }}>Clear overrides</button>` : nothing}
        </div>

        <versola-theme-token-controls .base=${base} .overrides=${this.overrides}
          @overrides-change=${(e: CustomEvent<OverridesChange>) => this.onOverridesChange(e.detail)}></versola-theme-token-controls>
      </section>

      <details class="advanced" ?open=${this.advancedOpen} @toggle=${(e: Event) => { this.advancedOpen = (e.target as HTMLDetailsElement).open; }}>
        <summary>Extra CSS (optional)</summary>
        <div class="hint" style="margin-top: 0.5rem">Added after the generated styles. Leave it empty unless a token cannot do what you need.</div>
        <versola-code-editor language="css" rows="8" .value=${this.editCss}
          @code-input=${(e: CustomEvent<{ value: string }>) => { this.editCss = e.detail.value; this.scheduleRender(); }}
        ></versola-code-editor>
      </details>
    `;
  }

  private renderPreview() {
    const forms = this.previewable;
    if (forms.length === 0) return html`<div class="hint">No form has an active version to preview yet.</div>`;
    return html`
      <div class="preview">
        <div class="preview-bar">
          <div class="ctrl-group">
            <label class="ctrl-label" for="preview-form">Form</label>
            <select id="preview-form" class="kv-value" style="width:auto"
              @change=${(e: Event) => { this.previewFormId = (e.target as HTMLSelectElement).value; this.previewScenarioId = ''; }}>
              ${forms.map(f => html`<option value=${f.id} ?selected=${f.id === this.previewFormId}>${f.id}</option>`)}
            </select>
          </div>
          ${renderScenarioControl(this.scenarios, pickScenario(this.scenarios, this.previewScenarioId)?.id ?? '', id => { this.previewScenarioId = id; })}
          <div class="ctrl-group">
            <span class="ctrl-label">Width</span>
            <div class="seg-control" role="group" aria-label="Preview width">
              ${[['Mobile', 390], ['Desktop', 1000]].map(([label, width]) => html`
                <button class="seg-btn ${this.previewWidth === width ? 'seg-active' : ''}" @click=${() => { this.previewWidth = width as number; }}>${label}</button>
              `)}
            </div>
          </div>
          <div class="ctrl-group">
            <span class="ctrl-label">Scheme</span>
            <div class="seg-control" role="group" aria-label="Preview scheme">
              <button class="seg-btn ${!this.previewDark ? 'seg-active' : ''}" @click=${() => { this.previewDark = false; }}>Light</button>
              <button class="seg-btn ${this.previewDark ? 'seg-active' : ''}" ?disabled=${this.kind === 'tokens' && (this.resolved?.mode ?? 'auto') !== 'auto'}
                @click=${() => { this.previewDark = true; }}>Dark</button>
            </div>
          </div>
        </div>
        ${this.renderError ? html`<div class="render-error" role="alert">${this.renderError}. The preview shows the last version that rendered.</div>` : nothing}
        <div class="preview-stage" aria-busy=${this.rendering ? 'true' : 'false'} style=${this.rendering || this.renderError ? 'opacity:0.55;transition:opacity 0.15s' : 'transition:opacity 0.15s'}>
          <iframe class="preview-iframe" title="Preview" style=${`width:${this.previewWidth}px`}
            .srcdoc=${this.previewSrcdoc()} sandbox="allow-same-origin allow-scripts"></iframe>
        </div>
      </div>
    `;
  }

  render() {
    const isNew = this.theme?.id === '';
    const tokensKind = this.kind === 'tokens';
    const canSave = this.editId.trim().length > 0 && !this.brandInvalid && (!tokensKind || this.base !== '');
    return html`
      <div class="title-stack" style="margin-bottom: var(--spacing-lg)">
        <div class="form-header-lead">
          <versola-nav-toggle></versola-nav-toggle>
          <h1 class="form-title">${isNew ? 'New Theme' : 'Edit Theme'}</h1>
        </div>
        ${isNew ? html`
          <div>
            <label class="field-label" for="theme-id">Theme ID</label>
            <input id="theme-id" class="kv-value" style="max-width:20rem" placeholder="e.g. default"
              .value=${this.editId}
              @input=${(e: Event) => { this.editId = (e.target as HTMLInputElement).value.trim(); }} />
          </div>
        ` : html`
          <div style="font-family: var(--font-mono); color: var(--accent); font-size: 1rem">${this.theme?.id}</div>
        `}
        <div>
          <label class="field-label" for="theme-scope">Scope</label>
          ${isNew ? html`
            <select id="theme-scope" class="kv-value" style="max-width:20rem"
              .value=${this.scope}
              @change=${(e: Event) => { this.scope = (e.target as HTMLSelectElement).value as 'global' | 'tenant'; }}>
              <option value="global" ?selected=${this.scope === 'global'}>Global</option>
              ${this.tenantId ? html`<option value="tenant" ?selected=${this.scope === 'tenant'}>This tenant (${this.tenantId})</option>` : nothing}
            </select>
          ` : html`
            <div class="kv-value" style="max-width:20rem">${this.theme.tenantId ? `This tenant (${this.theme.tenantId})` : 'Global'}</div>
          `}
        </div>
        ${isNew && this.presets.length > 0 ? html`
          <div>
            <label class="field-label">Built as</label>
            <div class="hint">Chosen when the theme is created; it cannot be switched afterwards.</div>
            <div class="kind-toggle seg-control" role="group" aria-label="Built as">
              <button class="seg-btn ${tokensKind ? 'seg-active' : ''}" @click=${() => this.setKind('tokens')}>Visual</button>
              <button class="seg-btn ${!tokensKind ? 'seg-active' : ''}" @click=${() => this.setKind('css')}>Stylesheet</button>
            </div>
          </div>
        ` : nothing}
      </div>

      ${tokensKind ? html`
        <div class="editor">
          <div class="controls">${this.renderPresets()}${this.renderTokens()}</div>
          ${this.renderPreview()}
        </div>
      ` : html`
        <div class="card">
          <div class="field-label">CSS</div>
          <div class="hint">Applied to all forms using this theme. A theme written as CSS replaces the default stylesheet entirely.</div>
          <versola-code-editor language="css" rows="20" .value=${this.editCss}
            @code-input=${(e: CustomEvent<{ value: string }>) => { this.editCss = e.detail.value; this.scheduleRender(); }}
          ></versola-code-editor>
        </div>
      `}

      <div class="form-actions">
        ${!isNew && this.theme.id !== 'default' ? html`<div class="form-actions-left"><button class="btn btn-danger" ?disabled=${this.saving} @click=${() => this.handleDelete()}>Delete</button></div>` : nothing}
        <button class="btn btn-secondary" ?disabled=${this.saving} @click=${() => this.handleCancel()}>Cancel</button>
        <button class="btn btn-primary" ?disabled=${this.saving || !canSave} @click=${() => this.handleSave()}>
          ${this.saving ? 'Saving…' : isNew ? 'Create' : 'Save'}
        </button>
        ${this.saveSuccess ? html`<span class="save-msg success">${this.saveSuccess}</span>` : nothing}
        ${this.saveError ? html`<span class="save-msg error">${this.saveError}</span>` : nothing}
      </div>
    `;
  }
}
