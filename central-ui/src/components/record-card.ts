import { LitElement, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { statusIcon } from './status-icon';

/**
 * One group of a record's settings on its page: a header with the group's name, an optional
 * one-line subtitle and either an `Edit` button (fires `edit`; the screen opens only this group
 * as its own page) or, for settings fixed when the record was created, a lock with
 * `fixedLabel`. The body is `versola-record-row`s, or anything the group needs.
 *
 * Renders into light DOM, on purpose: the body is the screen's own markup (a flow chain, a list
 * of URIs, a nested table) and keeps the screen's styles, and a test can read the whole card
 * through its region (`getByRole('region', { name })`). The host includes `recordStyles` from
 * styles/components.ts, which styles this card, its rows and the danger card.
 */
@customElement('versola-record-card')
export class VersolaRecordCard extends LitElement {
  @property({ type: String }) heading = '';
  @property({ type: String }) subheading = '';
  /** Shows the Edit button. The button's name is `Edit <heading>`. */
  @property({ type: Boolean }) editable = false;
  @property({ type: Boolean }) editDisabled = false;
  /** The lock marker of a group with no editor (`Fixed`); empty for no marker. */
  @property({ type: String }) fixedLabel = '';
  @property({ attribute: false }) body: unknown = null;

  protected createRenderRoot() {
    return this;
  }

  render() {
    return html`
      <section class="card record-card" aria-label=${this.heading}>
        <div class="record-card-head">
          <div>
            <h2>${this.heading}</h2>
            ${this.subheading ? html`<div class="record-card-sub">${this.subheading}</div>` : ''}
          </div>
          ${this.editable ? html`
            <button type="button" class="btn btn-secondary btn-sm" aria-label=${`Edit ${this.heading.toLowerCase()}`}
              ?disabled=${this.editDisabled}
              @click=${() => this.dispatchEvent(new CustomEvent('edit', { bubbles: true, composed: true }))}>Edit</button>
          ` : this.fixedLabel ? html`<span class="record-fixed">${statusIcon('lock', 'icon')}${this.fixedLabel}</span>` : ''}
        </div>
        ${this.body}
      </section>
    `;
  }
}

/**
 * A label / value row inside a record card. `fixed` names the reason the value cannot change
 * (`By type`, `Fixed at creation`) and is shown with a lock on the right. A row with no label
 * (an action row) leaves the label column empty so the value still lines up with the rows above.
 */
@customElement('versola-record-row')
export class VersolaRecordRow extends LitElement {
  @property({ type: String }) label = '';
  @property({ type: String }) fixed = '';
  @property({ attribute: false }) value: unknown = null;

  protected createRenderRoot() {
    return this;
  }

  render() {
    return html`
      <div class="record-row">
        <div class="record-label">${this.label}</div>
        <div class="record-value">${this.value}</div>
        ${this.fixed ? html`<div class="record-fixed">${statusIcon('lock', 'icon')}${this.fixed}</div>` : html`<div></div>`}
      </div>
    `;
  }
}
