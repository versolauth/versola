import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { buttonStyles, cardStyles, formStyles, methodBadgeStyles, tableStyles } from '../styles/components';
import { theme } from '../styles/theme';
import type { Permission, Resource, ResourceEndpointId } from '../types';
import { createPermission, deletePermission, fetchAllPermissions, fetchResources, updatePermission } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { formatResourceLabel, getLocalizedDescription } from '../utils/helpers';
import './content-header';
import './permission-form';

type ResolvedPermissionLink = {
  resource: Resource | null;
  method: string;
  path: string;
};

type PermissionResourceGroup = {
  key: string;
  title: string;
  links: ResolvedPermissionLink[];
};

@customElement('versola-permissions-list')
export class VersolaPermissionsList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @state() private permissions: Permission[] = [];
  @state() private resources: Resource[] = [];
  @state() private expandedPermissions: Set<string> = new Set();
  @state() private loading = false;
  @state() private saving = false;
  @state() private error = '';
  @state() private editing: Permission | null = null;
  @state() private showCreateForm = false;
  @state() private searchQuery = '';

  static styles = [theme, buttonStyles, cardStyles, formStyles, methodBadgeStyles, tableStyles, css`
    :host { display:block; }
    .status { color:var(--text-secondary); margin-bottom:var(--spacing-lg); }
    .error { color:var(--danger); }
    .search-bar { margin-bottom:var(--spacing-lg); max-width:28rem; }
    .empty-state { text-align:center; padding:3rem; color:var(--text-secondary); }
    .permission-actions { display:flex; align-items:center; gap:.5rem; margin-left:var(--spacing-md); }
    .permission-stack { display:grid; gap:var(--spacing-md); }
    .permission-card { padding:var(--spacing-lg); border:1px solid var(--border-dark); border-radius:var(--radius-lg); background:var(--bg-dark-card); display:grid; gap:var(--spacing-lg); transition:border-color var(--transition-base); }
    .permission-card:hover { border-color:var(--accent); }
    .permission-header { display:flex; justify-content:space-between; gap:var(--spacing-md); align-items:center; cursor:pointer; user-select:none; }
    .permission-title { display:grid; gap:.35rem; }
    .permission-name { color:var(--text-primary); font-size:1.125rem; font-weight:600; line-height:1.35; }
    .permission-code { color:var(--accent); font-size:.875rem; font-family:var(--font-mono, monospace); word-break:break-all; }
    .resource-groups { display:grid; gap:var(--spacing-md); }
    .resource-card { display:grid; gap:var(--spacing-md); padding:1rem; border:1px solid var(--border-dark); border-radius:var(--radius-md); background:rgba(255,255,255,.03); }
    .resource-label-card { max-width:min(28rem, 100%); padding:.15rem 0; }
    .resource-label { color:var(--accent); font-size:1rem; font-weight:600; line-height:1.35; word-break:break-all; }
    .endpoint-list { display:grid; gap:.75rem; }
    .endpoint-row { display:flex; align-items:center; justify-content:space-between; gap:.75rem; flex-wrap:wrap; padding:.875rem 1rem; border:1px solid var(--border-dark); border-radius:var(--radius-md); background:rgba(255,255,255,.02); }
    .endpoint-main { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap; min-width:0; }
    .endpoint-path { font-family:var(--font-mono, monospace); color:var(--text-primary); word-break:break-all; }
    @media (max-width: 720px) {
      .permission-header { flex-direction:column; }
      .permission-actions { width:100%; }
    }
  `];

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId')) {
      this.expandedPermissions = new Set();
      this.showCreateForm = false;
      this.editing = null;
      void this.loadData();
    }
  }

  private async loadData() {
    if (!this.tenantId) { this.permissions = []; this.resources = []; this.error = ''; return; }
    this.loading = true; this.error = '';
    try {
      const [permissions, resources] = await Promise.all([fetchAllPermissions(this.tenantId), fetchResources(this.tenantId)]);
      this.permissions = permissions;
      this.resources = resources;
      const validIds = new Set(permissions.map(permission => permission.id));
      this.expandedPermissions = new Set([...this.expandedPermissions].filter(id => validIds.has(id)));
    } catch (error) {
      this.permissions = []; this.resources = [];
      this.error = error instanceof Error ? error.message : 'Failed to load permissions';
    } finally {
      this.loading = false;
    }
  }

  private resolveEndpoint(endpointId: ResourceEndpointId) {
    for (const resource of this.resources) {
      const endpoint = resource.endpoints.find(item => item.id === endpointId);
      if (endpoint) return { resource, endpoint };
    }
    return null;
  }

  private getPermissionLinks(permission: Permission): ResolvedPermissionLink[] {
    const ids = permission.endpointIds ?? [];
    return ids.map(endpointId => {
      const match = this.resolveEndpoint(endpointId);
      if (!match) return { resource: null, method: 'UNKNOWN', path: 'Detached endpoint' };
      return {
        resource: match.resource,
        method: match.endpoint.method,
        path: match.endpoint.path,
      };
    });
  }

  private get sortedPermissions(): Permission[] {
    const query = this.searchQuery.trim().toLowerCase();
    const filteredPermissions = query
      ? this.permissions.filter(permission => permission.id.toLowerCase().includes(query))
      : this.permissions;

    return [...filteredPermissions].sort((a, b) => {
      const aLabel = getLocalizedDescription(a.description) || a.id;
      const bLabel = getLocalizedDescription(b.description) || b.id;
      return aLabel.localeCompare(bLabel);
    });
  }

  private upsertPermission(permission: Permission) {
    const existingIndex = this.permissions.findIndex(candidate => candidate.id === permission.id);
    if (existingIndex === -1) {
      this.permissions = [...this.permissions, permission];
      return;
    }

    this.permissions = this.permissions.map(candidate => candidate.id === permission.id ? permission : candidate);
  }

  private removePermissionFromState(permissionId: string) {
    this.permissions = this.permissions.filter(permission => permission.id !== permissionId);
    this.expandedPermissions = new Set([...this.expandedPermissions].filter(id => id !== permissionId));
  }

  private getResourceGroups(permission: Permission): PermissionResourceGroup[] {
    const links = this.getPermissionLinks(permission);
    if (links.length === 0) return [];

    const groups = new Map<string, PermissionResourceGroup>();
    for (const link of links) {
      const key = link.resource?.resource ?? 'unknown';
      const title = link.resource ? formatResourceLabel(link.resource.resource) : 'Unknown resource';
      const existing = groups.get(key) ?? { key, title, links: [] };
      existing.links.push(link);
      groups.set(key, existing);
    }

    return [...groups.values()].sort((a, b) => a.title.localeCompare(b.title));
  }

  private renderEndpoints(links: ResolvedPermissionLink[]) {
    if (links.length === 0) return '';
    return html`
      <div class="endpoint-list">
        ${links.map(link => html`
          <div class="endpoint-row">
            <div class="endpoint-main">
              <span class=${`method-badge method-${link.method.toLowerCase()}`}>${link.method}</span>
              <span class="endpoint-path">${link.path}</span>
            </div>
          </div>
        `)}
      </div>
    `;
  }

  private renderResourceGroups(permission: Permission) {
    const groups = this.getResourceGroups(permission);
    if (groups.length === 0) return '';
    return html`
      <div class="resource-groups">
        ${groups.map(group => html`
          <div class="resource-card">
            <div class="resource-label-card"><div class="resource-label">${group.title}</div></div>
            ${this.renderEndpoints(group.links)}
          </div>
        `)}
      </div>
    `;
  }

  private toggleExpand(permissionId: string) {
    if (this.expandedPermissions.has(permissionId)) {
      this.expandedPermissions.delete(permissionId);
    } else {
      this.expandedPermissions.add(permissionId);
    }
    this.requestUpdate();
  }

  private handleCardClick(permissionId: string) {
    this.toggleExpand(permissionId);
  }

  private async savePermission(event: CustomEvent<Permission>) {
    if (!this.tenantId) return;
    this.saving = true; this.error = '';
    try {
      if (this.editing) await updatePermission(this.tenantId, this.editing, event.detail); else await createPermission(this.tenantId, event.detail);
      this.upsertPermission(event.detail);
      this.showCreateForm = false; this.editing = null;
      this.expandedPermissions = new Set([...this.expandedPermissions, event.detail.id]);
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Failed to save permission';
    } finally {
      this.saving = false;
    }
  }

  private async removePermission(permissionId: string) {
    if (!this.tenantId) return;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete permission',
      messagePrefix: 'Delete permission ',
      messageSubject: permissionId,
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    try {
      await deletePermission(this.tenantId, permissionId);
      this.removePermissionFromState(permissionId);
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Failed to delete permission';
    }
  }

  render() {
    if (!this.tenantId) return html`<div class="card"><div class="card-body status">Select a tenant to manage permissions.</div></div>`;

    if (this.showCreateForm || this.editing) {
      return html`
        ${this.error ? html`<div class="status error">${this.error}</div>` : ''}
        <versola-permission-form
          .mode=${this.editing ? 'edit' : 'create'}
          .permission=${this.editing}
          .resources=${this.resources}
          .isSaving=${this.saving}
          @save=${this.savePermission}
          @cancel=${() => { this.showCreateForm = false; this.editing = null; }}
        ></versola-permission-form>
      `;
    }

    return html`
      <content-header title="Permissions">
        ${this.permissions.length > 0 ? html`
          <button slot="actions" class="btn btn-primary" @click=${() => { this.showCreateForm = true; this.editing = null; }}>+ Create Permission</button>
        ` : ''}
      </content-header>
      ${this.error ? html`<div class="status error">${this.error}</div>` : ''}
      ${this.loading ? html`<div class="status">Loading permissions…</div>` : ''}
      ${!this.loading && this.permissions.length > 0 ? html`<div class="search-bar"><input class="form-input" type="search" aria-label="Search permissions" .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} placeholder="Search permissions by ID" /></div>` : ''}
      ${!this.loading && this.permissions.length === 0 && !this.showCreateForm && !this.editing ? html`
        <div class="card">
          <div class="empty-state">
            <h3>No permissions yet</h3>
            <p>Create your first permission to get started</p>
            <button class="btn btn-primary" @click=${() => { this.showCreateForm = true; this.editing = null; }} style="margin-top: 1rem;">
              + Create Permission
            </button>
          </div>
        </div>
      ` : ''}
      ${!this.loading && this.permissions.length > 0 && this.sortedPermissions.length === 0 ? html`
        <div class="card">
          <div class="empty-state">
            <h3>No permissions match your search</h3>
          </div>
        </div>
      ` : ''}
      ${this.permissions.length > 0 ? html`
        <div class="permission-stack">
          ${this.sortedPermissions.map(permission => {
            const isExpanded = this.expandedPermissions.has(permission.id);
            return html`
            <div class="permission-card" @click=${() => this.handleCardClick(permission.id)}>
              <div class="permission-header">
                <div class="permission-title">
                  <div class="permission-name">${getLocalizedDescription(permission.description) || permission.id}</div>
                  <div class="permission-code">${permission.id}</div>
                </div>
                <div class="permission-actions" @click=${(e: Event) => e.stopPropagation()}>
                  <button class="icon-action" @click=${() => { this.editing = permission; this.showCreateForm = false; }} title="Edit" aria-label=${`Edit permission ${permission.id}`}>
                    ✎
                  </button>
                  <button class="icon-action danger" @click=${() => this.removePermission(permission.id)} title="Delete" aria-label=${`Delete permission ${permission.id}`}>
                    ✕
                  </button>
                </div>
              </div>
              ${isExpanded ? this.renderResourceGroups(permission) : ''}
            </div>
          `;})}
        </div>` : ''}
    `;
  }
}

declare global { interface HTMLElementTagNameMap { 'versola-permissions-list': VersolaPermissionsList; } }
