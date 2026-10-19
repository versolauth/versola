import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, formStyles, iconButtonStyles, methodBadgeStyles } from '../styles/components';
import type { Resource, ResourceEndpointId } from '../types';
import { formatResourceLabel } from '../utils/helpers';
import { statusIcon } from './status-icon';

/**
 * The hierarchical picker of a permission's endpoints (D8): add a resource from a select, then
 * add endpoints of that resource one by one; each added endpoint is a row with its method badge
 * and path and a remove button. Endpoint ids no resource carries any more are listed as detached,
 * to be removed. Fires `change` with `{ value: ResourceEndpointId[] }`.
 */
@customElement('versola-endpoint-picker')
export class VersolaEndpointPicker extends LitElement {
  @property({ attribute: false }) resources: Resource[] = [];
  @property({ attribute: false }) value: ResourceEndpointId[] = [];
  @state() private openResourceIds: string[] = [];
  @state() private resourceToAdd = '';
  @state() private endpointToAdd: Record<string, string> = {};

  static styles = [
    theme,
    buttonStyles,
    formStyles,
    iconButtonStyles,
    methodBadgeStyles,
    css`
      :host {
        display: block;
      }

      .picker-row {
        display: flex;
        gap: 0.5rem;
        align-items: flex-end;
        flex-wrap: wrap;
        margin-bottom: var(--spacing-md);
      }

      .picker-row .form-group {
        flex: 1 1 14rem;
        max-width: 22.8rem;
        margin-bottom: 0;
      }

      .resource {
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        background: var(--surface-inset);
        padding: 0.75rem var(--spacing-md);
        margin-bottom: 0.625rem;
      }

      .resource-head {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: var(--spacing-md);
      }

      .resource-name {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        color: var(--accent);
        font-weight: 600;
        overflow-wrap: anywhere;
      }

      .endpoints {
        display: grid;
        gap: 0.5rem;
        margin-top: 0.75rem;
      }

      .endpoint {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 0.75rem;
        padding: 0.5rem 0.75rem;
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        background: var(--surface);
      }

      .endpoint-main {
        display: flex;
        align-items: center;
        gap: 0.75rem;
        flex-wrap: wrap;
        min-width: 0;
      }

      .endpoint .picker-row {
        margin: 0;
        flex: 1;
      }

      .empty,
      .note {
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .note {
        margin-bottom: 0.5rem;
      }

      .icon-button.danger:hover {
        color: var(--danger);
      }
    `,
  ];

  updated(changed: Map<string, unknown>) {
    // A resource that holds a chosen endpoint is open from the start, so the editor shows what
    // the permission already has instead of a list of collapsed names.
    if (changed.has('resources') || changed.has('value')) {
      const chosen = this.resources
        .filter(resource => resource.endpoints.some(endpoint => this.value.includes(endpoint.id)))
        .map(resource => resource.resourceId);
      const known = new Set([...this.openResourceIds, ...chosen]);
      if (known.size !== this.openResourceIds.length) this.openResourceIds = [...known];
    }
  }

  private get detachedIds(): ResourceEndpointId[] {
    return this.value.filter(id => !this.resources.some(resource => resource.endpoints.some(endpoint => endpoint.id === id)));
  }

  private emit(value: ResourceEndpointId[]) {
    this.value = value;
    this.dispatchEvent(new CustomEvent('change', { detail: { value }, bubbles: true, composed: true }));
  }

  private addResource() {
    if (!this.resourceToAdd || this.openResourceIds.includes(this.resourceToAdd)) return;
    this.openResourceIds = [...this.openResourceIds, this.resourceToAdd];
    this.resourceToAdd = '';
  }

  private removeResource(resource: Resource) {
    const ids = new Set(resource.endpoints.map(endpoint => endpoint.id));
    this.openResourceIds = this.openResourceIds.filter(id => id !== resource.resourceId);
    this.emit(this.value.filter(id => !ids.has(id)));
  }

  private addEndpoint(resource: Resource) {
    const option = this.endpointToAdd[resource.resourceId];
    const endpoint = resource.endpoints.find(candidate => String(candidate.id) === option);
    if (!endpoint || this.value.includes(endpoint.id)) return;
    this.emit([...this.value, endpoint.id]);
    this.endpointToAdd = { ...this.endpointToAdd, [resource.resourceId]: '' };
  }

  render() {
    const open = this.openResourceIds
      .map(id => this.resources.find(resource => resource.resourceId === id))
      .filter((resource): resource is Resource => Boolean(resource));
    const available = this.resources.filter(resource => !this.openResourceIds.includes(resource.resourceId));
    return html`
      <div class="picker-row">
        <div class="form-group">
          <label for="endpoint-picker-resource">Add resource</label>
          <select id="endpoint-picker-resource" .value=${this.resourceToAdd}
            @change=${(e: Event) => this.resourceToAdd = (e.target as HTMLSelectElement).value}>
            <option value="">Select resource</option>
            ${available.map(resource => html`<option value=${resource.resourceId}>${formatResourceLabel(resource.resource)}</option>`)}
          </select>
        </div>
        <button type="button" class="btn btn-secondary" ?disabled=${!this.resourceToAdd} @click=${this.addResource}>Add resource</button>
      </div>

      ${open.length === 0 && this.detachedIds.length === 0 ? html`<div class="empty">No endpoints linked. The permission can be granted but opens nothing.</div>` : ''}

      ${open.map(resource => {
        const added = resource.endpoints.filter(endpoint => this.value.includes(endpoint.id));
        const rest = resource.endpoints.filter(endpoint => !this.value.includes(endpoint.id));
        const label = formatResourceLabel(resource.resource);
        return html`
          <div class="resource">
            <div class="resource-head">
              <span class="resource-name">${label}</span>
              <button type="button" class="icon-button danger" aria-label=${`Remove resource ${label}`} title="Remove resource"
                @click=${() => this.removeResource(resource)}>${statusIcon('x', 'icon')}</button>
            </div>
            <div class="endpoints">
              ${added.map(endpoint => html`
                <div class="endpoint">
                  <div class="endpoint-main">
                    <span class=${`method-badge method-${endpoint.method.toLowerCase()}`}>${endpoint.method}</span>
                    <span class="endpoint-path">${endpoint.path}</span>
                  </div>
                  <button type="button" class="icon-button danger" aria-label=${`Remove endpoint ${endpoint.method} ${endpoint.path}`} title="Remove endpoint"
                    @click=${() => this.emit(this.value.filter(id => id !== endpoint.id))}>${statusIcon('x', 'icon')}</button>
                </div>`)}
              ${added.length === 0 ? html`<div class="empty">No endpoints added yet.</div>` : ''}
              ${rest.length ? html`
                <div class="picker-row">
                  <div class="form-group">
                    <label for=${`endpoint-picker-${resource.resourceId}`}>Add endpoint</label>
                    <select id=${`endpoint-picker-${resource.resourceId}`} aria-label=${`Add endpoint for ${label}`}
                      .value=${this.endpointToAdd[resource.resourceId] ?? ''}
                      @change=${(e: Event) => this.endpointToAdd = { ...this.endpointToAdd, [resource.resourceId]: (e.target as HTMLSelectElement).value }}>
                      <option value="">Select endpoint</option>
                      ${rest.map(endpoint => html`<option value=${String(endpoint.id)}>${endpoint.method} ${endpoint.path}</option>`)}
                    </select>
                  </div>
                  <button type="button" class="btn btn-secondary" ?disabled=${!(this.endpointToAdd[resource.resourceId] ?? '')}
                    @click=${() => this.addEndpoint(resource)}>Add endpoint</button>
                </div>` : ''}
            </div>
          </div>
        `;
      })}

      ${this.detachedIds.length ? html`
        <div class="resource">
          <div class="resource-head"><span class="resource-name">Detached endpoints</span></div>
          <div class="endpoints">
            <div class="note">These endpoints no longer exist in Resources. Remove the ones you no longer want on this permission.</div>
            ${this.detachedIds.map(id => html`
              <div class="endpoint">
                <div class="endpoint-main"><span class="endpoint-path">Endpoint #${id}</span></div>
                <button type="button" class="icon-button danger" aria-label=${`Remove detached endpoint ${id}`} title="Remove"
                  @click=${() => this.emit(this.value.filter(candidate => candidate !== id))}>${statusIcon('x', 'icon')}</button>
              </div>`)}
          </div>
        </div>` : ''}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-endpoint-picker': VersolaEndpointPicker;
  }
}
