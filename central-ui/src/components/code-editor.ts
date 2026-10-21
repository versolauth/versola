import { LitElement, css, html, nothing } from 'lit';
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
  @property({ type: Boolean }) invalid = false;
  @property({ type: String }) placeholder = '';
  /** The accessible name of the text area: a `<label for>` cannot reach into this shadow root. */
  @property({ type: String }) label = '';
  /** Long lines scroll sideways inside the editor instead of wrapping (JSON texts, where a wrapped value reads as two). */
  @property({ type: Boolean }) nowrap = false;

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
        background: var(--field);
        border: 1.5px solid var(--border-input);
        border-radius: var(--radius-md);
        overflow: hidden;
        transition: border-color var(--transition-fast);
      }
      .wrapper:focus-within {
        background: var(--surface);
        border-color: var(--text-primary);
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }
      .wrapper.invalid {
        border-color: var(--danger);
      }
      .wrapper.invalid:focus-within {
        border-color: var(--danger);
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
      :host([nowrap]) pre, :host([nowrap]) textarea {
        white-space: pre;
        overflow-wrap: normal;
        word-break: normal;
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
        /* The wrapper draws the focus ring (:focus-within above). */
        outline: none;
        overflow: auto;
      }
      textarea::selection { background: var(--selection-bg); color: transparent; }
    `,
  ];

  render() {
    const tokens = tokenize(this.value, this.language);
    const height = `min-height: calc(${this.rows} * var(--ce-line-height) * var(--ce-font-size) + var(--ce-padding-y) * 2);`;
    return html`
      <div class="wrapper ${this.invalid ? 'invalid' : ''}">
        <pre aria-hidden="true" style=${height}>${tokens.map(t =>
          html`<span class=${`ch-tok ch-${t.kind}`}>${t.value}</span>`,
        )}<span class="ch-tok ch-text">${'\n'}</span></pre>
        <textarea
          .value=${this.value}
          ?disabled=${this.disabled}
          aria-label=${this.label || nothing}
          aria-invalid=${this.invalid ? 'true' : nothing}
          wrap=${this.nowrap ? 'off' : 'soft'}
          spellcheck="false"
          autocomplete="off"
          autocorrect="off"
          autocapitalize="off"
          placeholder=${this.placeholder}
          rows=${this.rows}
          style=${height}
          @input=${this.handleInput}
          @scroll=${this.handleScroll}
          @keydown=${this.handleKeyDown}
        ></textarea>
      </div>
    `;
  }

  /** Puts the caret at the start of the line holding `offset`, scrolls that line into view and focuses the text area. */
  focusAt(offset: number) {
    const ta = this.textarea;
    if (!ta) return;
    const at = Math.max(0, Math.min(offset, ta.value.length));
    ta.focus();
    ta.setSelectionRange(at, at);
    const line = ta.value.slice(0, at).split('\n').length - 1;
    const lineHeight = parseFloat(getComputedStyle(ta).lineHeight) || 20;
    ta.scrollTop = Math.max(0, line * lineHeight - ta.clientHeight / 3);
    ta.scrollLeft = 0;
    this.handleScroll();
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
