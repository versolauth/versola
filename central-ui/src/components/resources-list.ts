import { LitElement, css, html, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { badgeStyles, buttonStyles, cardStyles, formStyles, methodBadgeStyles, tableStyles } from '../styles/components';
import { celHighlightStyles } from '../styles/cel-highlight';
import { theme } from '../styles/theme';
import type { InjectRule, InjectTarget, Resource, ResourceEndpoint, ResourceEndpointId } from '../types';
import { createResource, deletePreviousResourceSecret, deleteResource, fetchAllClients, fetchAllPermissions, fetchChallengeSettings, getResources, rotateResourceSecret, updateResource } from '../utils/central-api';
import { renderHighlightedCel } from '../utils/cel-highlight';
import { validateCel } from '../utils/cel-validator';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { PERMISSIONS_UPDATED_EVENT, type PermissionsUpdatedDetail, copyToClipboard, formatResourceLabel, indexPermissionsByEndpoint } from '../utils/helpers';
import { validateResourceId, validateResourceUri } from '../utils/validators';
import './cel-editor';
import './content-header';
import './error-card';
import './loading-cards';
import './nav-toggle';

type ResourceEndpointDraft = {
  method: string;
  path: string;
  fetchUserInfo: boolean;
  allow: string;
  inject: InjectRule[];
  stepUpCondition: string;
  stepUpAcr: string;
  maxAge: string;
};

type EditableResourceEndpoint = ResourceEndpointDraft & {
  id: ResourceEndpoint['id'] | null;
  draftId: string;
};

type SaveResourceEndpointPayload = Omit<ResourceEndpointDraft, 'allow' | 'stepUpCondition' | 'stepUpAcr' | 'maxAge'> & {
  id?: ResourceEndpoint['id'];
  allow: string | null;
  stepUpCondition?: string | null;
  stepUpAcr?: string | null;
  maxAge?: number | null;
};

type PersistedResourceEndpointPayload = SaveResourceEndpointPayload & {
  id: ResourceEndpoint['id'];
};

type ResourceFormMode = 'none' | 'create-resource' | 'edit-resource';
type EndpointSearch = { method?: string; path: string };

const endpointMethods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;
const injectTargets: InjectTarget[] = ['header', 'query', 'body'];

function endpointLabel(endpoint: Pick<ResourceEndpointDraft, 'method' | 'path'>) {
  return `${endpoint.method} ${endpoint.path}`;
}

function parseEndpointSearch(query: string): EndpointSearch | null {
  const normalized = query.trim();
  if (normalized.startsWith('/')) return { path: normalized.toLowerCase() };

  const methodAndPath = /^(GET|POST|PUT|PATCH|DELETE)\s+(\/.*)$/i.exec(normalized);
  return methodAndPath
    ? { method: methodAndPath[1].toUpperCase(), path: methodAndPath[2].toLowerCase() }
    : null;
}

function cloneInject(rules: InjectRule[]): InjectRule[] {
  return rules.map(rule => ({ ...rule }));
}

@customElement('versola-resources-list')
export class VersolaResourcesList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @state() private resources: Resource[] = [];
  @state() private clientIds: string[] = [];
  @state() private acrVocabulary: Record<string, string[]> = {};
  /** Reverse index: endpoint id -> permission ids granting access to it. */
  @state() private permissionsByEndpoint: Map<ResourceEndpointId, string[]> = new Map();
  @state() private expandedResources: Set<string> = new Set();
  @state() private expandedEndpoints: Set<ResourceEndpointId> = new Set();
  @state() private searchQuery = '';
  @state() private endpointEditSearchQuery = '';
  @state() private loading = false;
  @state() private saving = false;
  @state() private secretActionResourceId: string | null = null;
  @state() private error = '';
  @state() private formMode: ResourceFormMode = 'none';
  @state() private activeResourceId: string | null = null;
  @state() private expandedEditableEndpoints: Set<string> = new Set();
  @state() private openInfoKey: string | null = null;
  @state() private resourceUri = '';
  @state() private resourceId = '';
  @state() private resourceAudience: string[] = [];
  @state() private audienceToAdd = '';
  @state() private resourceInternal = false;
  @state() private endpointDrafts: EditableResourceEndpoint[] = [];
  @state() private createdSecret: { resourceId: string; secret: string; action: 'created' | 'rotated' } | null = null;
  @state() private copyFeedback = '';
  @state() private collapsedAudiences: Set<string> = new Set();
  private nextEndpointDraftId = 0;
  private handleDocumentClick = () => {
    this.openInfoKey = null;
  };

  static styles = [theme, buttonStyles, cardStyles, formStyles, methodBadgeStyles, tableStyles, badgeStyles, celHighlightStyles, css`
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
    .empty-state-icon { font-size:3rem; margin-bottom:1rem; }
    .form-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:var(--spacing-xl); gap:var(--spacing-md); }
    .form-title { font-size:2rem; font-weight:700; color:var(--text-primary); margin:0; }
    .form-grid { display:grid; grid-template-columns:minmax(0, 1fr); gap:var(--spacing-lg); }
    .form-grid > * { min-width:0; }
    .form-actions { display:flex; gap:1rem; justify-content:flex-end; margin-top:var(--spacing-xl); padding-top:var(--spacing-xl); border-top:1px solid var(--border-dark); }
    .secondary-action-button { margin-right:auto; }
    .search-bar { margin-bottom:var(--spacing-lg); max-width:28rem; }
    .stack { display:grid; gap:var(--spacing-md); }
    .resource-shell { transition:border-color var(--transition-base); }
    .resource-shell:hover { border-color:var(--accent); }
    .resource-card { display:grid; gap:var(--spacing-lg); }
    .resource-header { display:flex; align-items:center; justify-content:space-between; gap:var(--spacing-md); cursor:pointer; user-select:none; }
    .resource-actions { display:flex; align-items:center; gap:.5rem; margin-left:var(--spacing-md); }
    .resource-label-card { max-width:min(32rem, 100%); padding:.15rem 0; display:flex; align-items:center; gap:.625rem; flex-wrap:wrap; }
    .resource-label { color:var(--accent); font-size:1rem; font-weight:600; line-height:1.35; word-break:break-all; }
    .resource-id-badge { display:inline-flex; align-items:center; min-height:1.5rem; padding:0 .6rem; border-radius:999px; font-size:.75rem; font-weight:600; letter-spacing:.01em; background:rgba(var(--accent-tint), .16); color:var(--accent); border:1px solid rgba(var(--accent-tint), .28); flex:none; }
    .input-with-info { display:flex; align-items:center; gap:.5rem; }
    .input-with-info > .form-input { flex:1; min-width:0; }
    .cred-mode-cards { display:grid; grid-template-columns:repeat(auto-fill, minmax(160px, 1fr)); gap:.75rem; margin-top:.5rem; }
    .cred-mode-card { display:flex; align-items:center; justify-content:center; text-align:center; padding:.625rem .75rem; border:1px solid var(--border-dark); border-radius:var(--radius-sm); background:transparent; color:var(--text-primary); font-size:.875rem; font-family:var(--font-mono); cursor:pointer; transition:all var(--transition-fast); }
    .cred-mode-card:hover { border-color:var(--accent); background:rgba(var(--accent-tint), .05); }
    .cred-mode-card.selected { border-color:var(--accent); background:rgba(var(--accent-tint), .12); }
    .audience-add-row { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap; margin-top:.5rem; }
    .audience-input-wrap { position:relative; flex:1; min-width:16rem; }
    .audience-suggestions { position:absolute; top:calc(100% + .25rem); left:0; right:0; z-index:30; display:grid; max-height:12rem; overflow-y:auto; padding:.25rem; border:1px solid var(--border-dark); border-radius:var(--radius-sm); background:var(--bg-dark-card); box-shadow:0 8px 20px rgba(0,0,0,.35); }
    .audience-suggestion { width:100%; padding:.5rem .625rem; border:0; border-radius:var(--radius-sm); background:transparent; color:var(--text-primary); font-family:var(--font-mono); font-size:.875rem; text-align:left; cursor:pointer; }
    .audience-suggestion:hover, .audience-suggestion:focus-visible { background:rgba(var(--accent-tint),.12); color:var(--accent); outline:none; }
    .audience-list { display:grid; gap:.5rem; margin-top:.75rem; }
    .audience-item { display:flex; align-items:center; justify-content:space-between; gap:.75rem; min-height:2.25rem; padding:.375rem .5rem .375rem .75rem; border:1px solid var(--border-dark); border-radius:var(--radius-sm); background:rgba(255,255,255,.02); font-family:var(--font-mono); font-size:.875rem; }
    .audience-empty { margin-top:.75rem; }
    .resource-audience { display:grid; gap:.625rem; }
    .resource-section-trigger { display:flex; align-items:center; gap:.625rem; width:max-content; padding:0; border:0; background:transparent; color:var(--text-primary); font-size:.875rem; font-weight:600; cursor:pointer; }
    .resource-section-trigger:hover { color:var(--accent); }
    .resource-section-chevron { color:var(--text-secondary); font-size:.7rem; }
    .audience-view-list { display:flex; flex-wrap:wrap; gap:.5rem; }
    .audience-view-item { padding:.375rem .625rem; border:1px solid rgba(var(--accent-tint),.3); border-radius:var(--radius-sm); background:rgba(var(--accent-tint),.08); color:var(--text-primary); font-family:var(--font-mono); font-size:.8125rem; }
    .secret-banner { margin-bottom:var(--spacing-lg); border-color:rgba(63, 185, 80, .35); background:linear-gradient(180deg, rgba(63, 185, 80, .08), rgba(63, 185, 80, .04)); }
    .secret-banner-header { display:flex; justify-content:space-between; align-items:flex-start; gap:var(--spacing-md); margin-bottom:var(--spacing-md); }
    .secret-banner-title { margin:0; font-size:1rem; color:var(--text-primary); }
    .secret-banner-text { margin:.35rem 0 0; color:var(--text-secondary); font-size:.875rem; }
    .secret-value { margin:0; padding:.875rem 1rem; background:rgba(0, 0, 0, .25); border:1px solid var(--border-dark); border-radius:var(--radius-md); color:var(--text-primary); font-family:var(--font-mono); font-size:.875rem; line-height:1.5; word-break:break-all; }
    .secret-banner-actions { display:flex; gap:.75rem; align-items:center; margin-top:var(--spacing-md); }
    .copy-feedback { font-size:.8125rem; color:var(--success, #3fb950); }
    .btn-ghost { background:transparent; color:var(--text-secondary); border-color:var(--border-dark); }
    .btn-ghost:not(:disabled):hover { color:var(--text-primary); border-color:var(--text-secondary); }
    .endpoint-list { display:grid; gap:.75rem; }
    .endpoint-row { display:flex; align-items:center; justify-content:space-between; gap:.75rem; flex-wrap:wrap; padding:.875rem 1rem; border:1px solid var(--border-dark); border-radius:var(--radius-md); background:rgba(255,255,255,.02); }
    .endpoint-main { display:flex; align-items:center; gap:.75rem; flex-wrap:wrap; min-width:0; }
    .endpoint-actions { display:flex; align-items:center; gap:.5rem; margin-left:auto; }
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
      border-left:2px solid rgba(var(--accent-tint), .18);
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
    .source-badge { display:inline-flex; align-items:center; min-height:1.5rem; padding:0 .6rem; border-radius:999px; font-size:.75rem; font-weight:600; letter-spacing:.01em; background:rgba(var(--accent-tint), .16); color:var(--accent); border:1px solid rgba(var(--accent-tint), .28); }
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
    .fetch-indicator.fetch-enabled .fetch-indicator-box { background:rgba(var(--accent-tint), .16); border-color:rgba(var(--accent-tint), .32); color:var(--accent); }
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
      border:1px solid rgba(var(--accent-tint), 0.4);
      border-radius:999px;
      background:rgba(var(--accent-tint), 0.12);
      color:var(--accent);
      font-size:.75rem;
      font-weight:700;
      line-height:1;
      padding:0.25rem 0.45rem;
      cursor:pointer;
      font-family:var(--font-family);
    }
    .option-info { position:relative; display:inline-flex; align-items:center; flex:none; }
    .option-info-button:hover { background:rgba(var(--accent-tint), 0.18); border-color:rgba(var(--accent-tint), 0.55); }
    .option-info-button:focus-visible { outline:none; box-shadow:0 0 0 2px rgba(var(--accent-tint), 0.2); }
    .option-tooltip {
      position:absolute;
      right:0;
      top:calc(100% + 0.4rem);
      z-index:20;
      min-width:18rem;
      max-width:min(28rem, 75vw);
      padding:0.75rem;
      border:1px solid rgba(var(--accent-tint), 0.28);
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
    .option-tooltip-code { font-family:var(--font-mono, monospace); font-size:.72rem; color:var(--accent); background:rgba(var(--accent-tint), .1); padding:.05rem .3rem; border-radius:.25rem; }
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
    .prefix-tags {
      display: flex;
      flex-wrap: wrap;
      gap: var(--spacing-sm);
      margin-top: var(--spacing-sm);
    }
    .prefix-tag {
      font-family: var(--font-mono);
      font-weight: 600;
      font-size: 0.9375rem;
      color: var(--accent);
      background: var(--bg-dark);
      border: 1px solid var(--border-dark);
      border-radius: var(--radius-md);
      padding: var(--spacing-xs) var(--spacing-md);
    }
    .permission-alternatives {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--spacing-xs);
      margin-top: var(--spacing-xs);
    }
    .permission-tag {
      font-family: var(--font-mono);
      font-weight: 600;
      font-size: 0.75rem;
      color: var(--accent);
      background: rgba(var(--accent-tint), 0.12);
      border: 1px solid rgba(var(--accent-tint), 0.32);
      border-radius: var(--radius-sm);
      padding: 0.125rem var(--spacing-sm);
    }
    .permission-or {
      font-size: 0.6875rem;
      font-style: italic;
      color: var(--text-secondary);
    }
    @media (max-width: 720px) {
      /* Same inherited-align-items trap as permissions-list: in a column,
         align-items:center centres the label horizontally instead of
         vertically. */
      .resource-header { flex-direction:column; align-items:flex-start; }
      /* .resource-actions is deliberately NOT in the stacking list below: it
         holds two icon buttons that belong side by side. Stacking them turned
         a compact ✎ ✕ pair into a vertical column. */
      .resource-actions { margin-left:0; }
      .form-actions, .section-header, .sub-actions { flex-direction:column; align-items:flex-start; }
      .endpoint-editor-grid { grid-template-columns:minmax(0, var(--compact-field-width)); }
      .rule-editor-main, .rule-editor-pattern, .header-editor-item { grid-template-columns:minmax(0, 1fr); }
    }
  `];

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId')) {
      this.expandedResources = new Set();
      this.expandedEndpoints = new Set();
      this.collapsedAudiences = new Set();
      this.resetForms();
      void this.loadData();
    }
  }

  connectedCallback() {
    super.connectedCallback();
    document.addEventListener('click', this.handleDocumentClick);
    window.addEventListener(PERMISSIONS_UPDATED_EVENT, this.handlePermissionsUpdated);
  }

  disconnectedCallback() {
    document.removeEventListener('click', this.handleDocumentClick);
    window.removeEventListener(PERMISSIONS_UPDATED_EVENT, this.handlePermissionsUpdated);
    super.disconnectedCallback();
  }

  private handlePermissionsUpdated = (event: Event) => {
    const detail = (event as CustomEvent<PermissionsUpdatedDetail>).detail;
    if (!detail || detail.tenantId !== this.tenantId) return;
    this.permissionsByEndpoint = indexPermissionsByEndpoint(detail.permissions);
  };

  private resetForms() {
    this.formMode = 'none'; this.activeResourceId = null; this.resourceUri = ''; this.resourceId = '';
    this.resourceAudience = [];
    this.audienceToAdd = '';
    this.resourceInternal = false;
    this.endpointDrafts = [];
    this.endpointEditSearchQuery = '';
    this.expandedEditableEndpoints = new Set();
  }

  private openCreateResourceForm() {
    this.error = '';
    this.formMode = 'create-resource';
    this.activeResourceId = null;
    this.resourceUri = 'https://';
    this.resourceId = '';
    this.resourceAudience = [];
    this.audienceToAdd = '';
    this.resourceInternal = false;
    this.endpointDrafts = [];
    this.endpointEditSearchQuery = '';
    this.expandedEditableEndpoints = new Set();
  }

  private openEditResourceForm(resource: Resource) {
    this.error = '';
    this.formMode = 'edit-resource';
    this.activeResourceId = resource.resourceId;
    this.resourceUri = resource.resource;
    this.resourceId = resource.resourceId;
    this.resourceAudience = [...resource.audience];
    this.audienceToAdd = '';
    this.resourceInternal = resource.hasSecret;
    this.endpointDrafts = resource.endpoints.map(endpoint => this.toEditableEndpoint(endpoint));
    this.endpointEditSearchQuery = '';
    this.expandedEditableEndpoints = new Set(); // Start with all cards collapsed
    this.expandedResources = new Set([...this.expandedResources, resource.resourceId]);
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
      stepUpCondition: '',
      stepUpAcr: '',
      maxAge: '',
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
    if (!this.tenantId) { this.resources = []; this.permissionsByEndpoint = new Map(); this.error = ''; return; }
    this.loading = true; this.error = '';
    try {
      const [resources, challengeSettings, clients, permissions] = await Promise.all([
        getResources(this.tenantId),
        fetchChallengeSettings(this.tenantId),
        fetchAllClients(this.tenantId),
        fetchAllPermissions(this.tenantId),
      ]);
      this.resources = resources;
      this.clientIds = clients.map(client => client.id);
      this.acrVocabulary = challengeSettings?.acrVocabulary ?? {};
      this.permissionsByEndpoint = indexPermissionsByEndpoint(permissions);
      const validIds = new Set(resources.map(resource => resource.resourceId));
      const validEndpointIds = new Set(resources.flatMap(resource => resource.endpoints.map(endpoint => endpoint.id)));
      this.expandedResources = new Set([...this.expandedResources].filter(id => validIds.has(id)));
      this.expandedEndpoints = new Set([...this.expandedEndpoints].filter(id => validEndpointIds.has(id)));
      this.collapsedAudiences = new Set([...this.collapsedAudiences].filter(id => validIds.has(id)));
      if (this.formMode === 'edit-resource' && this.activeResourceId !== null) {
        const activeResource = this.resources.find(resource => resource.resourceId === this.activeResourceId);
        if (!activeResource) {
          this.resetForms();
        }
      }
    } catch (error) {
      this.resources = []; this.permissionsByEndpoint = new Map(); this.error = error instanceof Error ? error.message : 'Failed to load resources';
    } finally { this.loading = false; }
  }

  private async saveResource(event: Event) {
    event.preventDefault(); if (!this.tenantId) return;
    const resource = this.resourceUri.trim();
    const resourceId = this.resourceId.trim();
    if (resourceId.length === 0) { this.error = 'Resource ID is required'; return; }
    const resourceIdValidation = validateResourceId(resourceId);
    if (!resourceIdValidation.valid) { this.error = resourceIdValidation.error ?? 'Resource ID is invalid'; return; }
    const validation = validateResourceUri(resource);
    if (!validation.valid) { this.error = validation.error ?? 'Resource URI is invalid'; return; }
    const invalidEndpoint = this.endpointDrafts.find(endpoint => this.isEndpointPathInvalid(endpoint.path));
    if (invalidEndpoint) {
      this.error = `Endpoint path must start with "/" and contain only latin letters, digits, "-", or a "{name}" parameter per segment (no consecutive "/", no repeated parameter names): ${endpointLabel(invalidEndpoint)}`;
      return;
    }
    const ambiguousEndpoint = this.findAmbiguousEndpoint();
    if (ambiguousEndpoint) {
      this.error = `Endpoint path differs from another endpoint of the same method only in its parameter names: ${endpointLabel(ambiguousEndpoint)}`;
      return;
    }
    const celIssue = this.findCelIssue();
    if (celIssue) { this.error = celIssue; return; }

    const endpointPayloads = this.endpointDrafts.map(endpoint => this.toEndpointPayload(endpoint));
    this.saving = true; this.error = '';
    try {
      let savedResourceId: string | null = null;
      let savedEndpoints: PersistedResourceEndpointPayload[] = [];
      let hasSecret = this.resourceInternal;
      let hasPreviousSecret = false;
      let secret: string | null = null;
      if (this.formMode === 'edit-resource' && this.activeResourceId !== null) {
        const activeResource = this.resources.find(candidate => candidate.resourceId === this.activeResourceId);
        if (!activeResource) throw new Error('Resource not found in local state');
        savedResourceId = this.activeResourceId;
        savedEndpoints = await updateResource(savedResourceId, activeResource.endpoints, resource, this.resourceAudience, endpointPayloads);
        hasSecret = activeResource.hasSecret;
        hasPreviousSecret = activeResource.hasPreviousSecret;
      } else {
        const createdResource = await createResource(this.tenantId, resourceId, resource, this.resourceAudience, endpointPayloads, this.resourceInternal);
        savedResourceId = createdResource.resourceId;
        savedEndpoints = createdResource.endpoints;
        secret = createdResource.secret;
      }

      if (savedResourceId !== null) {
        this.upsertResource(this.buildSavedResource(savedResourceId, resource, this.resourceAudience, savedEndpoints, hasSecret, hasPreviousSecret));
        this.expandedResources = new Set([...this.expandedResources, savedResourceId]);
        if (secret) {
          this.createdSecret = { resourceId: savedResourceId, secret, action: 'created' };
          this.copyFeedback = '';
        }
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

  private get isResourceIdInvalid() {
    const resourceId = this.resourceId.trim();
    return resourceId.length > 0 && !validateResourceId(resourceId).valid;
  }

  private isEndpointPathInvalid(path: string) {
    const trimmed = path.trim();
    if (trimmed.length === 0) return false;
    const segment = '([a-zA-Z0-9-]+|\\{[a-zA-Z_][a-zA-Z0-9_]*\\})';
    if (!new RegExp(`^\\/(${segment}(\\/${segment})*)?$`).test(trimmed)) return true;
    const params = trimmed.split('/').filter(part => part.startsWith('{'));
    return new Set(params).size !== params.length;
  }

  /** Two endpoints of the same method whose paths differ only in parameter names match
   * exactly the same requests, so edge could never tell them apart. */
  private findAmbiguousEndpoint() {
    const shape = (path: string) => path.trim().split('/').map(part => (part.startsWith('{') ? '{}' : part)).join('/');
    return this.endpointDrafts.find((endpoint, index) => this.endpointDrafts.slice(0, index).some(earlier =>
      earlier.method === endpoint.method &&
      shape(earlier.path) === shape(endpoint.path) &&
      earlier.path.trim() !== endpoint.path.trim()));
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
      await deleteResource(resource.resourceId);
      this.resources = this.resources.filter(candidate => candidate.resourceId !== resource.resourceId);
      this.expandedResources = new Set([...this.expandedResources].filter(id => id !== resource.resourceId));
      this.expandedEndpoints = new Set([...this.expandedEndpoints].filter(id => !resource.endpoints.some(endpoint => endpoint.id === id)));
      this.collapsedAudiences = new Set([...this.collapsedAudiences].filter(id => id !== resource.resourceId));
      if (this.activeResourceId === resource.resourceId) this.resetForms();
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Failed to delete resource';
    }
  }

  private updateResourceSecretState(resourceId: string, hasSecret: boolean, hasPreviousSecret: boolean) {
    this.resources = this.resources.map(resource =>
      resource.resourceId === resourceId ? { ...resource, hasSecret, hasPreviousSecret } : resource,
    );
  }

  private async handleRotateSecret(resource: Resource) {
    if (this.secretActionResourceId !== null) return;
    this.secretActionResourceId = resource.resourceId;
    this.error = '';
    try {
      const secret = await rotateResourceSecret(resource.resourceId);
      this.updateResourceSecretState(resource.resourceId, true, true);
      this.createdSecret = { resourceId: resource.resourceId, secret, action: 'rotated' };
      this.copyFeedback = '';
      this.resetForms();
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Failed to rotate resource secret';
    } finally {
      this.secretActionResourceId = null;
    }
  }

  private async handleActivateNewSecret(resource: Resource) {
    if (this.secretActionResourceId !== null) return;
    const confirmed = await confirmDestructiveAction({
      title: 'Activate new secret',
      messagePrefix: 'Activate the new secret for ',
      messageSubject: resource.resourceId,
      messageSuffix: '? Do this only after the upstream accepts the new secret. Edge will switch after its next sync.',
      confirmLabel: 'Activate',
    });
    if (!confirmed) return;

    this.secretActionResourceId = resource.resourceId;
    this.error = '';
    try {
      await deletePreviousResourceSecret(resource.resourceId);
      this.updateResourceSecretState(resource.resourceId, true, false);
      this.resetForms();
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Failed to activate new resource secret';
    } finally {
      this.secretActionResourceId = null;
    }
  }

  private dismissCreatedSecret() {
    this.createdSecret = null;
    this.copyFeedback = '';
  }

  private async handleCopySecret() {
    if (!this.createdSecret) return;
    const copied = await copyToClipboard(this.createdSecret.secret);
    this.copyFeedback = copied ? 'Secret copied to clipboard.' : 'Could not copy secret.';
  }

  private get filteredResources(): Resource[] {
    const query = this.searchQuery.trim().toLowerCase();
    const endpointSearch = this.endpointSearch;

    return this.resources.filter(resource => {
      const label = formatResourceLabel(resource.resource).toLowerCase();
      const original = resource.resource.toLowerCase();
      const resourceId = resource.resourceId.toLowerCase();
      const matchesResource = !query || label.includes(query) || original.includes(query) || resourceId.includes(query);
      return endpointSearch
        ? this.filteredEndpoints(resource, endpointSearch).length > 0
        : matchesResource;
    });
  }

  private get endpointSearch(): EndpointSearch | null {
    return parseEndpointSearch(this.searchQuery);
  }

  private matchesEndpointSearch(endpoint: Pick<ResourceEndpointDraft, 'method' | 'path'>, endpointSearch: EndpointSearch) {
    return endpoint.path.toLowerCase().includes(endpointSearch.path) &&
      (!endpointSearch.method || endpoint.method === endpointSearch.method);
  }

  private filteredEndpoints(resource: Resource, endpointSearch = this.endpointSearch): ResourceEndpoint[] {
    if (!endpointSearch) return resource.endpoints;

    return resource.endpoints.filter(endpoint => this.matchesEndpointSearch(endpoint, endpointSearch));
  }

  private get filteredEndpointDrafts(): EditableResourceEndpoint[] {
    const endpointSearch = parseEndpointSearch(this.endpointEditSearchQuery);
    if (!endpointSearch) return this.endpointDrafts;

    return this.endpointDrafts.filter(endpoint => this.matchesEndpointSearch(endpoint, endpointSearch));
  }

  private createEndpointDraftId() {
    this.nextEndpointDraftId += 1;
    return `endpoint-draft-${this.nextEndpointDraftId}`;
  }

  private syncExpandedEntityState(resources: Resource[] = this.resources) {
    const validResourceIds = new Set(resources.map(resource => resource.resourceId));
    const validEndpointIds = new Set(resources.flatMap(resource => resource.endpoints.map(endpoint => endpoint.id)));
    this.expandedResources = new Set([...this.expandedResources].filter(id => validResourceIds.has(id)));
    this.expandedEndpoints = new Set([...this.expandedEndpoints].filter(id => validEndpointIds.has(id)));
    this.collapsedAudiences = new Set([...this.collapsedAudiences].filter(id => validResourceIds.has(id)));
  }

  private buildSavedResource(
    resourceId: string,
    resource: string,
    audience: string[],
    endpoints: PersistedResourceEndpointPayload[],
    hasSecret: boolean,
    hasPreviousSecret: boolean,
  ): Resource {
    return {
      resourceId,
      resource,
      audience: [...audience],
      endpoints: endpoints.map(endpoint => ({
        id: endpoint.id,
        method: endpoint.method,
        path: endpoint.path,
        fetchUserInfo: endpoint.fetchUserInfo,
        allow: endpoint.allow ?? undefined,
        inject: cloneInject(endpoint.inject),
        stepUpCondition: endpoint.stepUpCondition ?? undefined,
        stepUpAcr: endpoint.stepUpAcr ?? undefined,
        maxAge: endpoint.maxAge ?? undefined,
      })),
      hasSecret,
      hasPreviousSecret,
    };
  }

  private upsertResource(resource: Resource) {
    const existingIndex = this.resources.findIndex(candidate => candidate.resourceId === resource.resourceId);
    this.resources = existingIndex === -1
      ? [resource, ...this.resources]
      : this.resources.map(candidate => candidate.resourceId === resource.resourceId ? resource : candidate);
    this.syncExpandedEntityState();
  }

  private addAudience() {
    const clientId = this.audienceToAdd;
    if (!clientId || !this.clientIds.includes(clientId) || this.resourceAudience.includes(clientId)) return;
    this.resourceAudience = [...this.resourceAudience, clientId];
    this.audienceToAdd = '';
  }

  private removeAudience(clientId: string) {
    this.resourceAudience = this.resourceAudience.filter(id => id !== clientId);
  }

  private get audienceSuggestions(): string[] {
    const query = this.audienceToAdd.trim().toLowerCase();
    if (!query) return [];
    return this.clientIds.filter(clientId =>
      !this.resourceAudience.includes(clientId) && clientId.toLowerCase().includes(query),
    );
  }

  private chooseAudienceSuggestion(clientId: string) {
    this.audienceToAdd = clientId;
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
      stepUpCondition: endpoint.stepUpCondition ?? '',
      stepUpAcr: endpoint.stepUpAcr ?? '',
      maxAge: endpoint.maxAge?.toString() ?? '',
    };
  }

  private toEndpointPayload(endpoint: EditableResourceEndpoint) {
    const allow = endpoint.allow.trim();
    const stepUpCondition = endpoint.stepUpCondition.trim();
    const stepUpAcr = endpoint.stepUpAcr.trim();
    const maxAge = parseInt(endpoint.maxAge);
    return {
      ...(endpoint.id !== null ? { id: endpoint.id } : {}),
      method: endpoint.method,
      path: endpoint.path,
      fetchUserInfo: endpoint.fetchUserInfo,
      allow: allow.length > 0 ? allow : null,
      inject: cloneInject(endpoint.inject),
      stepUpCondition: stepUpCondition.length > 0 ? stepUpCondition : null,
      stepUpAcr: stepUpAcr.length > 0 ? stepUpAcr : null,
      maxAge: Number.isFinite(maxAge) ? maxAge : null,
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

  private updateEndpointStepUpCondition(draftId: string, value: string) {
    this.updateEndpointDraft(draftId, endpoint => ({ ...endpoint, stepUpCondition: value }));
  }

  private updateEndpointStepUpAcr(draftId: string, value: string) {
    this.updateEndpointDraft(draftId, endpoint => ({ ...endpoint, stepUpAcr: value }));
  }

  private updateEndpointMaxAge(draftId: string, value: string) {
    this.updateEndpointDraft(draftId, endpoint => ({ ...endpoint, maxAge: value }));
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
            <li><span class="option-tooltip-code">request.path.params</span> — map of path parameters matched by the endpoint's <span class="option-tooltip-code">{name}</span> segments.</li>
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

  private toggleExpand(resourceId: string) {
    if (this.expandedResources.has(resourceId)) {
      this.expandedResources.delete(resourceId);
    } else {
      this.expandedResources.add(resourceId);
    }
    this.requestUpdate();
  }

  private handleCardClick(resourceId: string) {
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

  private toggleAudienceExpand(resourceId: string) {
    if (this.collapsedAudiences.has(resourceId)) {
      this.collapsedAudiences.delete(resourceId);
    } else {
      this.collapsedAudiences.add(resourceId);
    }
    this.requestUpdate();
  }

  /**
   * Permissions that grant access to this endpoint, from the reverse index.
   * Multiple permissions are alternatives, so they read as "a or b".
   */
  private renderRequiredPermissionSection(endpointId: ResourceEndpointId) {
    const permissionIds = this.permissionsByEndpoint.get(endpointId) ?? [];
    return html`
      <div class="endpoint-editor-section">
        <div class="endpoint-detail-label">Required Permission</div>
        ${permissionIds.length === 0
          ? html`<div class="endpoint-empty">— (none)</div>`
          : html`<div class="permission-alternatives">
              ${permissionIds.map((permissionId, index) => html`
                ${index > 0 ? html`<span class="permission-or">or</span>` : ''}
                <span class="permission-tag">${permissionId}</span>
              `)}
            </div>`}
      </div>
    `;
  }

  private renderAllowSection(allow: string | undefined) {
    const hasAllow = allow != null && allow.length > 0;
    return html`
      <div class="endpoint-editor-section">
        <div class="endpoint-detail-label">Allow</div>
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

  private renderStepUpSection(condition: string | undefined, acr: string | undefined) {
    return html`
      <div class="endpoint-editor-section">
        <div class="endpoint-detail-label">Step-up Condition</div>
        ${condition && condition.length > 0
          ? html`<div class="cel-inline">${renderHighlightedCel(condition)}</div>`
          : html`<div class="endpoint-empty">— (always)</div>`}
      </div>
      <div class="endpoint-editor-section">
        <div class="endpoint-detail-label">Step-up ACR</div>
        ${acr && acr.length > 0
          ? html`<div class="prefix-tags" style="margin-top: 0;">
              ${acr.split(' ').map(val => html`<span class="prefix-tag">${val}</span>`)}
            </div>`
          : html`<div class="endpoint-empty">— (any)</div>`}
      </div>
    `;
  }

  private renderMaxAgeSection(maxAge: number | undefined) {
    return html`
      <div class="endpoint-editor-section">
        <div class="endpoint-detail-label">Max Auth Age</div>
        ${maxAge != null
          ? html`<div class="endpoint-detail-value">${maxAge} seconds</div>`
          : html`<div class="endpoint-empty">— (unlimited)</div>`}
      </div>
    `;
  }

  private renderEndpointDetails(endpoint: ResourceEndpoint) {
    const hasStepUp = (endpoint.stepUpCondition != null && endpoint.stepUpCondition.length > 0)
      || (endpoint.stepUpAcr != null && endpoint.stepUpAcr.length > 0);
    const hasMaxAge = endpoint.maxAge != null;

    return html`
      <div class="endpoint-card-details">
        <div class="fetch-row">
          <span class="fetch-row-label">Fetch Userinfo</span>
          <span class=${`fetch-indicator ${endpoint.fetchUserInfo ? 'fetch-enabled' : 'fetch-disabled'}`}>
            <span class="fetch-indicator-box">✓</span>
          </span>
        </div>
        <div class="endpoint-detail-grid">
          ${this.renderRequiredPermissionSection(endpoint.id)}
          ${this.renderAllowSection(endpoint.allow)}
          ${hasStepUp ? this.renderStepUpSection(endpoint.stepUpCondition, endpoint.stepUpAcr) : ''}
          ${hasMaxAge ? this.renderMaxAgeSection(endpoint.maxAge) : ''}
          ${this.renderInjectSection(endpoint.inject)}
        </div>
      </div>
    `;
  }

  private renderResourceAudience(resource: Resource) {
    const audienceExpanded = !this.collapsedAudiences.has(resource.resourceId);
    return html`
      <div class="resource-audience" @click=${(event: Event) => event.stopPropagation()}>
        <button
          type="button"
          class="resource-section-trigger"
          aria-expanded=${audienceExpanded ? 'true' : 'false'}
          aria-label=${`${audienceExpanded ? 'Collapse' : 'Expand'} audience ${resource.resourceId}`}
          @click=${() => this.toggleAudienceExpand(resource.resourceId)}
        >
          <span class="resource-section-chevron">${audienceExpanded ? '▼' : '▶'}</span>
          <span>Audience</span>
        </button>
        ${audienceExpanded ? html`
          ${resource.audience.length > 0 ? html`
            <div class="audience-view-list" aria-label=${`Audience for ${resource.resourceId}`}>
              ${resource.audience.map(clientId => html`<span class="audience-view-item">${clientId}</span>`)}
            </div>
          ` : html`<div class="endpoint-empty">No clients are assigned to this resource.</div>`}
        ` : ''}
      </div>
    `;
  }

  private renderResourceEndpoints(resource: Resource) {
    const endpoints = this.filteredEndpoints(resource);
    if (endpoints.length === 0) {
      return html`<div class="status">No endpoints yet.</div>`;
    }

    return html`
      <div class="endpoint-list" @click=${(event: Event) => event.stopPropagation()}>
        ${endpoints.map(endpoint => {
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

  private renderStepUpEditor(draftId: string, condition: string, acr: string) {
    const vocabularyAcrs = Object.keys(this.acrVocabulary);
    const selectedAcrs = acr.split(' ').filter(v => v.length > 0);

    return html`
      <div class="endpoint-editor-section">
        <div class="editor-section-header">
          <div class="editor-section-title-row">
            <h3 class="editor-section-title">Step-up Condition (CEL)</h3>
            ${this.renderOptionInfo(
              `${draftId}-stepup-cond-info`,
              'Step-up Condition',
              html`
                <p>CEL boolean expression evaluated per request. The step-up ACR is required only when this evaluates to <code>true</code>.</p>
                <p>Leave empty to disable the ACR requirement entirely. Use <code>true</code> to always enforce it.</p>
                <p><strong>Note:</strong> Max Auth Age is enforced independently, regardless of this condition.</p>
              `,
              'Step-up condition info',
            )}
          </div>
        </div>
        <versola-cel-editor
          multiline
          rows="1"
          .value=${condition}
          ?disabled=${this.saving}
          placeholder="e.g. true or request.body.amount > 1000"
          aria-label="Step-up condition"
          @cel-input=${(event: CustomEvent<{ value: string }>) => this.updateEndpointStepUpCondition(draftId, event.detail.value)}
        ></versola-cel-editor>
      </div>

      <div class="endpoint-editor-section">
        <div class="editor-section-header">
          <div class="editor-section-title-row">
            <h3 class="editor-section-title">Step-up ACR</h3>
            ${this.renderOptionInfo(
              `${draftId}-stepup-acr-info`,
              'Step-up ACR',
              html`
                <p>Space-separated list of ACR values. The Edge will enforce that the access token carries at least one of these values in its <code>acr</code> claim when step-up is triggered.</p>
              `,
              'Step-up ACR info',
            )}
          </div>
        </div>
        ${vocabularyAcrs.length > 0 ? html`
          <div style="display: flex; gap: var(--spacing-md); flex-wrap: wrap;">
            ${vocabularyAcrs.map(val => html`
              <label style="display: flex; align-items: center; gap: var(--spacing-xs); font-size: 0.875rem; cursor: pointer;">
                <input type="checkbox"
                  .checked=${selectedAcrs.includes(val)}
                  @change=${(e: Event) => {
                    const checked = (e.target as HTMLInputElement).checked;
                    const next = checked
                      ? [...selectedAcrs, val]
                      : selectedAcrs.filter(v => v !== val);
                    this.updateEndpointStepUpAcr(draftId, next.join(' '));
                  }} />
                ${val}
              </label>
            `)}
          </div>
        ` : html`
          <div class="endpoint-empty">No ACR values registered. Configure them in <strong>Challenge Settings</strong>.</div>
        `}
      </div>
    `;
  }

  private renderMaxAgeEditor(draftId: string, maxAge: string) {
    return html`
      <div class="endpoint-editor-section">
        <div class="editor-section-header">
          <div class="editor-section-title-row">
            <h3 class="editor-section-title">Max Auth Age (seconds)</h3>
            ${this.renderOptionInfo(
              `${draftId}-max-age-info`,
              'Max Auth Age',
              html`
                <p>The Edge will enforce that the authentication event happened no longer than this many seconds ago, based on the <code>auth_time</code> claim in the token.</p>
                <p>Leave empty to disable this check.</p>
              `,
              'Max auth age info',
            )}
          </div>
        </div>
        <input
          class="form-input compact-input"
          type="number"
          .value=${maxAge}
          @input=${(event: Event) => this.updateEndpointMaxAge(draftId, (event.target as HTMLInputElement).value)}
          placeholder="e.g. 3600"
          min="0"
        />
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
            ${this.renderStepUpEditor(endpoint.draftId, endpoint.stepUpCondition, endpoint.stepUpAcr)}
            ${this.renderMaxAgeEditor(endpoint.draftId, endpoint.maxAge)}
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
    const activeResource = isEditResource
      ? this.resources.find(resource => resource.resourceId === this.activeResourceId)
      : undefined;

    return html`
      <div class="form-header">
        <div class="form-header-lead">
          <versola-nav-toggle></versola-nav-toggle>
          <div class="title-stack">
            <h1 class="form-title">${title}</h1>
            ${isEditResource ? html`<div class="entity-id-meta">${this.resourceUri || '—'}</div>` : ''}
          </div>
        </div>
      </div>
      <div class="card">
        <form @submit=${this.saveResource}>
          <div class="form-grid">
            <div class="form-group">
              <label class="form-label">Resource ID</label>
              <div class="input-with-info">
                <input class="form-input compact-input ${this.isResourceIdInvalid ? 'input-error' : ''}" type="text" aria-label="Resource ID" .value=${this.resourceId} @input=${(e: Event) => this.resourceId = (e.target as HTMLInputElement).value} placeholder="users-api" ?disabled=${this.saving || isEditResource} required />
                ${this.renderOptionInfo(
                  'resource-id-info',
                  'Resource ID',
                  'Used by the edge to route incoming user-agent requests to this resource. Requests to /resources/{resourceId}/* are forwarded to the configured resource URI. It is immutable after creation.',
                  'Resource ID info',
                )}
              </div>
            </div>
            <div class="form-group">
              <label class="form-label">Absolute resource URI</label>
                  <input class="form-input compact-input ${this.isResourceUriInvalid ? 'input-error' : ''}" type="url" aria-label="Absolute resource URI" .value=${this.resourceUri} @input=${(e: Event) => this.resourceUri = (e.target as HTMLInputElement).value} placeholder="https://api.example.com" ?disabled=${this.saving} required />
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">Audience</label>
            <div class="audience-add-row">
              <div class="audience-input-wrap">
                <input
                  class="form-input compact-input"
                  type="text"
                  aria-label="Audience client"
                  placeholder="Type or select a client"
                  .value=${this.audienceToAdd}
                  ?disabled=${this.saving || this.clientIds.length === 0}
                  @input=${(event: Event) => this.audienceToAdd = (event.target as HTMLInputElement).value}
                />
                ${this.audienceSuggestions.length > 0 ? html`
                  <div class="audience-suggestions" role="listbox" aria-label="Available clients">
                    ${this.audienceSuggestions.map(clientId => html`
                      <button
                        type="button"
                        class="audience-suggestion"
                        role="option"
                        @click=${() => this.chooseAudienceSuggestion(clientId)}
                      >${clientId}</button>
                    `)}
                  </div>
                ` : ''}
              </div>
              <button
                type="button"
                class="btn btn-secondary btn-sm"
                ?disabled=${this.saving || !this.clientIds.includes(this.audienceToAdd) || this.resourceAudience.includes(this.audienceToAdd)}
                @click=${() => this.addAudience()}
              >Add audience</button>
            </div>
            ${this.resourceAudience.length > 0 ? html`
              <div class="audience-list" aria-label="Selected resource audience">
                ${this.resourceAudience.map(clientId => html`
                  <div class="audience-item">
                    <span>${clientId}</span>
                    <button
                      type="button"
                      class="icon-action danger"
                      ?disabled=${this.saving}
                      aria-label=${`Remove audience ${clientId}`}
                      title=${`Remove audience ${clientId}`}
                      @click=${() => this.removeAudience(clientId)}
                    >✕</button>
                  </div>
                `)}
              </div>
            ` : html`<div class="status audience-empty">No clients are selected for this resource.</div>`}
            ${this.clientIds.length === 0 ? html`<div class="hint">No clients are configured for this tenant.</div>` : ''}
            <div class="hint">Clients allowed to request this resource and receive its URI in their access-token audience.</div>
          </div>
          ${!isEditResource ? html`
            <div class="form-group">
              <div class="editor-section-title-row">
                <label class="form-label" style="margin-bottom: 0;">Resource Type</label>
                ${this.renderOptionInfo(
                  'resource-type-info',
                  'Resource Type',
                  html`
                    <p><strong>Internal</strong> resources get a generated secret. Edge authenticates to them on the caller's behalf using that secret (HTTP Basic auth) instead of forwarding the caller's own access token.</p>
                    <p><strong>Public</strong> resources have no secret. Edge forwards the caller's original access token as-is, so the resource itself is responsible for validating it.</p>
                  `,
                  'Resource type info',
                )}
              </div>
              <div class="cred-mode-cards">
                <button
                  type="button"
                  class=${`cred-mode-card ${!this.resourceInternal ? 'selected' : ''}`}
                  ?disabled=${this.saving}
                  @click=${() => this.resourceInternal = false}
                >public</button>
                <button
                  type="button"
                  class=${`cred-mode-card ${this.resourceInternal ? 'selected' : ''}`}
                  ?disabled=${this.saving}
                  @click=${() => this.resourceInternal = true}
                >internal</button>
              </div>
              <div class="hint">Choose whether edge authenticates to this resource with its own secret (internal) or forwards the caller's token (public).</div>
            </div>
          ` : ''}
          <div class="section-header">
            <h2 class="section-title">Endpoints</h2>
            <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.saving} @click=${() => this.startCreateEndpoint()}>Add endpoint</button>
          </div>
          ${this.endpointDrafts.length === 0 ? html`<div class="status">No endpoints yet.</div>` : html`
            ${isEditResource ? html`
              <div class="search-bar">
                <label class="form-label" for="endpoint-edit-search">Filter endpoints</label>
                <input id="endpoint-edit-search" class="form-input" type="search" aria-label="Filter endpoints" .value=${this.endpointEditSearchQuery} @input=${(e: Event) => this.endpointEditSearchQuery = (e.target as HTMLInputElement).value} placeholder="/path or METHOD /path" />
              </div>
            ` : ''}
            ${this.filteredEndpointDrafts.length === 0 ? html`<div class="status">No endpoints match your search.</div>` : html`
              <div class="endpoint-list">
                ${this.filteredEndpointDrafts.map(endpoint => this.renderEditableEndpoint(endpoint))}
              </div>
            `}
          `}
          <div class="form-actions">
            ${activeResource && this.canManage && activeResource.hasSecret ? html`
              ${activeResource.hasPreviousSecret ? html`
                <button
                  type="button"
                  class="btn btn-secondary btn-sm secondary-action-button"
                  @click=${() => this.handleActivateNewSecret(activeResource)}
                  ?disabled=${this.secretActionResourceId !== null}
                  title="Activate new secret"
                  aria-label="Activate new secret"
                >Activate new secret</button>
              ` : html`
                <button
                  type="button"
                  class="btn btn-secondary secondary-action-button"
                  @click=${() => this.handleRotateSecret(activeResource)}
                  ?disabled=${this.secretActionResourceId !== null}
                >${this.secretActionResourceId === activeResource.resourceId ? 'Rotating…' : 'Rotate Secret'}</button>
              `}
            ` : ''}
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

    const secretTitle = this.createdSecret?.action === 'rotated'
      ? `Secret rotated: ${this.createdSecret.resourceId}`
      : this.createdSecret
        ? `Resource created: ${this.createdSecret.resourceId}`
        : '';
    const secretText = this.createdSecret?.action === 'rotated'
      ? 'Copy the new resource secret now. It may not be shown again.'
      : 'Copy this secret now. It may not be shown again.';

    return html`
      <content-header title="Resources">
        ${this.resources.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" @click=${() => this.openCreateResourceForm()}>+ Create Resource</button>
        ` : ''}
      </content-header>
      ${this.createdSecret ? html`
        <div class="card secret-banner">
          <div class="secret-banner-header">
            <div>
              <h3 class="secret-banner-title">${secretTitle}</h3>
              <p class="secret-banner-text">${secretText}</p>
            </div>
            <button class="btn btn-ghost btn-sm" @click=${this.dismissCreatedSecret}>Dismiss</button>
          </div>
          <pre class="secret-value">${this.createdSecret.secret}</pre>
          <div class="secret-banner-actions">
            <button class="btn btn-primary btn-sm" @click=${this.handleCopySecret}>Copy secret</button>
            ${this.copyFeedback ? html`<span class="copy-feedback">${this.copyFeedback}</span>` : ''}
          </div>
        </div>
      ` : ''}
      ${this.loading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
      : this.error ? html`
        <versola-error-card heading="Could not load resources" .message=${this.error} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.resources.length === 0 ? html`
        <div class="card">
          <div class="empty-state">
            <h3>No resources yet</h3>
            <p>Create your first resource to get started</p>
            ${this.canManage ? html`
            <button class="btn btn-primary" @click=${() => this.openCreateResourceForm()} style="margin-top: 1rem;">
              + Create Resource
            </button>` : ''}
          </div>
        </div>
      ` : html`
        <div class="search-bar">
          <label class="form-label" for="resource-search">Search resources or endpoints</label>
          <input id="resource-search" class="form-input" type="search" aria-label="Search resources or endpoints" .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} placeholder="Alias, URI, /path, or METHOD /path" />
        </div>
        ${this.filteredResources.length === 0 ? html`
          <div class="card">
            <div class="empty-state">
              <h3>No resources or endpoints match your search</h3>
            </div>
          </div>
        ` : html`
          <div class="stack">${this.filteredResources.map(resource => {
            const isExpanded = this.expandedResources.has(resource.resourceId);
            return html`<div class="card resource-shell" @click=${() => this.handleCardClick(resource.resourceId)}><div class="card-body resource-card">
            <div class="resource-header">
              <div class="resource-label-card">
                <div class="resource-label">${formatResourceLabel(resource.resource)}</div>
                <span class="resource-id-badge" title="Resource ID">${resource.resourceId}</span>
                ${resource.hasSecret ? html`<span class="badge badge-success">Internal</span>` : ''}
                ${resource.hasPreviousSecret ? html`<span class="badge badge-warning">Secret Rotation</span>` : ''}
              </div>
              ${this.canManage ? html`
              <div class="resource-actions" @click=${(e: Event) => e.stopPropagation()}>
                <button class="icon-action" @click=${() => this.openEditResourceForm(resource)} title="Edit resource" aria-label=${`Edit resource ${resource.resourceId}`}>✎</button>
                <button class="icon-action danger" @click=${() => this.removeResource(resource)} title="Delete resource" aria-label=${`Delete resource ${resource.resourceId}`}>✕</button>
              </div>` : ''}
            </div>
            ${isExpanded ? this.renderResourceAudience(resource) : ''}
            ${isExpanded ? this.renderResourceEndpoints(resource) : ''}
          </div></div>`;
          })}</div>
        `}
      `}
    `;
  }
}

declare global { interface HTMLElementTagNameMap { 'versola-resources-list': VersolaResourcesList; } }
