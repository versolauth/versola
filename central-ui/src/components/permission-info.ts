import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { Permission, Resource } from '../types';
import { resolvePermissionEndpointGroups } from '../utils/helpers';

// Module-level registry so opening one tooltip closes all others.
const registry = new Set<VersolaPermissionInfo>();

@customElement('versola-permission-info')
export class VersolaPermissionInfo extends LitElement {
  @property({ attribute: false }) permission!: Permission;
  @property({ attribute: false }) resources: Resource[] = [];

  @state() private open = false;

  private handleDocumentClick = () => {
    this.open = false;
  };

  connectedCallback() {
    super.connectedCallback();
    registry.add(this);
    document.addEventListener('click', this.handleDocumentClick);
  }

  disconnectedCallback() {
    registry.delete(this);
    document.removeEventListener('click', this.handleDocumentClick);
    super.disconnectedCallback();
  }

  close() {
    this.open = false;
  }

  private handleButtonClick(e: Event) {
    e.stopPropagation();
    const wasOpen = this.open;
    registry.forEach(inst => { if (inst !== this) inst.close(); });
    this.open = !wasOpen;
  }

  static styles = [
    theme,
    css`
      :host {
        position: relative;
        display: inline-flex;
        align-items: center;
        flex: none;
      }

      .option-info-button {
        flex: none;
        border: 1px solid rgba(88, 166, 255, 0.4);
        border-radius: 999px;
        background: rgba(88, 166, 255, 0.12);
        color: var(--accent);
        font-size: 0.75rem;
        font-weight: 700;
        line-height: 1;
        padding: 0.25rem 0.45rem;
        cursor: pointer;
        font-family: var(--font-family);
      }

      .option-info-button:hover {
        background: rgba(88, 166, 255, 0.18);
        border-color: rgba(88, 166, 255, 0.55);
      }

      .option-info-button:focus-visible {
        outline: none;
        box-shadow: 0 0 0 2px rgba(88, 166, 255, 0.2);
      }

      .option-tooltip {
        position: absolute;
        right: 0;
        top: calc(100% + 0.4rem);
        z-index: 20;
        min-width: 18rem;
        max-width: min(28rem, 75vw);
        max-height: 18rem;
        overflow: auto;
        padding: 0.75rem;
        border: 1px solid rgba(88, 166, 255, 0.28);
        border-radius: var(--radius-md);
        background: linear-gradient(180deg, rgba(22, 27, 34, 0.98), rgba(13, 17, 23, 0.98));
        box-shadow: 0 10px 24px rgba(0, 0, 0, 0.35);
      }

      .option-tooltip-title {
        margin-bottom: 0.5rem;
        color: var(--accent);
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .option-tooltip-groups,
      .option-endpoint-list {
        display: grid;
        gap: 0.375rem;
      }

      .option-tooltip-empty {
        color: var(--text-secondary);
        font-size: 0.75rem;
      }

      .option-tooltip-group {
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-sm);
        background: rgba(255, 255, 255, 0.03);
        padding: 0.625rem 0.75rem;
      }

      .option-tooltip-group-title {
        color: var(--accent);
        font-size: 0.75rem;
        font-weight: 600;
        font-family: var(--font-mono);
        margin-bottom: 0.25rem;
        overflow-wrap: anywhere;
      }

      .option-endpoint-row {
        color: var(--text-primary);
        font-size: 0.75rem;
        line-height: 1.4;
        font-family: var(--font-mono);
        overflow-wrap: anywhere;
      }

      .option-endpoint-method {
        color: var(--text-primary);
        font-weight: 600;
      }
    `,
  ];

  render() {
    const groups = resolvePermissionEndpointGroups(this.permission, this.resources);
    return html`
      <button
        type="button"
        class="option-info-button"
        aria-label=${`Show endpoints for ${this.permission?.id}`}
        aria-expanded=${this.open ? 'true' : 'false'}
        @click=${this.handleButtonClick}
      >i</button>
      ${this.open ? html`
        <div class="option-tooltip" role="tooltip" @click=${(e: Event) => e.stopPropagation()}>
          <div class="option-tooltip-title">Resources &amp; endpoints</div>
          ${groups.length > 0 ? html`
            <div class="option-tooltip-groups">
              ${groups.map(group => html`
                <div class="option-tooltip-group">
                  <div class="option-tooltip-group-title">${group.title}</div>
                  <div class="option-endpoint-list">
                    ${group.endpoints.map(ep => html`
                      <div class="option-endpoint-row">
                        <span class="option-endpoint-method">${ep.method}</span>
                        <span>${ep.path}</span>
                      </div>
                    `)}
                  </div>
                </div>
              `)}
            </div>
          ` : html`<div class="option-tooltip-empty">No endpoints</div>`}
        </div>
      ` : ''}
    `;
  }
}
