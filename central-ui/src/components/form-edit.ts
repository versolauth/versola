import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import type { BackendProperty, FormLocale, FormRecord, StringArrayProperty, ThemeRecord } from '../types';
import { updateForm } from '../utils/central-api';
import { buildPreviewSrcdoc } from '../utils/preview';
import './content-header';
import './code-editor';

@customElement('versola-form-edit')
export class VersolaFormEdit extends LitElement {
  @property({ type: Object }) form!: FormRecord;
  @property({ type: Array }) locales: FormLocale[] = [];
  @property({ type: Array }) themes: ThemeRecord[] = [];

  @state() private editId = '';
  @state() private activeLocale = '';
  @state() private previewLocale = '';
  @state() private editProperties: BackendProperty[] = [];
  @state() private previewPropertyValues: Record<string, string | boolean> = {};
  @state() private previewOpen = false;
  @state() private previewWidth = 0;
  @state() private previewTheme = '';
  @state() private editStyle = '';
  @state() private editJsSource = '';
  @state() private editJsCompiled = '';
  @state() private editLocalizations: Record<string, Record<string, string>> = {};
  @state() private saving = false;
  @state() private saveSuccess = '';
  @state() private saveError = '';

  private readonly handleIframeMessage = (e: MessageEvent) => {
    if (e.data?.type === 'versola:locale-change' && typeof e.data.locale === 'string') {
      this.previewLocale = e.data.locale;
    }
  };

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('message', this.handleIframeMessage);
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    window.removeEventListener('message', this.handleIframeMessage);
  }

  willUpdate(changed: Map<string, unknown>) {
    if (changed.has('form') && this.form) {
      this.editId = this.form.id;
      this.editStyle = this.form.style;
      this.editJsSource = this.form.jsSource ?? '';
      this.editJsCompiled = this.form.jsCompiled ?? '';
      this.editProperties = this.form.properties ? [...this.form.properties] : [];
      this.editLocalizations = Object.fromEntries(
        Object.entries(this.form.localizations).map(([loc, vals]) => [
          loc,
          typeof vals === 'object' && vals !== null ? { ...vals } : {},
        ]),
      );
    }
    if ((changed.has('locales') || changed.has('form')) && this.locales.length > 0) {
      if (!this.activeLocale) this.activeLocale = this.locales[0].code;
      if (!this.previewLocale) this.previewLocale = this.locales[0].code;
    }
  }

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    css`
      :host { display: block; }
      .form-title { font-size: 1.625rem; font-weight: 700; letter-spacing: -0.025em; color: var(--text-primary); margin: 0; }
      .locale-tabs { display: flex; gap: 0.5rem; margin-bottom: var(--spacing-md); flex-wrap: wrap; }
      .locale-tab { padding: 0.375rem 0.875rem; border-radius: var(--radius-md); border: 1px solid var(--border-dark); background: transparent; color: var(--text-secondary); font-size: 0.875rem; cursor: pointer; transition: all var(--transition-fast); }
      .locale-tab.active { background: rgba(88,166,255,0.15); border-color: var(--accent); color: var(--accent); }
      .field-label { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-secondary); margin-bottom: 0.5rem; }
      .section-gap { margin-top: var(--spacing-xl); }
      .hint { font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 0.5rem; }
      .hint code { font-family: var(--font-mono); color: var(--accent); }
      .kv-grid { display: grid; grid-template-columns: minmax(120px, 220px) 1fr; gap: 0.5rem 0.75rem; align-items: center; }
      .kv-key { font-family: var(--font-mono); font-size: 0.8125rem; color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .kv-value { width: 100%; padding: 0.5rem var(--spacing-md); background: rgba(0,0,0,0.25); border: 1px solid var(--border-dark); border-radius: var(--radius-md); font-size: 0.8125rem; color: var(--text-primary); box-sizing: border-box; }
      .kv-value:focus { outline: none; border-color: var(--accent); }
      .form-actions { display: flex; align-items: center; gap: 1rem; justify-content: flex-end; margin-top: var(--spacing-xl); padding-top: var(--spacing-xl); border-top: 1px solid var(--border-dark); }
      .save-msg { font-size: 0.875rem; }
      .save-msg.success { color: var(--success, #3fb950); }
      .save-msg.error { color: var(--danger); }
      .property-row { display: grid; grid-template-columns: 1fr 1fr auto; gap: var(--spacing-md); align-items: start; margin-bottom: var(--spacing-md); padding-bottom: var(--spacing-md); border-bottom: 1px solid var(--border-dark); }
      .property-row:last-child { border-bottom: none; }
      .allowed-values { margin-top: 0.5rem; display: flex; flex-wrap: wrap; gap: 0.5rem; }
      .allowed-value-tag { display: flex; align-items: center; gap: 0.25rem; background: rgba(88,166,255,0.1); border: 1px solid var(--accent); border-radius: var(--radius-sm); padding: 0.125rem 0.5rem; font-size: 0.75rem; color: var(--accent); }
      .remove-tag { cursor: pointer; font-weight: bold; }
      .add-prop-actions { display: flex; gap: var(--spacing-md); margin-bottom: var(--spacing-lg); }
      .split-layout { display: grid; grid-template-columns: 1fr 1fr; gap: var(--spacing-xl); }
      @media (max-width: 1024px) { .split-layout { grid-template-columns: 1fr; } }

      /* Controls toolbar — same as forms-list */
      .controls-toolbar {
        display: flex; align-items: center; flex-wrap: wrap;
        gap: var(--spacing-xl); padding: var(--spacing-md) 0;
        margin-top: var(--spacing-xl); border-top: 1px solid var(--border-dark);
      }
      .ctrl-group { display: inline-flex; flex-direction: column; gap: 0.25rem; }
      .ctrl-label { font-size: 0.625rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; color: var(--text-secondary); }
      .preview-bar { display: flex; justify-content: flex-start; padding: var(--spacing-md) 0; border-top: 1px solid var(--border-dark); }
      .preview-bar .preview-toggle-btn { padding: 0 0.875rem; height: 28px; background: var(--accent); border: 1px solid var(--accent); border-radius: var(--radius-md); color: #fff; font-size: 0.8125rem; font-weight: 600; cursor: pointer; transition: opacity var(--transition-fast); }
      .preview-bar .preview-toggle-btn:hover { opacity: 0.85; }
      /* Toggle switch */
      .toggle-wrap { display: inline-flex; align-items: center; gap: 0.5rem; height: 28px; }
      .toggle { position: relative; display: inline-block; width: 34px; height: 18px; flex-shrink: 0; cursor: pointer; }
      .toggle input { opacity: 0; position: absolute; width: 0; height: 0; }
      .toggle::before { content: ''; position: absolute; inset: 0; background: rgba(255,255,255,0.12); border: 1px solid var(--border-dark); border-radius: 9999px; transition: background 0.18s, border-color 0.18s; }
      .toggle:has(input:checked)::before { background: var(--accent); border-color: var(--accent); }
      .toggle::after { content: ''; position: absolute; top: 2px; left: 2px; width: 14px; height: 14px; background: rgba(255,255,255,0.5); border-radius: 50%; transition: transform 0.18s, background 0.18s; }
      .toggle:has(input:checked)::after { transform: translateX(16px); background: #fff; }
      .toggle-val { font-size: 0.8125rem; color: var(--text-secondary); min-width: 2rem; transition: color var(--transition-fast); }
      .toggle:has(input:checked) + .toggle-val { color: var(--text-primary); }
      /* Segmented control */
      .seg-control { display: inline-flex; height: 28px; }
      .seg-btn { padding: 0 0.875rem; background: transparent; border: 1px solid var(--border-dark); border-left-width: 0; color: var(--text-secondary); font-size: 0.8125rem; font-weight: 500; cursor: pointer; white-space: nowrap; transition: background 0.15s, color 0.15s; border-radius: 0; }
      .seg-btn:first-child { border-left-width: 1px; border-radius: var(--radius-md) 0 0 var(--radius-md); }
      .seg-btn:last-child { border-radius: 0 var(--radius-md) var(--radius-md) 0; }
      .seg-btn:hover:not(.seg-active) { background: rgba(255,255,255,0.05); color: var(--text-primary); }
      .seg-btn.seg-active { background: var(--accent); border-color: var(--accent); color: #fff; font-weight: 600; }
      /* Preview size presets */
      .preview-sizes { display: flex; gap: var(--spacing-sm); margin-bottom: var(--spacing-md); }
      .size-btn { padding: 0.25rem 0.75rem; background: transparent; border: 1px solid var(--border-dark); border-radius: var(--radius-md); color: var(--text-secondary); font-size: 0.75rem; font-weight: 500; cursor: pointer; transition: all var(--transition-fast); }
      .size-btn:hover { color: var(--text-primary); border-color: var(--accent); }
      .size-btn.active { background: rgba(88,166,255,0.1); border-color: var(--accent); color: var(--accent); }
      /* Preview area */
      .preview-area { padding: var(--spacing-md) 0 var(--spacing-lg); border-top: 1px solid var(--border-dark); }
      .preview-resizer { resize: horizontal; overflow: hidden; box-sizing: border-box; height: 600px; max-width: 100%; margin: 0 auto; border: 1px solid var(--border-dark); border-radius: var(--radius-md); background: #fff; }
      .preview-iframe { width: 100%; height: 100%; border: none; background: #fff; display: block; }
    `,
  ];

  private localizationKeys(): string[] {
    const keys = new Set<string>();
    for (const vals of Object.values(this.editLocalizations))
      for (const k of Object.keys(vals)) keys.add(k);
    return [...keys].sort();
  }

  private setLocalizationValue(locale: string, key: string, value: string) {
    const forLocale = this.editLocalizations[locale] ?? {};
    this.editLocalizations = { ...this.editLocalizations, [locale]: { ...forLocale, [key]: value } };
  }

  private effectivePropValue(prop: BackendProperty): string | boolean {
    const stored = this.previewPropertyValues[prop.name];
    if (stored !== undefined) return stored;
    return prop.type === 'StringArrayProperty' ? (prop.allowedValues[0] ?? '') : false;
  }

  private buildPreviewSrcdoc(): string {
    const themeId = this.previewTheme || this.themes.find(t => t.id === 'default')?.id || this.themes[0]?.id || '';
    return buildPreviewSrcdoc({
      formId: this.editId,
      properties: this.editProperties,
      getValue: p => this.effectivePropValue(p),
      localizations: this.editLocalizations,
      locale: this.previewLocale || this.locales[0]?.code || 'en',
      locales: this.locales.map(l => l.code),
      themeCss: this.themes.find(t => t.id === themeId)?.css ?? '',
      style: this.editStyle,
      jsCompiled: this.editJsCompiled || '',
    });
  }

  private addProperty(type: 'BooleanProperty' | 'StringArrayProperty') {
    const name = `new_property_${this.editProperties.length + 1}`;
    const prop: BackendProperty = type === 'BooleanProperty'
      ? { type, name }
      : { type, name, allowedValues: [] };
    this.editProperties = [...this.editProperties, prop];
  }

  private removeProperty(index: number) {
    this.editProperties = this.editProperties.filter((_, i) => i !== index);
  }

  private updatePropertyName(index: number, name: string) {
    this.editProperties = this.editProperties.map((p, i) => i === index ? { ...p, name } : p);
  }

  private addAllowedValue(index: number, value: string) {
    if (!value.trim()) return;
    const prop = this.editProperties[index];
    if (prop.type === 'StringArrayProperty') {
      const current = prop.allowedValues;
      if (!current.includes(value.trim())) {
        this.editProperties = this.editProperties.map((p, i) =>
          i === index ? { ...p, allowedValues: [...(p as StringArrayProperty).allowedValues, value.trim()] } : p
        );
      }
    }
  }

  private removeAllowedValue(propIndex: number, valueIndex: number) {
    const prop = this.editProperties[propIndex];
    if (prop.type === 'StringArrayProperty') {
      this.editProperties = this.editProperties.map((p, i) =>
        i === propIndex ? { ...p, allowedValues: (p as StringArrayProperty).allowedValues.filter((_, j) => j !== valueIndex) } : p
      );
    }
  }

  private async handleSave() {
    this.saving = true;
    this.saveSuccess = '';
    this.saveError = '';
    try {
      await updateForm(
        this.editId,
        this.editStyle,
        this.editJsSource || null,
        this.editJsCompiled || null,
        this.editLocalizations,
        this.editProperties,
      );
      this.saveSuccess = 'Saved as new version';
      setTimeout(() => { this.saveSuccess = ''; }, 2000);
      this.dispatchEvent(new CustomEvent('submit', {
        detail: {
          form: {
            ...this.form,
            style: this.editStyle,
            jsSource: this.editJsSource || null,
            jsCompiled: this.editJsCompiled || null,
            localizations: this.editLocalizations,
            properties: this.editProperties,
          },
        },
        bubbles: true, composed: true,
      }));
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'Save failed';
    } finally {
      this.saving = false;
    }
  }

  private handleCancel() {
    this.dispatchEvent(new CustomEvent('cancel', { bubbles: true, composed: true }));
  }

  private renderPropertyEditor() {
    return html`
      <div class="field-label section-gap">Backend Properties</div>
      <div class="hint">Define properties that can be used by the backend or for preview states.</div>

      <div class="add-prop-actions">
        <button class="btn btn-secondary btn-sm" @click=${() => this.addProperty('BooleanProperty')}>+ Add Boolean</button>
        <button class="btn btn-secondary btn-sm" @click=${() => this.addProperty('StringArrayProperty')}>+ Add OneOf</button>
      </div>

      <div class="properties-list">
        ${this.editProperties.map((prop, index) => html`
          <div class="property-row">
            <div>
              <div class="field-label" style="font-size: 0.65rem">Name</div>
              <input class="kv-value" .value=${prop.name} @input=${(e: Event) => this.updatePropertyName(index, (e.target as HTMLInputElement).value)} />
            </div>
            <div>
              <div class="field-label" style="font-size: 0.65rem">Type: ${prop.type.replace('Property', '')}</div>
              ${prop.type === 'StringArrayProperty' ? html`
                <div class="allowed-values">
                  ${prop.allowedValues.map((val, vIdx) => html`
                    <span class="allowed-value-tag">
                      ${val}
                      <span class="remove-tag" @click=${() => this.removeAllowedValue(index, vIdx)}>×</span>
                    </span>
                  `)}
                </div>
                <input class="kv-value" style="margin-top: 0.5rem" placeholder="Add value..." @keydown=${(e: KeyboardEvent) => {
                  if (e.key === 'Enter') {
                    this.addAllowedValue(index, (e.target as HTMLInputElement).value);
                    (e.target as HTMLInputElement).value = '';
                  }
                }} />
              ` : nothing}
            </div>
            <button class="btn btn-danger btn-sm" style="margin-top: 1.5rem" @click=${() => this.removeProperty(index)}>Remove</button>
          </div>
        `)}
        ${this.editProperties.length === 0 ? html`<div class="hint">No properties defined.</div>` : nothing}
      </div>
    `;
  }

  render() {
    const locale = this.activeLocale || (this.locales[0]?.code ?? 'en');
    const keys = this.localizationKeys();
    const values = this.editLocalizations[locale] ?? {};

    return html`
      <div class="title-stack" style="margin-bottom: var(--spacing-lg)">
        <h1 class="form-title">${this.form?.id === '' ? 'New Form' : 'New Version'}</h1>
        ${this.form?.id === '' ? html`
          <div>
            <label class="field-label">Form ID</label>
            <input class="kv-value" style="max-width:20rem" placeholder="e.g. credential"
              .value=${this.editId}
              @input=${(e: Event) => { this.editId = (e.target as HTMLInputElement).value.trim(); }} />
          </div>
        ` : html`
          <div style="font-family: var(--font-mono); color: var(--accent); font-size: 1rem">
            ${this.form?.id ?? ''} <span style="color: var(--text-secondary); font-size: 0.875rem">based on v${this.form?.version ?? 1}</span>
          </div>
        `}
      </div>

      <div class="card">
        <div class="field-label">Solid.js Source (JSX/TSX)</div>
        <div class="hint">The source code for your Solid.js form components.</div>
        <versola-code-editor language="typescript" rows="12" .value=${this.editJsSource}
          @code-input=${(e: CustomEvent<{ value: string }>) => { this.editJsSource = e.detail.value; }}
        ></versola-code-editor>

        <div class="field-label section-gap">Per-form CSS</div>
        <versola-code-editor language="css" rows="6" .value=${this.editStyle}
          @code-input=${(e: CustomEvent<{ value: string }>) => { this.editStyle = e.detail.value; }}
        ></versola-code-editor>

        <div class="split-layout">
          <div>
            <div class="field-label section-gap">Localizations</div>
            <div class="locale-tabs">
              ${this.locales.map(loc => html`
                <button class="locale-tab ${locale === loc.code ? 'active' : ''}"
                  @click=${() => { this.activeLocale = loc.code; }}>${loc.name}</button>
              `)}
            </div>
            ${keys.length === 0 ? html`<div class="hint">No placeholders found in the template yet.</div>` : html`
              <div class="kv-grid">
                ${keys.map(key => html`
                  <label class="kv-key" title=${key}>${key}</label>
                  <input class="kv-value" .value=${values[key] ?? ''} placeholder=${`{{${key}}}`}
                    @input=${(e: Event) => this.setLocalizationValue(locale, key, (e.target as HTMLInputElement).value)} />
                `)}
              </div>
            `}
          </div>

          <div>
            ${this.renderPropertyEditor()}
          </div>
        </div>

        <div class="controls-toolbar">


          ${this.themes.length > 0 ? html`
            <div class="ctrl-group">
              <span class="ctrl-label">Theme</span>
              <div class="seg-control">
                ${this.themes.map(t => html`
                  <button class="seg-btn ${(this.previewTheme || this.themes.find(x => x.id === 'default')?.id || this.themes[0]?.id) === t.id ? 'seg-active' : ''}"
                    @click=${() => { this.previewTheme = t.id; }}>${t.id}</button>
                `)}
              </div>
            </div>
          ` : nothing}

          ${this.editProperties.map(prop => {
            const val = this.effectivePropValue(prop);
            if (prop.type === 'BooleanProperty') {
              return html`
                <div class="ctrl-group">
                  <span class="ctrl-label">${prop.name}</span>
                  <div class="toggle-wrap">
                    <label class="toggle">
                      <input type="checkbox" .checked=${!!val}
                        @change=${(e: Event) => {
                          this.previewPropertyValues = { ...this.previewPropertyValues, [prop.name]: (e.target as HTMLInputElement).checked };
                        }} />
                    </label>
                    <span class="toggle-val">${val ? 'on' : 'off'}</span>
                  </div>
                </div>`;
            } else {
              return html`
                <div class="ctrl-group">
                  <span class="ctrl-label">${prop.name}</span>
                  <div class="seg-control">
                    ${prop.allowedValues.map(v => html`
                      <button class="seg-btn ${val === v ? 'seg-active' : ''}"
                        @click=${() => { this.previewPropertyValues = { ...this.previewPropertyValues, [prop.name]: v }; }}>${v}</button>
                    `)}
                  </div>
                </div>`;
            }
          })}

        </div>

        <div class="preview-bar">
          <button class="preview-toggle-btn" @click=${() => { this.previewOpen = !this.previewOpen; }}>
            ${this.previewOpen ? 'Hide preview' : 'Preview'}
          </button>
        </div>

        ${this.previewOpen ? html`
          <div class="preview-area">
            <div class="preview-sizes">
              <button class="size-btn ${this.previewWidth === 390 ? 'active' : ''}"
                @click=${() => { this.previewWidth = 390; }}>Mobile</button>
              <button class="size-btn ${this.previewWidth === 1280 ? 'active' : ''}"
                @click=${() => { this.previewWidth = 1280; }}>Desktop</button>
            </div>
            <div class="preview-resizer" style=${this.previewWidth ? `width: ${this.previewWidth}px` : 'width: 100%'}>
              <iframe class="preview-iframe" .srcdoc=${this.buildPreviewSrcdoc()} sandbox="allow-same-origin allow-scripts"></iframe>
            </div>
          </div>
        ` : nothing}

        <div class="form-actions">
          <button class="btn btn-secondary" ?disabled=${this.saving} @click=${() => this.handleCancel()}>Cancel</button>
          <button class="btn btn-primary" ?disabled=${this.saving || !this.editId.trim()} @click=${() => this.handleSave()}>
            ${this.saving ? 'Saving…' : 'Save as new version'}
          </button>
          ${this.saveSuccess ? html`<span class="save-msg success">${this.saveSuccess}</span>` : nothing}
          ${this.saveError ? html`<span class="save-msg error">${this.saveError}</span>` : nothing}
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-form-edit': VersolaFormEdit;
  }
}

