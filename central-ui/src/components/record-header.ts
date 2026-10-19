import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { chipStyles, iconButtonStyles } from '../styles/components';
import { copyToClipboard } from '../utils/helpers';
import { statusIcon } from './status-icon';

export type Crumb = { label: string; onSelect?: () => void };

/**
 * The top of a record's page: the breadcrumb back to its list, the title, and a meta row with
 * the record's ID (and a button that copies it) followed by the chips that say what the record
 * is (`meta`: its kind, its security status, a warning, when it was created).
 *
 * `crumbs` are the ancestors; the last one is the current page and has no `onSelect`.
 */
@customElement('versola-record-header')
export class VersolaRecordHeader extends LitElement {
  @property({ attribute: false }) crumbs: Crumb[] = [];
  @property({ type: String }) heading = '';
  /** The record's identifier, shown in monospace with a copy button. */
  @property({ type: String }) recordId = '';
  /** What the ID is called in the copy button's name: `Copy client ID`. */
  @property({ type: String }) idLabel = 'ID';
  @property({ attribute: false }) meta: unknown = null;

  static styles = [
    theme,
    chipStyles,
    iconButtonStyles,
    css`
      :host {
        display: block;
        margin-bottom: var(--spacing-lg);
      }

      .crumbs {
        display: flex;
        gap: 0.5rem;
        align-items: center;
        flex-wrap: wrap;
        font-size: var(--text-sm);
        color: var(--text-secondary);
        margin-bottom: var(--spacing-md);
      }

      .crumb-link {
        background: none;
        border: 0;
        padding: 0.25rem 0;
        min-height: 1.5rem;
        font: inherit;
        color: var(--accent);
        cursor: pointer;
      }

      .crumb-link:hover {
        text-decoration: underline;
      }

      .crumb-link:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
        border-radius: var(--radius-sm);
      }

      .view-title {
        margin: 0 0 var(--spacing-sm);
        font-size: var(--text-4xl);
        font-weight: 800;
        letter-spacing: -0.02em;
        color: var(--text-primary);
        overflow-wrap: anywhere;
      }

      .view-meta {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        align-items: center;
      }

      .record-id {
        font-family: var(--font-mono);
        font-size: var(--text-base);
        color: var(--accent);
        word-break: break-all;
      }

      .muted {
        color: var(--text-secondary);
      }

      @media (max-width: 768px) {
        .view-title {
          font-size: var(--text-3xl);
        }
      }
    `,
  ];

  render() {
    return html`
      ${this.crumbs.length ? html`
        <nav class="crumbs" aria-label="Breadcrumb">
          ${this.crumbs.map((crumb, index) => html`
            ${index > 0 ? html`<span aria-hidden="true">›</span>` : ''}
            ${crumb.onSelect
              ? html`<button type="button" class="crumb-link" @click=${crumb.onSelect}>${crumb.label}</button>`
              : html`<span aria-current="page">${crumb.label}</span>`}
          `)}
        </nav>` : ''}
      <div class="view-head">
        <h1 class="view-title">${this.heading}</h1>
        <div class="view-meta">
          ${this.recordId ? html`
            <span class="record-id">${this.recordId}</span>
            <button type="button" class="icon-button" aria-label=${`Copy ${this.idLabel}`} title=${`Copy ${this.idLabel}`}
              @click=${() => copyToClipboard(this.recordId)}>${statusIcon('copy', 'icon')}</button>
          ` : ''}
          ${this.meta}
        </div>
      </div>
    `;
  }
}
