import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, formStyles } from '../styles/components';
import { checkRange, type RangeDraft } from '../utils/activity-range';
import { formatDateField, formatDateTime, formatTimeField, offsetLabel } from '../utils/date-format';
import { isoSecond } from '../utils/user-audit';

/**
 * The custom range popover of the Activity screen: From and To as an explicit date (`1 Nov 2026`) and a 24-hour time,
 * read in the zone the screen shows, sent as UTC instants. Fires `apply` ({ from, to }, ISO) and `cancel`. Focus stays
 * inside while it is open, Esc cancels, the first field takes focus when it opens.
 */
@customElement('versola-activity-range')
export class VersolaActivityRange extends LitElement {
  @property({ type: Boolean, reflect: true }) open = false;
  @property({ type: String }) zone = 'UTC';
  /** The window on screen, ISO: what the fields start from. */
  @property({ type: String }) from = '';
  @property({ type: String }) to = '';
  /** Server's start of retention, ISO; empty until a report has said. */
  @property({ type: String }) retentionFrom = '';
  /** A refusal from the server for the range applied last (a 400), shown under the fields. */
  @property({ type: String }) serverProblem = '';
  @state() private draft: RangeDraft = { fromDate: '', fromTime: '', toDate: '', toTime: '' };

  static styles = [
    theme,
    buttonStyles,
    formStyles,
    css`
      :host { display: none; }
      :host([open]) { display: block; }

      .popover {
        box-sizing: border-box;
        width: min(26rem, calc(100vw - 2rem));
        padding: var(--spacing-md) var(--spacing-lg) var(--spacing-lg);
        background: var(--surface-raised);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-inner);
        box-shadow: var(--shadow-card);
        z-index: var(--z-dropdown);
      }

      input { box-sizing: border-box; }
      fieldset { margin: 0 0 var(--spacing-md); padding: 0; border: 0; min-width: 0; }
      legend { padding: 0; margin-bottom: var(--spacing-sm); font-size: var(--text-xs); font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: var(--text-secondary); }
      .pair { display: grid; grid-template-columns: minmax(0, 1fr) 6rem; gap: var(--spacing-sm); }
      .pair label { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; margin: 0; }
      .error-message { margin-top: var(--spacing-xs); }
      .note { margin: 0 0 var(--spacing-md); font-size: var(--text-sm); color: var(--text-secondary); }
      .actions { display: flex; justify-content: flex-end; gap: var(--spacing-sm); }
    `,
  ];

  willUpdate(changed: Map<PropertyKey, unknown>) {
    if (changed.has('open') && this.open) {
      this.draft = {
        fromDate: formatDateField(this.from, this.zone),
        fromTime: formatTimeField(this.from, this.zone),
        toDate: formatDateField(this.to, this.zone),
        toTime: formatTimeField(this.to, this.zone),
      };
    }
  }

  updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('open') && this.open) {
      void this.updateComplete.then(() => this.renderRoot.querySelector<HTMLInputElement>('#range-from-date')?.focus());
    }
  }

  private problems() {
    return checkRange(this.draft, this.zone, this.retentionFrom ? new Date(this.retentionFrom) : null, new Date());
  }

  private cancel() {
    this.dispatchEvent(new CustomEvent('cancel', { bubbles: true, composed: true }));
  }

  private apply(event?: Event) {
    event?.preventDefault();
    const check = this.problems();
    if (check.fromProblem || check.toProblem || !check.from || !check.to) return;
    this.dispatchEvent(new CustomEvent('apply', { detail: { from: isoSecond(check.from), to: isoSecond(check.to) }, bubbles: true, composed: true }));
  }

  private onKeyDown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.cancel();
      return;
    }
    if (event.key !== 'Tab') return;
    const focusable = [...this.renderRoot.querySelectorAll<HTMLElement>('input, button:not([disabled])')];
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = (this.renderRoot as ShadowRoot).activeElement;
    if (event.shiftKey && active === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private set(key: keyof RangeDraft) {
    return (event: Event) => { this.draft = { ...this.draft, [key]: (event.target as HTMLInputElement).value }; };
  }

  private field(id: string, label: string, key: keyof RangeDraft, placeholder: string, invalid: boolean, describedBy: string | typeof nothing) {
    return html`<div><label for=${id}>${label}</label>
      <input id=${id} type="text" inputmode="text" autocomplete="off" spellcheck="false" placeholder=${placeholder}
        class=${invalid ? 'input-error' : ''} aria-invalid=${invalid ? 'true' : 'false'} aria-describedby=${describedBy}
        .value=${this.draft[key]} @input=${this.set(key)} /></div>`;
  }

  render() {
    if (!this.open) return nothing;
    const check = this.problems();
    const problems = { from: check.fromProblem, to: check.toProblem };
    const invalid = Boolean(problems.from || problems.to);
    const retention = this.retentionFrom ? formatDateTime(this.retentionFrom, this.zone, { seconds: false }) : '';
    const zone = this.zone === 'UTC' ? 'UTC' : `${this.zone} (${offsetLabel(this.to || Date.now(), this.zone)})`;
    return html`
      <form class="popover" role="dialog" aria-label="Custom range" novalidate @submit=${this.apply} @keydown=${this.onKeyDown}>
        <fieldset>
          <legend>From</legend>
          <div class="pair">
            ${this.field('range-from-date', 'From date', 'fromDate', '1 Nov 2026', Boolean(problems.from), problems.from ? 'range-from-error' : nothing)}
            ${this.field('range-from-time', 'From time (24-hour)', 'fromTime', '00:00', Boolean(problems.from), problems.from ? 'range-from-error' : nothing)}
          </div>
          ${problems.from ? html`<div class="error-message" id="range-from-error" role="alert">${problems.from}</div>` : nothing}
        </fieldset>
        <fieldset>
          <legend>To</legend>
          <div class="pair">
            ${this.field('range-to-date', 'To date', 'toDate', '4 Nov 2026', Boolean(problems.to), problems.to ? 'range-to-error' : nothing)}
            ${this.field('range-to-time', 'To time (24-hour)', 'toTime', '12:00', Boolean(problems.to), problems.to ? 'range-to-error' : nothing)}
          </div>
          ${problems.to ? html`<div class="error-message" id="range-to-error" role="alert">${problems.to}</div>` : nothing}
        </fieldset>
        ${this.serverProblem ? html`<div class="error-message" role="alert" style="margin-bottom: var(--spacing-md)">${this.serverProblem}</div>` : nothing}
        <p class="note">${zone}.${retention ? ` Events can be shown from ${retention} until now.` : ''}</p>
        <div class="actions">
          <button type="button" class="btn btn-secondary" @click=${this.cancel}>Cancel</button>
          <button type="submit" class="btn btn-primary" ?disabled=${invalid}>Apply</button>
        </div>
      </form>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-activity-range': VersolaActivityRange;
  }
}
