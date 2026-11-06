import { LitElement, html, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';

/**
 * The last card of a record's page: the one destructive action, named for what it deletes, with
 * a line on what happens. Fires `action`; the screen confirms and deletes. `disabledReason`
 * disables the button and says why under the text (a default role, a key tenants still use).
 * Light DOM like `versola-record-card`; styled by `recordStyles` (styles/components.ts) in the host.
 */
@customElement('versola-danger-card')
export class VersolaDangerCard extends LitElement {
  @property({ type: String }) heading = '';
  @property({ type: String }) text = '';
  @property({ type: String }) actionLabel = '';
  /** A more specific name for the button when the label alone is ambiguous (`Delete client alpha-web`). */
  @property({ type: String }) actionAriaLabel = '';
  @property({ type: String }) disabledReason = '';

  protected createRenderRoot() {
    return this;
  }

  render() {
    const disabled = !!this.disabledReason;
    return html`
      <section class="card record-card danger-card" aria-label=${this.heading}>
        <div class="record-card-head">
          <div>
            <h2>${this.heading}</h2>
            ${this.text ? html`<div class="record-card-sub">${this.text}</div>` : ''}
            ${disabled ? html`<div class="record-card-sub danger-reason" id="danger-reason">${this.disabledReason}</div>` : ''}
          </div>
          <button type="button" class="btn btn-secondary btn-sm btn-delete"
            aria-label=${this.actionAriaLabel || this.actionLabel}
            ?disabled=${disabled} aria-describedby=${disabled ? 'danger-reason' : nothing} title=${disabled ? this.disabledReason : nothing}
            @click=${() => this.dispatchEvent(new CustomEvent('action', { bubbles: true, composed: true }))}>${this.actionLabel}</button>
        </div>
      </section>
    `;
  }
}
