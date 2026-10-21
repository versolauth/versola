import { LitElement, html, css, nothing, type PropertyValues, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, formStyles, recordStyles, segmentedStyles, tagStyles } from '../styles/components';
import type { FormPreview, FormRecord, Locale, ResolvedThemeTokens, ThemeOverrides, ThemePreset, ThemeRecord } from '../types';
import { createTheme, deleteTheme as deleteThemeRequest, renderTheme, updateTheme } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { dispatchRecordRoute } from '../utils/record-route';
import { buildPreviewSrcdoc, localeNamesOf } from '../utils/preview';
import { joinNames, overrideKeys, presetHint, tokenLongName, unsavedNames, type ThemeEditState } from '../utils/theme-summary';
import { PAGE_BG_VARIABLE, THEME_VARIABLES, isHexColour, pixels, valueProblem, variableByName } from '../utils/theme-variables';
import { pickScenario, renderScenarioControl } from './preview-scenarios';
import { statusIcon } from './status-icon';
import './code-editor';
import './danger-card';
import './nav-toggle';
import './preview-pane';
import './record-header';
import './theme-token-controls';
import type { OverridesChange, VersolaThemeTokenControls } from './theme-token-controls';

/** What a rule may target on `:root`: the theming API of the login forms' common.css. */
const CSS_TOKENS: Array<[string, string]> = [...THEME_VARIABLES.map((variable): [string, string] => [variable.name, variable.description]), [PAGE_BG_VARIABLE.name, PAGE_BG_VARIABLE.description]];

/** One row of token overrides; `key` identifies it while the name or the value is edited. */
type VariableRow = { key: number; name: string; value: string };

export type ThemeSaved = { theme: ThemeRecord; created: boolean };

/**
 * One theme's page, which is also its editor. A theme is a base theme (a row of pills, the preview beside
 * it shows the result) with the tokens it overrides (the Look card: each overridden token with a badge and
 * Reset, `Clear overrides`) and an Advanced card after Look: token overrides (single variables of the forms' theming API,
 * each a row with a control matched to it) and optional Custom CSS (collapsed behind "Add custom CSS" while empty, open when
 * the theme has CSS). Central lays them after the CSS generated from the values, token overrides first, so the cascade is
 * base theme, five values, token overrides, custom CSS.
 * A theme stored with CSS and no tokens opens on the Versola base with the CSS card open; saving stores both. Delete is the last card, disabled with the
 * clients that use the theme. A bar sticks to the bottom with Cancel and Save and names what is not
 * saved yet. The preview is a real login form drawn from the theme: a column that stays on screen
 * beside the controls, a band above them below 900px.
 *
 * The record does not fetch: the list passes the themes' presets, the forms to preview and the
 * clients that use this theme, and learns of a save or a delete through `theme-saved` and
 * `theme-deleted`.
 */
@customElement('versola-theme-record')
export class VersolaThemeRecord extends LitElement {
  @property({ attribute: false }) theme!: ThemeRecord;
  @property({ type: Boolean }) isNew = false;
  @property({ type: Boolean }) canManage = false;
  @property({ type: String }) tenantId: string | null = null;
  /** The built-in themes; empty from a central that has none. */
  @property({ attribute: false }) presets: ThemePreset[] = [];
  /** Every stored form version, and the states central lists for the built-in forms: the preview renders them. */
  @property({ attribute: false }) forms: FormRecord[] = [];
  @property({ attribute: false }) previews: FormPreview[] = [];
  @property({ attribute: false }) locales: Locale[] = [];
  /** The clients of the tenant that use this theme. */
  @property({ attribute: false }) usedBy: string[] = [];
  /** `created` / `saved`: said once, in a banner. */
  @property({ type: String }) notice = '';

  @state() private editId = '';
  @state() private editCss = '';
  @state() private scope: 'global' | 'tenant' = 'global';
  /** Opened without tokens: the theme was stored before tokens existed, so what is shown is the Versola base and saving adds the values. */
  @state() private withoutTokens = false;
  @state() private cssOpen = false;
  @state() private variableRows: VariableRow[] = [];
  private rowKey = 0;
  @state() private base = '';
  @state() private overrides: ThemeOverrides = {};
  /** The colour field holds text that is not a #rrggbb colour (reported by the token controls). */
  @state() private brandInvalid = false;
  @state() private submitted = false;

  @state() private renderedCss = '';
  @state() private renderError = '';
  /** True from an edit until central answers with the stylesheet, so the preview is not mistaken for the current one. */
  @state() private rendering = false;
  @state() private previewFormId = '';
  @state() private previewScenarioId = '';
  @state() private previewWidth = 390;
  /** Starts from the operator's device, so the preview shows what they see on real pages (the page glow exists only in the dark scheme). */
  @state() private previewDark = window.matchMedia('(prefers-color-scheme: dark)').matches;

  @state() private saving = false;
  @state() private error = '';
  @state() private saved: ThemeEditState = { base: '', overrides: {}, variables: {}, css: '' };

  private renderTimer: ReturnType<typeof setTimeout> | undefined;
  /** Bumped by every edit, so an answer to an earlier request is dropped, however it arrives. */
  private renderSeq = 0;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
    recordStyles,
    segmentedStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
      }

      *,
      *::before,
      *::after {
        box-sizing: border-box;
      }

      .form-title {
        font-size: var(--text-4xl);
        font-weight: 800;
        letter-spacing: -0.02em;
        color: var(--text-primary);
        margin: 0;
      }

      .new-head {
        margin-bottom: var(--spacing-lg);
      }

      .layout {
        display: grid;
        grid-template-columns: minmax(0, 1fr) 380px;
        gap: var(--spacing-lg);
        align-items: start;
      }

      @media (min-width: 1360px) {
        .layout {
          grid-template-columns: minmax(0, 1fr) 560px;
        }
      }

      @media (max-width: 899.98px) {
        .layout {
          grid-template-columns: minmax(0, 1fr);
        }

        versola-preview-pane {
          order: -1;
        }
      }

      .controls {
        min-width: 0;
      }

      .controls > * + * {
        margin-top: var(--spacing-md);
      }

      .card-body {
        padding: var(--spacing-md) var(--spacing-lg);
      }

      .hint {
        margin: 0 0 var(--spacing-sm);
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .danger-card .record-card-head {
        flex-wrap: wrap;
      }

      .head-actions {
        display: flex;
        gap: var(--spacing-sm);
        flex: none;
      }

      /* The base theme's pills: the shared segmented buttons, each with its brand colour as a dot. */
      .presets {
        gap: var(--spacing-sm);
      }

      .presets .seg-btn {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        min-height: 2rem;
        padding-left: 0.625rem;
      }

      .dot {
        flex: none;
        width: 0.75rem;
        height: 0.75rem;
        border-radius: 50%;
        box-shadow: 0 0 0 1.5px var(--border-input);
      }

      .seg-active .dot {
        box-shadow: 0 0 0 1.5px var(--bg-page);
      }

      .preset-hint {
        margin: var(--spacing-sm) 0 0;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      /* ── the bar ── */
      .bar {
        position: sticky;
        bottom: 0;
        z-index: var(--z-dropdown);
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-sm) var(--spacing-md);
        margin-top: var(--spacing-lg);
        padding: var(--spacing-md) 0;
        background: var(--bg-page);
        border-top: 1px solid var(--border-default);
      }

      .bar-text {
        font-size: var(--text-sm);
        color: var(--text-secondary);
        overflow-wrap: anywhere;
      }

      .bar-actions {
        display: flex;
        gap: var(--spacing-sm);
      }

      /* ── preview ── */
      .preview-toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm) var(--spacing-lg);
        align-items: center;
      }

      .preview-toolbar select {
        width: auto;
        min-width: 7rem;
      }

      /* One width at 1024: the phone a client's users most likely hold. */
      @media (max-width: 1359.98px) {
        .width-group {
          display: none;
        }
      }

      .render-error {
        margin: 0;
        padding: var(--spacing-sm) var(--spacing-md);
        color: var(--danger-text);
        background: var(--danger-subtle);
        font-size: var(--text-sm);
      }

      .part-title {
        margin: 0 0 var(--spacing-xs);
        font-size: var(--text-base);
        font-weight: 700;
        color: var(--text-primary);
      }

      .part-rule {
        border-top: 1px solid var(--border-default);
      }

      .var-rows {
        display: grid;
        gap: var(--spacing-md);
        margin: 0 0 var(--spacing-sm);
        padding: 0;
        list-style: none;
      }

      .var-line {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--spacing-sm);
      }

      .var-name {
        width: 11rem;
      }

      .var-value {
        display: flex;
        flex: 1;
        align-items: center;
        gap: var(--spacing-sm);
        min-width: 11rem;
      }

      .value-input {
        flex: 1;
        min-width: 0;
        max-width: 14rem;
      }

      .color-input {
        flex: none;
        width: 2.5rem;
        height: 2.25rem;
        padding: 0;
        border: 1.5px solid var(--border-input);
        border-radius: var(--radius-md);
        background: var(--field);
      }

      .unit {
        color: var(--text-secondary);
        font-size: var(--text-sm);
      }

      .var-help {
        margin-top: var(--spacing-xs);
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .tokens-title {
        margin: var(--spacing-md) 0 var(--spacing-xs);
        font-weight: 600;
      }

      .tokens {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
        gap: var(--spacing-xs) var(--spacing-md);
        margin: 0;
        padding: 0;
        list-style: none;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .tokens li {
        display: flex;
        flex-wrap: wrap;
        gap: 0 var(--spacing-sm);
        align-items: baseline;
      }

      .tokens code {
        font-family: var(--font-mono);
        color: var(--text-primary);
      }

      .field-label {
        display: block;
        margin-bottom: var(--spacing-sm);
        font-size: var(--text-xs);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: var(--text-secondary);
      }

      .mono {
        font-family: var(--font-mono);
      }
    `,
  ];

  willUpdate(changed: PropertyValues) {
    if (changed.has('theme') && this.theme) {
      this.editId = this.theme.id;
      this.editCss = this.theme.css;
      this.scope = this.theme.tenantId ? 'tenant' : 'global';
      this.withoutTokens = !this.isNew && !this.theme.tokens;
      this.cssOpen = this.editCss.trim() !== '';
      this.variableRows = Object.entries(this.theme.tokens?.variables ?? {}).map(([name, value]) => ({ key: this.rowKey++, name, value }));
      this.base = this.theme.tokens?.base ?? this.presets.find(p => p.id === 'versola')?.id ?? this.presets[0]?.id ?? '';
      this.overrides = { ...(this.theme.tokens?.overrides ?? {}) };
      this.brandInvalid = false;
      this.submitted = false;
      this.error = '';
      this.saved = { base: this.withoutTokens ? '' : this.base, overrides: { ...this.overrides }, variables: this.variablesMap, css: this.editCss };
      this.scheduleRender();
    } else if (changed.has('presets') && this.theme) {
      // The built-in themes arrived after the theme was opened: pick the default base.
      if (this.base === '' || !this.basePreset) this.base = this.presets.find(p => p.id === 'versola')?.id ?? this.presets[0]?.id ?? '';
      this.saved = { ...this.saved, base: this.isNew ? this.base : this.saved.base };
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

  // ── data ────────────────────────────────────────────────────────────────

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
      background: this.overrides.background ?? base.background,
    };
  }

  /** The active version of each form, which is what a preview renders. */
  private get previewable(): FormRecord[] {
    return this.forms.filter(f => f.active && f.jsCompiled);
  }

  private get unsaved(): string[] {
    return unsavedNames(this.saved, { base: this.base, overrides: this.overrides, variables: this.variablesMap, css: this.editCss });
  }

  /** The rows as the map central stores; a row without a name is not one yet. */
  private get variablesMap(): Record<string, string> {
    return Object.fromEntries(this.variableRows.filter(row => row.name !== '').map(row => [row.name, row.value.trim()]));
  }

  /** What each row's value is refused for, by row key. */
  private get rowProblems(): Map<number, string> {
    return new Map(this.variableRows.filter(row => row.name !== '').map((row): [number, string] => [row.key, valueProblem(row.value)]).filter(([, problem]) => problem !== ''));
  }

  /** The rows the preview may send: those central would not refuse. */
  private get renderableVariables(): Record<string, string> {
    const problems = this.rowProblems;
    return Object.fromEntries(this.variableRows.filter(row => row.name !== '' && !problems.has(row.key)).map(row => [row.name, row.value.trim()]));
  }

  private get dirty(): boolean {
    return this.isNew ? this.editId.trim() !== '' || this.unsaved.length > 0 : this.unsaved.length > 0;
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
      for (const key of Object.keys(next) as Array<keyof ThemeOverrides>) if (next[key] === values[key]) delete next[key];
      this.overrides = next;
    }
    this.scheduleRender();
  }

  private clearOverrides() {
    this.overrides = {};
    this.brandInvalid = false;
    this.renderRoot.querySelector<VersolaThemeTokenControls>('versola-theme-token-controls')?.clearBrandText();
    this.scheduleRender();
  }

  /** The scheme the preview shows now: the theme's own when it fixes one, else the Light / Dark switch's. */
  private get shownScheme(): 'light' | 'dark' {
    const mode = this.resolved?.mode ?? 'auto';
    return mode === 'light' || mode === 'dark' ? mode : this.previewDark ? 'dark' : 'light';
  }

  private scheduleRender() {
    clearTimeout(this.renderTimer);
    // Any request still in flight is for an older state of the editor; its answer must not be applied.
    this.renderSeq++;
    // A fixed scheme has no dark preview to switch on.
    if ((this.resolved?.mode ?? 'auto') !== 'auto') this.previewDark = false;
    if (!this.base) {
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
      const rendered = await renderTheme({ base: this.base, overrides: this.overrides, ...(Object.keys(this.renderableVariables).length > 0 ? { variables: this.renderableVariables } : {}) }, this.editCss);
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

  // ── actions ─────────────────────────────────────────────────────────────

  private get idError(): string {
    if (!this.isNew) return '';
    const id = this.editId.trim();
    if (!id) return this.submitted ? 'A theme ID is required.' : '';
    return '';
  }

  private get canSave(): boolean {
    return this.editId.trim().length > 0 && !this.brandInvalid && this.rowProblems.size === 0 && this.base !== '' && this.dirty;
  }

  private async save() {
    if (this.saving) return;
    this.submitted = true;
    if (!this.canSave) return;
    this.saving = true;
    this.error = '';
    try {
      const tenantId = this.isNew ? (this.scope === 'tenant' ? this.tenantId : null) : this.theme.tenantId;
      const variables = this.variablesMap;
      const tokens = { base: this.base, ...(overrideKeys(this.overrides).length > 0 ? { overrides: this.overrides } : {}), ...(Object.keys(variables).length > 0 ? { variables } : {}) };
      const record: ThemeRecord = { id: this.editId.trim(), css: this.editCss, tenantId, tokens };
      if (this.isNew) await createTheme(record);
      else await updateTheme(record);
      this.saved = { base: this.base, overrides: { ...this.overrides }, variables, css: this.editCss };
      this.withoutTokens = false;
      this.dispatchEvent(new CustomEvent<ThemeSaved>('theme-saved', { detail: { theme: record, created: this.isNew }, bubbles: true, composed: true }));
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Save failed';
    } finally {
      this.saving = false;
    }
  }

  private async leave() {
    if (this.dirty && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
  }

  private async deleteTheme() {
    const id = this.theme.id;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete theme',
      messagePrefix: 'Delete theme ',
      messageSubject: id,
      messageSuffix: '? No client uses it. This can\'t be undone.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    this.error = '';
    try {
      await deleteThemeRequest(id);
      this.dispatchEvent(new CustomEvent('theme-deleted', { detail: { id }, bubbles: true, composed: true }));
    } catch (error) {
      this.error = error instanceof Error ? `Could not delete ${id}: ${error.message}` : `Could not delete ${id}`;
    }
  }

  // ── preview ─────────────────────────────────────────────────────────────

  private get previewForm(): FormRecord | undefined {
    return this.previewable.find(f => f.id === this.previewFormId);
  }

  private get scenarios() {
    return this.previews.find(p => p.formId === this.previewFormId)?.scenarios ?? [];
  }

  private previewSrcdoc(): string {
    const form = this.previewForm;
    if (!form) return '';
    // The preview frame follows the operator's own device scheme unless told otherwise, so Light has to be forced too: on a dark
    // device it would otherwise show the dark card. A theme with a fixed scheme keeps it.
    const auto = (this.resolved?.mode ?? 'auto') === 'auto';
    const scheme = auto ? `:root{color-scheme:${this.previewDark ? 'dark' : 'light'}}` : this.previewDark ? ':root{color-scheme:dark}' : '';
    // Until central has answered there is no stylesheet: a frame drawn now would flash unstyled.
    if (this.renderedCss === '') return '';
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

  // ── rendering ───────────────────────────────────────────────────────────

  private renderBasics(): TemplateResult {
    const idError = this.idError;
    return html`
      <section class="card record-card" aria-label="Basics">
        <div class="record-card-head"><div><h2>Basics</h2><div class="record-card-sub">The ID and the scope are fixed once the theme is created.</div></div></div>
        <div class="card-body">
          <div class="form-group">
            <label for="theme-id">Theme ID *</label>
            <input id="theme-id" type="text" class="compact-input mono ${idError ? 'input-error' : ''}" placeholder="e.g. default" autocomplete="off" spellcheck="false"
              aria-invalid=${idError ? 'true' : 'false'} aria-describedby="theme-id-hint" .value=${this.editId}
              @input=${(e: Event) => { this.editId = (e.target as HTMLInputElement).value.trim(); }} />
            ${idError ? html`<div class="error-message" id="theme-id-hint" role="alert">${idError}</div>` : nothing}
          </div>
          <div class="form-group">
            <label for="theme-scope">Scope</label>
            <select id="theme-scope" class="compact-input" .value=${this.scope} @change=${(e: Event) => { this.scope = (e.target as HTMLSelectElement).value as 'global' | 'tenant'; }}>
              <option value="global" ?selected=${this.scope === 'global'}>Global</option>
              ${this.tenantId ? html`<option value="tenant" ?selected=${this.scope === 'tenant'}>This tenant (${this.tenantId})</option>` : nothing}
            </select>
          </div>
        </div>
      </section>`;
  }

  private renderBase(): TemplateResult {
    const base = this.basePreset;
    return html`
      <section class="card record-card" aria-label="Base theme">
        <div class="record-card-head"><div><h2>Base theme</h2>
          <div class="record-card-sub">Start from a built-in look; the preview shows it. What you change below is kept as overrides, so switching the base keeps them.</div></div></div>
        <div class="card-body">
          <div class="seg-control presets" role="group" aria-label="Base theme">
            ${this.presets.map(p => html`
              <button type="button" class="seg-btn ${this.base === p.id ? 'seg-active' : ''}" aria-pressed=${this.base === p.id ? 'true' : 'false'}
                ?disabled=${!this.canManage} @click=${() => this.pickBase(p.id)}>
                <span class="dot" style=${`background:${p.tokens.brand}`} aria-hidden="true"></span>${p.title.en ?? p.id}
              </button>`)}
          </div>
          ${this.withoutTokens ? html`<p class="preset-hint" id="without-tokens">This theme was stored with CSS only, before themes had values. It opens on Versola here; saving keeps the CSS and stores these values with it. Until then clients get the CSS alone.</p>` : nothing}
          ${base ? html`<p class="preset-hint" id="preset-hint">${base.title.en ?? base.id}: ${presetHint(base.tokens)}</p>` : nothing}
        </div>
      </section>`;
  }

  private renderLook(): TemplateResult {
    const base = this.basePreset?.tokens;
    if (!base || !this.resolved) return html`${nothing}`;
    const names = overrideKeys(this.overrides).map(tokenLongName);
    const baseTitle = this.basePreset?.title.en ?? this.base;
    return html`
      <section class="card record-card" aria-label="Look">
        <div class="record-card-head">
          <div><h2>Look</h2>
            <div class="record-card-sub">${names.length === 0 ? 'No overrides: this theme is the base theme.' : `Overrides on ${baseTitle}: ${names.join(', ')}.`}</div></div>
          ${names.length > 0 && this.canManage ? html`<div class="head-actions"><button type="button" class="btn btn-secondary btn-sm" @click=${() => this.clearOverrides()}>Clear overrides</button></div>` : nothing}
        </div>
        <div class="card-body">
          <versola-theme-token-controls ruled .base=${base} .overrides=${this.overrides} .scheme=${this.shownScheme}
            @overrides-change=${(e: CustomEvent<OverridesChange>) => this.onOverridesChange(e.detail)}></versola-theme-token-controls>
        </div>
      </section>`;
  }

  private setRow(key: number, change: Partial<VariableRow>) {
    this.variableRows = this.variableRows.map(row => (row.key === key ? { ...row, ...change } : row));
    this.scheduleRender();
  }

  private addRow() {
    const used = new Set(this.variableRows.map(row => row.name));
    const next = THEME_VARIABLES.find(variable => !used.has(variable.name));
    if (!next) return;
    this.variableRows = [...this.variableRows, { key: this.rowKey++, name: next.name, value: next.start }];
    this.scheduleRender();
  }

  private removeRow(key: number) {
    this.variableRows = this.variableRows.filter(row => row.key !== key);
    this.scheduleRender();
  }

  private pickName(row: VariableRow, name: string) {
    const kind = variableByName(name)?.kind;
    // A value that fits the old token's kind but not the new one's is replaced by the new token's start.
    const fits = kind === variableByName(row.name)?.kind;
    this.setRow(row.key, { name, value: fits ? row.value : (variableByName(name)?.start ?? row.value) });
  }

  /** The control a token's value is edited with: a colour picker and its hex, or a length in px; anything else (a value
   *  stored through the API, such as `color-mix(...)`) is plain text. */
  private renderValue(row: VariableRow, label: string, problem: string): TemplateResult {
    const kind = variableByName(row.name)?.kind;
    const describedBy = problem ? `row-problem-${row.key}` : nothing;
    const invalid = problem ? 'true' : 'false';
    const disabled = !this.canManage;
    if (kind === 'colour' && (isHexColour(row.value) || row.value.trim() === '')) {
      return html`
        <input type="color" class="color-input" aria-label=${`${label} colour picker`} .value=${isHexColour(row.value) ? row.value : '#000000'} ?disabled=${disabled}
          @input=${(e: Event) => this.setRow(row.key, { value: (e.target as HTMLInputElement).value })} />
        <input type="text" class="compact-input mono value-input" aria-label=${`${label} value`} aria-invalid=${invalid} aria-describedby=${describedBy} spellcheck="false" maxlength="120" .value=${row.value} ?disabled=${disabled}
          @input=${(e: Event) => this.setRow(row.key, { value: (e.target as HTMLInputElement).value })} />`;
    }
    const px = pixels(row.value);
    if (kind === 'length' && px !== null) {
      return html`
        <input type="number" min="0" step="1" class="compact-input mono value-input" aria-label=${`${label} value, px`} aria-invalid=${invalid} aria-describedby=${describedBy} .value=${String(px)} ?disabled=${disabled}
          @input=${(e: Event) => this.setRow(row.key, { value: `${(e.target as HTMLInputElement).value.trim()}px` })} />
        <span class="unit" aria-hidden="true">px</span>`;
    }
    return html`
      <input type="text" class="compact-input mono value-input" aria-label=${`${label} value`} aria-invalid=${invalid} aria-describedby=${describedBy} spellcheck="false" maxlength="120" .value=${row.value} ?disabled=${disabled}
        @input=${(e: Event) => this.setRow(row.key, { value: (e.target as HTMLInputElement).value })} />`;
  }

  private renderRow(row: VariableRow): TemplateResult {
    const problems = this.rowProblems;
    const problem = problems.get(row.key) ?? '';
    const taken = new Set(this.variableRows.filter(other => other.key !== row.key).map(other => other.name));
    const known = variableByName(row.name);
    return html`
      <li class="var-row">
        <div class="var-line">
          <select class="compact-input mono var-name" aria-label=${`Token for row ${this.variableRows.indexOf(row) + 1}`} ?disabled=${!this.canManage}
            @change=${(e: Event) => this.pickName(row, (e.target as HTMLSelectElement).value)}>
            ${known ? nothing : html`<option value=${row.name} selected>${row.name}</option>`}
            ${THEME_VARIABLES.map(variable => html`<option value=${variable.name} ?selected=${variable.name === row.name} ?disabled=${taken.has(variable.name)}>${variable.name}</option>`)}
          </select>
          <div class="var-value">${this.renderValue(row, row.name, problem)}</div>
          ${this.canManage ? html`<button type="button" class="btn btn-secondary btn-sm" aria-label=${`Remove ${row.name}`} @click=${() => this.removeRow(row.key)}>Remove</button>` : nothing}
        </div>
        <div class="var-help">${known?.description ?? 'not in the theming API'}</div>
        ${problem ? html`<div class="error-message" id=${`row-problem-${row.key}`} role="alert">${problem}</div>` : nothing}
      </li>`;
  }

  private renderAdvanced(): TemplateResult {
    const open = this.cssOpen || this.editCss.trim() !== '';
    const canAdd = this.canManage && this.variableRows.length < THEME_VARIABLES.length;
    return html`
      <section class="card record-card" aria-label="Advanced">
        <div class="record-card-head">
          <div><h2>Advanced</h2>
            <div class="record-card-sub">Token overrides, then your own CSS, both after the values above. Later wins: the base theme, the values, token overrides, custom CSS.</div></div>
        </div>
        <div class="card-body">
          <h3 class="part-title">Token overrides</h3>
          <p class="hint">Set a single variable of the sign-in screens without writing CSS.</p>
          ${this.variableRows.length === 0 ? html`<p class="hint" id="no-tokens">None.</p>` : html`<ul class="var-rows" aria-label="Token overrides">${this.variableRows.map(row => this.renderRow(row))}</ul>`}
          ${canAdd ? html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => this.addRow()}>Add token</button>` : nothing}
        </div>
        <div class="card-body part-rule">
          <h3 class="part-title">Custom CSS</h3>
          ${open ? html`
            <p class="hint" id="custom-css-hint">Applied after the colours, shape and background above, so your rules win. You are responsible for contrast and small screens.</p>
            <versola-code-editor id="custom-css" language="css" rows="10" label="Custom CSS" ?disabled=${!this.canManage} .value=${this.editCss}
              @code-input=${(e: CustomEvent<{ value: string }>) => { this.editCss = e.detail.value; this.scheduleRender(); }}></versola-code-editor>
            <p class="hint tokens-title">Variables a rule may set or read</p>
            <ul class="tokens" aria-label="Variables a rule may target">
              ${CSS_TOKENS.map(([name, text]) => html`<li><code>${name}</code><span>${text}</span></li>`)}
            </ul>` : html`
            <p class="hint">None. Add your own rules on top of everything above.</p>
            ${this.canManage ? html`<button type="button" class="btn btn-secondary btn-sm" aria-expanded="false" @click=${() => { this.cssOpen = true; }}>Add custom CSS</button>` : nothing}`}
        </div>
      </section>`;
  }

  private get deleteReason(): string {
    if (this.theme.id === 'default') return 'The default theme is the fallback for clients that name none, so it cannot be deleted.';
    if (this.usedBy.length > 0) return `Used by ${joinNames(this.usedBy)}. Move ${this.usedBy.length === 1 ? 'it' : 'them'} to another theme first.`;
    return '';
  }

  private renderPreview(): TemplateResult {
    const forms = this.previewable;
    const scenarios = this.scenarios;
    const blocked = (this.resolved?.mode ?? 'auto') !== 'auto';
    return html`
      <versola-preview-pane heading="Preview" ?busy=${this.rendering} ?stale=${!!this.renderError} .logicalWidth=${this.previewWidth} .logicalHeight=${600}>
        <div class="preview-toolbar" slot="toolbar">
          ${forms.length === 0 ? html`<span class="hint" style="margin:0">No form has an active version to preview yet.</span>` : html`
            <div class="ctrl-group">
              <label class="ctrl-label" for="preview-form">Form</label>
              <select id="preview-form" @change=${(e: Event) => { this.previewFormId = (e.target as HTMLSelectElement).value; this.previewScenarioId = ''; }}>
                ${forms.map(f => html`<option value=${f.id} ?selected=${f.id === this.previewFormId}>${f.id}</option>`)}
              </select>
            </div>
            ${renderScenarioControl(scenarios, pickScenario(scenarios, this.previewScenarioId)?.id ?? '', id => { this.previewScenarioId = id; })}
            <div class="ctrl-group width-group">
              <span class="ctrl-label">Width</span>
              <div class="seg-control" role="group" aria-label="Preview width">
                ${[['Mobile', 390], ['Desktop', 1000]].map(([label, width]) => html`
                  <button type="button" class="seg-btn ${this.previewWidth === width ? 'seg-active' : ''}" aria-pressed=${this.previewWidth === width ? 'true' : 'false'}
                    @click=${() => { this.previewWidth = width as number; }}>${label}</button>`)}
              </div>
            </div>
            <div class="ctrl-group">
              <span class="ctrl-label">Scheme</span>
              <div class="seg-control" role="group" aria-label="Preview scheme">
                <button type="button" class="seg-btn ${!this.previewDark ? 'seg-active' : ''}" aria-pressed=${!this.previewDark ? 'true' : 'false'} @click=${() => { this.previewDark = false; }}>Light</button>
                <button type="button" class="seg-btn ${this.previewDark ? 'seg-active' : ''}" aria-pressed=${this.previewDark ? 'true' : 'false'} ?disabled=${blocked}
                  @click=${() => { this.previewDark = true; }}>Dark</button>
              </div>
            </div>`}
        </div>
        ${this.renderError ? html`<p class="render-error" role="alert">${this.renderError}. The preview shows the last version that rendered.</p>` : nothing}
        ${forms.length === 0 ? nothing : html`<iframe class="preview-iframe" title="Preview" .srcdoc=${this.previewSrcdoc()} sandbox="allow-same-origin allow-scripts"></iframe>`}
        <div slot="note">Rendered by central from the values and the custom CSS as you change them. The width is the phone a client's users most likely hold.</div>
      </versola-preview-pane>`;
  }

  private renderMeta(): TemplateResult {
    const rest = this.usedBy.slice(2);
    return html`
      ${Object.keys(this.theme.tokens?.variables ?? {}).length > 0 ? html`<span class="chip">Token overrides</span>` : nothing}
      ${this.theme.css.trim() !== '' ? html`<span class="chip">Custom CSS</span>` : nothing}
      <span class="chip">${this.theme.tenantId ? 'This tenant' : 'Global'}</span>
      ${this.usedBy.length === 0 ? html`<span class="chip">No clients</span>` : html`
        <span class="muted">Used by</span>
        <span class="tag-list">${this.usedBy.slice(0, 2).map(id => html`<span class="tag" title=${id}>${id}</span>`)}${rest.length ? html`<span class="chip more" title=${rest.join(', ')}>+${rest.length}</span>` : nothing}</span>`}
    `;
  }

  render() {
    const unsaved = this.unsaved;
    return html`
      ${this.isNew ? html`
        <div class="new-head"><div class="form-header-lead"><versola-nav-toggle></versola-nav-toggle><h1 class="form-title">Create theme</h1></div></div>` : html`
        <versola-record-header .crumbs=${[{ label: 'Themes', onSelect: () => void this.leave() }, { label: this.theme.id }]}
          .heading=${this.theme.id} .recordId=${this.theme.id} idLabel="theme ID" idAsTitle .meta=${this.renderMeta()}></versola-record-header>`}

      ${this.notice ? html`<div class="banner ok" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('check')}${this.notice === 'created' ? 'Theme created' : 'Theme saved'}</div></div></div>` : nothing}
      ${this.error ? html`<div class="banner warn" role="alert"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}Something went wrong</div><div>${this.error}</div></div>
        <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.error = ''; }}>Dismiss</button></div>` : nothing}

      <div class="layout">
        <div class="controls">
          ${this.isNew ? this.renderBasics() : nothing}
          ${this.renderBase()}${this.renderLook()}${this.renderAdvanced()}
          ${!this.isNew && this.canManage ? html`
            <versola-danger-card heading="Delete theme" text="Clients that use a theme must be moved to another one before it can go."
              actionLabel="Delete theme" .actionAriaLabel=${`Delete theme ${this.theme.id}`} .disabledReason=${this.deleteReason}
              @action=${() => void this.deleteTheme()}></versola-danger-card>` : nothing}
        </div>
        ${this.renderPreview()}
      </div>

      ${this.canManage ? html`
        <div class="bar">
          <div class="bar-text" role="status">${this.isNew ? (unsaved.length ? `New theme: ${unsaved.join(', ')}` : 'New theme') : unsaved.length ? `Unsaved: ${unsaved.join(', ')}` : 'No unsaved changes'}</div>
          <div class="bar-actions">
            <button type="button" class="btn btn-secondary" ?disabled=${this.saving} @click=${() => void this.leave()}>Cancel</button>
            <button type="button" class="btn btn-primary" ?disabled=${this.saving || !this.canSave} @click=${() => void this.save()}>${this.saving ? 'Saving…' : this.isNew ? 'Create theme' : 'Save theme'}</button>
          </div>
        </div>` : nothing}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-theme-record': VersolaThemeRecord;
  }
}
