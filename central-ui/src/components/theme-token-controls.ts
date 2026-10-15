import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import type { ResolvedThemeTokens, ThemeMode, ThemeOverrides, ThemeShape, ThemeSurface } from '../types';

const HEX = /^#?[0-9a-fA-F]{6}$/;
// Colours to start from; any #rrggbb can be typed.
const SWATCHES = ['#ff6b4a', '#e5484d', '#b4348a', '#5b5bd6', '#2563eb', '#0e9f8e', '#2f9e63', '#f5c518', '#14130f'];
const SHAPES: Array<[ThemeShape, string]> = [['round', 'Round'], ['soft', 'Soft'], ['sharp', 'Sharp']];
const SURFACES: Array<[ThemeSurface, string, string]> = [
  ['bold', 'Bold', 'A deep shade of the brand colour behind the card'],
  ['soft', 'Soft', 'A pale tint of the brand colour'],
  ['neutral', 'Neutral', 'A plain grey page; the brand shows only in accents'],
];
const MODES: Array<[ThemeMode, string]> = [['auto', 'Auto'], ['light', 'Light'], ['dark', 'Dark']];

const normalize = (hex: string) => (hex.startsWith('#') ? hex : `#${hex}`).toLowerCase();

export interface OverridesChange {
  overrides: ThemeOverrides;
  /** The brand colour field holds text that is not a #rrggbb colour. */
  brandInvalid: boolean;
}

/**
 * The four tokens a theme sets (brand colour, page, shape, colour scheme) laid over a base: each shows the
 * base's value until it is overridden, then an "Overridden" badge with a Reset. Used for the theme itself
 * (the base is a built-in theme) and for a client's own overrides (the base is the theme the client uses).
 * It owns no state beyond the colour field's text: the overrides come in as a property and go out as an
 * `overrides-change` event.
 */
@customElement('versola-theme-token-controls')
export class VersolaThemeTokenControls extends LitElement {
  /** What the tokens are without any override: the values shown as inherited. */
  @property({ attribute: false }) base?: ResolvedThemeTokens;
  /** The base is a stylesheet, whose values are not known here (`base` is then not used). Nothing is shown as
   *  inherited, and every choice is written as an override even when it matches some preset: an override equal to
   *  a value the stylesheet may not have would otherwise be dropped. */
  @property({ type: Boolean }) stylesheet = false;
  @property({ attribute: false }) overrides: ThemeOverrides = {};
  /** Said on a token that is not overridden, e.g. where it comes from. Nothing when empty. */
  @property({ type: String }) inheritedNote = '';
  /** Draw a small schematic sign-in card in the resulting tokens above the controls. It is a sketch of brand,
   *  page and corners, not a render of a form: the theme editor has the real preview. */
  @property({ type: Boolean }) preview = false;

  @state() private brandText = '';

  willUpdate(changed: Map<string, unknown>) {
    if (!changed.has('overrides')) return;
    const own = this.overrides.brand;
    const text = this.brandText.trim();
    if (own !== undefined) {
      if (!HEX.test(text) || normalize(text) !== own) this.brandText = own;
    } else if (HEX.test(text) && normalize(text) !== this.base?.brand) {
      // Cleared from outside (Reset, Clear overrides, another theme). A colour typed that equals the base's stays:
      // it is no override, but it is what the operator typed.
      this.brandText = '';
    }
  }

  /** Empties the colour field, for a "Clear overrides" kept outside the controls (it would leave unparsable text). */
  clearBrandText() {
    this.brandText = '';
  }

  static styles = [
    theme,
    css`
      :host { display: block; }
      .field-label { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-secondary); margin-bottom: 0.5rem; }
      .hint { font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 0.5rem; }
      .kv-value { width: 100%; padding: 0.5rem var(--spacing-md); background: var(--bg-dark); border: 1px solid var(--border-dark); border-radius: var(--radius-md); font-size: 0.8125rem; color: var(--text-primary); box-sizing: border-box; }
      .kv-value:focus { outline: none; border-color: var(--accent); }
      .group + .group { margin-top: var(--spacing-lg); }
      .group-title { display: flex; justify-content: space-between; align-items: baseline; gap: var(--spacing-sm); }
      .badge-overridden { font-size: 0.6875rem; font-weight: 600; color: var(--accent); background: color-mix(in srgb, var(--accent) 14%, transparent); border-radius: 999px; padding: 0.125rem 0.5rem; white-space: nowrap; }
      .note-inherited { font-size: 0.6875rem; color: var(--text-secondary); white-space: nowrap; }
      .reset { font: inherit; font-size: 0.75rem; color: var(--accent); background: none; border: 0; cursor: pointer; padding: 0; text-decoration: underline; }

      .seg-control { display: inline-flex; flex-wrap: wrap; gap: 0.25rem; }
      .seg-btn { padding: 0.375rem 0.75rem; font: inherit; font-size: 0.8125rem; color: var(--text-secondary); background: transparent; border: 1px solid var(--border-dark); border-radius: var(--radius-md); cursor: pointer; }
      .seg-btn:hover { color: var(--text-primary); }
      .seg-btn.seg-active { color: var(--text-primary); border-color: var(--accent); background: color-mix(in srgb, var(--accent) 14%, transparent); }

      .swatches { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
      .swatch { width: 1.75rem; height: 1.75rem; border-radius: 50%; border: 2px solid var(--bg-card, transparent); box-shadow: 0 0 0 1px var(--border-dark); cursor: pointer; padding: 0; }
      .swatch.sel { box-shadow: 0 0 0 2px var(--text-primary); }
      .hex-row { display: flex; align-items: center; gap: var(--spacing-sm); margin-top: 0.5rem; }
      .hex-row input { width: 7.5rem; font-family: var(--font-mono); }
      .hex-row .bad { color: var(--danger); font-size: 0.75rem; }
      .sketch { display: grid; place-items: center; height: 4.5rem; border-radius: var(--radius-md); overflow: hidden; margin-bottom: var(--spacing-md); background: color-mix(in oklab, var(--b) 24%, #0c1110); }
      .sketch[data-surface='soft'] { background: color-mix(in oklab, var(--b) 14%, #f7f4ee); }
      .sketch[data-surface='neutral'] { background: #ecebe7; }
      .sketch[data-surface='soft'][data-mode='dark'] { background: color-mix(in oklab, var(--b) 7%, #0e0e0d); }
      .sketch[data-surface='neutral'][data-mode='dark'] { background: #0b0b0a; }
      .sketch-card { width: 8rem; display: flex; flex-direction: column; gap: 0.1875rem; padding: 0.375rem 0.5rem; background: #fffdf9; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.25); }
      .sketch[data-mode='dark'] .sketch-card { background: #262628; }
      .sketch[data-shape='soft'] .sketch-card { border-radius: 8px; }
      .sketch[data-shape='sharp'] .sketch-card { border-radius: 2px; }
      .sketch i { display: block; }
      .sketch-dot { width: 0.5rem; height: 0.5rem; border-radius: 50%; background: var(--b); }
      .sketch-field { height: 0.4375rem; background: #e6e1d6; border-radius: 6px; }
      .sketch[data-mode='dark'] .sketch-field { background: #3a3a3d; }
      .sketch[data-shape='sharp'] .sketch-field, .sketch[data-shape='sharp'] .sketch-btn { border-radius: 1px; }
      .sketch-btn { height: 0.625rem; background: var(--b); border-radius: 999px; margin-top: 0.125rem; }
      .chip { width: 1.25rem; height: 1.25rem; border-radius: 50%; border: 1px solid var(--border-dark); }
    `,
  ];

  private get brandInvalid(): boolean {
    return this.brandText.trim() !== '' && !HEX.test(this.brandText.trim());
  }

  /** The value each token shows; `undefined` for one a stylesheet sets and the operator has not overridden. */
  private get resolved(): Partial<ResolvedThemeTokens> {
    const base = this.stylesheet ? undefined : this.base;
    return {
      brand: this.overrides.brand ?? base?.brand,
      shape: this.overrides.shape ?? base?.shape,
      surface: this.overrides.surface ?? base?.surface,
      mode: this.overrides.mode ?? base?.mode,
    };
  }

  private emit(overrides: ThemeOverrides) {
    this.dispatchEvent(new CustomEvent<OverridesChange>('overrides-change', {
      detail: { overrides, brandInvalid: this.brandInvalid },
      bubbles: true,
      composed: true,
    }));
  }

  private setOverride<K extends keyof ThemeOverrides>(key: K, value: ThemeOverrides[K] | undefined) {
    const next = { ...this.overrides };
    // A value equal to the base's is not an override: the theme then follows the base when it changes. Not so over
    // a stylesheet, whose own values are unknown: the choice is the only record of what the operator wants.
    if (value === undefined || (!this.stylesheet && value === this.base?.[key])) delete next[key];
    else next[key] = value;
    this.emit(next);
  }

  private reset(key: keyof ThemeOverrides) {
    if (key === 'brand') this.brandText = '';
    this.setOverride(key, undefined);
  }

  private overriddenBadge(key: keyof ThemeOverrides, baseValue: string | undefined) {
    if (this.overrides[key] === undefined) {
      if (this.stylesheet) return html`<span class="note-inherited">Set by the stylesheet</span>`;
      return this.inheritedNote ? html`<span class="note-inherited">${this.inheritedNote}</span>` : nothing;
    }
    return html`
      <span>
        <span class="badge-overridden">${this.stylesheet ? 'Overridden' : `Overridden · base: ${baseValue}`}</span>
        <button class="reset" aria-label=${`Reset ${key} to ${this.stylesheet ? 'the stylesheet' : 'the base'}`} @click=${() => this.reset(key)}>Reset</button>
      </span>
    `;
  }

  render() {
    if (!this.base && !this.stylesheet) return nothing;
    const resolved = this.resolved;
    const base = this.base;
    return html`
      ${this.preview && resolved.brand && resolved.surface && resolved.shape && resolved.mode ? html`
        <div class="sketch" style=${`--b:${resolved.brand}`} data-surface=${resolved.surface} data-shape=${resolved.shape} data-mode=${resolved.mode}
          role="img" aria-label=${`Sketch of the sign-in page: ${resolved.brand}, ${resolved.surface} page, ${resolved.shape} corners, ${resolved.mode} scheme`}>
          <i class="sketch-card"><i class="sketch-dot"></i><i class="sketch-field"></i><i class="sketch-field"></i><i class="sketch-btn"></i></i>
        </div>
      ` : nothing}
      <div class="group">
        <div class="group-title"><div class="field-label">Brand colour</div>${this.overriddenBadge('brand', base?.brand)}</div>
        <div class="hint">The accent: ticks, links, focus rings and the page tint. Text on it is chosen to stay readable.</div>
        <div class="swatches" role="group" aria-label="Brand colour">
          ${SWATCHES.map(c => html`
            <button class="swatch ${resolved.brand === c ? 'sel' : ''}" style=${`background:${c}`} aria-label=${c} aria-pressed=${resolved.brand === c ? 'true' : 'false'}
              @click=${() => { this.brandText = c; this.setOverride('brand', c); }}></button>
          `)}
        </div>
        <div class="hex-row">
          <span class="chip" style=${`background:${resolved.brand ?? 'transparent'}`}></span>
          <input class="kv-value" aria-label="Brand colour, #rrggbb" placeholder=${resolved.brand ?? '#rrggbb'} spellcheck="false" maxlength="7"
            .value=${this.brandText}
            @input=${(e: Event) => {
              this.brandText = (e.target as HTMLInputElement).value;
              const text = this.brandText.trim();
              if (text === '') this.setOverride('brand', undefined);
              else if (HEX.test(text)) this.setOverride('brand', normalize(text));
              else this.emit(this.overrides);
            }} />
          ${this.brandInvalid ? html`<span class="bad" role="alert">Use #rrggbb</span>` : nothing}
        </div>
      </div>

      <div class="group">
        <div class="group-title"><div class="field-label">Page background</div>${this.overriddenBadge('surface', base?.surface)}</div>
        <div class="seg-control" role="group" aria-label="Page background">
          ${SURFACES.map(([value, label]) => html`
            <button class="seg-btn ${resolved.surface === value ? 'seg-active' : ''}" aria-pressed=${resolved.surface === value ? 'true' : 'false'}
              @click=${() => this.setOverride('surface', value)}>${label}</button>
          `)}
        </div>
        <div class="hint" style="margin-top: 0.5rem">${SURFACES.find(s => s[0] === resolved.surface)?.[2]}</div>
      </div>

      <div class="group">
        <div class="group-title"><div class="field-label">Shape</div>${this.overriddenBadge('shape', base?.shape)}</div>
        <div class="seg-control" role="group" aria-label="Shape">
          ${SHAPES.map(([value, label]) => html`
            <button class="seg-btn ${resolved.shape === value ? 'seg-active' : ''}" aria-pressed=${resolved.shape === value ? 'true' : 'false'}
              @click=${() => this.setOverride('shape', value)}>${label}</button>
          `)}
        </div>
      </div>

      <div class="group">
        <div class="group-title"><div class="field-label">Colour scheme</div>${this.overriddenBadge('mode', base?.mode)}</div>
        <div class="seg-control" role="group" aria-label="Colour scheme">
          ${MODES.map(([value, label]) => html`
            <button class="seg-btn ${resolved.mode === value ? 'seg-active' : ''}" aria-pressed=${resolved.mode === value ? 'true' : 'false'}
              @click=${() => this.setOverride('mode', value)}>${label}</button>
          `)}
        </div>
        <div class="hint" style="margin-top: 0.5rem">Auto follows the visitor's device.</div>
      </div>
    `;
  }
}
