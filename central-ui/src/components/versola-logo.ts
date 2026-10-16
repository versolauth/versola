import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { getResolvedTheme, THEME_CHANGE_EVENT, type ThemeName } from '../utils/theme';

@customElement('versola-logo')
export class VersolaLogo extends LitElement {
  @property({ type: Number }) size = 40;
  /** The two themes use different mark treatments (dark: gradient badge with
    * a dark background plate; light: a quiet outline, matching the marketing
    * site's client-facing auth mark) rather than the same shapes recolored,
    * so this can't be done with CSS custom properties alone — it re-renders
    * on theme change instead. */
  @state() private currentTheme: ThemeName = getResolvedTheme();

  private readonly handleThemeChange = (event: Event) => {
    this.currentTheme = (event as CustomEvent<ThemeName>).detail;
  };

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener(THEME_CHANGE_EVENT, this.handleThemeChange);
  }

  disconnectedCallback() {
    window.removeEventListener(THEME_CHANGE_EVENT, this.handleThemeChange);
    super.disconnectedCallback();
  }

  static styles = css`
    :host {
      display: inline-block;
    }

    svg {
      display: block;
    }
  `;

  /** A badge plate with the shield in the theme's accent: the mark the dark
    * favicon (public/logo-shield.svg) uses, drawn with the console's tokens so
    * it follows the palette. */
  private renderDark() {
    return html`
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="${this.size}" height="${this.size}">
        <!-- Background plate -->
        <rect width="64" height="64" rx="14" fill="var(--surface-inset)"/>
        <!-- Shield shape — clean modern pointed shield -->
        <path d="M32 6 C32 6 52 10 54 12 L54 30 Q54 48 32 58 Q10 48 10 30 L10 12 C12 10 32 6 32 6Z"
              fill="var(--surface)" stroke="var(--accent)" stroke-width="1.8"/>
        <!-- V letter -->
        <text x="32" y="41" font-family="Inter, sans-serif" font-weight="800" font-size="28" fill="var(--accent)" text-anchor="middle">V</text>
      </svg>
    `;
  }

  /** Outline treatment matching the marketing site's mark for light,
    * client-facing surfaces (versola-website: public/img/logo-mark-outline.svg)
    * — no badge plate, so it reads as a quiet accent on a light background
    * rather than a heavy lockup pasted onto it. */
  private renderLight() {
    return html`
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="${this.size}" height="${this.size}">
        <path d="M32 6 C32 6 52 10 54 12 L54 30 Q54 48 32 58 Q10 48 10 30 L10 12 C12 10 32 6 32 6Z"
              fill="var(--accent-subtle)"/>
        <path d="M32 6 C32 6 52 10 54 12 L54 30 Q54 48 32 58 Q10 48 10 30 L10 12 C12 10 32 6 32 6Z"
              fill="none" stroke="var(--accent)" stroke-width="2.2"/>
        <text x="32" y="41" font-family="Inter, sans-serif" font-weight="800" font-size="26" fill="var(--accent)" text-anchor="middle">V</text>
      </svg>
    `;
  }

  render() {
    return this.currentTheme === 'light' ? this.renderLight() : this.renderDark();
  }
}

