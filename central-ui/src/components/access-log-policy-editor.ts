import { LitElement, html, css, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, editorStyles, formStyles, segmentedStyles } from '../styles/components';
import {
  MODES, MODE_PATH, MODE_TEXT, NUMBER_FIELDS, validateDraft,
  type AccessLogDraft, type AccessLogMode, type AccessLogNumberField,
} from '../utils/access-log-policy';
import { statusIcon } from './status-icon';

/**
 * The fields of an edge's access-log policy: the mode (which requests get a `receive-http` line),
 * the slow threshold and the sample rate. A field left empty is not sent and means the edge's
 * built-in default, which every field names beneath it. The draft is owned by the host; this
 * component validates it live with the server's messages and says `draft-change` when a field
 * changes.
 */
@customElement('versola-access-log-policy-editor')
export class VersolaAccessLogPolicyEditor extends LitElement {
  @property({ attribute: false }) draft: AccessLogDraft = {};
  /** The inputs are inert while the host saves. */
  @property({ type: Boolean }) disabled = false;

  static styles = [
    theme,
    formStyles,
    editorStyles,
    segmentedStyles,
    bannerStyles,
    css`
      :host {
        display: block;
      }

      fieldset {
        border: 0;
        margin: 0;
        padding: 0;
        min-width: 0;
      }

      .field-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
        gap: var(--spacing-md) var(--spacing-lg);
        margin-top: var(--spacing-md);
      }

      .field-grid .form-group {
        margin: 0;
        min-width: 0;
      }

      .field-grid input {
        max-width: none;
        width: 100%;
        box-sizing: border-box;
      }

      .label-line {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        justify-content: space-between;
        gap: 0 var(--spacing-sm);
      }

      .unit {
        font-size: var(--text-xs);
        color: var(--text-secondary);
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
      }

      .default-line {
        margin-top: 0.125rem;
        font-size: var(--text-xs);
        color: var(--text-secondary);
      }

      .default-line strong {
        color: var(--text-primary);
        font-weight: 600;
      }

      .help {
        margin: 0 0 var(--spacing-md);
        padding: 0.75rem var(--spacing-md);
        border-radius: var(--radius-md);
        background: var(--surface-inset);
        font-size: var(--text-sm);
        color: var(--text-secondary);
        line-height: 1.5;
      }

      .help code,
      .default-line code {
        font-family: var(--font-mono);
      }

      .mode-block {
        display: grid;
        gap: 0.5rem;
      }

      .mode-text {
        margin: 0;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      fieldset:disabled .seg-btn {
        color: var(--text-muted);
        border-color: var(--border-default);
        cursor: default;
      }

      fieldset:disabled .seg-btn.seg-active {
        color: var(--bg-page);
        border-color: var(--text-secondary);
        background: var(--text-secondary);
      }
    `,
  ];

  private set(path: string, value: string) {
    this.dispatchEvent(new CustomEvent('draft-change', { detail: { draft: { ...this.draft, [path]: value } }, bubbles: true, composed: true }));
  }

  private get errors(): Record<string, string> {
    return validateDraft(this.draft);
  }

  /** The first field that fails, for the host to focus on a refused submit. */
  focusFirstError() {
    const first = Object.keys(this.errors)[0];
    if (!first) return;
    this.renderRoot.querySelector<HTMLInputElement>(`[id="${this.fieldId(first)}"]`)?.focus();
  }

  private fieldId(path: string) {
    return `al-${path}`;
  }

  private renderField(field: AccessLogNumberField) {
    const id = this.fieldId(field.path);
    const error = this.errors[field.path];
    return html`
      <div class="form-group">
        <div class="label-line">
          <label for=${id}>${field.label}</label>
          ${field.unit ? html`<span class="unit">${field.unit}</span>` : nothing}
        </div>
        <input type="text" id=${id} class="${error ? 'input-error' : ''}" inputmode=${field.kind === 'int' ? 'numeric' : 'decimal'}
          autocomplete="off" spellcheck="false" placeholder=${field.fallback}
          .value=${this.draft[field.path] ?? ''}
          aria-invalid=${error ? 'true' : nothing} aria-describedby="${id}-error ${id}-hint"
          @input=${(e: Event) => this.set(field.path, (e.target as HTMLInputElement).value)} />
        ${error ? html`<div class="error-message" id="${id}-error" role="alert">${error}</div>` : html`<span id="${id}-error"></span>`}
        <div class="hint" id="${id}-hint">${field.hint}</div>
        <div class="default-line">Empty: <strong>${field.fallback}</strong>${field.unit ? ` ${field.unit}` : ''}</div>
      </div>
    `;
  }

  private renderMode() {
    const value = (this.draft[MODE_PATH] ?? '') as AccessLogMode | '';
    const options: Array<{ id: AccessLogMode | ''; label: string }> = [{ id: '', label: 'Default (off)' }, ...MODES];
    return html`
      <div class="mode-block">
        <span class="ctrl-label" id="al-mode-label">Mode</span>
        <div class="seg-control" role="group" aria-labelledby="al-mode-label">
          ${options.map(option => html`
            <button type="button" class="seg-btn ${value === option.id ? 'seg-active' : ''}" aria-pressed=${value === option.id ? 'true' : 'false'}
              @click=${() => this.set(MODE_PATH, option.id)}>${option.label}</button>`)}
        </div>
        <p class="mode-text">${MODE_TEXT[value]}</p>
      </div>
    `;
  }

  render() {
    return html`
      <fieldset ?disabled=${this.disabled}>
        <div class="banner info" role="note">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('info')}Leave a field empty for the default</div>
            <div>An empty field is not sent. The edge then uses its built-in default, written under the field. There is no environment variable for this: the access log of an edge is set here, per edge.</div>
          </div>
        </div>

        <div class="editor-box">
          <h3>Access log</h3>
          <p class="editor-sub">Which requests this edge writes a <code>receive-http</code> log line for.</p>
          <p class="help">A request that failed (status 400 or above, or an error) is logged in every mode, and so is a slow one. The metrics (<code>http_server_requests_total</code>) count every request whatever the mode. Auth and central have no such setting: they always log every request.</p>
          ${this.renderMode()}
          <div class="field-grid">${NUMBER_FIELDS.map(field => this.renderField(field))}</div>
        </div>
      </fieldset>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-access-log-policy-editor': VersolaAccessLogPolicyEditor;
  }
}
