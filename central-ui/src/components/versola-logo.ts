import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { getStoredTheme, THEME_CHANGE_EVENT, type ThemeName } from '../utils/theme';

@customElement('versola-logo')
export class VersolaLogo extends LitElement {
  @property({ type: Number }) size = 40;
  /** The two themes use different mark treatments (dark: gradient badge with
    * a dark background plate; light: a quiet outline, matching the marketing
    * site's client-facing auth mark) rather than the same shapes recolored,
    * so this can't be done with CSS custom properties alone — it re-renders
    * on theme change instead. */
  @state() private currentTheme: ThemeName = getStoredTheme();

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

  private renderDark() {
    return html`
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="${this.size}" height="${this.size}">
        <defs>
          <linearGradient id="g" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stop-color="#58a6ff"/>
            <stop offset="100%" stop-color="#a371f7"/>
          </linearGradient>
          <linearGradient id="shieldFill" x1="50%" y1="0%" x2="50%" y2="100%">
            <stop offset="0%" stop-color="#1c2230"/>
            <stop offset="100%" stop-color="#131920"/>
          </linearGradient>
        </defs>
        <!-- Background -->
        <rect width="64" height="64" rx="14" fill="#0d1117"/>
        <!-- Shield shape — clean modern pointed shield -->
        <path d="M32 6 C32 6 52 10 54 12 L54 30 Q54 48 32 58 Q10 48 10 30 L10 12 C12 10 32 6 32 6Z"
              fill="url(#shieldFill)" stroke="url(#g)" stroke-width="1.8"/>
        <!-- V letter -->
        <text x="32" y="41" font-family="Inter, sans-serif" font-weight="800" font-size="28" fill="url(#g)" text-anchor="middle">V</text>
      </svg>
    `;
  }

  /** Outline treatment matching the marketing site's mark for light,
    * client-facing surfaces (versola-website: public/img/logo-mark-outline.svg)
    * — no dark badge plate and no gradient, so it reads as a quiet accent on
    * a light background rather than a heavy dark lockup pasted onto it. */
  private renderLight() {
    return html`
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="${this.size}" height="${this.size}">
        <path d="M32 6 C32 6 52 10 54 12 L54 30 Q54 48 32 58 Q10 48 10 30 L10 12 C12 10 32 6 32 6Z"
              fill="#155e75" fill-opacity="0.06"/>
        <path d="M32 6 C32 6 52 10 54 12 L54 30 Q54 48 32 58 Q10 48 10 30 L10 12 C12 10 32 6 32 6Z"
              fill="none" stroke="#155e75" stroke-width="2.2"/>
        <text x="32" y="41" font-family="Inter, sans-serif" font-weight="800" font-size="26" fill="#155e75" text-anchor="middle">V</text>
      </svg>
    `;
  }

  render() {
    return this.currentTheme === 'light' ? this.renderLight() : this.renderDark();
  }
}

