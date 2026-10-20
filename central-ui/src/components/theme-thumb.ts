import { LitElement, css, html, type TemplateResult } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import type { ThemeShape, ThemeSurface } from '../types';

/**
 * A small sketch of what a theme gives a login page: the page colour, the sheet and the brand.
 * The preset cards of the theme editor draw it at 4.5rem; the themes list draws a `small` one
 * beside the hex. The colours are the login forms' own, not the console's: a theme looks the same
 * whatever the console's scheme is (the deliberate literals the design system lists).
 *
 * The page backgrounds repeat those of ThemeCss (util) and common.css: bold = brand 24% into
 * #0c1110, soft = brand 14% into #f7f4ee, neutral = #ecebe7. Keep them in step when those change.
 */
@customElement('versola-theme-thumb')
export class VersolaThemeThumb extends LitElement {
  @property({ type: String }) brand = '#155e75';
  @property({ type: String }) shape: ThemeShape | string = 'soft';
  @property({ type: String }) surface: ThemeSurface | string = 'bold';
  @property({ type: Boolean, reflect: true }) small = false;

  static styles = css`
    :host { display: block; }
    .th { display: grid; place-items: center; height: 4.5rem; border-radius: 6px; overflow: hidden; background: color-mix(in oklab, var(--b) 24%, #0c1110); }
    .th[data-surface='soft'] { background: color-mix(in oklab, var(--b) 14%, #f7f4ee); }
    .th[data-surface='neutral'] { background: #ecebe7; }
    i { display: block; }
    .th-sheet { width: 58%; height: 78%; background: #fffdf9; border-radius: 8px; display: flex; flex-direction: column; gap: 3px; padding: 6px 5px; box-shadow: 0 3px 8px rgba(0,0,0,0.2); box-sizing: border-box; }
    .th[data-shape='soft'] .th-sheet { border-radius: 5px; }
    .th[data-shape='sharp'] .th-sheet { border-radius: 1px; }
    .th-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--b); }
    .th-line { width: 50%; height: 3px; background: #1c1b19; border-radius: 2px; }
    .th-field { height: 6px; background: #efeae0; border-radius: 4px; }
    .th-btn { height: 7px; background: #1c1b19; border-radius: 5px; margin-top: auto; }

    /* The list's thumbnail: page, sheet and a button in the brand colour. */
    :host([small]) { flex: none; }
    :host([small]) .th { width: 2.875rem; height: 1.875rem; outline: 1px solid var(--border-default); outline-offset: -1px; }
    :host([small]) .th-sheet { width: 54%; height: 70%; padding: 3px; gap: 0; border-radius: 3px; box-shadow: none; }
    :host([small]) .th-dot, :host([small]) .th-line, :host([small]) .th-field { display: none; }
    :host([small]) .th-btn { height: 4px; background: var(--b); border-radius: 2px; }
  `;

  render() {
    return html`
      <span class="th" style=${`--b:${this.brand}`} data-surface=${this.surface} data-shape=${this.shape} aria-hidden="true">
        <i class="th-sheet"><i class="th-dot"></i><i class="th-line"></i><i class="th-field"></i><i class="th-field"></i><i class="th-btn"></i></i>
      </span>`;
  }
}

export function themeThumb(tokens: { brand: string; shape: ThemeShape | string; surface: ThemeSurface | string }, options: { small?: boolean } = {}): TemplateResult {
  return html`<versola-theme-thumb brand=${tokens.brand} shape=${tokens.shape} surface=${tokens.surface} ?small=${options.small ?? false}></versola-theme-thumb>`;
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-theme-thumb': VersolaThemeThumb;
  }
}
