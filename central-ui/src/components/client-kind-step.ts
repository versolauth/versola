import { statusIcon, type StatusIconName } from './status-icon';
import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme, resetStyles } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { SecurityProfile } from '../types';
import {
  CLIENT_KINDS,
  ClientKind,
  PlanLine,
  clientPreset,
} from '../utils/client-presets';

const KIND_ICON_PATHS: Record<ClientKind, string> = {
  web: 'M3 5h18v14H3zM3 9h18',
  device: 'M7 3h10v18H7zM11 18h2',
  service: 'M4 4h16v7H4zM4 13h16v7H4zM8 7.5h.01M8 16.5h.01',
};

const PLAN_MARKS: Record<PlanLine['state'], StatusIconName> = {
  on: 'check',
  off: 'x',
  fixed: 'lock',
  na: 'dash',
  pick: 'pick',
  note: 'info',
};

/**
 * First step of client creation: what is being built. The kind and the tenant's security
 * profile decide the client's credential and its request-integrity settings, so the step
 * spells out every consequence before the rest of the wizard asks for anything.
 */
@customElement('versola-client-kind-step')
export class VersolaClientKindStep extends LitElement {
  @property({ attribute: false }) kind: ClientKind | null = null;
  @property({ attribute: false }) profile: SecurityProfile = 'fapi2';

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
        grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
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
        /* icon, name, blurb, bullets, examples: the bullets take the slack, so the examples line of
           every card sits on the same baseline however many bullets a card has. */
        grid-template-rows: auto auto auto 1fr auto;
        font-family: inherit;
        transition: border-color var(--transition-fast);
      }

      .kind-card:hover { border-color: var(--accent); }

      .kind-card[aria-pressed='true'] {
        border-color: var(--accent);
        background: rgba(var(--accent-tint), 0.06);
        box-shadow: 0 0 0 2px rgba(var(--accent-tint), 0.16);
      }

      .kind-card[aria-pressed='true'] .kind-blurb,
      .kind-card[aria-pressed='true'] .kind-type { color: var(--text-primary); }
      .kind-icon { color: var(--accent); }
      .kind-name { font-size: 0.9375rem; font-weight: 600; color: var(--text-primary); }
      .kind-blurb { font-size: 0.8125rem; color: var(--text-secondary); line-height: 1.45; }

      .kind-gets {
        list-style: none;
        margin: 0.25rem 0 0;
        padding: 0;
        display: grid;
        align-content: start;
        gap: 0.25rem;
        font-size: 0.8125rem;
        color: var(--text-primary);
      }
      .kind-gets li::before {
        content: '✓';
        color: var(--success);
        margin-right: 0.5rem;
      }
      .kind-type {
        font-size: 0.75rem;
        color: var(--text-secondary);
        margin-top: 0.5rem;
        padding-top: 0.5rem;
        border-top: 1px solid var(--border-dark);
      }

      .plan { display: grid; gap: 0.6rem; margin-top: 0.25rem; }
      .plan-line { display: grid; grid-template-columns: 1.3rem 1fr; gap: 0.5rem; }

      .plan-mark { font-size: 0.8125rem; line-height: 1.4; padding-top: 0.15rem; }
      .plan-mark svg { display: block; }
      .plan-mark.on { color: var(--success); }
      .plan-mark.off { color: var(--danger); }
      .plan-mark.fixed { color: var(--text-secondary); }
      .plan-mark.na { color: var(--text-secondary); opacity: 0.55; }
      .plan-mark.pick { color: var(--accent); }
      .plan-mark.note { color: var(--warning); }

      .plan-text { font-size: 0.875rem; color: var(--text-primary); }
      .plan-line.na .plan-text, .plan-line.off .plan-text { color: var(--text-secondary); }
      .plan-why { font-size: 0.8125rem; color: var(--text-secondary); line-height: 1.45; margin-top: 0.1rem; }

      .lead { font-size: 0.8125rem; color: var(--text-secondary); margin-bottom: 0.6rem; }
    `,
  ];

  private selectKind(kind: ClientKind) {
    this.dispatchEvent(new CustomEvent('kind-change', { detail: { kind }, bubbles: true, composed: true }));
  }

  private renderPlanLine(line: PlanLine) {
    return html`
      <div class="plan-line ${line.state}">
        <span class="plan-mark ${line.state}">${statusIcon(PLAN_MARKS[line.state], '', 14)}</span>
        <span>
          <span class="plan-text">${line.text}</span>
          ${line.why ? html`<div class="plan-why">${line.why}</div>` : ''}
        </span>
      </div>
    `;
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
            <svg class="kind-icon" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor"
              stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d=${KIND_ICON_PATHS[k.id]}></path></svg>
            <span class="kind-name">${k.name}</span>
            <span class="kind-blurb">${k.blurb}</span>
            <ul class="kind-gets">${k.gets.map(item => html`<li>${item}</li>`)}</ul>
            <span class="kind-type">${k.examples}</span>
          </button>
        `)}
      </div>

      ${kind ? html`
        <div class="lead">
          ${kind.name} sets:
        </div>
        <div class="plan">
          ${clientPreset(kind.id, this.profile).plan.map(line => this.renderPlanLine(line))}
        </div>
      ` : html`
        <div class="lead">Pick what you are building to see what it sets.</div>
      `}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-client-kind-step': VersolaClientKindStep;
  }
}
