import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, iconButtonStyles, tagStyles } from '../styles/components';
import { formatWithOffset } from '../utils/date-format';
import { copyToClipboard } from '../utils/helpers';
import { CATEGORY_LABELS, categoryOf, describeEvent, describeUserAgent, outcomeSentence, type AuditEvent } from '../utils/user-audit';
import { resultBadgeStyles, countryLabel } from './activity-shared';
import { statusIcon } from './status-icon';
import './side-panel';

/**
 * One event of the timeline, in the shared side panel as an overlay: when it happened (in the selected zone and in
 * UTC), the result, the event type, client, address, browser, session and request ids with copy buttons, the details the
 * service recorded as JSON, and the one action, to show the events of the same request. Fires `close` and `show-request`.
 */
@customElement('versola-activity-event-panel')
export class VersolaActivityEventPanel extends LitElement {
  @property({ attribute: false }) event!: AuditEvent;
  @property({ type: String }) zone = 'UTC';
  @state() private copied = '';

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    iconButtonStyles,
    tagStyles,
    resultBadgeStyles,
    css`
      :host { display: contents; }

      .card { padding: 0; margin-bottom: var(--spacing-md); border-radius: var(--radius-inner); box-shadow: none; }
      .card-head { padding: var(--spacing-md) var(--spacing-lg); border-bottom: 1px solid var(--border-default); }
      .card-head h3 { margin: 0; font-size: var(--text-lg); font-weight: 700; color: var(--text-primary); }
      .card-head p { margin: 0.25rem 0 0; font-size: var(--text-sm); color: var(--text-secondary); }

      dl.facts { display: grid; grid-template-columns: 7.5rem minmax(0, 1fr); gap: 0.75rem var(--spacing-md); margin: 0; padding: var(--spacing-md) var(--spacing-lg); }
      dl.facts dt { font-size: var(--text-sm); color: var(--text-secondary); }
      dl.facts dd { margin: 0; min-width: 0; font-size: var(--text-base); color: var(--text-primary); overflow-wrap: anywhere; }
      dd .second { display: block; font-size: var(--text-sm); color: var(--text-secondary); }

      .copyable { display: inline-flex; align-items: flex-start; gap: 0.25rem; }
      .copyable .mono { font-size: var(--text-sm); word-break: break-all; }

      pre.json {
        margin: var(--spacing-md) var(--spacing-lg) var(--spacing-lg);
        padding: var(--spacing-md);
        max-height: 20rem;
        overflow: auto;
        background: var(--code-bg);
        border-radius: var(--radius-inner);
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        line-height: 1.5;
        color: var(--text-primary);
        white-space: pre-wrap;
        overflow-wrap: anywhere;
      }
      pre.json:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }

      .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }

      @media (max-width: 720px) {
        dl.facts { grid-template-columns: minmax(0, 1fr); gap: 0.25rem; }
        dl.facts dd { margin-bottom: 0.5rem; }
      }
    `,
  ];

  private async copy(label: string, value: string) {
    if (await copyToClipboard(value)) {
      this.copied = `${label} copied`;
      window.setTimeout(() => { if (this.copied === `${label} copied`) this.copied = ''; }, 2000);
    }
  }

  private copyable(label: string, value: string, mono = true) {
    return html`<span class="copyable"><span class=${mono ? 'mono' : ''}>${value}</span>
      <button type="button" class="icon-button" aria-label=${`Copy ${label}`} title=${`Copy ${label}`} @click=${() => this.copy(label, value)}>${statusIcon('copy', 'icon', 14)}</button></span>`;
  }

  render() {
    const event = this.event;
    if (!event) return nothing;
    const { title } = describeEvent(event);
    const category = CATEGORY_LABELS[categoryOf(event)];
    const utc = formatWithOffset(event.at, 'UTC');
    const inZone = formatWithOffset(event.at, this.zone);
    const flagged = event.severity === 'high' || event.kind === 'token.reuse_detected';
    return html`
      <versola-side-panel overlay kind=${`Event · ${category}`} heading=${title} .showOpenPage=${false}
        .meta=${html`${flagged ? html`<span class="chip danger">High risk</span>` : nothing}${event.kind === 'token.reuse_detected' ? html`<span class="chip warn">Token family revoked</span>` : nothing}`}>
        <section class="card" aria-labelledby="what-happened">
          <div class="card-head"><h3 id="what-happened">What happened</h3></div>
          <dl class="facts">
            <dt>When</dt>
            <dd><time datetime=${event.at}>${inZone}</time>${this.zone === 'UTC' ? nothing : html`<span class="second">${utc}</span>`}</dd>
            <dt>Result</dt>
            <dd>${outcomeSentence(event)}${event.reason ? html` <span class="muted">· ${event.reason}</span>` : nothing}</dd>
            <dt>Event type</dt>
            <dd>${this.copyable('event type', event.kind)}</dd>
            ${event.actorId || event.actorType !== 'user' ? html`<dt>Actor</dt><dd>${event.actorType}${event.actorId ? html` <span class="mono">${event.actorId}</span>` : nothing}</dd>` : nothing}
            ${event.clientId ? html`<dt>Client</dt><dd><span class="tag">${event.clientId}</span></dd>` : nothing}
            ${event.ip ? html`<dt>IP address</dt><dd><span class="mono">${event.ip}</span>${event.country ? html` · ${countryLabel(event.country)}` : nothing}</dd>` : nothing}
            ${event.userAgent ? html`<dt>Browser</dt><dd title=${event.userAgent}>${describeUserAgent(event.userAgent)}</dd>` : nothing}
            ${event.sessionId ? html`<dt>Session</dt><dd>${this.copyable('session ID', event.sessionId)}</dd>` : nothing}
            ${event.traceId ? html`<dt>Request ID</dt><dd>${this.copyable('request ID', event.traceId)}</dd>` : nothing}
          </dl>
        </section>

        <section class="card" aria-labelledby="event-details">
          <div class="card-head"><h3 id="event-details">Details</h3><p>As recorded by the service. Token values are not included.</p></div>
          <pre class="json" tabindex="0" aria-label="Event details as JSON">${JSON.stringify(event.details, null, 2)}</pre>
        </section>

        ${event.traceId ? html`
          <button slot="footer" type="button" class="btn btn-secondary"
            @click=${() => this.dispatchEvent(new CustomEvent('show-request', { detail: { traceId: event.traceId }, bubbles: true, composed: true }))}>Show events of this request</button>` : nothing}
        <div class="sr-only" role="status" aria-live="polite">${this.copied}</div>
      </versola-side-panel>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-activity-event-panel': VersolaActivityEventPanel;
  }
}
