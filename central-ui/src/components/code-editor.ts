import { LitElement, css, html } from 'lit';
import { customElement, property, query } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { codeHighlightStyles } from '../styles/code-highlight';
import { tokenize, type EditorLanguage } from '../utils/code-highlight';

@customElement('versola-code-editor')
export class VersolaCodeEditor extends LitElement {
  @property({ type: String }) value = '';
  @property({ type: String }) language: EditorLanguage = 'html';
  @property({ type: Number }) rows = 10;
  @property({ type: Boolean }) disabled = false;

  @query('textarea') private textarea!: HTMLTextAreaElement;
  @query('pre') private pre!: HTMLPreElement;

  static styles = [
    theme,
    codeHighlightStyles,
    css`
      :host {
        display: block;
        --ce-padding-y: 0.625rem;
        --ce-padding-x: 0.875rem;
        --ce-line-height: 1.6;
        --ce-font-size: 0.8125rem;
      }
      .wrapper {
        position: relative;
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        overflow: hidden;
        transition: border-color var(--transition-fast);
      }
      .wrapper:focus-within {
        border-color: var(--accent);
        box-shadow: 0 0 0 2px rgba(88, 166, 255, 0.15);
      }
      pre, textarea {
        margin: 0;
        padding: var(--ce-padding-y) var(--ce-padding-x);
        font-family: var(--font-mono, monospace);
        font-size: var(--ce-font-size);
        line-height: var(--ce-line-height);
        letter-spacing: 0;
        tab-size: 2;
        border: 0;
        background: transparent;
        box-sizing: border-box;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        word-break: break-word;
      }
      pre {
        position: absolute;
        inset: 0;
        pointer-events: none;
        color: var(--text-primary);
        overflow: hidden;
      }
      textarea {
        position: relative;
        display: block;
        width: 100%;
        color: transparent;
        caret-color: var(--accent);
        resize: vertical;
        outline: none;
        overflow: auto;
      }
      textarea::selection { background: rgba(88, 166, 255, 0.35); color: transparent; }
    `,
  ];

  render() {
    const tokens = tokenize(this.value, this.language);
    const height = `min-height: calc(${this.rows} * var(--ce-line-height) * var(--ce-font-size) + var(--ce-padding-y) * 2);`;
    return html`
      <div class="wrapper">
        <pre aria-hidden="true" style=${height}>${tokens.map(t =>
          html`<span class=${`ch-tok ch-${t.kind}`}>${t.value}</span>`,
        )}<span class="ch-tok ch-text">${'\n'}</span></pre>
        <textarea
          .value=${this.value}
          ?disabled=${this.disabled}
          spellcheck="false"
          autocomplete="off"
          autocorrect="off"
          autocapitalize="off"
          rows=${this.rows}
          style=${height}
          @input=${this.handleInput}
          @scroll=${this.handleScroll}
          @keydown=${this.handleKeyDown}
        ></textarea>
      </div>
    `;
  }

  private handleInput(event: Event) {
    const next = (event.target as HTMLTextAreaElement).value;
    this.value = next;
    this.dispatchEvent(new CustomEvent('code-input', { detail: { value: next }, bubbles: true, composed: true }));
  }

  private handleScroll() {
    if (!this.pre || !this.textarea) return;
    this.pre.scrollTop = this.textarea.scrollTop;
    this.pre.scrollLeft = this.textarea.scrollLeft;
  }

  private handleKeyDown(event: KeyboardEvent) {
    if (event.key === 'Tab') {
      event.preventDefault();
      const ta = event.target as HTMLTextAreaElement;
      const start = ta.selectionStart;
      const end = ta.selectionEnd;
      const next = ta.value.slice(0, start) + '  ' + ta.value.slice(end);
      this.value = next;
      this.updateComplete.then(() => {
        ta.selectionStart = ta.selectionEnd = start + 2;
      });
    }
  }
}
