import { LitElement, html, css, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, formStyles, segmentedStyles } from '../styles/components';
import {
  BY_OPTIONS, MODES, MODE_TEXT, newDeclaredDraft, validateDeclared,
  type DeclaredBy, type DeclaredDraft, type DeclaredOwner, type RateLimitMode,
} from '../utils/declared-rate-limit';
import { MAX_WINDOWS } from '../utils/rate-limit-policies';

/**
 * The rate limit declared on a resource endpoint or on an OAuth client: nothing (the default), or one limit with its mode, what
 * it counts by, its windows and its burst. The draft is owned by the host (null means no limit); this component validates it
 * live with central's messages and says `draft-change` when anything changes. A field that is still empty is not called wrong
 * until the host says the form was submitted. It adds to the edge's own policies, and the smallest limit wins.
 */
@customElement('versola-declared-rate-limit-editor')
export class VersolaDeclaredRateLimitEditor extends LitElement {
  @property({ attribute: false }) draft: DeclaredDraft | null = null;
  @property() owner: DeclaredOwner = 'endpoint';
  /** The inputs are inert while the host saves. */
  @property({ type: Boolean }) disabled = false;
  /** Set by the host on a submit: empty fields are errors from then on. */
  @property({ type: Boolean }) submitted = false;
  /** Prefix of the field ids, so two editors on a page do not share them. */
  @property() fieldId = 'declared-rl';

  static styles = [
    theme,
    buttonStyles,
    formStyles,
    segmentedStyles,
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

      .block {
        display: grid;
        gap: 0.5rem;
        margin-top: var(--spacing-md);
      }

      .block:first-child {
        margin-top: 0;
      }

      .block .form-group {
        margin: 0;
      }

      .block select,
      .block input[type='text'] {
        max-width: none;
        width: 100%;
        box-sizing: border-box;
      }

      .text-line {
        margin: 0;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .window {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto auto;
        align-items: start;
        gap: 0.5rem;
      }

      .window .between {
        padding-top: 0.625rem;
        font-size: var(--text-sm);
        color: var(--text-secondary);
        white-space: nowrap;
      }

      .window .remove {
        margin-top: 0.125rem;
      }

      .block input.burst {
        width: 12rem;
        max-width: 100%;
      }

      .actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        margin-top: var(--spacing-md);
      }

      @media (max-width: 560px) {
        .window {
          grid-template-columns: minmax(0, 1fr) auto;
        }

        .window .between {
          padding-top: 0;
        }
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

  private change(draft: DeclaredDraft | null) {
    this.dispatchEvent(new CustomEvent('draft-change', { detail: { draft }, bubbles: true, composed: true }));
  }

  private patch(patch: Partial<DeclaredDraft>) {
    if (this.draft) this.change({ ...this.draft, ...patch });
  }

  private updateWindow(index: number, patch: Partial<DeclaredDraft['limits'][number]>) {
    if (this.draft) this.patch({ limits: this.draft.limits.map((window, i) => i === index ? { ...window, ...patch } : window) });
  }

  private get noun() {
    return this.owner === 'endpoint' ? 'the endpoint' : 'this client';
  }

  /** Errors to show: an empty required field stays quiet until the first submit. */
  private get errors(): Record<string, string> {
    const all = validateDeclared(this.draft, this.owner);
    if (this.submitted || !this.draft) return all;
    const quiet = new Set<string>();
    this.draft.limits.forEach((window, j) => {
      if (window.limit.trim() === '') quiet.add(`limit.${j}`);
      if (window.windowSeconds.trim() === '') quiet.add(`window.${j}`);
    });
    return Object.fromEntries(Object.entries(all).filter(([path]) => !quiet.has(path)));
  }

  /** The first field that fails, for the host to focus on a refused submit. */
  focusFirstError() {
    const first = Object.keys(validateDeclared(this.draft, this.owner))[0];
    if (!first) return;
    const [field, j] = first.split('.');
    const id = field === 'limit' || field === 'window' ? `${this.fieldId}-${field}-${j}` : field === 'limits' ? `${this.fieldId}-limit-0` : `${this.fieldId}-${field}`;
    this.updateComplete.then(() => this.renderRoot.querySelector<HTMLElement>(`[id="${id}"]`)?.focus());
  }

  private renderMode(draft: DeclaredDraft) {
    const labelId = `${this.fieldId}-mode-label`;
    return html`
      <div class="block">
        <span class="ctrl-label" id=${labelId}>Mode</span>
        <div class="seg-control" role="group" aria-labelledby=${labelId}>
          ${MODES.map(option => html`
            <button type="button" class="seg-btn ${draft.mode === option.id ? 'seg-active' : ''}" aria-pressed=${draft.mode === option.id ? 'true' : 'false'}
              @click=${() => this.patch({ mode: option.id as RateLimitMode })}>${option.label}</button>`)}
        </div>
        <p class="text-line">${MODE_TEXT[draft.mode].replace('The policy is ignored.', 'The limit is ignored.')}</p>
      </div>
    `;
  }

  private renderBy(draft: DeclaredDraft, errors: Record<string, string>) {
    const id = `${this.fieldId}-by`;
    const options = BY_OPTIONS[this.owner];
    const selected = options.find(option => option.id === draft.by) ?? options[0];
    return html`
      <div class="form-group block">
        <label for=${id}>Count by</label>
        <select id=${id} .value=${draft.by} aria-invalid=${errors.by ? 'true' : nothing}
          @change=${(e: Event) => this.patch({ by: (e.target as HTMLSelectElement).value as DeclaredBy })}>
          ${options.map(option => html`<option value=${option.id} ?selected=${draft.by === option.id}>${option.label}</option>`)}
        </select>
        ${errors.by ? html`<div class="error-message" role="alert">${errors.by}</div>` : nothing}
        <div class="hint">${selected.text} A call that lacks the value is not counted.</div>
      </div>
    `;
  }

  private renderWindows(draft: DeclaredDraft, errors: Record<string, string>) {
    return html`
      <div class="block">
        <span class="ctrl-label">Limits</span>
        ${draft.limits.map((window, j) => {
          const limitError = errors[`limit.${j}`];
          const windowError = errors[`window.${j}`];
          const limitId = `${this.fieldId}-limit-${j}`;
          const windowId = `${this.fieldId}-window-${j}`;
          return html`
            <div class="window">
              <div class="form-group">
                <input type="text" id=${limitId} class=${limitError ? 'input-error' : ''} inputmode="numeric" autocomplete="off" spellcheck="false" placeholder="100"
                  aria-label=${`Window ${j + 1}: requests`} .value=${window.limit} aria-invalid=${limitError ? 'true' : nothing} aria-describedby=${`${limitId}-error`}
                  @input=${(e: Event) => this.updateWindow(j, { limit: (e.target as HTMLInputElement).value })} />
                ${limitError ? html`<div class="error-message" id="${limitId}-error" role="alert">${limitError}</div>` : html`<span id="${limitId}-error"></span>`}
              </div>
              <span class="between">requests per</span>
              <div class="form-group">
                <input type="text" id=${windowId} class=${windowError ? 'input-error' : ''} inputmode="numeric" autocomplete="off" spellcheck="false" placeholder="60"
                  aria-label=${`Window ${j + 1}: seconds`} .value=${window.windowSeconds} aria-invalid=${windowError ? 'true' : nothing} aria-describedby=${`${windowId}-error`}
                  @input=${(e: Event) => this.updateWindow(j, { windowSeconds: (e.target as HTMLInputElement).value })} />
                ${windowError ? html`<div class="error-message" id="${windowId}-error" role="alert">${windowError}</div>` : html`<span id="${windowId}-error"></span>`}
              </div>
              <span class="between">seconds</span>
              <button type="button" class="btn btn-secondary btn-sm remove" ?disabled=${draft.limits.length === 1} aria-label=${`Remove window ${j + 1}`}
                @click=${() => this.patch({ limits: draft.limits.filter((_, i) => i !== j) })}>Remove</button>
            </div>`;
        })}
        ${errors.limits ? html`<div class="error-message" role="alert">${errors.limits}</div>` : nothing}
        <div>
          <button type="button" class="btn btn-secondary btn-sm" ?disabled=${draft.limits.length >= MAX_WINDOWS}
            @click=${() => this.patch({ limits: [...draft.limits, { limit: '', windowSeconds: '' }] })}>Add window</button>
        </div>
        <p class="text-line">Every window must pass. A short window limits bursts, a long one the sustained rate.</p>
      </div>
    `;
  }

  private renderBurst(draft: DeclaredDraft, errors: Record<string, string>) {
    const id = `${this.fieldId}-burst`;
    return html`
      <div class="form-group block">
        <label for=${id}>Burst</label>
        <input type="text" id=${id} class="burst ${errors.burst ? 'input-error' : ''}" inputmode="numeric" autocomplete="off" spellcheck="false" placeholder="1"
          .value=${draft.burst} aria-invalid=${errors.burst ? 'true' : nothing} aria-describedby="${id}-error ${id}-hint"
          @input=${(e: Event) => this.patch({ burst: (e.target as HTMLInputElement).value })} />
        ${errors.burst ? html`<div class="error-message" id="${id}-error" role="alert">${errors.burst}</div>` : html`<span id="${id}-error"></span>`}
        <div class="hint" id="${id}-hint">How many calls may arrive together. Empty: 1, strict spacing. Not above the smallest limit.</div>
      </div>
    `;
  }

  render() {
    const draft = this.draft;
    if (!draft) {
      return html`
        <fieldset ?disabled=${this.disabled}>
          <p class="text-line">No limit is set for ${this.noun}. It is limited only by the edge’s own rate limit policies.</p>
          <div class="actions">
            <button type="button" class="btn btn-secondary" @click=${() => this.change(newDeclaredDraft())}>Set a limit</button>
          </div>
        </fieldset>`;
    }
    const errors = this.errors;
    return html`
      <fieldset ?disabled=${this.disabled}>
        <p class="text-line">Checked together with the edge’s own rate limit policies: a call must pass every one, so the smallest limit wins. In metrics it is the policy
          <code>${this.owner === 'endpoint' ? 'resource-endpoint' : 'resource-client'}</code>. Start in shadow, read <code>would_limit</code>, then enforce.</p>
        ${this.renderMode(draft)}
        ${this.renderBy(draft, errors)}
        ${this.renderWindows(draft, errors)}
        ${this.renderBurst(draft, errors)}
        <div class="actions">
          <button type="button" class="btn btn-secondary" @click=${() => this.change(null)}>Remove limit</button>
        </div>
      </fieldset>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-declared-rate-limit-editor': VersolaDeclaredRateLimitEditor;
  }
}
