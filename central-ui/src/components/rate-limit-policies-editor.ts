import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { repeat } from 'lit/directives/repeat.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, editorStyles, formStyles, segmentedStyles } from '../styles/components';
import {
  DIMENSIONS, MAX_POLICIES, MAX_WINDOWS, MODES, MODE_TEXT, SCOPES, draftFromPolicy, newPolicyDraft, validateDraft,
  type PolicyDraft, type RateLimitDimension, type RateLimitDraft, type RateLimitMode, type WindowDraft,
} from '../utils/rate-limit-policies';
import { fetchRecommendedRateLimitPolicies } from '../utils/central-api';
import { statusIcon } from './status-icon';

/**
 * The rate limit policies of an edge: one block per policy (name, mode, what to count by, scope, the
 * windows, and burst and cost under "Advanced"). The draft is owned by the host; this component
 * validates it live with the server's messages and says `draft-change` when anything changes. A
 * field that is still empty is not called wrong until the host says the form was submitted.
 * "Add recommended policies" fetches the starting set (all in shadow) from central and adds the
 * ones whose name is not already in the draft.
 */
@customElement('versola-rate-limit-policies-editor')
export class VersolaRateLimitPoliciesEditor extends LitElement {
  @property({ attribute: false }) draft: RateLimitDraft = { policies: [] };
  /** The inputs are inert while the host saves. */
  @property({ type: Boolean }) disabled = false;
  /** Set by the host on a submit: empty fields are errors from then on. */
  @property({ type: Boolean }) submitted = false;
  @state() private loadingRecommended = false;
  @state() private recommendedError = '';

  static styles = [
    theme,
    buttonStyles,
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

      .policy-head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: var(--spacing-md);
        margin-bottom: var(--spacing-md);
      }

      .policy-head h3 {
        overflow-wrap: anywhere;
      }

      .block {
        display: grid;
        gap: 0.5rem;
        margin-top: var(--spacing-md);
      }

      .block:first-of-type {
        margin-top: 0;
      }

      .block .form-group {
        margin: 0;
      }

      .block input[type='text'],
      .block select {
        max-width: none;
        width: 100%;
        box-sizing: border-box;
      }

      .text-line {
        margin: 0;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .checks {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem var(--spacing-lg);
      }

      .check {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        min-height: 1.5rem;
        font-size: var(--text-base);
        color: var(--text-primary);
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
        cursor: pointer;
      }

      .check input {
        margin: 0;
        width: 1rem;
        height: 1rem;
        accent-color: var(--accent);
      }

      .check input:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
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

      .add-row {
        margin-top: 0.25rem;
      }

      .pair {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(12rem, 1fr));
        gap: var(--spacing-md) var(--spacing-lg);
        margin-top: var(--spacing-md);
      }

      details.advanced {
        margin-top: var(--spacing-md);
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

      .actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
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

  private change(draft: RateLimitDraft) {
    this.dispatchEvent(new CustomEvent('draft-change', { detail: { draft }, bubbles: true, composed: true }));
  }

  private patch(key: number, patch: Partial<PolicyDraft>) {
    this.change({ policies: this.draft.policies.map(policy => policy.key === key ? { ...policy, ...patch } : policy) });
  }

  private updateWindow(policy: PolicyDraft, index: number, patch: Partial<WindowDraft>) {
    this.patch(policy.key, { limits: policy.limits.map((window, i) => i === index ? { ...window, ...patch } : window) });
  }

  private addPolicy() {
    const policy = newPolicyDraft();
    this.change({ policies: [...this.draft.policies, policy] });
    // The host owns the draft: the new block exists once it has re-rendered this element with it.
    setTimeout(() => void this.updateComplete.then(() => this.renderRoot.querySelector<HTMLInputElement>(`[id="rl-${policy.key}-name"]`)?.focus()));
  }

  private async addRecommended() {
    this.loadingRecommended = true;
    this.recommendedError = '';
    try {
      const recommended = await fetchRecommendedRateLimitPolicies();
      const have = new Set(this.draft.policies.map(policy => policy.name.trim()));
      const added = recommended.policies.filter(policy => !have.has(policy.name)).map(draftFromPolicy);
      this.change({ policies: [...this.draft.policies, ...added] });
    } catch (error) {
      this.recommendedError = error instanceof Error ? error.message : 'Could not load the recommended policies';
    } finally {
      this.loadingRecommended = false;
    }
  }

  private removePolicy(key: number) {
    this.change({ policies: this.draft.policies.filter(policy => policy.key !== key) });
  }

  private toggleDimension(policy: PolicyDraft, dimension: RateLimitDimension, on: boolean) {
    this.patch(policy.key, { by: on ? [...policy.by, dimension] : policy.by.filter(candidate => candidate !== dimension) });
  }

  /** Errors to show: an empty required field stays quiet until the first submit. */
  private get errors(): Record<string, string> {
    const all = validateDraft(this.draft);
    if (this.submitted) return all;
    const quiet = new Set<string>();
    this.draft.policies.forEach((policy, n) => {
      if (policy.name.trim() === '') quiet.add(`${n}.name`);
      policy.limits.forEach((window, j) => {
        if (window.limit.trim() === '') quiet.add(`${n}.limit.${j}`);
        if (window.windowSeconds.trim() === '') quiet.add(`${n}.window.${j}`);
      });
    });
    return Object.fromEntries(Object.entries(all).filter(([path]) => !quiet.has(path)));
  }

  /** The first field that fails, for the host to focus on a refused submit. */
  focusFirstError() {
    const first = Object.keys(validateDraft(this.draft))[0];
    if (!first) return;
    const [n, field, j] = first.split('.');
    const policy = this.draft.policies[Number(n)];
    if (!policy) return;
    if (field === 'burst' || field === 'cost') {
      const details = this.renderRoot.querySelector<HTMLDetailsElement>(`[id="rl-${policy.key}-advanced"]`);
      if (details) details.open = true;
    }
    const id = field === 'limit' ? `rl-${policy.key}-limit-${j}` : field === 'window' ? `rl-${policy.key}-window-${j}`
      : field === 'limits' ? `rl-${policy.key}-limit-0` : `rl-${policy.key}-${field}`;
    this.updateComplete.then(() => this.renderRoot.querySelector<HTMLInputElement>(`[id="${id}"]`)?.focus());
  }

  private renderMode(policy: PolicyDraft) {
    const options = MODES;
    const labelId = `rl-${policy.key}-mode-label`;
    return html`
      <div class="block">
        <span class="ctrl-label" id=${labelId}>Mode</span>
        <div class="seg-control" role="group" aria-labelledby=${labelId}>
          ${options.map(option => html`
            <button type="button" class="seg-btn ${policy.mode === option.id ? 'seg-active' : ''}" aria-pressed=${policy.mode === option.id ? 'true' : 'false'}
              @click=${() => this.patch(policy.key, { mode: option.id as RateLimitMode })}>${option.label}</button>`)}
        </div>
        <p class="text-line">${MODE_TEXT[policy.mode]}</p>
      </div>
    `;
  }

  private renderBy(policy: PolicyDraft) {
    const labelId = `rl-${policy.key}-by-label`;
    // A stored `ip` is kept and shown, but cannot be added: the edge has no address check yet.
    const options = policy.by.includes('ip') ? [{ id: 'ip' as RateLimitDimension, label: 'Address' }, ...DIMENSIONS] : DIMENSIONS;
    return html`
      <div class="block">
        <span class="ctrl-label" id=${labelId}>Count by</span>
        <div class="checks" role="group" aria-labelledby=${labelId}>
          ${options.map(option => html`
            <label class="check">
              <input type="checkbox" .checked=${policy.by.includes(option.id)} ?disabled=${option.id === 'ip' && !policy.by.includes('ip')}
                @change=${(e: Event) => this.toggleDimension(policy, option.id, (e.target as HTMLInputElement).checked)} />
              <span>${option.label}</span>
            </label>`)}
        </div>
        <p class="text-line">${policy.by.length === 0 ? 'Nothing selected: one bucket for the whole scope, shared by every caller.' : 'One bucket per combination of these. A call that lacks one of them (a bearer token has no DPoP key) is not counted by this policy.'}</p>
      </div>
    `;
  }

  private renderWindows(policy: PolicyDraft, n: number, errors: Record<string, string>) {
    const setError = errors[`${n}.limits`];
    return html`
      <div class="block">
        <span class="ctrl-label" id="rl-${policy.key}-limits-label">Limits</span>
        ${policy.limits.map((window, j) => {
          const limitError = errors[`${n}.limit.${j}`];
          const windowError = errors[`${n}.window.${j}`];
          const limitId = `rl-${policy.key}-limit-${j}`;
          const windowId = `rl-${policy.key}-window-${j}`;
          return html`
            <div class="window">
              <div class="form-group">
                <input type="text" id=${limitId} class=${limitError ? 'input-error' : ''} inputmode="numeric" autocomplete="off" spellcheck="false" placeholder="100"
                  aria-label=${`Window ${j + 1}: requests`} .value=${window.limit} aria-invalid=${limitError ? 'true' : nothing} aria-describedby=${`${limitId}-error`}
                  @input=${(e: Event) => this.updateWindow(policy, j, { limit: (e.target as HTMLInputElement).value })} />
                ${limitError ? html`<div class="error-message" id="${limitId}-error" role="alert">${limitError}</div>` : html`<span id="${limitId}-error"></span>`}
              </div>
              <span class="between">requests per</span>
              <div class="form-group">
                <input type="text" id=${windowId} class=${windowError ? 'input-error' : ''} inputmode="numeric" autocomplete="off" spellcheck="false" placeholder="60"
                  aria-label=${`Window ${j + 1}: seconds`} .value=${window.windowSeconds} aria-invalid=${windowError ? 'true' : nothing} aria-describedby=${`${windowId}-error`}
                  @input=${(e: Event) => this.updateWindow(policy, j, { windowSeconds: (e.target as HTMLInputElement).value })} />
                ${windowError ? html`<div class="error-message" id="${windowId}-error" role="alert">${windowError}</div>` : html`<span id="${windowId}-error"></span>`}
              </div>
              <span class="between">seconds</span>
              <button type="button" class="btn btn-secondary btn-sm remove" ?disabled=${policy.limits.length === 1} aria-label=${`Remove window ${j + 1} of ${policy.name || 'this policy'}`}
                @click=${() => this.patch(policy.key, { limits: policy.limits.filter((_, i) => i !== j) })}>Remove</button>
            </div>`;
        })}
        ${setError ? html`<div class="error-message" role="alert">${setError}</div>` : nothing}
        <div class="add-row">
          <button type="button" class="btn btn-secondary btn-sm" ?disabled=${policy.limits.length >= MAX_WINDOWS}
            @click=${() => this.patch(policy.key, { limits: [...policy.limits, { limit: '', windowSeconds: '' }] })}>Add window</button>
        </div>
        <p class="text-line">Every window must pass. A short window limits bursts, a long one the sustained rate.</p>
      </div>
    `;
  }

  private renderAdvanced(policy: PolicyDraft, n: number, errors: Record<string, string>) {
    const burstError = errors[`${n}.burst`];
    const costError = errors[`${n}.cost`];
    const id = `rl-${policy.key}`;
    return html`
      <details class="advanced" id="${id}-advanced" ?open=${Boolean(burstError || costError)}>
        <summary>Advanced <span class="summary-hint">burst, cost</span></summary>
        <div class="pair">
          <div class="form-group">
            <label for="${id}-burst">Burst</label>
            <input type="text" id="${id}-burst" class=${burstError ? 'input-error' : ''} inputmode="numeric" autocomplete="off" spellcheck="false" placeholder="1"
              .value=${policy.burst} aria-invalid=${burstError ? 'true' : nothing} aria-describedby="${id}-burst-error ${id}-burst-hint"
              @input=${(e: Event) => this.patch(policy.key, { burst: (e.target as HTMLInputElement).value })} />
            ${burstError ? html`<div class="error-message" id="${id}-burst-error" role="alert">${burstError}</div>` : html`<span id="${id}-burst-error"></span>`}
            <div class="hint" id="${id}-burst-hint">How many calls may arrive together. Empty: 1, strict spacing. Not above the smallest limit.</div>
          </div>
          <div class="form-group">
            <label for="${id}-cost">Cost</label>
            <input type="text" id="${id}-cost" class=${costError ? 'input-error' : ''} inputmode="numeric" autocomplete="off" spellcheck="false" placeholder="1"
              .value=${policy.cost} aria-invalid=${costError ? 'true' : nothing} aria-describedby="${id}-cost-error ${id}-cost-hint"
              @input=${(e: Event) => this.patch(policy.key, { cost: (e.target as HTMLInputElement).value })} />
            ${costError ? html`<div class="error-message" id="${id}-cost-error" role="alert">${costError}</div>` : html`<span id="${id}-cost-error"></span>`}
            <div class="hint" id="${id}-cost-hint">What one call takes from the bucket. Empty: 1. Not above burst.</div>
          </div>
        </div>
      </details>
    `;
  }

  private renderPolicy(policy: PolicyDraft, n: number, errors: Record<string, string>) {
    const id = `rl-${policy.key}`;
    const nameError = errors[`${n}.name`];
    const scope = SCOPES.find(candidate => candidate.id === policy.scope)!;
    return html`
      <div class="editor-box" role="group" aria-label=${`Policy ${policy.name || n + 1}`}>
        <div class="policy-head">
          <h3>${policy.name.trim() || `Policy ${n + 1}`}</h3>
          <button type="button" class="btn btn-secondary btn-sm" aria-label=${`Remove policy ${policy.name || n + 1}`} @click=${() => this.removePolicy(policy.key)}>Remove policy</button>
        </div>
        <div class="form-group block">
          <label for="${id}-name">Name</label>
          <input type="text" id="${id}-name" class=${nameError ? 'input-error' : ''} autocomplete="off" spellcheck="false" placeholder="per-client"
            .value=${policy.name} aria-invalid=${nameError ? 'true' : nothing} aria-describedby="${id}-name-error ${id}-name-hint"
            @input=${(e: Event) => this.patch(policy.key, { name: (e.target as HTMLInputElement).value })} />
          ${nameError ? html`<div class="error-message" id="${id}-name-error" role="alert">${nameError}</div>` : html`<span id="${id}-name-error"></span>`}
          <div class="hint" id="${id}-name-hint">Lower-case letters, digits, <code>_</code> and <code>-</code>. It labels the metrics and is unique on this edge.</div>
        </div>
        ${this.renderMode(policy)}
        ${this.renderBy(policy)}
        <div class="form-group block">
          <label for="${id}-scope">Scope</label>
          <select id="${id}-scope" .value=${policy.scope} @change=${(e: Event) => this.patch(policy.key, { scope: (e.target as HTMLSelectElement).value as PolicyDraft['scope'] })}>
            ${SCOPES.map(option => html`<option value=${option.id} ?selected=${policy.scope === option.id}>${option.label}</option>`)}
          </select>
          <div class="hint">${scope.text}</div>
        </div>
        ${this.renderWindows(policy, n, errors)}
        ${this.renderAdvanced(policy, n, errors)}
      </div>
    `;
  }

  render() {
    const errors = this.errors;
    const policies = this.draft.policies;
    return html`
      <fieldset ?disabled=${this.disabled}>
        <div class="banner info" role="note">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('info')}Start in shadow, then enforce</div>
            <div>A policy in shadow mode is checked on every proxied call and the edge counts what it would refuse (<code>edge_rate_limit_decisions_total</code>, result <code>would_limit</code>); nothing is refused. Read it for a while, then switch a policy to enforce: calls over the limit get <code>429</code> with <code>Retry-After</code>. The limit is per edge pod: with several pods the edge as a whole allows up to the limit times the number of pods.</div>
          </div>
        </div>
        ${policies.length === 0 ? html`
          <div class="editor-box">
            <h3>No policies yet</h3>
            <p class="editor-sub">This edge applies no rate limit. Start from the recommended set: three generous policies, all in shadow, that only count until you enforce them.</p>
            <button type="button" class="btn btn-primary" ?disabled=${this.loadingRecommended} @click=${() => this.addRecommended()}>${this.loadingRecommended ? 'Loading…' : 'Add recommended policies'}</button>
            ${this.recommendedError ? html`<div class="error-message" role="alert">${this.recommendedError}</div>` : nothing}
          </div>` : nothing}
        ${repeat(policies, policy => policy.key, (policy, n) => this.renderPolicy(policy, n, errors))}
        ${errors.set ? html`<div class="error-message" role="alert">${errors.set}</div>` : nothing}
        <div class="actions">
          <button type="button" class="btn btn-secondary" ?disabled=${policies.length >= MAX_POLICIES} @click=${() => this.addPolicy()}>Add policy</button>
          ${policies.length > 0 ? html`<button type="button" class="btn btn-secondary" ?disabled=${this.loadingRecommended} @click=${() => this.addRecommended()}>${this.loadingRecommended ? 'Loading…' : 'Add recommended policies'}</button>` : nothing}
        </div>
        ${policies.length > 0 && this.recommendedError ? html`<div class="error-message" role="alert">${this.recommendedError}</div>` : nothing}
      </fieldset>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-rate-limit-policies-editor': VersolaRateLimitPoliciesEditor;
  }
}
