import { LitElement, html, css, nothing, type PropertyValues, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, formStyles, segmentedStyles, tagStyles } from '../styles/components';
import type { BackendProperty, FormPreview, FormRecord, Locale, StringArrayProperty, ThemeRecord } from '../types';
import { setActiveFormVersion, updateForm } from '../utils/central-api';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { checkLocalizations, effectiveTexts, formatLocalizations, isBuiltInForm, localeGaps, namesWithOverflow, type Localizations } from '../utils/form-texts';
import { exclusiveAnyOfValues, humanizeLabel, toggleAnyOf } from '../utils/helpers';
import { dispatchRecordRoute } from '../utils/record-route';
import { buildPreviewSrcdoc, localeNamesOf, previewStylesheets } from '../utils/preview';
import { pickScenario, renderScenarioControl } from './preview-scenarios';
import { statusIcon } from './status-icon';
import { valueChips } from './value-chips';
import './code-editor';
import './nav-toggle';
import './preview-pane';
import './record-header';

export const FORM_TABS = ['texts', 'style', 'script', 'properties', 'versions'] as const;
export type FormTab = (typeof FORM_TABS)[number];
const TAB_LABELS: Record<FormTab, string> = { texts: 'Texts', style: 'Style', script: 'Script', properties: 'Properties', versions: 'Versions' };

type PropertyValue = string | boolean | number | string[];
export type FormSaved = { id: string };

const WIDTHS = [['Mobile', 390], ['Desktop', 1280]] as const;

/**
 * One form's page, which is also its editor: the record header (ID, kind, active version, languages),
 * tabs (Texts, Style, Script, Properties for a custom form, Versions) and a live preview that stays
 * on screen beside them, in the language being edited. Saving stores a new version without
 * activating it (`Save as new version`, the page's one primary action); activating, and rolling back,
 * is `Set active` under Versions. A form has no delete, so there is no danger card.
 *
 * The record does not fetch: the list passes every stored version and asks for a reload through
 * `forms-changed`. The tab is `?edit=<tab>` (Texts when absent).
 */
@customElement('versola-form-record')
export class VersolaFormRecord extends LitElement {
  /** Every stored version of this form, newest first; empty for a form being created. */
  @property({ attribute: false }) versions: FormRecord[] = [];
  @property({ type: Boolean }) isNew = false;
  @property({ type: Boolean }) canManage = false;
  @property({ type: String }) tab: string | null = null;
  @property({ attribute: false }) locales: Locale[] = [];
  @property({ attribute: false }) themes: ThemeRecord[] = [];
  /** The states central lists for the built-in forms (all forms; this one's are picked by its id). */
  @property({ attribute: false }) previews: FormPreview[] = [];
  /** A version the list has just stored for this form: said once, in a banner. */
  @property({ attribute: false }) saved: { id: string; version: number } | null = null;

  @state() private editId = '';
  @state() private baseVersion = 0;
  @state() private editStyle = '';
  @state() private editJsSource = '';
  /** The texts as last read from a well-formed edit; what the preview and the summary show while the text is mid-edit. */
  @state() private editLocalizations: Localizations = {};
  /** What is typed in the JSON editor, kept as typed whatever its faults. */
  @state() private localizationsText = '';
  @state() private localizationErrors: string[] = [];
  @state() private editProperties: BackendProperty[] = [];
  @state() private locale = 'en';
  @state() private previewVersion: number | null = null;
  @state() private previewTheme = '';
  @state() private previewScenarioId = '';
  @state() private previewWidth = 390;
  @state() private propertyValues: Record<string, PropertyValue> = {};
  @state() private themeStylesheets: Record<string, string> = {};
  @state() private frameDoc = '';
  @state() private saving = false;
  @state() private working = false;
  @state() private error = '';
  @state() private activated: number | null = null;
  @state() private submitted = false;

  private baseline = '';
  private pendingDoc = '';
  private docTimer: ReturnType<typeof setTimeout> | undefined;

  private readonly handleIframeMessage = (e: MessageEvent) => {
    if (e.data?.type === 'versola:locale-change' && typeof e.data.locale === 'string') this.locale = e.data.locale;
  };

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
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

      .muted {
        color: var(--text-secondary);
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

      .new-head .actions {
        margin-top: var(--spacing-md);
      }

      .layout {
        display: grid;
        grid-template-columns: minmax(0, 1fr) 340px;
        gap: var(--spacing-lg);
        align-items: start;
      }

      @media (min-width: 1360px) {
        .layout {
          grid-template-columns: minmax(0, 1fr) 500px;
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

      .editor {
        padding: 0;
        overflow: visible;
      }

      /* ── tabs ── */
      .tabs {
        display: flex;
        gap: var(--spacing-xs);
        padding: 0 var(--spacing-md);
        border-bottom: 1px solid var(--border-default);
        overflow-x: auto;
      }

      .tab {
        display: inline-flex;
        align-items: center;
        gap: 0.375rem;
        min-height: 3rem;
        padding: 0 var(--spacing-md);
        border: 0;
        border-bottom: 3px solid transparent;
        margin-bottom: -1px;
        background: none;
        color: var(--text-secondary);
        font: inherit;
        font-weight: 600;
        cursor: pointer;
        white-space: nowrap;
      }

      .tab:hover {
        color: var(--text-primary);
      }

      .tab[aria-selected='true'] {
        color: var(--text-primary);
        border-bottom-color: var(--accent);
      }

      .tab:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: -2px;
      }

      .gap-dot {
        width: 0.5rem;
        height: 0.5rem;
        border-radius: 50%;
        background: var(--warning);
      }

      .panel {
        min-width: 0;
      }

      .panel-pad {
        padding: var(--spacing-lg);
      }

      /* ── texts ── */
      .texts-summary {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr);
        gap: 0.25rem var(--spacing-md);
        margin: 0 0 var(--spacing-md);
        padding: var(--spacing-sm) var(--spacing-md);
        background: var(--surface-inset);
        border: 1px solid var(--border-subtle);
        border-radius: var(--radius-md);
        font-size: var(--text-sm);
      }

      .texts-summary dt {
        font-size: var(--text-xs);
        font-weight: 700;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        color: var(--text-secondary);
        padding-top: 0.125rem;
      }

      .texts-summary dd {
        margin: 0;
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem 0.75rem;
        min-width: 0;
      }

      .texts-summary code {
        font-family: var(--font-mono);
      }

      .texts-summary .gap {
        color: var(--warning-text);
        overflow-wrap: anywhere;
      }

      .texts-errors {
        margin: var(--spacing-sm) 0 0;
        padding: 0;
        list-style: none;
      }

      .texts-errors li {
        padding: 0.25rem 0 0.25rem var(--spacing-md);
        border-left: 3px solid var(--danger);
        font-size: var(--text-sm);
        color: var(--danger-text);
        overflow-wrap: anywhere;
      }

      .texts-errors li + li {
        margin-top: 0.25rem;
      }

      .texts-ok {
        margin: var(--spacing-sm) 0 0;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .empty-note {
        padding: var(--spacing-lg);
        color: var(--text-secondary);
        font-size: var(--text-base);
      }

      /* ── script, style, properties ── */
      .hint {
        margin: 0 0 var(--spacing-sm);
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .property-row {
        display: grid;
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
        gap: var(--spacing-md);
        align-items: start;
        padding: var(--spacing-md) 0;
        border-top: 1px solid var(--border-subtle);
      }

      .property-actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        margin-bottom: var(--spacing-md);
      }

      .allowed-values {
        display: flex;
        flex-wrap: wrap;
        gap: 0.375rem;
        margin-bottom: 0.5rem;
      }

      .tag button {
        margin-left: 0.375rem;
        padding: 0;
        border: 0;
        background: none;
        color: inherit;
        font: inherit;
        cursor: pointer;
      }

      .tag button:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      /* ── versions ── */
      .version-row {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr) auto;
        gap: var(--spacing-sm) var(--spacing-md);
        align-items: center;
        padding: var(--spacing-md) var(--spacing-lg);
        border-top: 1px solid var(--border-subtle);
      }

      .version-row:first-of-type {
        border-top: 0;
      }

      .version-name {
        font-family: var(--font-mono);
        font-weight: 600;
        color: var(--accent);
      }

      .version-actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        justify-content: flex-end;
      }

      @media (max-width: 560px) {
        .version-row {
          grid-template-columns: minmax(0, 1fr);
        }

        .version-actions {
          justify-content: flex-start;
        }

        .property-row {
          grid-template-columns: minmax(0, 1fr);
        }
      }

      /* ── preview toolbar ── */
      .preview-toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm) var(--spacing-lg);
        align-items: center;
      }

      .preview-toolbar select {
        width: auto;
        min-width: 8rem;
      }

      .toggle-wrap {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        font-size: var(--text-sm);
      }

      .number-field {
        width: 5rem;
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('message', this.handleIframeMessage);
  }

  disconnectedCallback() {
    window.removeEventListener('message', this.handleIframeMessage);
    clearTimeout(this.docTimer);
    super.disconnectedCallback();
  }

  // ── data ────────────────────────────────────────────────────────────────

  private get formId(): string {
    return this.isNew ? this.editId.trim() : this.versions[0]?.id ?? this.editId;
  }

  private get latest(): FormRecord | undefined {
    return this.versions[0];
  }

  private get active(): FormRecord | undefined {
    return this.versions.find(version => version.active);
  }

  private get currentTab(): FormTab {
    return this.availableTabs.includes(this.tab as FormTab) ? (this.tab as FormTab) : this.isNew ? 'script' : 'texts';
  }

  private get builtIn(): boolean {
    return !this.isNew && isBuiltInForm(this.formId, this.previews);
  }

  /** A built-in form's backend properties are central's to define: only a custom form's author edits them. */
  private get availableTabs(): FormTab[] {
    return this.isNew
      ? ['script', 'style', 'texts', 'properties']
      : this.builtIn ? ['texts', 'style', 'script', 'versions'] : ['texts', 'style', 'script', 'properties', 'versions'];
  }

  private get scenarios() {
    return this.previews.find(preview => preview.formId === this.formId)?.scenarios ?? [];
  }

  private get usableLocales(): Locale[] {
    const active = this.locales.filter(locale => locale.active);
    const list = active.length > 0 ? active : this.locales;
    return list.length > 0 ? list : [{ code: 'en', name: 'English', isDefault: true, active: true }];
  }

  private get reference(): string {
    return this.usableLocales.find(locale => locale.isDefault)?.code ?? this.usableLocales[0].code;
  }

  private get gaps(): Record<string, string[]> {
    return localeGaps(this.editLocalizations, this.usableLocales.map(locale => locale.code));
  }

  /** A built-in form's own keys: the reference language of its newest stored version. A custom form's are its author's. */
  private get builtInKeys(): string[] | null {
    if (!this.builtIn) return null;
    const stored = this.latest?.localizations ?? {};
    const texts = stored[this.reference] ?? Object.values(stored)[0];
    return texts ? Object.keys(texts) : null;
  }

  /** The text of the editor while it is not well-formed is part of what is unsaved, though the texts are not yet known. */
  private snapshot(): string {
    const pending = this.localizationErrors.length > 0 && this.localizationsText !== this.baselineText ? this.localizationsText : '';
    return JSON.stringify([this.isNew ? this.editId.trim() : '', this.editStyle, this.editJsSource, this.editLocalizations, this.editProperties, pending]);
  }

  private baselineText = '';

  private setLocalizationsText(text: string) {
    this.localizationsText = text;
    const check = checkLocalizations(text, { locales: this.usableLocales.map(locale => locale.code), reference: this.reference, builtInKeys: this.builtInKeys });
    this.localizationErrors = check.errors;
    if (check.parsed) this.editLocalizations = check.parsed;
  }

  private get dirty(): boolean {
    return this.snapshot() !== this.baseline;
  }

  private loadBase(version: FormRecord | undefined) {
    this.baseVersion = version?.version ?? 0;
    this.editStyle = version?.style ?? '';
    this.editJsSource = version?.jsSource ?? '';
    this.editProperties = version?.properties ? [...version.properties] : [];
    this.editLocalizations = Object.fromEntries(
      Object.entries(version?.localizations ?? {}).map(([code, texts]) => [code, typeof texts === 'object' && texts !== null ? { ...texts } : {}]),
    );
    // The stored texts are shown as they are, faults included: a version saved before the rules were
    // enforced is fixed here, not hidden.
    this.localizationsText = formatLocalizations(Object.keys(this.editLocalizations).length === 0
      ? Object.fromEntries(this.usableLocales.map(locale => [locale.code, {}]))
      : this.editLocalizations);
    this.baselineText = this.localizationsText;
    this.setLocalizationsText(this.localizationsText);
    this.baseline = this.snapshot();
  }

  willUpdate(changed: PropertyValues) {
    if (changed.has('themes')) void previewStylesheets(this.themes).then(sheets => { this.themeStylesheets = sheets; });
    const previous = changed.get('versions') as FormRecord[] | undefined;
    const idChanged = changed.has('versions') && (previous?.[0]?.id ?? '') !== (this.versions[0]?.id ?? '');
    const newer = changed.has('versions') && (this.versions[0]?.version ?? 0) > (previous?.[0]?.version ?? 0);
    if (changed.has('isNew') || idChanged || newer || (changed.has('versions') && this.baseline === '')) {
      this.editId = this.isNew ? '' : this.versions[0]?.id ?? '';
      this.loadBase(this.isNew ? undefined : this.versions[0]);
      this.previewVersion = null;
      this.error = '';
      this.activated = null;
      this.submitted = false;
    } else if (changed.has('locales') || changed.has('previews')) this.setLocalizationsText(this.localizationsText);
    if (!this.usableLocales.some(locale => locale.code === this.locale)) this.locale = this.reference;
    this.scheduleFrame();
  }

  // ── preview ─────────────────────────────────────────────────────────────

  private effectivePropValue(prop: BackendProperty): PropertyValue {
    const stored = this.propertyValues[prop.name];
    if (stored !== undefined) return stored;
    if (prop.type === 'StringArrayProperty') return prop.allowedValues;
    if (prop.type === 'NumberProperty') return prop.default;
    return false;
  }

  private get previewSource(): { style: string; jsCompiled: string; localizations: Localizations; properties: BackendProperty[] } {
    const stored = this.previewVersion === null ? undefined : this.versions.find(version => version.version === this.previewVersion);
    if (stored) return { style: stored.style, jsCompiled: stored.jsCompiled ?? '', localizations: stored.localizations, properties: stored.properties };
    return {
      style: this.editStyle,
      // The compiled script is the stored version's: the console edits the source for reference.
      jsCompiled: (this.baseVersion ? this.versions.find(version => version.version === this.baseVersion)?.jsCompiled : null) ?? '',
      localizations: this.editLocalizations,
      properties: this.editProperties,
    };
  }

  private get previewThemeId(): string {
    return this.previewTheme || this.themes.find(t => t.id === 'default')?.id || this.themes[0]?.id || '';
  }

  private buildSrcdoc(): string {
    const source = this.previewSource;
    const themeId = this.previewThemeId;
    // Every key shows in the reference language until the chosen one has a text for it, as the form itself does.
    const shown: Localizations = {
      ...source.localizations,
      [this.locale]: effectiveTexts(source.localizations, this.locale, this.reference),
    };
    return buildPreviewSrcdoc({
      formId: this.formId,
      properties: source.properties,
      getValue: prop => this.effectivePropValue(prop),
      scenario: pickScenario(this.scenarios, this.previewScenarioId),
      localizations: shown,
      locale: this.locale,
      locales: this.usableLocales.map(locale => locale.code),
      localeNames: localeNamesOf(this.usableLocales),
      themeCss: this.themeStylesheets[themeId] ?? this.themes.find(t => t.id === themeId)?.css ?? '',
      style: source.style,
      jsCompiled: source.jsCompiled,
    });
  }

  /** The frame is redrawn a moment after the last keystroke, not on every one. */
  private scheduleFrame() {
    const next = this.buildSrcdoc();
    if (next === this.pendingDoc) return;
    const first = this.pendingDoc === '';
    this.pendingDoc = next;
    clearTimeout(this.docTimer);
    if (first) {
      this.frameDoc = next;
      return;
    }
    this.docTimer = setTimeout(() => { this.frameDoc = this.pendingDoc; }, 200);
  }

  // ── actions ─────────────────────────────────────────────────────────────

  private selectTab(tab: FormTab) {
    dispatchRecordRoute(this, { edit: tab === 'texts' ? null : tab });
  }

  private async leave() {
    if (this.dirty && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
  }

  private get idError(): string {
    if (!this.isNew) return '';
    const id = this.editId.trim();
    if (!id) return this.submitted ? 'A form ID is required.' : '';
    if (!/^[a-z0-9][a-z0-9-]*$/.test(id)) return 'Lowercase letters, digits and dashes.';
    return '';
  }

  private async save() {
    if (this.saving) return;
    this.submitted = true;
    if (!this.formId || this.idError) return;
    if (this.localizationErrors.length > 0) {
      // The faults are listed under the editor; bring the person to them rather than to a banner that repeats them.
      this.selectTab('texts');
      this.focusTextsEditor();
      return;
    }
    this.saving = true;
    this.error = '';
    try {
      await updateForm(this.formId, this.editStyle, this.editJsSource || null, this.previewSourceCompiled || null, this.editLocalizations, this.editProperties);
      this.baseline = this.snapshot();
      this.dispatchEvent(new CustomEvent<FormSaved>('form-saved', { detail: { id: this.formId }, bubbles: true, composed: true }));
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Save failed';
    } finally {
      this.saving = false;
    }
  }

  /** The Texts tab is drawn after the route changes, which is not this component's update: look for the editor for a moment. */
  private focusTextsEditor(attempts = 20) {
    const textarea = this.renderRoot.querySelector<HTMLElement>('#form-texts')?.shadowRoot?.querySelector('textarea');
    if (textarea) textarea.focus();
    else if (attempts > 0) requestAnimationFrame(() => this.focusTextsEditor(attempts - 1));
  }

  /** What is stored as compiled: the base version's, which the console does not change. */
  private get previewSourceCompiled(): string {
    return this.versions.find(version => version.version === this.baseVersion)?.jsCompiled ?? '';
  }

  private async setActive(version: number) {
    if (this.working) return;
    this.working = true;
    this.error = '';
    try {
      await setActiveFormVersion(this.formId, version);
      this.activated = version;
      this.dispatchEvent(new CustomEvent('forms-changed', { bubbles: true, composed: true }));
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Could not set the version active';
    } finally {
      this.working = false;
    }
  }

  private async editCopyOf(version: FormRecord) {
    if (this.dirty && !await confirmDiscardDraft()) return;
    this.loadBase(version);
    this.previewVersion = null;
    this.selectTab('texts');
  }

  // ── properties (a custom form's) ────────────────────────────────────────

  private addProperty(type: 'BooleanProperty' | 'StringArrayProperty' | 'NumberProperty') {
    const name = `new_property_${this.editProperties.length + 1}`;
    const prop: BackendProperty = type === 'BooleanProperty' ? { type, name } : type === 'NumberProperty' ? { type, name, default: 0 } : { type, name, allowedValues: [] };
    this.editProperties = [...this.editProperties, prop];
  }

  private removeProperty(index: number) {
    this.editProperties = this.editProperties.filter((_, i) => i !== index);
  }

  private renameProperty(index: number, name: string) {
    this.editProperties = this.editProperties.map((prop, i) => (i === index ? { ...prop, name } : prop));
  }

  private addAllowedValue(index: number, value: string) {
    const trimmed = value.trim();
    const prop = this.editProperties[index];
    if (!trimmed || prop.type !== 'StringArrayProperty' || prop.allowedValues.includes(trimmed)) return;
    this.editProperties = this.editProperties.map((p, i) => (i === index ? { ...p, allowedValues: [...(p as StringArrayProperty).allowedValues, trimmed] } : p));
  }

  private removeAllowedValue(propIndex: number, valueIndex: number) {
    this.editProperties = this.editProperties.map((p, i) =>
      i === propIndex && p.type === 'StringArrayProperty' ? { ...p, allowedValues: p.allowedValues.filter((_, j) => j !== valueIndex) } : p);
  }

  // ── rendering ───────────────────────────────────────────────────────────

  private renderTexts(): TemplateResult {
    const codes = this.usableLocales.map(locale => locale.code);
    const texts = this.editLocalizations;
    const gaps = localeGaps(texts, codes);
    const present = Object.keys(texts);
    const errors = this.localizationErrors;
    return html`
      <div class="panel-pad">
        <p class="hint">All languages in one JSON object: the language code, then each key with its text. Every language has the same keys, and a
          <code class="mono">{placeholder}</code> is spelled the same wherever a key uses it.</p>
        <dl class="texts-summary" aria-label="Summary of the texts">
          <dt>Languages</dt>
          <dd>${present.length === 0 ? html`<span class="muted">none yet</span>` : present.map(code => html`<code>${code}</code>`)}</dd>
          <dt>Missing</dt>
          <dd>${Object.keys(gaps).length === 0 ? html`<span class="muted">Every language has every key.</span>`
            : Object.entries(gaps).map(([code, keys]) => html`<span class="gap"><code>${code}</code>: ${namesWithOverflow(keys)}</span>`)}</dd>
        </dl>
        <versola-code-editor id="form-texts" language="json" rows="24" label="Form texts, JSON" ?disabled=${!this.canManage} ?invalid=${errors.length > 0}
          .value=${this.localizationsText} @code-input=${(e: CustomEvent<{ value: string }>) => this.setLocalizationsText(e.detail.value)}></versola-code-editor>
        <div aria-live="polite" aria-atomic="true">
          ${errors.length > 0 ? html`
            <ul class="texts-errors" aria-label="Problems in the texts">${errors.map(error => html`<li>${error}</li>`)}</ul>`
            : html`<p class="texts-ok">${statusIcon('check')} The texts can be saved.</p>`}
        </div>
      </div>`;
  }

  private renderStyle(): TemplateResult {
    return html`
      <div class="panel-pad">
        <p class="hint">Applied to this form only, after the theme's stylesheet.</p>
        <versola-code-editor language="css" rows="18" label="Per-form CSS" ?disabled=${!this.canManage} .value=${this.editStyle}
          @code-input=${(e: CustomEvent<{ value: string }>) => { this.editStyle = e.detail.value; }}></versola-code-editor>
      </div>`;
  }

  private renderScript(): TemplateResult {
    return html`
      <div class="panel-pad">
        <p class="hint">The source of the Solid.js form (JSX/TSX), for reference. A built-in form's source has its shared modules inlined and its type annotations removed; the compiled JavaScript is what runs.</p>
        <versola-code-editor language="typescript" rows="22" label="Solid.js source" ?disabled=${!this.canManage} .value=${this.editJsSource}
          @code-input=${(e: CustomEvent<{ value: string }>) => { this.editJsSource = e.detail.value; }}></versola-code-editor>
      </div>`;
  }

  private renderProperties(): TemplateResult {
    return html`
      <div class="panel-pad">
        <p class="hint">Properties the backend reads, and the states the preview can show for this form.</p>
        ${this.canManage ? html`
          <div class="property-actions">
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.addProperty('BooleanProperty')}>+ Add Boolean</button>
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.addProperty('StringArrayProperty')}>+ Add AnyOf</button>
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.addProperty('NumberProperty')}>+ Add Number</button>
          </div>` : nothing}
        ${this.editProperties.length === 0 ? html`<div class="empty-note" style="padding:0">No properties defined.</div>` : this.editProperties.map((prop, index) => html`
          <div class="property-row">
            <div class="form-group">
              <label for=${`prop-name-${index}`}>Name</label>
              <input id=${`prop-name-${index}`} type="text" class="compact-input" .value=${prop.name} ?readonly=${!this.canManage}
                @input=${(e: Event) => this.renameProperty(index, (e.target as HTMLInputElement).value)} />
            </div>
            <div class="form-group">
              <span class="field-label">Type: ${prop.type.replace('Property', '')}</span>
              ${prop.type === 'StringArrayProperty' ? html`
                <div class="allowed-values">
                  ${prop.allowedValues.map((value, valueIndex) => html`
                    <span class="tag">${value}${this.canManage ? html`<button type="button" aria-label=${`Remove value ${value}`} @click=${() => this.removeAllowedValue(index, valueIndex)}>×</button>` : nothing}</span>`)}
                </div>
                ${this.canManage ? html`<input type="text" class="compact-input" aria-label=${`Add a value to ${prop.name}`} placeholder="Add value, then Enter"
                  @keydown=${(e: KeyboardEvent) => {
                    if (e.key !== 'Enter') return;
                    e.preventDefault();
                    this.addAllowedValue(index, (e.target as HTMLInputElement).value);
                    (e.target as HTMLInputElement).value = '';
                  }} />` : nothing}` : nothing}
            </div>
            ${this.canManage ? html`<button type="button" class="btn btn-danger-secondary btn-sm" aria-label=${`Remove property ${prop.name}`} @click=${() => this.removeProperty(index)}>Remove</button>` : nothing}
          </div>`)}
      </div>`;
  }

  private renderVersions(): TemplateResult {
    const active = this.active;
    const newest = this.latest;
    const pending = newest && (!active || newest.version > active.version);
    return html`
      ${this.isNew || this.versions.length === 0 ? html`<div class="empty-note">No version is stored yet. Saving stores v1.</div>` : html`
        ${pending ? html`
          <div class="banner warn" role="status" style="margin:var(--spacing-md)">
            <div class="banner-text">
              <div class="banner-title">${statusIcon('alert')}v${newest!.version} is stored but not active</div>
              <div>${active ? `Users still get v${active.version}. Set v${newest!.version} active to roll it out.` : 'No version is active yet, so users get nothing from this form. Set one active.'}</div>
            </div>
          </div>` : nothing}
        ${this.versions.map(version => {
          const newer = !active || version.version > active.version;
          return html`
            <div class="version-row" data-version=${version.version}>
              <span class="version-name">v${version.version}</span>
              <div class="chips">
                ${version.active ? html`<span class="chip ok">Active</span>` : newer ? html`<span class="chip warn">Stored, not active</span>` : html`<span class="chip">Older</span>`}
                ${this.previewVersion === version.version ? html`<span class="chip value">Previewing</span>` : nothing}
              </div>
              <div class="version-actions">
                <button type="button" class="btn btn-secondary btn-sm" aria-label=${this.previewVersion === version.version ? 'Back to my edits' : `Preview v${version.version}`}
                  @click=${() => { this.previewVersion = this.previewVersion === version.version ? null : version.version; }}>${this.previewVersion === version.version ? 'Back to my edits' : 'Preview'}</button>
                ${this.canManage ? html`
                  <button type="button" class="btn btn-secondary btn-sm" aria-label=${`Edit a copy of v${version.version}`} @click=${() => void this.editCopyOf(version)}>Edit a copy</button>
                  ${version.active ? nothing : html`<button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.working} aria-label=${`Set active v${version.version}`}
                    @click=${() => void this.setActive(version.version)}>Set active</button>`}` : nothing}
              </div>
            </div>`;
        })}`}
    `;
  }

  private renderPanel(tab: FormTab): TemplateResult {
    switch (tab) {
      case 'texts': return this.renderTexts();
      case 'style': return this.renderStyle();
      case 'script': return this.renderScript();
      case 'properties': return this.renderProperties();
      default: return this.renderVersions();
    }
  }

  private renderPreviewToolbar(): TemplateResult {
    const scenarios = this.scenarios;
    const source = this.previewSource;
    return html`
      <div class="preview-toolbar" slot="toolbar">
        <div class="ctrl-group">
          <label class="ctrl-label" for="preview-locale">Language</label>
          <select id="preview-locale" .value=${this.locale} @change=${(e: Event) => { this.locale = (e.target as HTMLSelectElement).value; }}>
            ${this.usableLocales.map(l => html`<option value=${l.code} ?selected=${l.code === this.locale}>${l.name}</option>`)}
          </select>
        </div>
        ${renderScenarioControl(scenarios, pickScenario(scenarios, this.previewScenarioId)?.id ?? '', id => { this.previewScenarioId = id; })}
        ${this.themes.length > 0 ? html`
          <div class="ctrl-group">
            <label class="ctrl-label" for="preview-theme">Theme</label>
            <select id="preview-theme" .value=${this.previewThemeId} @change=${(e: Event) => { this.previewTheme = (e.target as HTMLSelectElement).value; }}>
              ${this.themes.map(t => html`<option value=${t.id} ?selected=${t.id === this.previewThemeId}>${t.id}</option>`)}
            </select>
          </div>` : nothing}
        <div class="ctrl-group">
          <span class="ctrl-label">Width</span>
          <div class="seg-control" role="group" aria-label="Preview width">
            ${WIDTHS.map(([label, width]) => html`<button type="button" class="seg-btn ${this.previewWidth === width ? 'seg-active' : ''}" aria-pressed=${this.previewWidth === width ? 'true' : 'false'}
              @click=${() => { this.previewWidth = width; }}>${label}</button>`)}
          </div>
        </div>
        ${scenarios.length > 0 ? nothing : source.properties.map(prop => this.renderPropertyControl(prop))}
        ${this.previewVersion !== null ? html`<span class="chip warn">Previewing stored v${this.previewVersion}</span>` : nothing}
      </div>`;
  }

  private renderPropertyControl(prop: BackendProperty): TemplateResult {
    const value = this.effectivePropValue(prop);
    const set = (next: PropertyValue) => { this.propertyValues = { ...this.propertyValues, [prop.name]: next }; };
    if (prop.type === 'BooleanProperty') {
      return html`<label class="toggle-wrap"><span class="ctrl-label">${humanizeLabel(prop.name)}</span><input type="checkbox" .checked=${!!value}
        @change=${(e: Event) => set((e.target as HTMLInputElement).checked)} />${value ? 'on' : 'off'}</label>`;
    }
    if (prop.type === 'NumberProperty') {
      return html`<label class="ctrl-group"><span class="ctrl-label">${humanizeLabel(prop.name)}</span><input type="number" class="number-field" .value=${String(value)}
        min=${prop.min !== undefined ? prop.min : nothing} max=${prop.max !== undefined ? prop.max : nothing}
        @change=${(e: Event) => set(Number((e.target as HTMLInputElement).value))} /></label>`;
    }
    const selected = Array.isArray(value) ? value : [];
    const exclusive = exclusiveAnyOfValues(prop.name);
    return html`
      <div class="ctrl-group">
        <span class="ctrl-label">${humanizeLabel(prop.name)}</span>
        <div class="seg-control" role="group" aria-label=${humanizeLabel(prop.name)}>
          ${prop.allowedValues.map(option => {
            const on = selected.includes(option);
            return html`<button type="button" class="seg-btn ${on ? 'seg-active' : ''}" aria-pressed=${on ? 'true' : 'false'} ?disabled=${on && selected.length === 1}
              @click=${() => { const next = toggleAnyOf(selected, option, exclusive); if (next) set(next); }}>${option}</button>`;
          })}
        </div>
      </div>`;
  }

  private renderMeta(): TemplateResult {
    const summary = this.summary;
    const active = this.active;
    const newest = this.latest;
    return html`
      <span class="chip ${this.builtIn ? '' : 'value'}">${this.builtIn ? 'Built-in' : 'Custom'}</span>
      ${active ? html`<span class="chip ok">Active v${active.version}</span>` : html`<span class="chip warn">No active version</span>`}
      ${newest && active && newest.version > active.version ? html`<span class="chip warn">v${newest.version} not active</span>` : nothing}
      ${summary.complete.length > 0 ? valueChips(summary.complete, { mono: false, extra: summary.missing.length ? html`<span class="chip warn">Missing: ${namesWithOverflow(summary.missing)}</span>` : nothing })
        : summary.missing.length ? html`<span class="chip warn">Missing: ${namesWithOverflow(summary.missing)}</span>` : nothing}
    `;
  }

  private get summary() {
    const codes = this.usableLocales.map(locale => locale.code);
    const gaps = localeGaps(this.editLocalizations, codes);
    return { complete: codes.filter(code => !(code in gaps)), missing: codes.filter(code => code in gaps) };
  }

  private renderBanners(): TemplateResult {
    const next = (this.latest?.version ?? 0) + 1;
    const active = this.active;
    const saved = this.saved && this.saved.id === this.formId ? this.saved : null;
    return html`
      ${this.error ? html`<div class="banner warn" role="alert"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}Something went wrong</div><div>${this.error}</div></div>
        <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.error = ''; }}>Dismiss</button></div>` : nothing}
      ${this.activated !== null ? html`<div class="banner ok" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('check')}v${this.activated} is active</div><div>Users get it from now on.</div></div></div>` : nothing}
      ${saved ? html`<div class="banner ok" role="status"><div class="banner-text"><div class="banner-title">${statusIcon('check')}Stored v${saved.version}</div>
        <div>${active && active.version === saved.version ? 'It is active.' : `It is not active yet${active ? `: users keep v${active.version}` : ''}. Set it active under Versions.`}</div></div>
        ${active && active.version === saved.version ? nothing : html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => this.selectTab('versions')}>Open versions</button>`}</div>` : nothing}
      ${this.canManage ? html`<div class="banner info" role="note"><div class="banner-text"><div class="banner-title">${this.isNew ? 'A new form' : `Editing a copy of v${this.baseVersion}`}</div>
        <div>Saving stores v${next} without activating it.${active ? ` Users keep v${active.version} until you set a newer version active under Versions.` : ' Set it active under Versions to use it.'}</div></div></div>` : nothing}
    `;
  }

  private renderHeader(): TemplateResult {
    const save = this.canManage ? html`<button slot="actions" type="button" class="btn btn-primary" ?disabled=${this.saving} @click=${() => void this.save()}>${this.saving ? 'Saving…' : 'Save as new version'}</button>` : nothing;
    if (this.isNew) {
      const idError = this.idError;
      return html`
        <div class="new-head">
          <div class="form-header-lead"><versola-nav-toggle></versola-nav-toggle><h1 class="form-title">Create form</h1></div>
          <div class="form-group" style="margin-top:var(--spacing-md)">
            <label for="form-id">Form ID *</label>
            <input id="form-id" type="text" class="compact-input mono ${idError ? 'input-error' : ''}" autocomplete="off" spellcheck="false" placeholder="e.g. signup"
              aria-invalid=${idError ? 'true' : 'false'} aria-describedby="form-id-hint" .value=${this.editId}
              @input=${(e: Event) => { this.editId = (e.target as HTMLInputElement).value.trim(); }} />
            ${idError ? html`<div class="error-message" id="form-id-hint" role="alert">${idError}</div>` : html`<div class="hint" id="form-id-hint" style="margin:0.25rem 0 0">Lowercase letters, digits and dashes. Fixed once created.</div>`}
          </div>
          <div class="actions">
            <button type="button" class="btn btn-secondary" ?disabled=${this.saving} @click=${() => void this.leave()}>Cancel</button>
            ${save ? html`<button type="button" class="btn btn-primary" ?disabled=${this.saving} @click=${() => void this.save()}>${this.saving ? 'Saving…' : 'Save as new version'}</button>` : nothing}
          </div>
        </div>`;
    }
    return html`
      <versola-record-header .crumbs=${[{ label: 'Forms', onSelect: () => void this.leave() }, { label: this.formId }]}
        .heading=${this.formId} .recordId=${this.formId} idLabel="form ID" idAsTitle .meta=${this.renderMeta()}>${save}</versola-record-header>`;
  }

  render() {
    const tab = this.currentTab;
    const tabs = this.availableTabs;
    const gapsInTexts = Object.keys(this.gaps).length > 0;
    return html`
      ${this.renderHeader()}
      ${this.renderBanners()}
      <div class="layout">
        <section class="card editor" aria-label="Form editor">
          <div class="tabs" role="tablist" aria-label="Form regions">
            ${tabs.map(id => html`
              <button type="button" role="tab" id=${`tab-${id}`} class="tab" aria-selected=${id === tab ? 'true' : 'false'} aria-controls="tab-panel"
                tabindex=${id === tab ? 0 : -1} @click=${() => this.selectTab(id)}
                @keydown=${(e: KeyboardEvent) => this.onTabKey(e, tabs, id)}>${TAB_LABELS[id]}${id === 'texts' && gapsInTexts ? html`<span class="gap-dot" title="Some languages are missing texts"></span>` : nothing}</button>`)}
          </div>
          <div class="panel" id="tab-panel" role="tabpanel" aria-labelledby=${`tab-${tab}`}>${this.renderPanel(tab)}</div>
        </section>
        <versola-preview-pane heading="Preview" .logicalWidth=${this.previewWidth} .logicalHeight=${this.previewWidth > 500 ? 720 : 600}>
          ${this.renderPreviewToolbar()}
          <iframe class="preview-iframe" title="Form preview" .srcdoc=${this.frameDoc} sandbox="allow-same-origin allow-scripts"></iframe>
          <div slot="note">Follows the texts as you type them, in the language chosen above.${this.locale === this.reference ? nothing : ` A key that ${this.locale} lacks shows in ${this.usableLocales.find(l => l.code === this.reference)?.name ?? this.reference}.`}</div>
        </versola-preview-pane>
      </div>
    `;
  }

  private onTabKey(e: KeyboardEvent, tabs: FormTab[], current: FormTab) {
    const step = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
    if (!step) return;
    e.preventDefault();
    const next = tabs[(tabs.indexOf(current) + step + tabs.length) % tabs.length];
    this.selectTab(next);
    void this.updateComplete.then(() => this.renderRoot.querySelector<HTMLElement>(`#tab-${next}`)?.focus());
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-form-record': VersolaFormRecord;
  }
}
