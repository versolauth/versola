import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme, resetStyles } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import {
  AssuranceTier,
  CLIENT_KINDS,
  ClientKind,
  PlanLine,
  clientPreset,
} from '../utils/client-presets';

const KIND_ICONS: Record<ClientKind, string> = {
  web: '▤',
  device: '▢',
  service: '⬡',
};

const PLAN_MARKS: Record<PlanLine['state'], string> = {
  on: '✓',
  off: '✕',
  fixed: '⊙',
  na: '—',
  pick: '◆',
};

/**
 * First step of client creation: what is being built, and how much the deployment can carry.
 * The pair decides the client's credential and its request-integrity settings, so the step
 * spells out every consequence before the rest of the wizard asks for anything.
 */
@customElement('versola-client-kind-step')
export class VersolaClientKindStep extends LitElement {
  @property({ attribute: false }) kind: ClientKind | null = null;
  @property({ attribute: false }) tier: AssuranceTier = 'high';

  static styles = [
    theme,
    resetStyles,
    buttonStyles,
    cardStyles,
    formStyles,
    css`
      :host { display: block; }

      .kind-grid {
        display: grid;
        grid-template-columns: repeat(3, 1fr);
        gap: var(--spacing-md);
        margin-bottom: var(--spacing-xl);
      }

      .kind-card {
        text-align: left;
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-lg);
        padding: 1rem;
        cursor: pointer;
        display: grid;
        gap: 0.4rem;
        align-content: start;
        font-family: inherit;
        transition: border-color var(--transition-fast);
      }

      .kind-card:hover { border-color: var(--accent); }

      .kind-card[aria-pressed='true'] {
        border-color: var(--accent);
        background: rgba(var(--accent-tint), 0.06);
        box-shadow: 0 0 0 2px rgba(var(--accent-tint), 0.16);
      }

      .kind-icon { font-size: 1.15rem; color: var(--accent); line-height: 1; }
      .kind-name { font-size: 0.9375rem; font-weight: 600; color: var(--text-primary); }
      .kind-blurb { font-size: 0.8125rem; color: var(--text-secondary); line-height: 1.45; }

      .kind-type {
        font-family: var(--font-mono);
        font-size: 0.6875rem;
        color: var(--text-secondary);
        margin-top: 0.15rem;
      }

      .tier-row {
        display: flex;
        align-items: baseline;
        gap: var(--spacing-md);
        margin-bottom: 0.75rem;
      }

      .seg {
        display: inline-flex;
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        overflow: hidden;
      }

      .seg button {
        padding: 0.5rem 0.9rem;
        background: var(--bg-dark);
        border: none;
        border-right: 1px solid var(--border-dark);
        color: var(--text-secondary);
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        cursor: pointer;
      }

      .seg button:last-child { border-right: none; }

      .seg button[aria-pressed='true'] {
        background: rgba(var(--accent-tint), 0.12);
        color: var(--accent);
        font-weight: 600;
      }

      .seg.danger button[aria-pressed='true'] {
        background: rgba(var(--warning-tint, 180, 83, 9), 0.12);
        color: var(--warning);
      }

      .plan { display: grid; gap: 0.6rem; margin-top: 0.25rem; }
      .plan-line { display: grid; grid-template-columns: 1.3rem 1fr; gap: 0.5rem; }

      .plan-mark { font-size: 0.8125rem; line-height: 1.4; }
      .plan-mark.on { color: var(--success); }
      .plan-mark.off { color: var(--danger); }
      .plan-mark.fixed { color: var(--text-secondary); }
      .plan-mark.na { color: var(--text-secondary); opacity: 0.55; }
      .plan-mark.pick { color: var(--accent); }

      .plan-text { font-size: 0.875rem; color: var(--text-primary); }
      .plan-line.na .plan-text, .plan-line.off .plan-text { color: var(--text-secondary); }
      .plan-why { font-size: 0.8125rem; color: var(--text-secondary); line-height: 1.45; margin-top: 0.1rem; }

      .warn {
        padding: 0.75rem 0.9rem;
        border-radius: var(--radius-md);
        background: rgba(var(--warning-tint, 180, 83, 9), 0.08);
        border-left: 3px solid var(--warning);
        font-size: 0.8125rem;
        color: var(--text-secondary);
        line-height: 1.5;
        margin-bottom: var(--spacing-lg);
      }

      .warn strong { color: var(--text-primary); }

      .lead { font-size: 0.8125rem; color: var(--text-secondary); margin-bottom: 0.6rem; }
    `,
  ];

  private selectKind(kind: ClientKind) {
    this.dispatchEvent(new CustomEvent('kind-change', { detail: { kind }, bubbles: true, composed: true }));
  }

  private selectTier(tier: AssuranceTier) {
    this.dispatchEvent(new CustomEvent('tier-change', { detail: { tier }, bubbles: true, composed: true }));
  }

  private renderPlanLine(line: PlanLine) {
    return html`
      <div class="plan-line ${line.state}">
        <span class="plan-mark ${line.state}">${PLAN_MARKS[line.state]}</span>
        <span>
          <span class="plan-text">${line.text}</span>
          ${line.why ? html`<div class="plan-why">${line.why}</div>` : ''}
        </span>
      </div>
    `;
  }

  private tierHint(kind: ClientKind | null) {
    if (this.tier === 'high') {
      return 'Default. Strong client credentials and request integrity, on from the start.';
    }
    return kind === 'web'
      ? 'Client secret, and edge can front it. The usual way to start.'
      : 'Weaker guarantees: no certificate or key binding on the access token.';
  }

  render() {
    const kind = CLIENT_KINDS.find(k => k.id === this.kind) ?? null;

    return html`
      <div class="kind-grid">
        ${CLIENT_KINDS.map(k => html`
          <button
            type="button"
            class="kind-card"
            aria-pressed=${k.id === this.kind}
            @click=${() => this.selectKind(k.id)}
          >
            <span class="kind-icon">${KIND_ICONS[k.id]}</span>
            <span class="kind-name">${k.name}</span>
            <span class="kind-blurb">${k.blurb}</span>
            <span class="kind-type">${k.clientTypeLabel}</span>
          </button>
        `)}
      </div>

      <div class="tier-row">
        <div class="seg ${this.tier === 'compat' ? 'danger' : ''}">
          <button type="button" aria-pressed=${this.tier === 'high'} @click=${() => this.selectTier('high')}>
            High assurance
          </button>
          <button type="button" aria-pressed=${this.tier === 'compat'} @click=${() => this.selectTier('compat')}>
            Compatibility
          </button>
        </div>
        <span class="plan-why" style="margin:0">${this.tierHint(this.kind)}</span>
      </div>

      ${kind && this.tier === 'compat' ? html`
        <div class="warn">
          <strong>${kind.name} · Compatibility</strong> issues access tokens with no certificate or key
          binding. Anyone who copies one from a log, a proxy or a crash dump can spend it until it
          expires. Fine to start on - the client page keeps showing that it is the weaker tier.
        </div>
      ` : ''}

      ${kind ? html`
        <div class="lead">
          ${kind.name} · ${this.tier === 'high' ? 'High assurance' : 'Compatibility'} sets:
        </div>
        <div class="plan">
          ${clientPreset(kind.id, this.tier).plan.map(line => this.renderPlanLine(line))}
        </div>
      ` : html`
        <div class="lead">Pick what you are building to see what the combination sets.</div>
      `}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-client-kind-step': VersolaClientKindStep;
  }
}
