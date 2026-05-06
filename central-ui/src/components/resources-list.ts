import { LitElement, css, html, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { buttonStyles, cardStyles, formStyles, methodBadgeStyles, tableStyles } from '../styles/components';
import { celHighlightStyles } from '../styles/cel-highlight';
import { theme } from '../styles/theme';
import type { InjectRule, InjectTarget, Resource, ResourceEndpoint, ResourceEndpointId } from '../types';
import { createResource, deleteResource, fetchResources, updateResource } from '../utils/central-api';
import { renderHighlightedCel } from '../utils/cel-highlight';
import { validateCel } from '../utils/cel-validator';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { formatResourceLabel } from '../utils/helpers';
import { validateResourceUri } from '../utils/validators';
import './cel-editor';
import './content-header';

type ResourceEndpointDraft = {
  method: string;
  path: string;
  fetchUserInfo: boolean;
  allow: string;
  inject: InjectRule[];
};

type EditableResourceEndpoint = ResourceEndpointDraft & {
  id: ResourceEndpoint['id'] | null;
  draftId: string;
};

type SaveResourceEndpointPayload = Omit<ResourceEndpointDraft, 'allow'> & {
  id?: ResourceEndpoint['id'];
  allow: string | null;
};

type PersistedResourceEndpointPayload = SaveResourceEndpointPayload & {
  id: ResourceEndpoint['id'];
};

type ResourceFormMode = 'none' | 'create-resource' | 'edit-resource';

const endpointMethods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;
const injectTargets: InjectTarget[] = ['header', 'query', 'body'];

function endpointLabel(endpoint: Pick<ResourceEndpointDraft, 'method' | 'path'>) {
  return `${endpoint.method} ${endpoint.path}`;
}

function cloneInject(rules: InjectRule[]): InjectRule[] {
  return rules.map(rule => ({ ...rule }));
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
  @state() private resourceAlias = '';
  @state() private endpointDrafts: EditableResourceEndpoint[] = [];
  private nextEndpointDraftId = 0;
  private handleDocumentClick = () => {
    this.openInfoKey = null;
  };

  static styles = [theme, buttonStyles, cardStyles, formStyles, methodBadgeStyles, tableStyles, celHighlightStyles, css`
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
    .resource-label-card { max-width:min(32rem, 100%); padding:.15rem 0; display:flex; align-items:center; gap:.625rem; flex-wrap:wrap; }
    .resource-label { color:var(--accent); font-size:1rem; font-weight:600; line-height:1.35; word-break:break-all; }
    .resource-alias-badge { display:inline-flex; align-items:center; min-height:1.5rem; padding:0 .6rem; border-radius:999px; font-size:.75rem; font-weight:600; letter-spacing:.01em; background:rgba(88, 166, 255, .16); color:#7cc4ff; border:1px solid rgba(88, 166, 255, .28); flex:none; }
    .input-with-info { display:flex; align-items:center; gap:.5rem; }
    .input-with-info > .form-input { flex:1; min-width:0; }
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
      font-size:0.8125rem;
      font-weight:600;
    }
    .option-tooltip-copy { color:var(--text-primary); font-size:0.75rem; line-height:1.45; }
    .option-tooltip-copy p { margin:0 0 .5rem; }
    .option-tooltip-copy p:last-child { margin-bottom:0; }
    .option-tooltip-section-title { margin:.625rem 0 .25rem; color:var(--text-secondary); font-size:.7rem; font-weight:600; text-transform:uppercase; letter-spacing:.04em; }
    .option-tooltip-section-title:first-child { margin-top:0; }
    .option-tooltip-list { margin:0; padding-left:1rem; display:grid; gap:.2rem; }
    .option-tooltip-code { font-family:var(--font-mono, monospace); font-size:.72rem; color:var(--accent); background:rgba(88, 166, 255, .1); padding:.05rem .3rem; border-radius:.25rem; }
    .option-tooltip-pre { margin:.25rem 0 0; padding:.5rem .625rem; border-radius:var(--radius-sm); background:rgba(0,0,0,.35); border:1px solid rgba(139, 148, 158, .18); font-family:var(--font-mono, monospace); font-size:.72rem; color:var(--text-primary); white-space:pre-wrap; word-break:break-word; }
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
    this.formMode = 'none'; this.activeResourceId = null; this.resourceUri = ''; this.resourceAlias = '';
    this.endpointDrafts = [];
    this.expandedEditableEndpoints = new Set();
  }

  private openCreateResourceForm() {
    this.error = '';
    this.formMode = 'create-resource';
    this.activeResourceId = null;
    this.resourceUri = 'https://';
    this.resourceAlias = '';
    this.endpointDrafts = [];
    this.expandedEditableEndpoints = new Set();
  }

  private openEditResourceForm(resource: Resource) {
    this.error = '';
    this.formMode = 'edit-resource';
    this.activeResourceId = resource.id;
    this.resourceUri = resource.resource;
    this.resourceAlias = resource.alias;
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
      allow: '',
      inject: [],
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
    const alias = this.resourceAlias.trim();
    if (alias.length === 0) { this.error = 'Alias is required'; return; }
    const validation = validateResourceUri(resource);
    if (!validation.valid) { this.error = validation.error ?? 'Resource URI is invalid'; return; }
    const invalidEndpoint = this.endpointDrafts.find(endpoint => this.isEndpointPathInvalid(endpoint.path));
    if (invalidEndpoint) {
      this.error = `Endpoint path must start with “/” and contain no “*” or “:”: ${endpointLabel(invalidEndpoint)}`;
      return;
    }
    const celIssue = this.findCelIssue();
    if (celIssue) { this.error = celIssue; return; }

    const endpointPayloads = this.endpointDrafts.map(endpoint => this.toEndpointPayload(endpoint));
    this.saving = true; this.error = '';
    try {
      let savedResourceId: number | null = null;
      let savedEndpoints: PersistedResourceEndpointPayload[] = [];
      if (this.formMode === 'edit-resource' && this.activeResourceId !== null) {
        const activeResource = this.resources.find(candidate => candidate.id === this.activeResourceId);
        if (!activeResource) throw new Error('Resource not found in local state');
        savedResourceId = this.activeResourceId;
        savedEndpoints = await updateResource(savedResourceId, activeResource.endpoints, alias, resource, endpointPayloads);
      } else {
        const createdResource = await createResource(this.tenantId, alias, resource, endpointPayloads);
        savedResourceId = createdResource.id;
        savedEndpoints = createdResource.endpoints;
      }

      if (savedResourceId !== null) {
        this.upsertResource(this.buildSavedResource(savedResourceId, alias, resource, savedEndpoints));
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
    const trimmed = path.trim();
    if (trimmed.length === 0) return false;
    if (!trimmed.startsWith('/')) return true;
    if (trimmed.includes('*') || trimmed.includes(':')) return true;
    return false;
  }

  private findCelIssue(): string | null {
    for (const endpoint of this.endpointDrafts) {
      const label = endpointLabel(endpoint);
      if (endpoint.allow.trim().length > 0) {
        const allowResult = validateCel(endpoint.allow);
        if (!allowResult.valid) return `Invalid allow expression in ${label}: ${allowResult.error.message}`;
      }
      for (const rule of endpoint.inject) {
        if (rule.expression.trim().length === 0) {
          return `Inject expression is required in ${label}${rule.name ? ` for ${rule.name}` : ''}`;
        }
        const exprResult = validateCel(rule.expression);
        if (!exprResult.valid) {
          return `Invalid inject expression in ${label}${rule.name ? ` for ${rule.name}` : ''}: ${exprResult.error.message}`;
        }
      }
    }
    return null;
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
      const alias = resource.alias.toLowerCase();
      return label.includes(query) || original.includes(query) || alias.includes(query) || String(resource.id).includes(query);
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

  private buildSavedResource(id: number, alias: string, resource: string, endpoints: PersistedResourceEndpointPayload[]): Resource {
    return {
      id,
      alias,
      resource,
      endpoints: endpoints.map(endpoint => ({
        id: endpoint.id,
        method: endpoint.method,
        path: endpoint.path,
        fetchUserInfo: endpoint.fetchUserInfo,
        allow: endpoint.allow ?? undefined,
        inject: cloneInject(endpoint.inject),
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
      allow: endpoint.allow ?? '',
      inject: cloneInject(endpoint.inject),
    };
  }

  private toEndpointPayload(endpoint: EditableResourceEndpoint) {
    const allow = endpoint.allow.trim();
    return {
      ...(endpoint.id !== null ? { id: endpoint.id } : {}),
      method: endpoint.method,
      path: endpoint.path,
      fetchUserInfo: endpoint.fetchUserInfo,
      allow: allow.length > 0 ? allow : null,
      inject: cloneInject(endpoint.inject),
    };
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
      endpoint.draftId === draftId ? updater(endpoint) : endpoint
    ));
  }

  private updateEndpointField(draftId: string, field: 'method' | 'path', value: string) {
    this.updateEndpointDraft(draftId, endpoint => ({ ...endpoint, [field]: value }));
  }

  private updateEndpointAllow(draftId: string, value: string) {
    this.updateEndpointDraft(draftId, endpoint => ({ ...endpoint, allow: value }));
  }

  private updateEndpointFetchUserInfo(draftId: string, value: boolean) {
    this.updateEndpointDraft(draftId, endpoint => ({ ...endpoint, fetchUserInfo: value }));
  }

  private addInjectRule(draftId: string) {
    this.updateEndpointDraft(draftId, endpoint => ({
      ...endpoint,
      inject: [...endpoint.inject, { target: 'header', name: '', expression: '' }],
    }));
  }

  private removeInjectRule(draftId: string, index: number) {
    this.updateEndpointDraft(draftId, endpoint => ({
      ...endpoint,
      inject: endpoint.inject.filter((_, i) => i !== index),
    }));
  }

  private updateInjectRule(draftId: string, index: number, patch: Partial<InjectRule>) {
    this.updateEndpointDraft(draftId, endpoint => ({
      ...endpoint,
      inject: endpoint.inject.map((rule, i) => i === index ? { ...rule, ...patch } : rule),
    }));
  }

  private toggleInfo(key: string) {
    this.openInfoKey = this.openInfoKey === key ? null : key;
  }

  private renderAllowInfo(): TemplateResult {
    return html`
      <p>CEL expression evaluated per request. Must return <span class="option-tooltip-code">true</span> to authorize the call. Leave empty to skip the check.</p>
      <div class="option-tooltip-section-title">Root variables</div>
      <ul class="option-tooltip-list">
        <li><span class="option-tooltip-code">token</span> — claims of the validated access token (e.g. <span class="option-tooltip-code">token.sub</span>, <span class="option-tooltip-code">token.scope</span>).</li>
        <li><span class="option-tooltip-code">user</span> — userinfo claims (only when "Fetch userinfo" is enabled).</li>
        <li><span class="option-tooltip-code">request</span> — incoming request data:
          <ul class="option-tooltip-list">
            <li><span class="option-tooltip-code">request.path</span> — map of path parameters from <span class="option-tooltip-code">{name}</span> placeholders.</li>
            <li><span class="option-tooltip-code">request.query</span> — map of query parameters (first value per key).</li>
            <li><span class="option-tooltip-code">request.queryAll</span> — map of query parameters (all values per key as a list).</li>
            <li><span class="option-tooltip-code">request.headers</span> — map of request headers (first value per key).</li>
            <li><span class="option-tooltip-code">request.headersAll</span> — map of request headers (all values per key as a list).</li>
            <li><span class="option-tooltip-code">request.body</span> — parsed JSON body (only when content-type is <span class="option-tooltip-code">application/json</span>).</li>
          </ul>
        </li>
      </ul>
      <div class="option-tooltip-section-title">Operators</div>
      <ul class="option-tooltip-list">
        <li>Comparison: <span class="option-tooltip-code">== != &lt; &lt;= &gt; &gt;=</span></li>
        <li>Logical: <span class="option-tooltip-code">&amp;&amp; || !</span></li>
        <li>Membership: <span class="option-tooltip-code">in</span></li>
        <li>Arithmetic: <span class="option-tooltip-code">+ - * / %</span></li>
        <li>Ternary: <span class="option-tooltip-code">cond ? a : b</span></li>
        <li>Index / field: <span class="option-tooltip-code">x[i]</span>, <span class="option-tooltip-code">x.y</span></li>
      </ul>
      <div class="option-tooltip-section-title">Macros</div>
      <ul class="option-tooltip-list">
        <li><span class="option-tooltip-code">has(x.y)</span> — field presence</li>
        <li><span class="option-tooltip-code">list.all(v, p)</span>, <span class="option-tooltip-code">list.exists(v, p)</span>, <span class="option-tooltip-code">list.exists_one(v, p)</span></li>
        <li><span class="option-tooltip-code">list.filter(v, p)</span>, <span class="option-tooltip-code">list.map(v, p)</span></li>
        <li><span class="option-tooltip-code">size(x)</span> — string, list, or map length</li>
      </ul>
      <div class="option-tooltip-section-title">Example</div>
      <pre class="option-tooltip-pre">"read" in token.scope &amp;&amp; user.department == "engineering"</pre>
    `;
  }

  private renderOptionInfo(key: string, title: string, description: string | TemplateResult, ariaLabel: string) {
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

  private renderAllowSection(allow: string | undefined) {
    const hasAllow = allow != null && allow.length > 0;
    return html`
      <div class="endpoint-editor-section">
        <div class="endpoint-detail-label">Allow (CEL)</div>
        ${hasAllow
          ? html`<div class="cel-inline">${renderHighlightedCel(allow)}</div>`
          : html`<div class="endpoint-empty">— (unrestricted)</div>`}
      </div>
    `;
  }

  private renderInjectSection(inject: InjectRule[]) {
    return html`
      <div class="endpoint-editor-section">
        <div class="endpoint-detail-label">Inject</div>
        ${inject.length === 0 ? html`<div class="endpoint-empty">None</div>` : html`
          <div class="header-list">
            ${inject.map(rule => html`
              <div class="header-item">
                <div class="header-item-part">
                  <span class="source-badge">${rule.target}</span>
                  <div class="header-item-key" style="margin-left:.5rem">${rule.name}</div>
                </div>
                <div class="header-item-part header-item-part-expression">
                  <div class="header-item-value cel-inline">${renderHighlightedCel(rule.expression)}</div>
                </div>
              </div>
            `)}
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
          ${this.renderAllowSection(endpoint.allow)}
          ${this.renderInjectSection(endpoint.inject)}
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

  private renderAllowEditor(draftId: string, allow: string) {
    return html`
      <div class="endpoint-editor-section">
        <div class="editor-section-header">
          <div class="editor-section-title-row">
            <h3 class="editor-section-title">Allow (CEL)</h3>
            ${this.renderOptionInfo(
              `${draftId}-allow-info`,
              'Allow expression',
              this.renderAllowInfo(),
              'Allow expression info',
            )}
          </div>
        </div>
        <versola-cel-editor
          multiline
          rows="2"
          .value=${allow}
          ?disabled=${this.saving}
          placeholder="leave empty for no authorization check"
          aria-label="Allow expression"
          @cel-input=${(event: CustomEvent<{ value: string }>) => this.updateEndpointAllow(draftId, event.detail.value)}
        ></versola-cel-editor>
      </div>
    `;
  }

  private renderInjectEditor(draftId: string, inject: InjectRule[]) {
    return html`
      <div class="endpoint-editor-section">
        <div class="editor-section-header">
          <div class="editor-section-title-row">
            <h3 class="editor-section-title">Inject</h3>
            ${this.renderOptionInfo(
              `${draftId}-inject-info`,
              'Inject',
              'Each rule injects a value into the upstream request. Target is header, query, or body (top-level JSON only). Expression is CEL evaluated against token, user, and request. Injected values overwrite client-supplied ones.',
              'Inject info',
            )}
          </div>
          <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.saving} @click=${() => this.addInjectRule(draftId)}>
            Add rule
          </button>
        </div>
        ${inject.length === 0 ? html`<div class="endpoint-empty">No inject rules yet.</div>` : html`
          <div class="header-editor-list">
            ${inject.map((rule, index) => html`
              <div class="header-editor-item">
                <div class="form-group">
                  <label class="form-label">Target</label>
                  <select
                    class="form-select compact-input"
                    .value=${rule.target}
                    @change=${(event: Event) => this.updateInjectRule(draftId, index, { target: (event.target as HTMLSelectElement).value as InjectTarget })}
                  >
                    ${injectTargets.map(target => html`<option value=${target}>${target}</option>`)}
                  </select>
                </div>
                <div class="form-group">
                  <label class="form-label">Name</label>
                  <input
                    class="form-input compact-input"
                    type="text"
                    .value=${rule.name}
                    @input=${(event: Event) => this.updateInjectRule(draftId, index, { name: (event.target as HTMLInputElement).value })}
                    placeholder=${rule.target === 'header' ? 'X-User-Id' : rule.target === 'query' ? 'tenant' : 'userId'}
                  />
                </div>
                <div class="form-group">
                  <label class="form-label">Expression (CEL)</label>
                  <versola-cel-editor
                    class="compact-input"
                    required
                    .value=${rule.expression}
                    ?disabled=${this.saving}
                    placeholder="token.sub"
                    aria-label="Inject expression"
                    @cel-input=${(event: CustomEvent<{ value: string }>) => this.updateInjectRule(draftId, index, { expression: event.detail.value })}
                  ></versola-cel-editor>
                </div>
                <button
                  type="button"
                  class="icon-action danger editor-remove"
                  ?disabled=${this.saving}
                  @click=${() => this.removeInjectRule(draftId, index)}
                  title="Remove rule"
                  aria-label="Remove inject rule"
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
            <div class="endpoint-editor-section">
              <label class="fetch-row">
                <input
                  type="checkbox"
                  ?checked=${endpoint.fetchUserInfo}
                  @change=${(event: Event) => this.updateEndpointFetchUserInfo(endpoint.draftId, (event.target as HTMLInputElement).checked)}
                />
                <span class="fetch-row-label">Fetch userinfo</span>
              </label>
            </div>
            ${this.renderAllowEditor(endpoint.draftId, endpoint.allow)}
            ${this.renderInjectEditor(endpoint.draftId, endpoint.inject)}
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
              <label class="form-label">Alias</label>
              <div class="input-with-info">
                <input class="form-input compact-input" type="text" aria-label="Resource alias" .value=${this.resourceAlias} @input=${(e: Event) => this.resourceAlias = (e.target as HTMLInputElement).value} placeholder="users-api" ?disabled=${this.saving} required />
                ${this.renderOptionInfo(
                  'resource-alias-info',
                  'Alias',
                  'Used by the edge to route incoming user-agent requests to this resource. Requests to /resources/{alias}/* are forwarded to the configured resource URI.',
                  'Resource alias info',
                )}
              </div>
            </div>
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
          <div class="resource-label-card">
            <div class="resource-label">${formatResourceLabel(resource.resource)}</div>
            <span class="resource-alias-badge" title="Alias">${resource.alias}</span>
          </div>
          <div class="resource-actions" @click=${(e: Event) => e.stopPropagation()}>
            <button class="icon-action" @click=${() => this.openEditResourceForm(resource)} title="Edit resource" aria-label=${`Edit resource ${resource.alias}`}>✎</button>
            <button class="icon-action danger" @click=${() => this.removeResource(resource)} title="Delete resource" aria-label=${`Delete resource ${resource.alias}`}>✕</button>
          </div>
        </div>
        ${isExpanded ? this.renderResourceEndpoints(resource) : ''}
      </div></div>`;
      })}</div>
    `;
  }
}

declare global { interface HTMLElementTagNameMap { 'versola-resources-list': VersolaResourcesList; } }
