import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import type { FormPreviewScenario, FormRecord, Locale, ThemeOverrides, ThemeTokens } from '../types';
import { renderTheme } from '../utils/central-api';
import { consentStepFor } from '../utils/consent-preview';
import { buildPreviewSrcdoc, localeNamesOf } from '../utils/preview';

/**
 * A real render of the Consent form with the look a client would have: central composes the theme's stylesheet with the
 * client's own overrides (the same composition auth does), and the form's compiled bundle draws it in a frame with fixed
 * sample parameters. Nothing is composed here: the console holds no colour formulas.
 */
@customElement('versola-client-consent-preview')
export class VersolaClientConsentPreview extends LitElement {
  /** The consent form's active version; `null` when central has none or the forms could not be read. */
  @property({ attribute: false }) form: FormRecord | null = null;
  /** The consent scenario of the preview catalog; `null` when the catalog is not available. */
  @property({ attribute: false }) scenario: FormPreviewScenario | null = null;
  /** The theme the client uses: its tokens (null for a stylesheet theme) and its own CSS. */
  @property({ attribute: false }) tokens: ThemeTokens | null = null;
  @property({ type: String }) themeCss = '';
  @property({ attribute: false }) overrides: ThemeOverrides = {};
  /** The scheme the look is fixed to, when it is: a fixed scheme has no dark variant to switch on. */
  @property({ type: String }) fixedMode: 'light' | 'dark' | null = null;
  @property({ attribute: false }) clientName: Record<string, string> = {};
  @property({ type: String }) logoUri = '';
  @property({ attribute: false }) locales: Locale[] = [];

  @state() private renderedCss = '';
  @state() private renderError = '';
  // True from a change until central answers, so the frame is not mistaken for the current look.
  @state() private rendering = false;
  @state() private width = 390;
  // Starts from the operator's device, so the preview shows what they see on real pages (the page glow exists only in the dark scheme).
  @state() private dark = window.matchMedia('(prefers-color-scheme: dark)').matches;

  private renderTimer: ReturnType<typeof setTimeout> | undefined;
  // Bumped by every change, so an answer to an earlier request is dropped, however it arrives.
  private renderSeq = 0;
  private renderedKey: string | undefined;

  static styles = [
    theme,
    css`
      :host { display: block; margin-bottom: var(--spacing-md); }
      .bar { display: flex; flex-wrap: wrap; align-items: center; gap: var(--spacing-md); margin-bottom: var(--spacing-sm); }
      .ctrl-group { display: flex; align-items: center; gap: var(--spacing-sm); flex-wrap: wrap; }
      .ctrl-label { font-size: 0.75rem; font-weight: 600; color: var(--text-secondary); }
      .seg-control { display: inline-flex; flex-wrap: wrap; gap: 0.25rem; }
      .seg-btn { padding: 0.375rem 0.75rem; font: inherit; font-size: 0.8125rem; color: var(--text-secondary); background: transparent; border: 1px solid var(--border-dark); border-radius: var(--radius-md); cursor: pointer; }
      .seg-btn:hover { color: var(--text-primary); }
      .seg-btn:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
      .seg-btn.seg-active { color: var(--text-primary); border-color: var(--accent); background: color-mix(in srgb, var(--accent) 14%, transparent); }
      .seg-btn:disabled { opacity: 0.6; cursor: default; }
      .stage { background: rgba(0,0,0,0.2); border: 1px solid var(--border-dark); border-radius: var(--radius-md); padding: var(--spacing-sm); display: flex; justify-content: center; overflow: auto; transition: opacity 0.15s; }
      .stage[aria-busy='true'] { opacity: 0.55; }
      iframe { border: 0; border-radius: var(--radius-md); background: #fff; height: 32rem; max-width: 100%; }
      .note { font-size: 0.8125rem; color: var(--text-secondary); }
      .status { font-size: 0.75rem; color: var(--text-secondary); }
      .error { color: var(--danger); font-size: 0.8125rem; margin-bottom: var(--spacing-sm); }
    `,
  ];

  disconnectedCallback() {
    clearTimeout(this.renderTimer);
    super.disconnectedCallback();
  }

  willUpdate() {
    if (!this.form || !this.scenario) return;
    // The parent hands over a new object on every render, so a change is told by value.
    const key = JSON.stringify([this.tokens, this.themeCss, this.overrides]);
    if (key === this.renderedKey) return;
    const first = this.renderedKey === undefined;
    this.renderedKey = key;
    clearTimeout(this.renderTimer);
    // Any request still in flight is for an older state; its answer must not be applied.
    const seq = ++this.renderSeq;
    this.rendering = true;
    this.renderTimer = setTimeout(() => void this.renderNow(seq), first ? 0 : 200);
  }

  private async renderNow(seq: number) {
    try {
      const rendered = await renderTheme(this.tokens, this.themeCss, this.overrides);
      if (seq !== this.renderSeq) return;
      this.renderedCss = rendered.css;
      this.renderError = '';
    } catch (error) {
      if (seq !== this.renderSeq) return;
      // The frame keeps the last stylesheet that rendered, shown as out of date next to the reason.
      this.renderError = error instanceof Error ? error.message : 'Could not render the preview';
    } finally {
      if (seq === this.renderSeq) this.rendering = false;
    }
  }

  private srcdoc(): string {
    if (!this.form || !this.scenario || this.renderedCss === '') return '';
    const locale = this.locales.find(l => l.isDefault)?.code ?? this.locales[0]?.code ?? 'en';
    // The frame follows the operator's own device scheme unless told otherwise, so Light has to be forced too: on a dark
    // device it would otherwise show the dark card. A look with a fixed scheme keeps it.
    const dark = this.fixedMode === null && this.dark;
    const scheme = this.fixedMode === null ? `:root{color-scheme:${dark ? 'dark' : 'light'}}` : '';
    return buildPreviewSrcdoc({
      formId: this.form.id,
      properties: this.form.properties,
      getValue: () => false,
      scenario: { ...this.scenario, step: consentStepFor(this.scenario, this.clientName, this.logoUri, locale) },
      localizations: this.form.localizations,
      locale,
      locales: this.locales.map(l => l.code),
      localeNames: localeNamesOf(this.locales),
      themeCss: `${this.renderedCss}\n${scheme}`,
      style: this.form.style,
      jsCompiled: this.form.jsCompiled ?? '',
    });
  }

  render() {
    if (!this.form || !this.scenario) {
      return html`<div class="note">The consent screen cannot be previewed here: central has no consent form to draw.</div>`;
    }
    const srcdoc = this.srcdoc();
    return html`
      <div class="bar">
        <div class="ctrl-group">
          <span class="ctrl-label">Scheme</span>
          <div class="seg-control" role="group" aria-label="Preview scheme">
            <button type="button" class="seg-btn ${!this.dark || this.fixedMode !== null ? 'seg-active' : ''}" aria-pressed=${!this.dark || this.fixedMode !== null ? 'true' : 'false'}
              @click=${() => { this.dark = false; }}>Light</button>
            <button type="button" class="seg-btn ${this.dark && this.fixedMode === null ? 'seg-active' : ''}" aria-pressed=${this.dark && this.fixedMode === null ? 'true' : 'false'}
              ?disabled=${this.fixedMode !== null} @click=${() => { this.dark = true; }}>Dark</button>
          </div>
        </div>
        <div class="ctrl-group">
          <span class="ctrl-label">Width</span>
          <div class="seg-control" role="group" aria-label="Preview width">
            ${[['Mobile', 390], ['Desktop', 1000]].map(([label, width]) => html`
              <button type="button" class="seg-btn ${this.width === width ? 'seg-active' : ''}" aria-pressed=${this.width === width ? 'true' : 'false'}
                @click=${() => { this.width = width as number; }}>${label}</button>
            `)}
          </div>
        </div>
        ${this.rendering ? html`<span class="status" role="status">Rendering…</span>` : nothing}
      </div>
      ${this.renderError ? html`<div class="error" role="alert">${this.renderError}.${srcdoc ? ' The preview shows the last version that rendered.' : ''}</div>` : nothing}
      ${srcdoc ? html`
        <div class="stage" aria-busy=${this.rendering ? 'true' : 'false'}>
          <iframe title="Preview of the consent screen for this app" style=${`width:${this.width}px`} .srcdoc=${srcdoc} sandbox="allow-same-origin allow-scripts"></iframe>
        </div>
      ` : nothing}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-client-consent-preview': VersolaClientConsentPreview;
  }
}
