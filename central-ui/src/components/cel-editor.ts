import { LitElement, css, html, nothing, type PropertyValues } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { celHighlightStyles } from '../styles/cel-highlight';
import { tokenizeCel } from '../utils/cel-highlight';
import { validateCel, type CelValidationResult } from '../utils/cel-validator';

@customElement('versola-cel-editor')
export class VersolaCelEditor extends LitElement {
  @property({ type: String }) value = '';
  @property({ type: String }) placeholder = '';
  @property({ type: Boolean }) disabled = false;
  @property({ type: Boolean }) multiline = false;
  @property({ type: Boolean }) required = false;
  @property({ type: Number }) rows = 2;
  @property({ type: String, attribute: 'aria-label' }) ariaLabel: string | null = null;
  @property({ type: Boolean, attribute: 'has-error', reflect: true }) hasError = false;

  @state() private validationMessage: string | null = null;
  private lastDispatchedValidity: { valid: boolean; message: string | null } | null = null;

  @query('textarea') private textarea!: HTMLTextAreaElement;
  @query('pre') private pre!: HTMLPreElement;

  static styles = [
    theme,
    celHighlightStyles,
    css`
      :host {
        display: block;
        --cel-padding-y: 0.625rem;
        --cel-padding-x: 0.875rem;
        --cel-line-height: 1.5;
        --cel-font-size: 0.8125rem;
      }
      .wrapper {
        position: relative;
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        transition: border-color var(--transition-fast);
        overflow: hidden;
      }
      .wrapper:focus-within {
        border-color: var(--accent);
        box-shadow: 0 0 0 2px rgba(88, 166, 255, 0.15);
      }
      :host([has-error]) .wrapper { border-color: var(--danger); }
      .error-message {
        margin-top: 0.375rem;
        font-size: 0.75rem;
        color: var(--danger);
        line-height: 1.4;
      }
      pre, textarea {
        margin: 0;
        padding: var(--cel-padding-y) var(--cel-padding-x);
        font-family: var(--font-mono, monospace);
        font-size: var(--cel-font-size);
        line-height: var(--cel-line-height);
        letter-spacing: 0;
        tab-size: 2;
        border: 0;
        background: transparent;
        box-sizing: border-box;
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
        resize: none;
        outline: none;
        overflow: auto;
      }
      textarea::placeholder { color: var(--text-secondary); opacity: 0.7; }
      textarea::selection { background: rgba(88, 166, 255, 0.35); color: transparent; }
      .multiline pre, .multiline textarea {
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        word-break: break-word;
      }
      .single pre, .single textarea {
        white-space: pre;
        overflow-y: hidden;
      }
      .single textarea { overflow-x: auto; }
    `,
  ];

  willUpdate(changed: PropertyValues) {
    if (changed.has('value') || changed.has('required')) {
      const validation = this.computeValidation(this.value);
      const message = validation.valid ? null : validation.error.message;
      this.validationMessage = message;
      this.hasError = message != null;
      this.maybeDispatchValidity(validation, message);
    }
  }

  render() {
    const tokens = tokenizeCel(this.value);
    const errorMessage = this.validationMessage;
    const lineCount = this.multiline ? Math.max(this.rows, 1) : 1;
    const heightStyle = this.multiline
      ? `min-height: calc(${lineCount} * var(--cel-line-height) * var(--cel-font-size) + var(--cel-padding-y) * 2);`
      : `height: calc(1 * var(--cel-line-height) * var(--cel-font-size) + var(--cel-padding-y) * 2);`;
    return html`
      <div class=${`wrapper ${this.multiline ? 'multiline' : 'single'}`}>
        <pre aria-hidden="true" style=${heightStyle}>${tokens.map(token =>
          html`<span class=${`cel-tok cel-${token.kind}${token.error ? ' cel-error' : ''}`}>${token.value}</span>`,
        )}<span class="cel-tok cel-plain">${'\n'}</span></pre>
        <textarea
          .value=${this.value}
          ?disabled=${this.disabled}
          placeholder=${this.placeholder}
          aria-label=${this.ariaLabel ?? ''}
          aria-invalid=${errorMessage != null ? 'true' : 'false'}
          spellcheck="false"
          autocomplete="off"
          autocorrect="off"
          autocapitalize="off"
          rows=${this.multiline ? this.rows : 1}
          style=${heightStyle}
          @input=${this.handleInput}
          @scroll=${this.handleScroll}
          @keydown=${this.handleKeyDown}
        ></textarea>
      </div>
      ${errorMessage != null ? html`<div class="error-message" role="alert">${errorMessage}</div>` : nothing}
    `;
  }

  private computeValidation(value: string): CelValidationResult {
    if (this.required && value.trim().length === 0) {
      return { valid: false, error: { message: 'Expression is required', position: 0 } };
    }
    return validateCel(value);
  }

  private maybeDispatchValidity(validation: CelValidationResult, message: string | null) {
    const nextValid = validation.valid;
    if (
      this.lastDispatchedValidity &&
      this.lastDispatchedValidity.valid === nextValid &&
      this.lastDispatchedValidity.message === message
    ) return;
    this.lastDispatchedValidity = { valid: nextValid, message };
    this.dispatchEvent(new CustomEvent('cel-validity', {
      detail: { valid: nextValid, message },
      bubbles: true,
      composed: true,
    }));
  }

  private handleInput(event: Event) {
    const next = (event.target as HTMLTextAreaElement).value;
    this.value = next;
    this.dispatchEvent(new CustomEvent('cel-input', { detail: { value: next }, bubbles: true, composed: true }));
  }

  private handleScroll() {
    if (!this.pre || !this.textarea) return;
    this.pre.scrollTop = this.textarea.scrollTop;
    this.pre.scrollLeft = this.textarea.scrollLeft;
  }

  private handleKeyDown(event: KeyboardEvent) {
    if (!this.multiline && event.key === 'Enter') {
      event.preventDefault();
    }
  }
}
