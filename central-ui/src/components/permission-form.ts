import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { buttonStyles, cardStyles, formStyles, methodBadgeStyles, tableStyles } from '../styles/components';
import { theme } from '../styles/theme';
import type { Permission, Resource, ResourceEndpointId } from '../types';
import { formatResourceLabel, getLocalizedDescription } from '../utils/helpers';
import { validatePermission } from '../utils/validators';

@customElement('versola-permission-form')
export class VersolaPermissionForm extends LitElement {
  @property({ type: String }) mode: 'create' | 'edit' = 'create';
  @property({ attribute: false }) permission: Permission | null = null;
  @property({ attribute: false }) resources: Resource[] = [];
  @property({ type: Boolean }) isSaving = false;

  @state() private permissionId = '';
  @state() private descriptionEn = '';
  @state() private selectedResourceIds: number[] = [];
  @state() private selectedEndpointIds: ResourceEndpointId[] = [];
  @state() private resourceToAddId = '';
  @state() private endpointToAddByResource: Record<string, string> = {};

  private get isPermissionIdInvalid() {
    const permissionId = this.permissionId.trim();
    return this.mode !== 'edit' && permissionId.length > 0 && !validatePermission(permissionId);
  }
  @state() private expandedResourceId: number | null = null;

  static styles = [theme, buttonStyles, cardStyles, formStyles, methodBadgeStyles, tableStyles, css`
    :host {
      display:block;
      margin-bottom:var(--spacing-xl);
      --compact-field-max-width: 22.8rem;
      --compact-field-width: min(100%, var(--compact-field-max-width));
      --inline-action-button-width: 7.5rem;
    }
    .form-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:var(--spacing-xl); gap:var(--spacing-md); }
    .form-title { font-size:2rem; font-weight:700; color:var(--text-primary); margin:0; }
    .form-grid { display:grid; gap:var(--spacing-lg); }
    .resource-picker { display:flex; align-items:flex-end; gap:var(--spacing-md); margin-bottom:var(--spacing-lg); width:100%; max-width:min(100%, calc(var(--compact-field-max-width) + var(--inline-action-button-width) + var(--spacing-md))); }
    .resource-picker .form-group { flex:0 1 var(--compact-field-width); margin-bottom:0; min-width:0; }
    .resource-picker .btn { flex:0 0 var(--inline-action-button-width); width:var(--inline-action-button-width); min-width:var(--inline-action-button-width); }
    .resource-stack { display:grid; gap:var(--spacing-md); }
    .resource-card { display:grid; gap:var(--spacing-md); min-width:0; padding:1rem; border:1px solid var(--border-dark); border-radius:var(--radius-md); background:rgba(255,255,255,.03); }
    .resource-card-header { display:flex; align-items:flex-start; justify-content:space-between; gap:var(--spacing-md); }
    .resource-card-trigger { display:flex; align-items:center; gap:.75rem; min-width:0; flex:1; padding:0; border:0; background:none; color:inherit; cursor:pointer; text-align:left; }
    .resource-chevron { color:var(--text-secondary); font-size:.875rem; flex:none; }
    .resource-label-card { max-width:min(28rem, 100%); padding:.15rem 0; }
    .resource-label { color:var(--accent); font-size:1rem; font-weight:600; line-height:1.35; word-break:break-all; }
    .endpoint-picker { display:flex; align-items:flex-end; gap:var(--spacing-md); width:100%; max-width:min(100%, calc(var(--compact-field-max-width) + var(--inline-action-button-width) + var(--spacing-md))); }
    .endpoint-picker .form-group { flex:0 1 var(--compact-field-width); margin-bottom:0; min-width:0; }
    .endpoint-picker .btn { flex:0 0 var(--inline-action-button-width); width:var(--inline-action-button-width); min-width:var(--inline-action-button-width); }
    .endpoint-list { display:grid; gap:.75rem; }
    .endpoint-row { display:flex; align-items:center; justify-content:space-between; gap:.75rem; flex-wrap:wrap; padding:.875rem 1rem; border:1px solid var(--border-dark); border-radius:var(--radius-md); background:rgba(255,255,255,.02); }
    .endpoint-main { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap; min-width:0; }
    .endpoint-meta { color:var(--text-secondary); font-size:.875rem; }
    .endpoint-path { font-family:var(--font-mono, monospace); color:var(--text-primary); word-break:break-all; }
    .empty-state, .hint-note { color:var(--text-secondary); font-size:.875rem; }
    .actions { display:flex; gap:1rem; justify-content:flex-end; margin-top:var(--spacing-xl); padding-top:var(--spacing-xl); border-top:1px solid var(--border-dark); }
    @media (max-width: 720px) {
      .form-header, .resource-picker, .endpoint-picker { flex-direction:column; align-items:flex-start; }
      .resource-picker .form-group, .resource-picker .btn, .endpoint-picker .form-group, .endpoint-picker .btn { width:100%; max-width:var(--compact-field-width); min-width:0; }
      .actions { flex-direction:column-reverse; }
    }
  `];

  updated(changed: Map<string, unknown>) {
    if (changed.has('permission') || changed.has('mode') || changed.has('resources')) this.resetForm();
  }

  private resetForm() {
    const endpointIds = [...(this.permission?.endpointIds ?? [])];
    const selectedResourceIds = this.resources
      .filter(resource => resource.endpoints.some(endpoint => endpointIds.includes(endpoint.id)))
      .map(resource => resource.id);

    this.permissionId = this.permission?.id ?? '';
    this.descriptionEn = getLocalizedDescription(this.permission?.description ?? {}, 'en');
    this.selectedResourceIds = selectedResourceIds;
    this.selectedEndpointIds = endpointIds;
    this.resourceToAddId = '';
    this.endpointToAddByResource = {};
    this.expandedResourceId = selectedResourceIds[0] ?? null;
  }

  private get selectedResources(): Resource[] {
    return this.selectedResourceIds
      .map(id => this.resources.find(resource => resource.id === id))
      .filter((resource): resource is Resource => Boolean(resource));
  }

  private get availableResources(): Resource[] {
    return this.resources.filter(resource => !this.selectedResourceIds.includes(resource.id));
  }

  private get detachedEndpointIds(): ResourceEndpointId[] {
    return this.selectedEndpointIds.filter(endpointId => !this.resources.some(resource => resource.endpoints.some(endpoint => endpoint.id === endpointId)));
  }

  private addResource() {
    const resourceId = Number(this.resourceToAddId);
    if (!resourceId || this.selectedResourceIds.includes(resourceId)) return;
    this.selectedResourceIds = [resourceId, ...this.selectedResourceIds];
    this.resourceToAddId = '';
    this.expandedResourceId = resourceId;
  }

  private removeResource(resourceId: number) {
    const resource = this.resources.find(item => item.id === resourceId);
    const resourceEndpointIds = new Set(resource?.endpoints.map(endpoint => endpoint.id) ?? []);
    const nextSelectedResourceIds = this.selectedResourceIds.filter(id => id !== resourceId);
    this.selectedResourceIds = nextSelectedResourceIds;
    this.selectedEndpointIds = this.selectedEndpointIds.filter(endpointId => !resourceEndpointIds.has(endpointId));
    if (this.expandedResourceId === resourceId) this.expandedResourceId = nextSelectedResourceIds[0] ?? null;

    const nextEndpointToAdd = { ...this.endpointToAddByResource };
    delete nextEndpointToAdd[String(resourceId)];
    this.endpointToAddByResource = nextEndpointToAdd;
  }

  private toggleResource(resourceId: number) {
    this.expandedResourceId = this.expandedResourceId === resourceId ? null : resourceId;
  }

  private getAddedEndpoints(resource: Resource) {
    const selectedEndpointIds = new Set(this.selectedEndpointIds);
    return resource.endpoints.filter(endpoint => selectedEndpointIds.has(endpoint.id));
  }

  private getAvailableEndpoints(resource: Resource) {
    const selectedEndpointIds = new Set(this.selectedEndpointIds);
    return resource.endpoints.filter(endpoint => !selectedEndpointIds.has(endpoint.id));
  }

  private updateEndpointToAdd(resourceId: number, value: string) {
    this.endpointToAddByResource = { ...this.endpointToAddByResource, [String(resourceId)]: value };
  }

  private addEndpoint(resource: Resource) {
    const optionValue = this.endpointToAddByResource[String(resource.id)] ?? '';
    if (!optionValue) return;
    const endpoint = resource.endpoints.find(candidate => String(candidate.id) === optionValue);
    if (!endpoint || this.selectedEndpointIds.includes(endpoint.id)) return;
    this.selectedEndpointIds = [...this.selectedEndpointIds, endpoint.id];
    this.endpointToAddByResource = { ...this.endpointToAddByResource, [String(resource.id)]: '' };
  }

  private removeEndpoint(endpointId: ResourceEndpointId) {
    this.selectedEndpointIds = this.selectedEndpointIds.filter(id => id !== endpointId);
  }

  private removeDetachedEndpoint(endpointId: ResourceEndpointId) {
    this.removeEndpoint(endpointId);
  }

  private submit(event: Event) {
    event.preventDefault();
    const id = this.permissionId.trim();
    if (!id || (this.mode !== 'edit' && !validatePermission(id))) return;

    this.dispatchEvent(new CustomEvent<Permission>('save', {
      detail: {
        id,
        description: { en: this.descriptionEn.trim() || id },
        endpointIds: [...new Set(this.selectedEndpointIds)].sort((a, b) => String(a).localeCompare(String(b))),
      },
      bubbles: true,
      composed: true,
    }));
  }

  private renderDetachedEndpoints() {
    if (this.detachedEndpointIds.length === 0) return '';

    return html`
      <div class="resource-card">
        <div class="resource-card-header">
          <div class="resource-label-card"><div class="resource-label">Detached endpoints</div></div>
        </div>
        <div class="hint-note">These linked endpoints are no longer available in resources. Remove any you no longer want attached to this permission.</div>
        <div class="endpoint-list">
          ${this.detachedEndpointIds.map(endpointId => html`
            <div class="endpoint-row">
              <div class="endpoint-main">
                <span class="endpoint-path">Endpoint ID ${endpointId}</span>
                <span class="endpoint-meta">Detached from resources</span>
              </div>
              <button
                type="button"
                class="icon-action danger"
                @click=${() => this.removeDetachedEndpoint(endpointId)}
                title="Remove detached endpoint"
                aria-label=${`Remove detached endpoint ${endpointId}`}
              >✕</button>
            </div>
          `)}
        </div>
      </div>
    `;
  }

  render() {
    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">${this.mode === 'create' ? 'Create Permission' : 'Edit Permission'}</h1>
          ${this.mode === 'edit' ? html`<div class="entity-id-meta">${this.permissionId || '—'}</div>` : ''}
        </div>
      </div>
      <div class="card">
        <form @submit=${this.submit}>
          <div class="form-grid">
            ${this.mode !== 'edit' ? html`
              <div class="form-group">
                <label class="form-label" for="permission-id">Permission ID *</label>
                <input id="permission-id" type="text" class="form-input compact-input ${this.isPermissionIdInvalid ? 'input-error' : ''}" .value=${this.permissionId} @input=${(e: Event) => this.permissionId = (e.target as HTMLInputElement).value} required />
                <div class="hint">Lowercase letters, numbers, underscore, dot or colon separators, start each segment with letter</div>
              </div>
            ` : ''}
            <div class="form-group">
              <label class="form-label" for="permission-description">English description</label>
              <input id="permission-description" type="text" class="form-input compact-input" .value=${this.descriptionEn} @input=${(e: Event) => this.descriptionEn = (e.target as HTMLInputElement).value} />
            </div>
          </div>

          <div class="resource-picker">
            <div class="form-group">
              <label class="form-label" for="permission-resource-picker">Add resource</label>
              <select id="permission-resource-picker" class="form-select" .value=${this.resourceToAddId} @change=${(e: Event) => this.resourceToAddId = (e.target as HTMLSelectElement).value}>
                <option value="">Select resource</option>
                ${this.availableResources.map(resource => html`<option value=${String(resource.id)}>${formatResourceLabel(resource.resource)}</option>`)}
              </select>
            </div>
            <button type="button" class="btn btn-secondary inline-action-button" ?disabled=${!this.resourceToAddId} @click=${() => this.addResource()}>Add resource</button>
          </div>

          ${this.selectedResources.length === 0 ? html`<div class="empty-state">No resources selected.</div>` : html`
            <div class="resource-stack">
              ${this.selectedResources.map(resource => html`
                <div class="resource-card">
                  <div class="resource-card-header">
                    <button type="button" class="resource-card-trigger" @click=${() => this.toggleResource(resource.id)} aria-expanded=${this.expandedResourceId === resource.id ? 'true' : 'false'}>
                      <span class="resource-chevron">${this.expandedResourceId === resource.id ? '▾' : '▸'}</span>
                      <div class="resource-label-card"><div class="resource-label">${formatResourceLabel(resource.resource)}</div></div>
                    </button>
                    <button type="button" class="icon-action danger" @click=${() => this.removeResource(resource.id)} title="Remove resource" aria-label="Remove resource">✕</button>
                  </div>

                  ${this.expandedResourceId === resource.id ? html`
                    <div class="endpoint-picker">
                      <div class="form-group">
                        <label class="form-label" for=${`permission-endpoint-picker-${resource.id}`}>Add endpoint</label>
                        <select
                          id=${`permission-endpoint-picker-${resource.id}`}
                          class="form-select"
                          aria-label=${`Add endpoint for ${formatResourceLabel(resource.resource)}`}
                          .value=${this.endpointToAddByResource[String(resource.id)] ?? ''}
                          @change=${(e: Event) => this.updateEndpointToAdd(resource.id, (e.target as HTMLSelectElement).value)}
                        >
                          <option value="">Select endpoint</option>
                          ${this.getAvailableEndpoints(resource).map(endpoint => html`
                            <option value=${String(endpoint.id)}>${endpoint.method} ${endpoint.path}</option>
                          `)}
                        </select>
                      </div>
                      <button
                        type="button"
                        class="btn btn-secondary inline-action-button"
                        ?disabled=${!(this.endpointToAddByResource[String(resource.id)] ?? '')}
                        @click=${() => this.addEndpoint(resource)}
                      >Add endpoint</button>
                    </div>

                    ${this.getAddedEndpoints(resource).length === 0 ? html`<div class="empty-state">No endpoints added yet.</div>` : html`
                      <div class="endpoint-list">
                        ${this.getAddedEndpoints(resource).map(endpoint => html`
                          <div class="endpoint-row">
                            <div class="endpoint-main">
                              <span class=${`method-badge method-${endpoint.method.toLowerCase()}`}>${endpoint.method}</span>
                              <span class="endpoint-path">${endpoint.path}</span>
                            </div>
                            <button
                              type="button"
                              class="icon-action danger"
                              @click=${() => this.removeEndpoint(endpoint.id)}
                              title="Remove endpoint"
                              aria-label=${`Remove endpoint ${endpoint.method} ${endpoint.path}`}
                            >✕</button>
                          </div>
                        `)}
                      </div>
                    `}
                  ` : ''}
                </div>
              `)}
            </div>
          `}

          ${this.renderDetachedEndpoints()}

          <div class="actions">
            <button type="button" class="btn btn-secondary" @click=${() => this.dispatchEvent(new CustomEvent('cancel', { bubbles: true, composed: true }))}>Cancel</button>
            <button type="submit" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : (this.mode === 'create' ? 'Create Permission' : 'Update Permission')}</button>
          </div>
        </form>
      </div>
    `;
  }
}

declare global { interface HTMLElementTagNameMap { 'versola-permission-form': VersolaPermissionForm; } }
