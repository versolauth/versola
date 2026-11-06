import { LitElement, html, css, nothing, type TemplateResult } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, editorStyles, formStyles, segmentedStyles } from '../styles/components';
import {
  BREAKER_ENABLED_PATH, BREAKER_FIELDS, INBOUND_ADVANCED, INBOUND_MAIN, INBOUND_MODE_PATH, INBOUND_MODE_TEXT, MODES,
  OUTBOUND_MAIN, OUTBOUND_MODE_PATH, OUTBOUND_MODE_TEXT, RETRY_FIELDS, startingLimitNote, validateDraft,
  type NumberField, type OverloadDraft, type OverloadMode,
} from '../utils/overload-policy';
import { statusIcon } from './status-icon';

/**
 * The fields of an edge's overload policy: an inbound section (mode, limits, the learned limit's
 * bounds, and the gains under "Advanced") and an outbound section (mode, per-peer limits, circuit
 * breaker, retry budget). A field left empty is not sent and inherits what the edge's deployment
 * uses, which every field names beneath it. The draft is owned by the host; this component
 * validates it live with the server's messages and says `draft-change` when a field changes.
 */
@customElement('versola-overload-policy-editor')
export class VersolaOverloadPolicyEditor extends LitElement {
  @property({ attribute: false }) draft: OverloadDraft = {};
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
        overflow-wrap: anywhere;
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
      .default-line code,
      .note code {
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

      .sub-title {
        margin: var(--spacing-lg) 0 0;
        font-size: var(--text-sm);
        font-weight: 700;
        color: var(--text-primary);
      }

      .sub-text {
        margin: 0.125rem 0 0;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .tri-state {
        margin-top: var(--spacing-md);
      }

      .note {
        margin: var(--spacing-sm) 0 0;
        font-size: var(--text-sm);
        color: var(--warning-text);
      }

      details.advanced {
        margin-top: var(--spacing-lg);
        border-top: 1px solid var(--border-default);
        padding-top: var(--spacing-md);
      }

      details.advanced > summary {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        min-height: 1.5rem;
        cursor: pointer;
        font-size: var(--text-sm);
        font-weight: 700;
        color: var(--text-primary);
        border-radius: var(--radius-sm);
      }

      details.advanced > summary:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      details.advanced > summary .summary-hint {
        font-weight: 400;
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
    const details = this.renderRoot.querySelector<HTMLDetailsElement>('details.advanced');
    if (details && first.startsWith('inbound.adaptive.') && INBOUND_ADVANCED.some(field => field.path === first)) details.open = true;
    this.renderRoot.querySelector<HTMLInputElement>(`[id="${this.fieldId(first)}"]`)?.focus();
  }

  private fieldId(path: string) {
    return `ov-${path.replace(/\./g, '-')}`;
  }

  private renderField(field: NumberField) {
    const id = this.fieldId(field.path);
    const error = this.errors[field.path];
    const placeholder = /^[\d.]+$/.test(field.fallback) ? field.fallback : 'default';
    return html`
      <div class="form-group">
        <div class="label-line">
          <label for=${id}>${field.label}</label>
          ${field.unit ? html`<span class="unit">${field.unit}</span>` : nothing}
        </div>
        <input type="text" id=${id} class="${error ? 'input-error' : ''}" inputmode=${field.kind === 'int' ? 'numeric' : 'decimal'}
          autocomplete="off" spellcheck="false" placeholder=${placeholder}
          .value=${this.draft[field.path] ?? ''}
          aria-invalid=${error ? 'true' : nothing} aria-describedby="${id}-error ${id}-hint"
          @input=${(e: Event) => this.set(field.path, (e.target as HTMLInputElement).value)} />
        ${error ? html`<div class="error-message" id="${id}-error" role="alert">${error}</div>` : html`<span id="${id}-error"></span>`}
        <div class="hint" id="${id}-hint">${field.hint}</div>
        <div class="default-line">Empty: ${field.env ? html`<code>${field.env}</code> if the edge sets it, otherwise ` : nothing}<strong>${field.fallback}</strong>${field.unit && /^[\d.]+$/.test(field.fallback) ? ` ${field.fallback === '1' && field.unit !== 'ms' ? field.unit.replace(/s$/, '') : field.unit}` : ''}</div>
      </div>
    `;
  }

  private renderFields(fields: NumberField[]) {
    return html`<div class="field-grid">${fields.map(field => this.renderField(field))}</div>`;
  }

  private renderMode(path: string, label: string, text: Record<OverloadMode | '', string>): TemplateResult {
    const value = (this.draft[path] ?? '') as OverloadMode | '';
    const options: Array<{ id: OverloadMode | ''; label: string }> = [{ id: '', label: 'Deployment default' }, ...MODES];
    const labelId = `${this.fieldId(path)}-label`;
    return html`
      <div class="mode-block">
        <span class="ctrl-label" id=${labelId}>${label}</span>
        <div class="seg-control" role="group" aria-labelledby=${labelId}>
          ${options.map(option => html`
            <button type="button" class="seg-btn ${value === option.id ? 'seg-active' : ''}" aria-pressed=${value === option.id ? 'true' : 'false'}
              @click=${() => this.set(path, option.id)}>${option.label}</button>`)}
        </div>
        <p class="mode-text">${text[value]}</p>
      </div>
    `;
  }

  private renderTriState(path: string, label: string, fallback: string, onText: string): TemplateResult {
    const value = this.draft[path] ?? '';
    const options = [{ id: '', label: 'Deployment default' }, { id: 'true', label: 'On' }, { id: 'false', label: 'Off' }];
    const labelId = `${this.fieldId(path)}-label`;
    return html`
      <div class="mode-block">
        <span class="ctrl-label" id=${labelId}>${label}</span>
        <div class="seg-control" role="group" aria-labelledby=${labelId}>
          ${options.map(option => html`
            <button type="button" class="seg-btn ${value === option.id ? 'seg-active' : ''}" aria-pressed=${value === option.id ? 'true' : 'false'}
              @click=${() => this.set(path, option.id)}>${option.label}</button>`)}
        </div>
        <p class="mode-text">${value === '' ? `Empty: ${fallback}.` : value === 'true' ? onText : 'Calls to a failing peer are not cut off.'}</p>
      </div>
    `;
  }

  render() {
    const errors = this.errors;
    const advancedHasError = INBOUND_ADVANCED.some(field => errors[field.path]);
    const note = startingLimitNote(this.draft);
    return html`
      <fieldset ?disabled=${this.disabled}>
        <div class="banner info" role="note">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('info')}Leave a field empty to inherit</div>
            <div>An empty field is not sent. The edge then uses its deployment's value: the <code>HTTP_ADMISSION_*</code> or <code>HTTP_CLIENT_GUARD_*</code> environment variable when it sets one, otherwise the built-in default written under the field.</div>
          </div>
        </div>

        <div class="editor-box">
          <h3>Inbound admission</h3>
          <p class="editor-sub">What the edge does when it is asked for more than it can serve.</p>
          <p class="help">A new login is refused first, then work already begun (code exchange, logout), then proxied calls; health, sync and back-channel logout never. The limit is learned from call times and executor lag between the lowest and highest limit; the starting limit is only where it begins. Start in Shadow and watch <code>http_admission_refused_total{mode="shadow"}</code> through a peak: near zero means Enforce is safe. If a healthy edge refuses, raise the highest limit; if latency climbs before it refuses, lower the tolerance or the lag target.</p>
          ${this.renderMode(INBOUND_MODE_PATH, 'Mode', INBOUND_MODE_TEXT)}
          ${this.renderFields(INBOUND_MAIN)}
          ${note ? html`<p class="note" role="status">${note}</p>` : nothing}
          <details class="advanced" ?open=${advancedHasError}>
            <summary>Advanced <span class="summary-hint">tolerance, smoothing, lag target</span></summary>
            ${this.renderFields(INBOUND_ADVANCED)}
          </details>
        </div>

        <div class="editor-box">
          <h3>Outbound calls</h3>
          <p class="editor-sub">The edge's calls to auth and to resource backends, limited per peer.</p>
          <p class="help">Shadow counts what Enforce would reject (<code>http_client_guard_rejected_total{mode="shadow"}</code>) and lets every call through. Set the request timeout a little above the backend's p99, keep the queue short, and turn the circuit breaker on last.</p>
          ${this.renderMode(OUTBOUND_MODE_PATH, 'Mode', OUTBOUND_MODE_TEXT)}
          ${this.renderFields(OUTBOUND_MAIN)}

          <h4 class="sub-title">Circuit breaker</h4>
          <p class="sub-text">Opens when too many calls to a peer fail (a transport error, a timeout, 502, 503 or 504), refuses at once while open, then lets a few probes through.</p>
          <div class="tri-state">${this.renderTriState(BREAKER_ENABLED_PATH, 'Circuit breaker', 'off', 'Calls to a peer are refused at once while its circuit is open.')}</div>
          ${this.renderFields(BREAKER_FIELDS)}

          <h4 class="sub-title">Retry budget</h4>
          <p class="sub-text">What retries may add to the load on a peer, so a struggling peer is not buried under them.</p>
          ${this.renderFields(RETRY_FIELDS)}
        </div>
      </fieldset>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-overload-policy-editor': VersolaOverloadPolicyEditor;
  }
}
