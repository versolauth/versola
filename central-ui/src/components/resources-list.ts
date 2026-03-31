import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { buttonStyles, cardStyles, formStyles, methodBadgeStyles, tableStyles } from '../styles/components';
import { theme } from '../styles/theme';
import type { AclRuleGroup, AclRuleNode, AclRuleTree, Resource, ResourceEndpoint, ResourceEndpointId, Rule } from '../types';
import { createResource, deleteResource, fetchResources, updateResource } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { formatResourceLabel } from '../utils/helpers';
import { validateResourceUri } from '../utils/validators';
import './content-header';

type ResourceEndpointDraft = {
  method: string;
  path: string;
  fetchUserInfo: boolean;
  allowRules: ResourceEndpoint['allowRules'];
  denyRules: ResourceEndpoint['denyRules'];
  injectHeaders: Record<string, string>;
};

type EditableResourceEndpoint = ResourceEndpointDraft & {
  id: ResourceEndpoint['id'] | null;
  draftId: string;
};

type SaveResourceEndpointPayload = ResourceEndpointDraft & {
  id?: ResourceEndpoint['id'];
};

type PersistedResourceEndpointPayload = SaveResourceEndpointPayload & {
  id: ResourceEndpoint['id'];
};

type AclSectionType = 'allow' | 'deny';
type ClaimSource = 'access_token' | 'userinfo';
type ResourceFormMode = 'none' | 'create-resource' | 'edit-resource';

const endpointMethods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;

const operatorOptions: Array<{ value: Rule['operator']; label: string }> = [
  { value: 'eq', label: '=' },
  { value: 'ne', label: '!=' },
  { value: 'gt', label: '>' },
  { value: 'gte', label: '>=' },
  { value: 'lt', label: '<' },
  { value: 'lte', label: '<=' },
  { value: 'in', label: 'in' },
  { value: 'not_in', label: 'not in' },
  { value: 'contains', label: 'contains' },
  { value: 'not_contains', label: 'not contains' },
  { value: 'starts_with', label: 'starts with' },
  { value: 'ends_with', label: 'ends with' },
  { value: 'matches', label: 'matches' },
];

function endpointLabel(endpoint: Pick<ResourceEndpointDraft, 'method' | 'path'>) {
  return `${endpoint.method} ${endpoint.path}`;
}

function createEmptyRule(): Rule {
  return { subject: 'jwt.sub', operator: 'eq', value: '' };
}

function createRuleNode(rule: Rule = createEmptyRule()): AclRuleNode {
  return { kind: 'rule', rule };
}

function createAndGroup(rules: Rule[] = [createEmptyRule()]): AclRuleGroup {
  return { kind: 'all', children: rules.map(rule => createRuleNode(rule)) };
}

function createOrGroupRoot(children: AclRuleGroup[] = []): AclRuleGroup {
  return { kind: 'any', children };
}

function cloneAclGroup(group: AclRuleGroup): AclRuleGroup {
  return JSON.parse(JSON.stringify(group)) as AclRuleGroup;
}

@customElement('versola-resources-list')
export class VersolaResourcesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @state() private resources: Resource[] = [];
  @state() private expandedResources: Set<number> = new Set();
  @state() private expandedEndpoints: Set<ResourceEndpointId> = new Set();
  @state() private searchQuery = '';
  @state() private loading = false;
  @state() private saving = false;
  @state() private error = '';
  @state() private formMode: ResourceFormMode = 'none';
  @state() private activeResourceId: number | null = null;
  @state() private expandedEditableEndpoints: Set<string> = new Set();
  @state() private openInfoKey: string | null = null;
  @state() private resourceUri = '';
  @state() private endpointDrafts: EditableResourceEndpoint[] = [];
  private nextEndpointDraftId = 0;
  private handleDocumentClick = () => {
    this.openInfoKey = null;
  };

  static styles = [theme, buttonStyles, cardStyles, formStyles, methodBadgeStyles, tableStyles, css`
    :host {
      display:block;
      --compact-field-max-width: 22.8rem;
      --compact-field-width: min(100%, var(--compact-field-max-width));
      --inline-action-button-width: 5.25rem;
    }
    *, *::before, *::after { box-sizing:border-box; }
    .status { color:var(--text-secondary); margin-bottom:var(--spacing-lg); }
    .error { color:var(--danger); }
    .empty-state { text-align:center; padding:3rem; color:var(--text-secondary); }
    .form-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:var(--spacing-xl); gap:var(--spacing-md); }
    .form-title { font-size:2rem; font-weight:700; color:var(--text-primary); margin:0; }
    .form-grid { display:grid; grid-template-columns:minmax(0, 1fr); gap:var(--spacing-lg); }
    .form-grid > * { min-width:0; }
    .form-actions { display:flex; gap:1rem; justify-content:flex-end; margin-top:var(--spacing-xl); padding-top:var(--spacing-xl); border-top:1px solid var(--border-dark); }
    .search-bar { margin-bottom:var(--spacing-lg); max-width:28rem; }
    .stack { display:grid; gap:var(--spacing-md); }
    .resource-shell { transition:border-color var(--transition-base); }
    .resource-shell:hover { border-color:var(--accent); }
    .resource-card { display:grid; gap:var(--spacing-lg); }
    .resource-header { display:flex; align-items:center; justify-content:space-between; gap:var(--spacing-md); cursor:pointer; user-select:none; }
    .resource-actions { display:flex; align-items:center; gap:.5rem; margin-left:var(--spacing-md); }
    .resource-label-card { max-width:min(32rem, 100%); padding:.15rem 0; }
    .resource-label { color:var(--accent); font-size:1rem; font-weight:600; line-height:1.35; word-break:break-all; }
    .endpoint-list { display:grid; gap:.75rem; }
    .endpoint-row { display:flex; align-items:center; justify-content:space-between; gap:.75rem; flex-wrap:wrap; padding:.875rem 1rem; border:1px solid var(--border-dark); border-radius:var(--radius-md); background:rgba(255,255,255,.02); }
    .endpoint-main { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap; min-width:0; }
    .endpoint-actions { display:flex; align-items:center; gap:.5rem; margin-left:auto; }
    .endpoint-path { font-family:var(--font-mono, monospace); color:var(--text-primary); word-break:break-all; }
    .endpoint-card { display:grid; gap:.75rem; padding:.875rem 1rem; border:1px solid var(--border-dark); border-radius:var(--radius-md); background:rgba(255,255,255,.02); cursor:pointer; transition:border-color var(--transition-base), background var(--transition-base); }
    .endpoint-card:hover { border-color:var(--accent); background:rgba(255,255,255,.03); }
    .endpoint-card-header { display:flex; align-items:center; justify-content:space-between; gap:.75rem; flex-wrap:wrap; }
    .endpoint-card-chevron { color:var(--text-secondary); font-size:.875rem; margin-left:auto; }
    .endpoint-card-details { display:grid; gap:.75rem; padding-top:.75rem; border-top:1px solid var(--border-dark); }
    .fetch-row { display:flex; align-items:center; gap:.625rem; flex-wrap:wrap; color:var(--text-primary); font-size:.875rem; }
    .fetch-row-label { font-weight:600; }
    .endpoint-detail-grid { display:grid; gap:.75rem; }
    .endpoint-detail-section { display:grid; gap:.625rem; padding:.875rem; border:1px solid var(--border-dark); border-radius:var(--radius-sm); background:linear-gradient(180deg, rgba(255,255,255,.03), rgba(255,255,255,.015)); }
    .endpoint-detail-label { color:var(--text-secondary); font-size:.75rem; font-weight:600; letter-spacing:.04em; text-transform:uppercase; }
    .endpoint-detail-value { color:var(--text-primary); font-size:.875rem; }
    .endpoint-detail-value.mono { font-family:var(--font-mono, monospace); word-break:break-word; }
    .endpoint-empty { color:var(--text-secondary); font-size:.875rem; }
    .rule-group-list, .header-list { display:grid; gap:.75rem; }
    .rule-group-view, .rule-group-editor { display:grid; gap:.625rem; }
    .rule-group-view, .rule-group-editor {
      padding-left:.875rem;
      border-left:2px solid rgba(88, 166, 255, .18);
    }
    .rule-group-actions { display:flex; gap:.5rem; flex-wrap:wrap; }
    .rule-logic-separator {
      color:var(--text-secondary);
      font-size:.72rem;
      font-weight:700;
      letter-spacing:.08em;
      text-transform:uppercase;
    }
    .rule-item, .header-item { display:grid; gap:.4rem; padding:.75rem; border:1px solid var(--border-dark); border-radius:var(--radius-sm); background:rgba(255,255,255,.03); }
    .source-badge { display:inline-flex; align-items:center; min-height:1.5rem; padding:0 .6rem; border-radius:999px; font-size:.75rem; font-weight:600; letter-spacing:.01em; background:rgba(88, 166, 255, .16); color:#7cc4ff; border:1px solid rgba(88, 166, 255, .28); }
    .rule-expression { display:flex; align-items:center; gap:.5rem; flex-wrap:wrap; }
    .rule-field, .rule-operator, .rule-value, .header-item-key, .header-item-value { font-size:.875rem; font-family:var(--font-mono, monospace); word-break:break-word; }
    .rule-field, .rule-value, .header-item-key, .header-item-value { color:var(--text-primary); }
    .rule-operator { color:var(--text-secondary); }
    .rule-meta { color:var(--text-secondary); font-size:.8125rem; }
    .header-item { grid-template-columns:minmax(0, 14rem) minmax(0, 1fr); gap:0; padding:0; overflow:hidden; align-items:stretch; }
    .header-item-part { display:flex; align-items:center; min-width:0; padding:.75rem .875rem; }
    .header-item-part + .header-item-part { border-left:1px solid var(--border-dark); }
    .header-item-part-expression { gap:.625rem; flex-wrap:wrap; }
    .header-item-key { font-weight:600; }
    .fetch-indicator { display:inline-flex; align-items:center; gap:.625rem; }
    .fetch-indicator-box { width:1.1rem; height:1.1rem; border-radius:.3rem; border:1px solid var(--border-dark); display:inline-flex; align-items:center; justify-content:center; font-size:.8rem; font-weight:700; }
    .fetch-indicator.fetch-enabled .fetch-indicator-box { background:rgba(88, 166, 255, .16); border-color:rgba(88, 166, 255, .32); color:#7cc4ff; }
    .fetch-indicator.fetch-disabled .fetch-indicator-box { background:rgba(255,255,255,.03); color:transparent; }
    .section-header { display:flex; align-items:center; justify-content:space-between; gap:var(--spacing-md); margin:var(--spacing-xl) 0 var(--spacing-md); }
    .section-title { margin:0; font-size:1rem; font-weight:600; color:var(--text-primary); }
    .endpoint-editor {
      display:grid;
      gap:.75rem;
      min-width:0;
      padding:.875rem 1rem;
      border:1px solid var(--border-dark);
      border-radius:var(--radius-md);
      background:transparent;
    }
    .endpoint-editor .form-group { margin-bottom:0; min-width:0; }
    .endpoint-editor-grid { display:grid; grid-template-columns:minmax(0, 10rem) minmax(0, var(--compact-field-width)); gap:var(--spacing-lg); }
    .endpoint-editor-header { display:flex; align-items:flex-start; justify-content:space-between; gap:var(--spacing-md); }
    .endpoint-editor-trigger { display:flex; align-items:flex-start; gap:.75rem; min-width:0; flex:1; padding:0; border:0; background:none; color:inherit; cursor:pointer; text-align:left; }
    .endpoint-editor-heading { display:grid; gap:.25rem; min-width:0; }
    .endpoint-editor-chevron { color:var(--text-secondary); font-size:.875rem; flex:none; }
    .endpoint-editor-body { display:grid; gap:var(--spacing-lg); padding-top:var(--spacing-md); border-top:1px solid var(--border-dark); }
    .endpoint-editor-section { display:grid; gap:.625rem; padding-top:var(--spacing-md); border-top:1px solid rgba(139, 148, 158, .18); }
    .endpoint-editor-body > .endpoint-editor-section:first-of-type { padding-top:0; border-top:0; }
    .editor-section-header { display:flex; align-items:center; justify-content:space-between; gap:var(--spacing-md); }
    .editor-section-title-row { display:flex; align-items:center; gap:.5rem; min-width:0; }
    .editor-section-title { margin:0; font-size:.9rem; color:var(--text-primary); }
    .option-info-button {
      flex:none;
      border:1px solid rgba(88, 166, 255, 0.4);
      border-radius:999px;
      background:rgba(88, 166, 255, 0.12);
      color:var(--accent);
      font-size:.75rem;
      font-weight:700;
      line-height:1;
      padding:0.25rem 0.45rem;
      cursor:pointer;
      font-family:var(--font-family);
    }
    .option-info { position:relative; display:inline-flex; align-items:center; flex:none; }
    .option-info-button:hover { background:rgba(88, 166, 255, 0.18); border-color:rgba(88, 166, 255, 0.55); }
    .option-info-button:focus-visible { outline:none; box-shadow:0 0 0 2px rgba(88, 166, 255, 0.2); }
    .option-tooltip {
      position:absolute;
      right:0;
      top:calc(100% + 0.4rem);
      z-index:20;
      min-width:18rem;
      max-width:min(28rem, 75vw);
      padding:0.75rem;
      border:1px solid rgba(88, 166, 255, 0.28);
      border-radius:var(--radius-md);
      background:linear-gradient(180deg, rgba(22, 27, 34, 0.98), rgba(13, 17, 23, 0.98));
      box-shadow:0 10px 24px rgba(0, 0, 0, 0.35);
      display:none;
    }
    .option-info.option-info-open .option-tooltip { display:block; }
    .option-tooltip-title {
      margin-bottom:0.5rem;
      color:var(--accent);
      font-size:0.75rem;
      font-weight:600;
      text-transform:uppercase;
      letter-spacing:0.05em;
    }
    .option-tooltip-copy { color:var(--text-primary); font-size:0.75rem; line-height:1.45; }
    .rule-group-editor-list, .rule-editor-list, .header-editor-list { display:grid; gap:.75rem; }
    .rule-group-editor:not(:first-child), .header-editor-list > :not(:first-child) { padding-top:.75rem; border-top:1px solid rgba(139, 148, 158, .16); }
    .rule-editor-item, .header-editor-item { display:grid; gap:.75rem; padding:.75rem 0 0; border:0; border-top:1px solid rgba(139, 148, 158, .16); border-radius:0; background:transparent; }
    .rule-editor-list > :first-child, .header-editor-list > :first-child { padding-top:0; border-top:0; }
    .rule-editor-main { display:grid; grid-template-columns:repeat(4, minmax(0, 1fr)) auto; gap:.75rem; align-items:start; }
    .rule-editor-pattern { display:grid; grid-template-columns:minmax(0, 10rem) minmax(0, 12rem); gap:.75rem; padding-top:.75rem; border-top:1px solid rgba(139, 148, 158, .16); }
    .header-editor-item { grid-template-columns:minmax(0, 1.05fr) minmax(0, .85fr) minmax(0, 1.1fr) auto; align-items:start; }
    .editor-remove { justify-self:end; align-self:start; }
    .sub-actions { display:flex; gap:.75rem; justify-content:flex-end; margin-top:var(--spacing-lg); }
    @media (max-width: 720px) {
      .resource-header { flex-direction:column; }
      .resource-actions, .form-actions, .section-header, .sub-actions { flex-direction:column; align-items:flex-start; }
      .endpoint-editor-grid { grid-template-columns:minmax(0, var(--compact-field-width)); }
      .rule-editor-main, .rule-editor-pattern, .header-editor-item { grid-template-columns:minmax(0, 1fr); }
    }
  `];

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId')) {
      this.expandedResources = new Set();
      this.expandedEndpoints = new Set();
      this.resetForms();
      void this.loadData();
    }
  }

  connectedCallback() {
    super.connectedCallback();
    document.addEventListener('click', this.handleDocumentClick);
  }

  disconnectedCallback() {
    document.removeEventListener('click', this.handleDocumentClick);
    super.disconnectedCallback();
  }

  private resetForms() {
    this.formMode = 'none'; this.activeResourceId = null; this.resourceUri = '';
    this.endpointDrafts = [];
    this.expandedEditableEndpoints = new Set();
  }

  private openCreateResourceForm() {
    this.error = '';
    this.formMode = 'create-resource';
    this.activeResourceId = null;
    this.resourceUri = 'https://';
    this.endpointDrafts = [];
    this.expandedEditableEndpoints = new Set();
  }

  private openEditResourceForm(resource: Resource) {
    this.error = '';
    this.formMode = 'edit-resource';
    this.activeResourceId = resource.id;
    this.resourceUri = resource.resource;
    this.endpointDrafts = resource.endpoints.map(endpoint => this.toEditableEndpoint(endpoint));
    this.expandedEditableEndpoints = new Set(); // Start with all cards collapsed
    this.expandedResources = new Set([...this.expandedResources, resource.id]);
  }

  private createEmptyEndpointDraft(): EditableResourceEndpoint {
    return {
      id: null,
      draftId: this.createEndpointDraftId(),
      method: 'GET',
      path: '/',
      fetchUserInfo: false,
      allowRules: createOrGroupRoot(),
      denyRules: createOrGroupRoot(),
      injectHeaders: {},
    };
  }

  private startCreateEndpoint() {
    if (this.formMode === 'none') return;

    this.error = '';
    const draft = this.createEmptyEndpointDraft();
    this.endpointDrafts = [...this.endpointDrafts, draft];
    this.expandedEditableEndpoints = new Set([...this.expandedEditableEndpoints, draft.draftId]);
  }

  private async loadData() {
    if (!this.tenantId) { this.resources = []; this.error = ''; return; }
    this.loading = true; this.error = '';
    try {
      const resources = await fetchResources(this.tenantId);
      this.resources = resources;
      const validIds = new Set(resources.map(resource => resource.id));
      const validEndpointIds = new Set(resources.flatMap(resource => resource.endpoints.map(endpoint => endpoint.id)));
      this.expandedResources = new Set([...this.expandedResources].filter(id => validIds.has(id)));
      this.expandedEndpoints = new Set([...this.expandedEndpoints].filter(id => validEndpointIds.has(id)));
      if (this.formMode === 'edit-resource' && this.activeResourceId !== null) {
        const activeResource = this.resources.find(resource => resource.id === this.activeResourceId);
        if (!activeResource) {
          this.resetForms();
        }
      }
    } catch (error) {
      this.resources = []; this.error = error instanceof Error ? error.message : 'Failed to load resources';
    } finally { this.loading = false; }
  }

  private async saveResource(event: Event) {
    event.preventDefault(); if (!this.tenantId) return;
    const resource = this.resourceUri.trim();
    const validation = validateResourceUri(resource);
    if (!validation.valid) { this.error = validation.error ?? 'Resource URI is invalid'; return; }
    const invalidEndpoint = this.endpointDrafts.find(endpoint => !endpoint.path.startsWith('/'));
    if (invalidEndpoint) {
      this.error = `Endpoint path must start with “/”: ${endpointLabel(invalidEndpoint)}`;
      return;
    }

    const endpointPayloads = this.endpointDrafts.map(endpoint => this.toEndpointPayload(endpoint));
    this.saving = true; this.error = '';
    try {
      let savedResourceId: number | null = null;
      let savedEndpoints: PersistedResourceEndpointPayload[] = [];
      if (this.formMode === 'edit-resource' && this.activeResourceId !== null) {
        const activeResource = this.resources.find(candidate => candidate.id === this.activeResourceId);
        if (!activeResource) throw new Error('Resource not found in local state');
        savedResourceId = this.activeResourceId;
        savedEndpoints = await updateResource(savedResourceId, activeResource.endpoints, resource, endpointPayloads);
      } else {
        const createdResource = await createResource(this.tenantId, resource, endpointPayloads);
        savedResourceId = createdResource.id;
        savedEndpoints = createdResource.endpoints;
      }

      if (savedResourceId !== null) {
        this.upsertResource(this.buildSavedResource(savedResourceId, resource, savedEndpoints));
        this.expandedResources = new Set([...this.expandedResources, savedResourceId]);
      }

      this.resetForms();
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Failed to save resource';
    } finally { this.saving = false; }
  }

  private get isResourceUriInvalid() {
    const resource = this.resourceUri.trim();
    return resource.length > 0 && !validateResourceUri(resource).valid;
  }

  private isEndpointPathInvalid(path: string) {
    return path.trim().length > 0 && !path.startsWith('/');
  }

  private async removeEndpoint(draftId: string, label: string) {
    const confirmed = await confirmDestructiveAction({
      title: 'Remove endpoint',
      messagePrefix: 'Remove endpoint ',
      messageSubject: label,
      messageSuffix: ' from this resource?',
      confirmLabel: 'Remove endpoint',
    });
    if (!confirmed) return;
    this.error = '';
    this.endpointDrafts = this.endpointDrafts.filter(endpoint => endpoint.draftId !== draftId);
    this.expandedEditableEndpoints = new Set([...this.expandedEditableEndpoints].filter(id => id !== draftId));
  }

  private async removeResource(resource: Resource) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete resource',
      messagePrefix: 'Delete resource ',
      messageSubject: formatResourceLabel(resource.resource),
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;

    this.error = '';
    try {
      await deleteResource(resource.id);
      this.resources = this.resources.filter(candidate => candidate.id !== resource.id);
      this.expandedResources = new Set([...this.expandedResources].filter(id => id !== resource.id));
      this.expandedEndpoints = new Set([...this.expandedEndpoints].filter(id => !resource.endpoints.some(endpoint => endpoint.id === id)));
      if (this.activeResourceId === resource.id) this.resetForms();
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Failed to delete resource';
    }
  }

  private get filteredResources(): Resource[] {
    const query = this.searchQuery.trim().toLowerCase();
    if (!query) return this.resources;

    return this.resources.filter(resource => {
      const label = formatResourceLabel(resource.resource).toLowerCase();
      const original = resource.resource.toLowerCase();
      return label.includes(query) || original.includes(query) || String(resource.id).includes(query);
    });
  }

  private createEndpointDraftId() {
    this.nextEndpointDraftId += 1;
    return `endpoint-draft-${this.nextEndpointDraftId}`;
  }

  private syncExpandedEntityState(resources: Resource[] = this.resources) {
    const validResourceIds = new Set(resources.map(resource => resource.id));
    const validEndpointIds = new Set(resources.flatMap(resource => resource.endpoints.map(endpoint => endpoint.id)));
    this.expandedResources = new Set([...this.expandedResources].filter(id => validResourceIds.has(id)));
    this.expandedEndpoints = new Set([...this.expandedEndpoints].filter(id => validEndpointIds.has(id)));
  }

  private buildSavedResource(id: number, resource: string, endpoints: PersistedResourceEndpointPayload[]): Resource {
    return {
      id,
      resource,
      endpoints: endpoints.map(endpoint => ({
        id: endpoint.id,
        method: endpoint.method,
        path: endpoint.path,
        fetchUserInfo: endpoint.fetchUserInfo,
        allowRules: cloneAclGroup(endpoint.allowRules),
        denyRules: cloneAclGroup(endpoint.denyRules),
        injectHeaders: { ...endpoint.injectHeaders },
      })),
    };
  }

  private upsertResource(resource: Resource) {
    const existingIndex = this.resources.findIndex(candidate => candidate.id === resource.id);
    this.resources = existingIndex === -1
      ? [resource, ...this.resources]
      : this.resources.map(candidate => candidate.id === resource.id ? resource : candidate);
    this.syncExpandedEntityState();
  }

  private toEditableEndpoint(endpoint: ResourceEndpoint): EditableResourceEndpoint {
    return {
      id: endpoint.id,
      draftId: `endpoint-${endpoint.id}`,
      method: endpoint.method,
      path: endpoint.path,
      fetchUserInfo: endpoint.fetchUserInfo,
      allowRules: cloneAclGroup(endpoint.allowRules),
      denyRules: cloneAclGroup(endpoint.denyRules),
      injectHeaders: { ...endpoint.injectHeaders },
    };
  }

  private toEndpointPayload(endpoint: EditableResourceEndpoint) {
    const normalized = this.withDerivedFetchUserInfo(endpoint);

    return {
      ...(normalized.id !== null ? { id: normalized.id } : {}),
      method: normalized.method,
      path: normalized.path,
      fetchUserInfo: normalized.fetchUserInfo,
      allowRules: cloneAclGroup(normalized.allowRules),
      denyRules: cloneAclGroup(normalized.denyRules),
      injectHeaders: { ...normalized.injectHeaders },
    };
  }

  private unwrapReference(value: string) {
    const trimmed = value?.trim() || '';
    const match = trimmed.match(/^\$\{(.+)\}$/);
    return match ? match[1] : trimmed;
  }

  private getReferenceSource(value: string): ClaimSource {
    return this.unwrapReference(value).startsWith('userinfo.') ? 'userinfo' : 'access_token';
  }

  private getReferencePath(value: string): string {
    const normalized = this.unwrapReference(value);

    if (normalized.startsWith('userinfo.')) return normalized.slice('userinfo.'.length);
    if (normalized.startsWith('jwt.')) return normalized.slice('jwt.'.length);

    return normalized;
  }

  private serializeReference(source: ClaimSource, path: string): string {
    const normalized = this.unwrapReference(path)
      .replace(/^userinfo\./, '')
      .replace(/^jwt\./, '');

    if (!normalized) return '';

    if (source === 'userinfo') return `userinfo.${normalized}`;

    if (
      normalized.startsWith('authorization_details.') ||
      normalized.startsWith('request.') ||
      normalized.startsWith('resource.')
    ) {
      return normalized;
    }

    return `jwt.${normalized}`;
  }

  private collectRules(tree: AclRuleTree): Rule[] {
    if (tree.kind === 'rule') {
      return [tree.rule];
    }

    return tree.children.flatMap(child => this.collectRules(child));
  }

  private getAclKey(type: AclSectionType) {
    return type === 'allow' ? 'allowRules' : 'denyRules';
  }

  private getRuleGroups(group: AclRuleGroup): AclRuleGroup[] {
    return group.children.filter((child): child is AclRuleGroup => child.kind === 'all');
  }

  private shouldFetchUserInfo(endpoint: Pick<ResourceEndpointDraft, 'allowRules' | 'denyRules' | 'injectHeaders'>) {
    const hasUserInfoRule = [...this.collectRules(endpoint.allowRules), ...this.collectRules(endpoint.denyRules)]
      .some(rule => this.getReferenceSource(rule.subject) === 'userinfo');

    const hasUserInfoHeader = Object.values(endpoint.injectHeaders)
      .some(value => this.getReferenceSource(value) === 'userinfo');

    return hasUserInfoRule || hasUserInfoHeader;
  }

  private withDerivedFetchUserInfo<T extends ResourceEndpointDraft | EditableResourceEndpoint>(endpoint: T): T {
    return {
      ...endpoint,
      fetchUserInfo: this.shouldFetchUserInfo(endpoint),
    };
  }

  private stringifyRuleValue(value: Rule['value']) {
    return Array.isArray(value) ? JSON.stringify(value) : String(value ?? '');
  }

  private parseRuleValue(value: string): Rule['value'] {
    const trimmed = value.trim();

    if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
      try {
        const parsed = JSON.parse(trimmed);
        if (Array.isArray(parsed)) return parsed.map(item => String(item));
      } catch {
        return value;
      }
    }

    if (/^-?[0-9]+(?:\.[0-9]+)?$/.test(trimmed)) {
      return Number(trimmed);
    }

    return value;
  }

  private toggleEditableEndpoint(draftId: string) {
    if (this.expandedEditableEndpoints.has(draftId)) {
      this.expandedEditableEndpoints.delete(draftId);
    } else {
      this.expandedEditableEndpoints.add(draftId);
    }
    this.requestUpdate();
  }

  private updateEndpointDraft(draftId: string, updater: (endpoint: EditableResourceEndpoint) => EditableResourceEndpoint) {
    this.endpointDrafts = this.endpointDrafts.map(endpoint => (
      endpoint.draftId === draftId ? this.withDerivedFetchUserInfo(updater(endpoint)) : endpoint
    ));
  }

  private updateEndpointField(draftId: string, field: 'method' | 'path', value: string) {
    this.updateEndpointDraft(draftId, endpoint => ({ ...endpoint, [field]: value }));
  }

  private updateAclGroup(
    draftId: string,
    type: AclSectionType,
    updater: (group: AclRuleGroup) => AclRuleGroup,
  ) {
    const key = this.getAclKey(type);

    this.updateEndpointDraft(draftId, endpoint => ({
      ...endpoint,
      [key]: updater(cloneAclGroup(endpoint[key])),
    }));
  }

  private addRuleGroup(draftId: string, type: AclSectionType) {
    this.updateAclGroup(draftId, type, group => ({
      ...group,
      children: [...group.children, createAndGroup()],
    }));
  }

  private removeRuleGroup(draftId: string, type: AclSectionType, groupIndex: number) {
    this.updateAclGroup(draftId, type, group => ({
      ...group,
      children: group.children.filter((_, index) => index !== groupIndex),
    }));
  }

  private updateRule(
    draftId: string,
    type: AclSectionType,
    groupIndex: number,
    ruleIndex: number,
    updater: (rule: Rule) => Rule,
  ) {
    this.updateAclGroup(draftId, type, root => {
      const nextRoot = cloneAclGroup(root);
      const group = nextRoot.children[groupIndex];

      if (!group || group.kind !== 'all') {
        return nextRoot;
      }

      const node = group.children[ruleIndex];
      if (!node || node.kind !== 'rule') {
        return nextRoot;
      }

      group.children[ruleIndex] = { kind: 'rule', rule: updater(node.rule) };
      return nextRoot;
    });
  }

  private addRule(draftId: string, type: AclSectionType, groupIndex: number) {
    this.updateAclGroup(draftId, type, root => {
      const nextRoot = cloneAclGroup(root);
      const group = nextRoot.children[groupIndex];

      if (!group || group.kind !== 'all') {
        return nextRoot;
      }

      group.children = [...group.children, createRuleNode()];
      return nextRoot;
    });
  }

  private removeRule(draftId: string, type: AclSectionType, groupIndex: number, ruleIndex: number) {
    this.updateAclGroup(draftId, type, root => {
      const nextRoot = cloneAclGroup(root);
      const group = nextRoot.children[groupIndex];

      if (!group || group.kind !== 'all') {
        return nextRoot;
      }

      group.children = group.children.filter((_, index) => index !== ruleIndex);
      if (group.children.length === 0) {
        nextRoot.children = nextRoot.children.filter((_, index) => index !== groupIndex);
      }

      return nextRoot;
    });
  }

  private updateRuleSource(draftId: string, type: AclSectionType, groupIndex: number, ruleIndex: number, source: ClaimSource) {
    this.updateRule(draftId, type, groupIndex, ruleIndex, rule => ({
      ...rule,
      subject: this.serializeReference(source, this.getReferencePath(rule.subject)),
    }));
  }

  private updateRulePath(draftId: string, type: AclSectionType, groupIndex: number, ruleIndex: number, path: string) {
    this.updateRule(draftId, type, groupIndex, ruleIndex, rule => ({
      ...rule,
      subject: this.serializeReference(this.getReferenceSource(rule.subject), path),
    }));
  }

  private updateRuleOperator(draftId: string, type: AclSectionType, groupIndex: number, ruleIndex: number, operator: Rule['operator']) {
    this.updateRule(draftId, type, groupIndex, ruleIndex, rule => ({ ...rule, operator }));
  }

  private updateRuleValue(draftId: string, type: AclSectionType, groupIndex: number, ruleIndex: number, value: string) {
    this.updateRule(draftId, type, groupIndex, ruleIndex, rule => ({ ...rule, value: this.parseRuleValue(value) }));
  }

  private updateRulePattern(draftId: string, type: AclSectionType, groupIndex: number, ruleIndex: number, pattern: Rule['pattern'] | '') {
    this.updateRule(draftId, type, groupIndex, ruleIndex, rule => ({
      ...rule,
      pattern: pattern ? pattern : undefined,
    }));
  }

  private toggleInfo(key: string) {
    this.openInfoKey = this.openInfoKey === key ? null : key;
  }

  private renderOptionInfo(key: string, title: string, description: string, ariaLabel: string) {
    return html`
      <div class=${`option-info ${this.openInfoKey === key ? 'option-info-open' : ''}`} @click=${(event: Event) => event.stopPropagation()}>
        <button
          type="button"
          class="option-info-button"
          aria-label=${ariaLabel}
          aria-expanded=${this.openInfoKey === key ? 'true' : 'false'}
          @click=${() => this.toggleInfo(key)}
        >i</button>
        <div class="option-tooltip" role="tooltip">
          <div class="option-tooltip-title">${title}</div>
          <div class="option-tooltip-copy">${description}</div>
        </div>
      </div>
    `;
  }

  private addHeader(draftId: string) {
    this.updateEndpointDraft(draftId, endpoint => {
      const base = 'X-New-Header';
      let candidate = base;
      let index = 1;

      while (candidate in endpoint.injectHeaders) {
        candidate = `${base}-${index}`;
        index += 1;
      }

      return {
        ...endpoint,
        injectHeaders: {
          ...endpoint.injectHeaders,
          [candidate]: '',
        },
      };
    });
  }

  private renameHeader(draftId: string, currentName: string, nextName: string) {
    const normalized = nextName.trim();

    if (!normalized || normalized === currentName) {
      if (!normalized) this.error = 'Header name cannot be empty';
      return;
    }

    this.updateEndpointDraft(draftId, endpoint => {
      if (normalized in endpoint.injectHeaders) {
        this.error = `Header ${normalized} already exists on this endpoint`;
        return endpoint;
      }

      const renamedEntries = Object.entries(endpoint.injectHeaders).map(([name, value]) => (
        name === currentName ? [normalized, value] : [name, value]
      ));

      this.error = '';

      return {
        ...endpoint,
        injectHeaders: Object.fromEntries(renamedEntries),
      };
    });
  }

  private removeHeader(draftId: string, name: string) {
    this.updateEndpointDraft(draftId, endpoint => {
      const headers = { ...endpoint.injectHeaders };
      delete headers[name];
      return { ...endpoint, injectHeaders: headers };
    });
  }

  private updateHeader(draftId: string, name: string, value: string) {
    this.updateEndpointDraft(draftId, endpoint => ({
      ...endpoint,
      injectHeaders: {
        ...endpoint.injectHeaders,
        [name]: value,
      },
    }));
  }

  private updateHeaderSource(draftId: string, name: string, source: ClaimSource) {
    const endpoint = this.endpointDrafts.find(candidate => candidate.draftId === draftId);
    const currentValue = endpoint?.injectHeaders?.[name] || '';
    this.updateHeader(draftId, name, this.serializeReference(source, this.getReferencePath(currentValue)));
  }

  private updateHeaderPath(draftId: string, name: string, path: string) {
    const endpoint = this.endpointDrafts.find(candidate => candidate.draftId === draftId);
    const currentValue = endpoint?.injectHeaders?.[name] || '';
    this.updateHeader(draftId, name, this.serializeReference(this.getReferenceSource(currentValue), path));
  }

  private toggleExpand(resourceId: number) {
    if (this.expandedResources.has(resourceId)) {
      this.expandedResources.delete(resourceId);
    } else {
      this.expandedResources.add(resourceId);
    }
    this.requestUpdate();
  }

  private handleCardClick(resourceId: number) {
    this.toggleExpand(resourceId);
  }

  private toggleEndpointExpand(endpointId: ResourceEndpointId) {
    if (this.expandedEndpoints.has(endpointId)) {
      this.expandedEndpoints.delete(endpointId);
    } else {
      this.expandedEndpoints.add(endpointId);
    }
    this.requestUpdate();
  }

  private formatRuleValue(value: Rule['value']) {
    if (Array.isArray(value)) {
      return `[${value.join(', ')}]`;
    }

    return String(value);
  }

  private getRuleSource(subject: string) {
    if (subject.startsWith('userinfo.')) {
      return {
        badge: 'userinfo',
        field: subject.slice('userinfo.'.length),
      };
    }

    if (subject.startsWith('jwt.')) {
      return {
        badge: 'access token',
        field: subject.slice('jwt.'.length),
      };
    }

    return {
      badge: 'access token',
      field: subject,
    };
  }

  private formatRuleOperator(operator: Rule['operator']) {
    switch (operator) {
      case 'eq':
        return '=';
      case 'ne':
        return '≠';
      case 'gt':
        return '>';
      case 'gte':
        return '≥';
      case 'lt':
        return '<';
      case 'lte':
        return '≤';
      default:
        return operator;
    }
  }

  private renderLogicSeparator(value: 'and' | 'or') {
    return html`<div class="rule-logic-separator">${value}</div>`;
  }

  private renderRuleItem(rule: Rule) {
    const source = this.getRuleSource(rule.subject);

    return html`
      <div class="rule-item">
        <div class="rule-expression">
          <span class="source-badge">${source.badge}</span>
          <span class="rule-field">${source.field}</span>
          <span class="rule-operator">${this.formatRuleOperator(rule.operator)}</span>
          <span class="rule-value">${this.formatRuleValue(rule.value)}</span>
        </div>
        ${rule.pattern ? html`<div class="rule-meta">Pattern: ${rule.pattern}</div>` : ''}
      </div>
    `;
  }

  private renderRuleSection(title: string, group: AclRuleGroup) {
    const groups = this.getRuleGroups(group);

    return html`
      <div class="endpoint-editor-section">
        <div class="endpoint-detail-label">${title}</div>
        ${groups.length === 0 ? html`<div class="endpoint-empty">None</div>` : html`
          <div class="rule-group-list">
            ${groups.map((andGroup, groupIndex) => {
              const rules = andGroup.children.filter((child): child is AclRuleNode => child.kind === 'rule');

              return html`
                ${groupIndex > 0 ? this.renderLogicSeparator('or') : ''}
                <div class="rule-group-view">
                  ${rules.map((ruleNode, ruleIndex) => html`
                    ${ruleIndex > 0 ? this.renderLogicSeparator('and') : ''}
                    ${this.renderRuleItem(ruleNode.rule)}
                  `)}
                </div>
              `;
            })}
          </div>
        `}
      </div>
    `;
  }

  private renderHeadersSection(headers: Record<string, string>) {
    const entries = Object.entries(headers);

    return html`
      <div class="endpoint-editor-section">
        <div class="endpoint-detail-label">Inject headers</div>
        ${entries.length === 0 ? html`<div class="endpoint-empty">None</div>` : html`
          <div class="header-list">
            ${entries.map(([key, value]) => {
              const source = this.getRuleSource(value);

              return html`
                <div class="header-item">
                  <div class="header-item-part">
                    <div class="header-item-key">${key}</div>
                  </div>
                  <div class="header-item-part header-item-part-expression">
                    <span class="source-badge">${source.badge}</span>
                    <div class="header-item-value">${source.field}</div>
                  </div>
                </div>
              `;
            })}
          </div>
        `}
      </div>
    `;
  }

  private renderEndpointDetails(endpoint: ResourceEndpoint) {
    return html`
      <div class="endpoint-card-details">
        <div class="fetch-row">
          <span class="fetch-row-label">Fetch Userinfo</span>
          <span class=${`fetch-indicator ${endpoint.fetchUserInfo ? 'fetch-enabled' : 'fetch-disabled'}`}>
            <span class="fetch-indicator-box">✓</span>
          </span>
        </div>
        <div class="endpoint-detail-grid">
          ${this.renderRuleSection('Match rules', endpoint.allowRules)}
          ${this.renderRuleSection('Deny rules', endpoint.denyRules)}
          ${this.renderHeadersSection(endpoint.injectHeaders)}
        </div>
      </div>
    `;
  }

  private renderResourceEndpoints(resource: Resource) {
    if (resource.endpoints.length === 0) {
      return html`<div class="status">No endpoints yet.</div>`;
    }

    return html`
      <div class="endpoint-list" @click=${(event: Event) => event.stopPropagation()}>
        ${resource.endpoints.map(endpoint => {
          const isExpanded = this.expandedEndpoints.has(endpoint.id);

          return html`
            <div class="endpoint-card" @click=${(event: Event) => { event.stopPropagation(); this.toggleEndpointExpand(endpoint.id); }}>
              <div class="endpoint-card-header">
                <div class="endpoint-main">
                  <span class=${`method-badge method-${endpoint.method.toLowerCase()}`}>${endpoint.method}</span>
                  <span class="endpoint-path">${endpoint.path}</span>
                </div>
                <span class="endpoint-card-chevron">${isExpanded ? '▼' : '▶'}</span>
              </div>
              ${isExpanded ? this.renderEndpointDetails(endpoint) : ''}
            </div>
          `;
        })}
      </div>
    `;
  }

  private renderRuleEditorItem(
    draftId: string,
    type: AclSectionType,
    groupIndex: number,
    ruleIndex: number,
    rule: Rule,
  ) {
    return html`
      <div class="rule-editor-item">
        <div class="rule-editor-main">
          <div class="form-group">
            <label class="form-label">Source</label>
            <select
              class="form-select compact-input"
              .value=${this.getReferenceSource(rule.subject)}
              @change=${(event: Event) => this.updateRuleSource(
                draftId,
                type,
                groupIndex,
                ruleIndex,
                (event.target as HTMLSelectElement).value as ClaimSource,
              )}
            >
              <option value="access_token">access token</option>
              <option value="userinfo">userinfo</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">Path</label>
            <input
              class="form-input compact-input"
              type="text"
              .value=${this.getReferencePath(rule.subject)}
              @input=${(event: Event) => this.updateRulePath(draftId, type, groupIndex, ruleIndex, (event.target as HTMLInputElement).value)}
              placeholder="sub"
            />
          </div>
          <div class="form-group">
            <label class="form-label">Operator</label>
            <select
              class="form-select compact-input"
              .value=${rule.operator}
              @change=${(event: Event) => this.updateRuleOperator(
                draftId,
                type,
                groupIndex,
                ruleIndex,
                (event.target as HTMLSelectElement).value as Rule['operator'],
              )}
            >
              ${operatorOptions.map(option => html`<option value=${option.value}>${option.label}</option>`)}
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">Value</label>
            <input
              class="form-input compact-input"
              type="text"
              .value=${this.stringifyRuleValue(rule.value)}
              @input=${(event: Event) => this.updateRuleValue(draftId, type, groupIndex, ruleIndex, (event.target as HTMLInputElement).value)}
              placeholder='read, 2, or ["engineering"]'
            />
          </div>
          <button
            type="button"
            class="icon-action danger editor-remove"
            ?disabled=${this.saving}
            @click=${() => this.removeRule(draftId, type, groupIndex, ruleIndex)}
            title="Remove rule"
            aria-label="Remove rule"
          >✕</button>
        </div>
        ${rule.operator === 'matches' ? html`
          <div class="rule-editor-pattern">
            <div class="form-group">
              <label class="form-label">Pattern</label>
              <select
                class="form-select compact-input"
                .value=${rule.pattern || 'glob'}
                @change=${(event: Event) => this.updateRulePattern(
                  draftId,
                  type,
                  groupIndex,
                  ruleIndex,
                  (event.target as HTMLSelectElement).value as Rule['pattern'] | '',
                )}
              >
                <option value="glob">glob</option>
                <option value="regex">regex</option>
              </select>
            </div>
          </div>
        ` : ''}
      </div>
    `;
  }

  private renderRulesEditor(draftId: string, type: AclSectionType, title: string, group: AclRuleGroup) {
    const description = type === 'allow'
      ? 'Requests must satisfy these conditions to be authorized.'
      : 'Use deny rules to block request authorization even if match rules are satisfied.';
    const groups = this.getRuleGroups(group);

    return html`
      <div class="endpoint-editor-section">
        <div class="editor-section-header">
          <div class="editor-section-title-row">
            <h3 class="editor-section-title">${title}</h3>
            ${this.renderOptionInfo(
              `${draftId}-${type}-info`,
              title,
              description,
              `${title} info`,
            )}
          </div>
          <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.saving} @click=${() => this.addRuleGroup(draftId, type)}>
            Add OR group
          </button>
        </div>
        ${groups.length === 0 ? html`<div class="endpoint-empty">No rules yet.</div>` : html`
          <div class="rule-group-editor-list">
            ${groups.map((andGroup, groupIndex) => {
              const rules = andGroup.children.filter((child): child is AclRuleNode => child.kind === 'rule');

              return html`
                ${groupIndex > 0 ? this.renderLogicSeparator('or') : ''}
                <div class="rule-group-editor">
                  <div class="rule-editor-list">
                    ${rules.map((ruleNode, ruleIndex) => html`
                      ${ruleIndex > 0 ? this.renderLogicSeparator('and') : ''}
                      ${this.renderRuleEditorItem(draftId, type, groupIndex, ruleIndex, ruleNode.rule)}
                    `)}
                  </div>
                  <div class="rule-group-actions">
                    <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.saving} @click=${() => this.addRule(draftId, type, groupIndex)}>
                      Add AND rule
                    </button>
                    <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.saving} @click=${() => this.removeRuleGroup(draftId, type, groupIndex)}>
                      Remove group
                    </button>
                  </div>
                </div>
              `;
            })}
          </div>
        `}
      </div>
    `;
  }

  private renderHeadersEditor(draftId: string, headers: Record<string, string>) {
    const entries = Object.entries(headers);

    return html`
      <div class="endpoint-editor-section">
        <div class="editor-section-header">
          <div class="editor-section-title-row">
            <h3 class="editor-section-title">Inject headers</h3>
            ${this.renderOptionInfo(
              `${draftId}-headers-info`,
              'Inject headers',
              'Attach claim-derived values as request headers before the upstream call.',
              'Inject headers info',
            )}
          </div>
          <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.saving} @click=${() => this.addHeader(draftId)}>
            Add header
          </button>
        </div>
        ${entries.length === 0 ? html`<div class="endpoint-empty">No headers yet.</div>` : html`
          <div class="header-editor-list">
            ${entries.map(([name, value]) => html`
              <div class="header-editor-item">
                <div class="form-group">
                  <label class="form-label">Header name</label>
                  <input
                    class="form-input compact-input"
                    type="text"
                    .value=${name}
                    @change=${(event: Event) => this.renameHeader(draftId, name, (event.target as HTMLInputElement).value)}
                    placeholder="X-User-Id"
                  />
                </div>
                <div class="form-group">
                  <label class="form-label">Source</label>
                  <select
                    class="form-select compact-input"
                    .value=${this.getReferenceSource(value)}
                    @change=${(event: Event) => this.updateHeaderSource(
                      draftId,
                      name,
                      (event.target as HTMLSelectElement).value as ClaimSource,
                    )}
                  >
                    <option value="access_token">access token</option>
                    <option value="userinfo">userinfo</option>
                  </select>
                </div>
                <div class="form-group">
                  <label class="form-label">Value</label>
                  <input
                    class="form-input compact-input"
                    type="text"
                    .value=${this.getReferencePath(value)}
                    @input=${(event: Event) => this.updateHeaderPath(draftId, name, (event.target as HTMLInputElement).value)}
                    placeholder="sub"
                  />
                </div>
                <button
                  type="button"
                  class="icon-action danger editor-remove"
                  ?disabled=${this.saving}
                  @click=${() => this.removeHeader(draftId, name)}
                  title="Remove header"
                  aria-label=${`Remove header ${name}`}
                >✕</button>
              </div>
            `)}
          </div>
        `}
      </div>
    `;
  }

  private renderEditableEndpoint(endpoint: EditableResourceEndpoint) {
    const isExpanded = this.expandedEditableEndpoints.has(endpoint.draftId);

    return html`
      <div class="endpoint-editor">
        <div class="endpoint-editor-header">
          <button type="button" class="endpoint-editor-trigger" @click=${() => this.toggleEditableEndpoint(endpoint.draftId)}>
            <span class="endpoint-editor-chevron">${isExpanded ? '▾' : '▸'}</span>
            <div class="endpoint-editor-heading">
              <div class="endpoint-main">
                <span class=${`method-badge method-${endpoint.method.toLowerCase()}`}>${endpoint.method}</span>
                <span class="endpoint-path">${endpoint.path || 'New endpoint'}</span>
              </div>
            </div>
          </button>
          <div class="endpoint-actions">
            <button
              type="button"
              class="icon-action danger"
              ?disabled=${this.saving}
              @click=${() => this.removeEndpoint(endpoint.draftId, endpointLabel(endpoint))}
              title="Remove endpoint"
              aria-label=${`Remove endpoint ${endpointLabel(endpoint)}`}
            >✕</button>
          </div>
        </div>
        ${isExpanded ? html`
          <div class="endpoint-editor-body">
            <div class="endpoint-editor-grid">
              <div class="form-group">
                <label class="form-label">Method</label>
                <select
                  class="form-select compact-input"
                  .value=${endpoint.method}
                  @change=${(event: Event) => this.updateEndpointField(endpoint.draftId, 'method', (event.target as HTMLSelectElement).value)}
                >
                  ${endpointMethods.map(method => html`<option value=${method}>${method}</option>`)}
                </select>
              </div>
              <div class="form-group">
                <label class="form-label">Relative path</label>
                <input
                  class="form-input compact-input ${this.isEndpointPathInvalid(endpoint.path) ? 'input-error' : ''}"
                  type="text"
                  .value=${endpoint.path}
                  @input=${(event: Event) => this.updateEndpointField(endpoint.draftId, 'path', (event.target as HTMLInputElement).value)}
                  placeholder="/users"
                  required
                />
              </div>
            </div>
            ${this.renderRulesEditor(endpoint.draftId, 'allow', 'Match rules', endpoint.allowRules)}
            ${this.renderRulesEditor(endpoint.draftId, 'deny', 'Deny rules', endpoint.denyRules)}
            ${this.renderHeadersEditor(endpoint.draftId, endpoint.injectHeaders)}
          </div>
        ` : ''}
      </div>
    `;
  }

  private renderForm() {
    if (this.formMode === 'none') return '';

    const isEditResource = this.formMode === 'edit-resource';
    const title = isEditResource ? 'Edit Resource' : 'Create Resource';
    const submitLabel = isEditResource ? 'Save Resource' : 'Create Resource';

    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">${title}</h1>
          ${isEditResource ? html`<div class="entity-id-meta">${this.resourceUri || '—'}</div>` : ''}
        </div>
      </div>
      <div class="card">
        <form @submit=${this.saveResource}>
          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">Absolute resource URI</label>
                  <input class="form-input compact-input ${this.isResourceUriInvalid ? 'input-error' : ''}" type="url" aria-label="Absolute resource URI" .value=${this.resourceUri} @input=${(e: Event) => this.resourceUri = (e.target as HTMLInputElement).value} placeholder="https://api.example.com" ?disabled=${this.saving} required />
            </div>
          </div>
          <div class="section-header">
            <h2 class="section-title">Endpoints</h2>
            <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.saving} @click=${() => this.startCreateEndpoint()}>Add endpoint</button>
          </div>
          ${this.endpointDrafts.length === 0 ? html`<div class="status">No endpoints yet.</div>` : html`
            <div class="endpoint-list">
              ${this.endpointDrafts.map(endpoint => this.renderEditableEndpoint(endpoint))}
            </div>
          `}
          <div class="form-actions">
            <button type="button" class="btn btn-secondary" @click=${() => this.resetForms()}>Cancel</button>
            <button class="btn btn-primary" ?disabled=${this.saving}>${this.saving ? 'Saving…' : submitLabel}</button>
          </div>
        </form>
      </div>
    `;
  }

  render() {
    if (!this.tenantId) return html`<div class="card"><div class="card-body status">Select a tenant to manage resources.</div></div>`;

    if (this.formMode !== 'none') {
      return html`
        ${this.error ? html`<div class="status error">${this.error}</div>` : ''}
        ${this.renderForm()}
      `;
    }

    return html`
      <content-header title="Resources">
        ${this.resources.length > 0 ? html`
          <button slot="actions" class="btn btn-primary" @click=${() => this.openCreateResourceForm()}>+ Create Resource</button>
        ` : ''}
      </content-header>
      ${this.error ? html`<div class="status error">${this.error}</div>` : ''}
      ${this.loading ? html`<div class="status">Loading resources…</div>` : ''}
      ${!this.loading && this.resources.length > 0 ? html`<div class="search-bar"><input class="form-input" type="search" aria-label="Search resources" .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} placeholder="Search resources" /></div>` : ''}
      ${!this.loading && this.resources.length === 0 && this.formMode === 'none' ? html`
        <div class="card">
          <div class="empty-state">
            <h3>No resources yet</h3>
            <p>Create your first resource to get started</p>
            <button class="btn btn-primary" @click=${() => this.openCreateResourceForm()} style="margin-top: 1rem;">
              + Create Resource
            </button>
          </div>
        </div>
      ` : ''}
      ${!this.loading && this.resources.length > 0 && this.filteredResources.length === 0 ? html`
        <div class="card">
          <div class="empty-state">
            <h3>No resources match your search</h3>
          </div>
        </div>
      ` : ''}
      <div class="stack">${this.filteredResources.map(resource => {
        const isExpanded = this.expandedResources.has(resource.id);
        return html`<div class="card resource-shell" @click=${() => this.handleCardClick(resource.id)}><div class="card-body resource-card">
        <div class="resource-header">
          <div class="resource-label-card"><div class="resource-label">${formatResourceLabel(resource.resource)}</div></div>
          <div class="resource-actions" @click=${(e: Event) => e.stopPropagation()}>
            <button class="icon-action" @click=${() => this.openEditResourceForm(resource)} title="Edit resource" aria-label=${`Edit resource ${formatResourceLabel(resource.resource)}`}>✎</button>
            <button class="icon-action danger" @click=${() => this.removeResource(resource)} title="Delete resource" aria-label=${`Delete resource ${formatResourceLabel(resource.resource)}`}>✕</button>
          </div>
        </div>
        ${isExpanded ? this.renderResourceEndpoints(resource) : ''}
      </div></div>`;
      })}</div>
    `;
  }
}

declare global { interface HTMLElementTagNameMap { 'versola-resources-list': VersolaResourcesList; } }
