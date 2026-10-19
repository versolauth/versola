import { LitElement, html, css, nothing, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, editorStyles, formStyles, methodBadgeStyles, tagStyles, toggleStyles } from '../styles/components';
import type { InjectRule, InjectTarget, ResourceEndpoint, ResourceEndpointId } from '../types';
import type { SaveResourceEndpointPayload } from '../utils/central-api';
import { validateCel } from '../utils/cel-validator';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { splitDuration } from '../utils/duration';
import { statusIcon } from './status-icon';
import { breakPath } from './break-text';
import { acrValues, methodBadge } from './resource-endpoints-table';
import './cel-editor';
import './nav-toggle';

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;
const INJECT_TARGETS: InjectTarget[] = ['header', 'query', 'body'];

/** An endpoint being edited. `baseline` is what the server has (null for one added here). */
type Draft = {
  key: string;
  id: ResourceEndpointId | null;
  method: string;
  path: string;
  fetchUserInfo: boolean;
  allow: string;
  inject: InjectRule[];
  stepUpCondition: string;
  stepUpAcr: string;
  maxAge: string;
  baseline: string | null;
};

type Status = 'new' | 'edited' | 'same';
type Problem = { draftKey: string; message: string };

export type EndpointsSaveDetail = {
  deleteEndpoints: ResourceEndpointId[];
  createEndpoints: SaveResourceEndpointPayload[];
};

const PATH_SEGMENT = '([a-zA-Z0-9-]+|\\{[a-zA-Z_][a-zA-Z0-9_]*\\})';
const PATH_PATTERN = new RegExp(`^\\/(${PATH_SEGMENT}(\\/${PATH_SEGMENT})*)?$`);
const PATH_RULE = 'Start with "/"; each segment is latin letters, digits and "-", or one {name}, with no consecutive "/" and no repeated names.';

export function endpointLabel(endpoint: { method: string; path: string }) {
  return `${endpoint.method} ${endpoint.path}`;
}

function isPathInvalid(path: string): boolean {
  const trimmed = path.trim();
  if (!PATH_PATTERN.test(trimmed)) return true;
  const params = trimmed.split('/').filter(part => part.startsWith('{'));
  return new Set(params).size !== params.length;
}

/** Two endpoints of one method whose paths differ only in parameter names match the same requests. */
function pathShape(path: string): string {
  return path.trim().split('/').map(part => (part.startsWith('{') ? '{}' : part)).join('/');
}

function cloneInject(rules: InjectRule[]): InjectRule[] {
  return rules.map(rule => ({ ...rule }));
}

function snapshot(draft: Pick<Draft, 'method' | 'path' | 'fetchUserInfo' | 'allow' | 'inject' | 'stepUpCondition' | 'stepUpAcr' | 'maxAge'>): string {
  return JSON.stringify([
    draft.method, draft.path.trim(), draft.fetchUserInfo, draft.allow.trim(),
    draft.inject.map(rule => [rule.target, rule.name.trim(), rule.expression.trim()]),
    draft.stepUpCondition.trim(), draft.stepUpAcr.trim().split(/\s+/).filter(Boolean).join(' '), draft.maxAge.trim(),
  ]);
}

function toDraft(endpoint: ResourceEndpoint): Draft {
  const draft: Draft = {
    key: `endpoint-${endpoint.id}`,
    id: endpoint.id,
    method: endpoint.method,
    path: endpoint.path,
    fetchUserInfo: endpoint.fetchUserInfo,
    allow: endpoint.allow ?? '',
    inject: cloneInject(endpoint.inject),
    stepUpCondition: endpoint.stepUpCondition ?? '',
    stepUpAcr: endpoint.stepUpAcr ?? '',
    maxAge: endpoint.maxAge?.toString() ?? '',
    baseline: null,
  };
  draft.baseline = snapshot(draft);
  return draft;
}

function toPayload(draft: Draft): SaveResourceEndpointPayload {
  const allow = draft.allow.trim();
  const condition = draft.stepUpCondition.trim();
  const acr = acrValues(draft.stepUpAcr).join(' ');
  const maxAge = parseInt(draft.maxAge, 10);
  return {
    ...(draft.id !== null ? { id: draft.id } : {}),
    method: draft.method,
    path: draft.path.trim(),
    fetchUserInfo: draft.fetchUserInfo,
    allow: allow.length > 0 ? allow : null,
    inject: draft.inject.map(rule => ({ target: rule.target, name: rule.name.trim(), expression: rule.expression })),
    stepUpCondition: condition.length > 0 ? condition : null,
    stepUpAcr: acr.length > 0 ? acr : null,
    maxAge: Number.isFinite(maxAge) ? maxAge : null,
  };
}

type EndpointFilter = { method?: string; path: string };

/** `/path` or `METHOD /path`; anything else is plain text matched against `METHOD /path`. */
function parseFilter(query: string): EndpointFilter | { text: string } | null {
  const normalized = query.trim();
  if (!normalized) return null;
  if (normalized.startsWith('/')) return { path: normalized.toLowerCase() };
  const methodAndPath = /^(GET|POST|PUT|PATCH|DELETE)\s+(\/.*)$/i.exec(normalized);
  return methodAndPath ? { method: methodAndPath[1].toUpperCase(), path: methodAndPath[2].toLowerCase() } : { text: normalized.toLowerCase() };
}

/**
 * The Edit endpoints page of a resource: master / detail. The list on the left holds every
 * endpoint with its method and path, a marker for what has not been saved (`new`, `edited`) and
 * which rules it carries (`allow`, `step-up`, `inject`); the panels on the right edit the selected one
 * in the order the record's table reads: Request, Who may call, Step-up, Inject. Adding, editing and
 * removing only change drafts; `Save endpoints` sends what changed in one request (the removed ids and
 * the new and edited endpoints; an edited one keeps its id, so the permissions linked to it stay).
 * Required permissions are read-only here: they are linked from Permissions.
 */
@customElement('versola-resource-endpoints-editor')
export class VersolaResourceEndpointsEditor extends LitElement {
  @property({ attribute: false }) endpoints: ResourceEndpoint[] = [];
  @property({ type: String }) resourceId = '';
  @property({ type: String }) resourceUri = '';
  /** Endpoint id -> the permissions that grant it. */
  @property({ attribute: false }) permissionsByEndpoint: Map<ResourceEndpointId, string[]> = new Map();
  /** ACR value -> the factors it stands for (Security › Step-up); the choices of the step-up panel. */
  @property({ attribute: false }) acrVocabulary: Record<string, string[]> = {};
  @property({ type: Boolean }) saving = false;
  /** What the server answered a save with. */
  @property({ type: String }) error = '';

  @state() private drafts: Draft[] = [];
  @state() private removed: Array<{ id: ResourceEndpointId; label: string }> = [];
  @state() private selectedKey: string | null = null;
  @state() private filter = '';
  @state() private problem: Problem | null = null;
  @state() private infoOpen = false;
  private initializedFor: ResourceEndpoint[] | null = null;
  private nextNew = 0;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    editorStyles,
    toggleStyles,
    tagStyles,
    methodBadgeStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
      }

      *,
      *::before,
      *::after {
        box-sizing: border-box;
      }

      .form-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: var(--spacing-xl);
      }

      .form-title {
        font-size: var(--text-4xl);
        font-weight: 800;
        letter-spacing: -0.02em;
        color: var(--text-primary);
        margin: 0;
      }

      .entity-id-meta .host {
        color: var(--text-secondary);
        font-family: var(--font-family);
        font-size: var(--text-base);
        font-weight: 400;
      }

      .split {
        padding: 0;
        display: grid;
        grid-template-columns: minmax(16rem, 22rem) minmax(0, 1fr);
        overflow: hidden;
      }

      .master {
        border-right: 1px solid var(--border-default);
        display: flex;
        flex-direction: column;
        min-width: 0;
      }

      .master-tools {
        display: flex;
        gap: var(--spacing-sm);
        align-items: center;
        padding: var(--spacing-md);
        border-bottom: 1px solid var(--border-default);
      }

      .master-tools input {
        flex: 1;
        min-width: 0;
        padding-block: 0.5rem;
      }

      .master-list {
        list-style: none;
        margin: 0;
        padding: 0;
        max-height: min(46rem, 75vh);
        overflow-y: auto;
      }

      .master-list li + li {
        border-top: 1px solid var(--border-subtle);
      }

      .ep-row {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr) auto;
        gap: 0.25rem 0.625rem;
        align-items: start;
        width: 100%;
        padding: 0.75rem var(--spacing-md);
        border: 0;
        border-left: 3px solid transparent;
        background: none;
        color: inherit;
        font: inherit;
        text-align: left;
        cursor: pointer;
      }

      .ep-row:hover {
        background: var(--surface-inset);
      }

      .ep-row[aria-current='true'] {
        background: var(--accent-subtle);
        border-left-color: var(--accent);
      }

      .ep-row:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: -2px;
      }

      .ep-path {
        font-family: var(--font-mono);
        font-size: var(--text-base);
        font-weight: 600;
        overflow-wrap: break-word;
        color: var(--text-primary);
      }

      .ep-rules {
        grid-column: 2 / -1;
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem;
      }

      .mini {
        display: inline-flex;
        padding: 0.0625rem 0.5rem;
        border: 1px solid var(--border-default);
        border-radius: var(--radius-pill);
        font-size: var(--text-xs);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--text-secondary);
        background: var(--surface);
      }

      .marker {
        align-self: start;
        padding: 0.0625rem 0.5rem;
        border: 1px solid var(--warning);
        border-radius: var(--radius-pill);
        font-size: var(--text-xs);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--text-primary);
      }

      .marker.new {
        border-color: var(--success);
      }

      .marker.fix {
        border-color: var(--danger);
      }

      .master-empty {
        padding: var(--spacing-lg) var(--spacing-md);
        color: var(--text-secondary);
        font-size: var(--text-sm);
      }

      .detail {
        padding: var(--spacing-md) var(--spacing-lg) var(--spacing-lg);
        min-width: 0;
      }

      .detail-head {
        flex-wrap: wrap;
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: var(--spacing-md);
        margin-bottom: var(--spacing-md);
      }

      .detail-title {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.25rem 0.625rem;
        min-width: 0;
        margin: 0;
        font-size: var(--text-lg);
        font-weight: 700;
      }

      .detail-title .ep-path {
        font-size: var(--text-lg);
      }

      .detail-empty {
        padding: var(--spacing-xl) var(--spacing-md);
        color: var(--text-secondary);
        text-align: center;
      }

      .kicker {
        display: block;
        margin-bottom: var(--spacing-sm);
        font-size: var(--text-xs);
        font-weight: 700;
        color: var(--text-secondary);
        text-transform: uppercase;
        letter-spacing: 0.08em;
      }

      .req-grid {
        display: grid;
        grid-template-columns: 10rem minmax(0, 1fr);
        gap: var(--spacing-md);
      }

      .req-grid .form-group,
      .detail .form-group {
        min-width: 0;
        margin-bottom: var(--spacing-md);
      }

      .detail .editor-box .form-group:last-child {
        margin-bottom: 0;
      }

      input.mono {
        font-family: var(--font-mono);
      }

      code {
        font-family: var(--font-mono);
        font-size: 0.95em;
      }

      .perm-line {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.375rem 0.5rem;
      }

      .or {
        font-size: var(--text-xs);
        color: var(--text-secondary);
      }

      .panel-head {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: var(--spacing-md);
      }

      .label-row {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        margin-bottom: var(--spacing-sm);
      }

      .label-row .kicker {
        margin: 0;
      }

      .acr-list {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm) var(--spacing-lg);
      }

      .acr-list label {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        margin: 0;
        min-height: 1.5rem;
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        font-weight: 400;
        letter-spacing: 0;
        text-transform: none;
        color: var(--text-primary);
        cursor: pointer;
      }

      .acr-list input {
        width: 1rem;
        height: 1rem;
        margin: 0;
        accent-color: var(--accent);
      }

      .acr-list input:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      .inject-row {
        display: grid;
        grid-template-columns: 8rem minmax(0, 12rem) minmax(0, 1fr) auto;
        gap: var(--spacing-sm) var(--spacing-md);
        align-items: start;
        padding-top: var(--spacing-md);
      }

      .inject-row + .inject-row {
        margin-top: var(--spacing-md);
        border-top: 1px solid var(--border-subtle);
      }

      .inject-row .form-group {
        margin-bottom: 0;
      }

      .icon-x {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 1.75rem;
        min-height: 1.75rem;
        margin-top: 1.5rem;
        border: 0;
        border-radius: var(--radius-sm);
        background: none;
        color: var(--text-secondary);
        cursor: pointer;
      }

      .icon-x:hover {
        color: var(--danger);
      }

      .icon-x:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      .muted {
        color: var(--text-secondary);
        font-size: var(--text-sm);
      }

      .bar {
        display: flex;
        flex-wrap: wrap;
        justify-content: space-between;
        align-items: center;
        gap: var(--spacing-md);
        margin-top: var(--spacing-lg);
        padding-top: var(--spacing-lg);
        border-top: 1px solid var(--border-default);
      }

      .bar-note {
        flex: 1 1 18rem;
        min-width: 0;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .bar-note b {
        font-family: var(--font-mono);
        font-weight: 600;
        color: var(--text-primary);
        overflow-wrap: anywhere;
      }

      .bar-actions {
        display: flex;
        gap: var(--spacing-md);
      }

      /* The (i) next to Allow and its reference of variables, operators and macros. */
      .option-info {
        position: relative;
        display: inline-flex;
        align-items: center;
        flex: none;
      }

      .option-info-button {
        flex: none;
        min-width: 1.5rem;
        min-height: 1.5rem;
        border: 1px solid var(--accent-border);
        border-radius: var(--radius-pill);
        background: var(--accent-subtle);
        color: var(--accent-text);
        font-size: var(--text-xs);
        font-weight: 700;
        line-height: 1;
        padding: 0.25rem 0.5rem;
        cursor: pointer;
        font-family: var(--font-family);
      }

      .option-info-button:hover {
        border-color: var(--accent);
      }

      .option-info-button:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      .option-tooltip {
        position: absolute;
        left: 0;
        top: calc(100% + 0.4rem);
        z-index: var(--z-dropdown);
        min-width: min(18rem, 80vw);
        max-width: min(28rem, 85vw);
        padding: 0.75rem;
        border: 1px solid var(--accent-border);
        border-radius: var(--radius-inner);
        background: var(--surface-raised);
        box-shadow: var(--shadow-card);
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
      }

      .option-tooltip-title {
        margin-bottom: 0.5rem;
        color: var(--accent);
        font-size: var(--text-sm);
        font-weight: 600;
      }

      .option-tooltip-copy {
        color: var(--text-primary);
        font-size: var(--text-xs);
        line-height: 1.45;
      }

      .option-tooltip-copy p {
        margin: 0 0 0.5rem;
      }

      .option-tooltip-section-title {
        margin: 0.625rem 0 0.25rem;
        color: var(--text-secondary);
        font-size: var(--text-xs);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.08em;
      }

      .option-tooltip-list {
        margin: 0;
        padding-left: 1rem;
        display: grid;
        gap: 0.2rem;
      }

      .option-tooltip-code {
        font-family: var(--font-mono);
        font-size: var(--text-xs);
        color: var(--accent-text);
        background: var(--accent-subtle);
        padding: 0.05rem 0.3rem;
        border-radius: var(--radius-sm);
      }

      .option-tooltip-pre {
        margin: 0.25rem 0 0;
        padding: 0.5rem 0.625rem;
        border-radius: var(--radius-sm);
        background: var(--surface-inset);
        border: 1px solid var(--border-default);
        font-family: var(--font-mono);
        font-size: var(--text-xs);
        color: var(--text-primary);
        white-space: pre-wrap;
        overflow-wrap: anywhere;
      }

      @media (max-width: 900px) {
        .split {
          grid-template-columns: minmax(0, 1fr);
        }

        .master {
          border-right: 0;
          border-bottom: 1px solid var(--border-default);
        }

        .master-list {
          max-height: 16rem;
        }

        .inject-row {
          grid-template-columns: minmax(0, 1fr);
        }

        .icon-x {
          margin-top: 0;
          justify-self: end;
        }

        .req-grid {
          grid-template-columns: minmax(0, 1fr);
        }

        .detail {
          padding-inline: var(--spacing-md);
        }
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    document.addEventListener('click', this.closeInfo);
  }

  disconnectedCallback() {
    document.removeEventListener('click', this.closeInfo);
    super.disconnectedCallback();
  }

  private closeInfo = () => { this.infoOpen = false; };

  willUpdate(changed: Map<PropertyKey, unknown>) {
    if (changed.has('endpoints') && this.initializedFor !== this.endpoints) {
      this.initializedFor = this.endpoints;
      this.drafts = this.endpoints.map(toDraft);
      this.removed = [];
      this.selectedKey = this.drafts[0]?.key ?? null;
      this.problem = null;
    }
  }

  // ── drafts ──────────────────────────────────────────────────────────────

  private statusOf(draft: Draft): Status {
    if (draft.baseline === null) return 'new';
    return snapshot(draft) === draft.baseline ? 'same' : 'edited';
  }

  private get dirty(): boolean {
    return this.removed.length > 0 || this.drafts.some(draft => this.statusOf(draft) !== 'same');
  }

  private get selected(): Draft | null {
    return this.drafts.find(draft => draft.key === this.selectedKey) ?? null;
  }

  private patch(key: string, change: Partial<Draft>) {
    this.drafts = this.drafts.map(draft => (draft.key === key ? { ...draft, ...change } : draft));
    if (this.problem?.draftKey === key) this.problem = null;
  }

  private patchInject(key: string, index: number, change: Partial<InjectRule>) {
    const draft = this.drafts.find(candidate => candidate.key === key);
    if (!draft) return;
    this.patch(key, { inject: draft.inject.map((rule, i) => (i === index ? { ...rule, ...change } : rule)) });
  }

  private async add() {
    const draft: Draft = {
      key: `new-${++this.nextNew}`, id: null, method: 'GET', path: '/', fetchUserInfo: false, allow: '',
      inject: [], stepUpCondition: '', stepUpAcr: '', maxAge: '', baseline: null,
    };
    this.drafts = [...this.drafts, draft];
    this.filter = '';
    this.selectedKey = draft.key;
    await this.updateComplete;
    const path = this.renderRoot.querySelector<HTMLInputElement>('#endpoint-path');
    path?.focus();
    path?.select();
  }

  private async removeEndpoint(draft: Draft) {
    if (draft.id !== null) {
      const permissions = this.permissionsByEndpoint.get(draft.id) ?? [];
      const detach = permissions.length
        ? ` ${permissions.join(', ')} ${permissions.length === 1 ? 'loses' : 'lose'} it and shows it as detached until you link another endpoint.`
        : '';
      const confirmed = await confirmDestructiveAction({
        title: 'Remove endpoint',
        messagePrefix: 'Remove endpoint ',
        messageSubject: endpointLabel(draft),
        messageSuffix: ` from this resource?${detach} Nothing changes until you save.`,
        confirmLabel: 'Remove endpoint',
      });
      if (!confirmed) return;
      this.removed = [...this.removed, { id: draft.id, label: endpointLabel(draft) }];
    }
    const index = this.drafts.findIndex(candidate => candidate.key === draft.key);
    this.drafts = this.drafts.filter(candidate => candidate.key !== draft.key);
    this.selectedKey = this.drafts[Math.min(index, this.drafts.length - 1)]?.key ?? null;
    this.problem = null;
  }

  private async cancel() {
    if (this.dirty && !await confirmDiscardDraft()) return;
    this.dispatchEvent(new CustomEvent('cancel', { bubbles: true, composed: true }));
  }

  // ── validation ──────────────────────────────────────────────────────────

  private pathError(draft: Draft): string {
    return isPathInvalid(draft.path) ? PATH_RULE : '';
  }

  private clashError(draft: Draft): string {
    const index = this.drafts.indexOf(draft);
    for (const earlier of this.drafts.slice(0, index)) {
      if (earlier.method !== draft.method || pathShape(earlier.path) !== pathShape(draft.path)) continue;
      return earlier.path.trim() === draft.path.trim()
        ? `Another endpoint is already ${endpointLabel(draft)}.`
        : 'Differs from another endpoint of the same method only in its parameter names, so edge could not tell them apart.';
    }
    return '';
  }

  private maxAgeError(draft: Draft): string {
    return draft.maxAge.trim() && !/^\d+$/.test(draft.maxAge.trim()) ? 'Whole seconds, 0 or more.' : '';
  }

  private injectErrors(draft: Draft): string[] {
    return draft.inject.map(rule => (rule.name.trim() ? '' : 'A name is required.'));
  }

  /** The first thing that blocks saving, in the wording the page has always used. */
  private findProblem(): Problem | null {
    for (const draft of this.drafts) {
      const label = endpointLabel(draft);
      if (this.pathError(draft)) {
        return { draftKey: draft.key, message: `Endpoint path must start with "/" and contain only latin letters, digits, "-", or a "{name}" parameter per segment (no consecutive "/", no repeated parameter names): ${label}` };
      }
    }
    for (const draft of this.drafts) {
      const label = endpointLabel(draft);
      if (this.clashError(draft)) {
        return { draftKey: draft.key, message: this.clashError(draft).startsWith('Another')
          ? `Two endpoints are ${label}: an endpoint is one method and one path`
          : `Endpoint path differs from another endpoint of the same method only in its parameter names: ${label}` };
      }
      if (draft.allow.trim()) {
        const result = validateCel(draft.allow);
        if (!result.valid) return { draftKey: draft.key, message: `Invalid allow expression in ${label}: ${result.error.message}` };
      }
      if (draft.stepUpCondition.trim()) {
        const result = validateCel(draft.stepUpCondition);
        if (!result.valid) return { draftKey: draft.key, message: `Invalid step-up condition in ${label}: ${result.error.message}` };
      }
      if (this.maxAgeError(draft)) return { draftKey: draft.key, message: `Max auth age must be whole seconds in ${label}` };
      for (const rule of draft.inject) {
        const named = rule.name ? ` for ${rule.name}` : '';
        if (!rule.name.trim()) return { draftKey: draft.key, message: `Inject name is required in ${label}` };
        if (rule.expression.trim().length === 0) return { draftKey: draft.key, message: `Inject expression is required in ${label}${named}` };
        const result = validateCel(rule.expression);
        if (!result.valid) return { draftKey: draft.key, message: `Invalid inject expression in ${label}${named}: ${result.error.message}` };
      }
    }
    return null;
  }

  private hasProblem(draft: Draft): boolean {
    return !!(this.pathError(draft) || this.clashError(draft) || this.maxAgeError(draft) || this.injectErrors(draft).some(Boolean));
  }

  private save() {
    if (this.saving) return;
    const problem = this.findProblem();
    this.problem = problem;
    if (problem) {
      this.selectedKey = problem.draftKey;
      return;
    }
    const changed = this.drafts.filter(draft => this.statusOf(draft) !== 'same');
    this.dispatchEvent(new CustomEvent<EndpointsSaveDetail>('save', {
      detail: { deleteEndpoints: this.removed.map(endpoint => endpoint.id), createEndpoints: changed.map(toPayload) },
      bubbles: true,
      composed: true,
    }));
  }

  // ── list ────────────────────────────────────────────────────────────────

  private get visible(): Draft[] {
    const filter = parseFilter(this.filter);
    if (!filter) return this.drafts;
    return this.drafts.filter(draft => {
      if ('text' in filter) return `${draft.method} ${draft.path}`.toLowerCase().includes(filter.text);
      return draft.path.toLowerCase().includes(filter.path) && (!filter.method || draft.method === filter.method);
    });
  }

  private renderRow(draft: Draft) {
    const status = this.statusOf(draft);
    const stepUp = !!(draft.stepUpAcr.trim() || draft.stepUpCondition.trim() || draft.maxAge.trim());
    const fix = this.hasProblem(draft);
    return html`
      <li>
        <button type="button" class="ep-row" aria-current=${draft.key === this.selectedKey ? 'true' : 'false'}
          @click=${() => { this.selectedKey = draft.key; }}>
          ${methodBadge(draft.method)}
          <span class="ep-path">${breakPath(draft.path || '/')}</span>
          ${fix ? html`<span class="marker fix">Fix</span>` : status === 'new' ? html`<span class="marker new">New</span>` : status === 'edited' ? html`<span class="marker">Edited</span>` : html`<span></span>`}
          ${draft.allow.trim() || stepUp || draft.inject.length ? html`
            <span class="ep-rules">
              ${draft.allow.trim() ? html`<span class="mini">Allow</span>` : nothing}
              ${stepUp ? html`<span class="mini">Step-up</span>` : nothing}
              ${draft.inject.length ? html`<span class="mini">Inject</span>` : nothing}
            </span>` : nothing}
        </button>
      </li>`;
  }

  // ── detail ──────────────────────────────────────────────────────────────

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
      <pre class="option-tooltip-pre">"read" in token.scope &amp;&amp; user.department == "engineering"</pre>`;
  }

  /** A CEL box tall enough for what it holds: a line per 80 characters (28 on a phone), so nothing is clipped. */
  private celRows(value: string, min: number): number {
    const perLine = window.innerWidth < 720 ? 28 : 80;
    const lines = value.split('\n').reduce((total, line) => total + Math.max(1, Math.ceil(line.length / perLine)), 0);
    return Math.min(8, Math.max(min, lines));
  }

  private renderRequest(draft: Draft) {
    const pathError = this.pathError(draft);
    const clash = this.clashError(draft);
    const error = pathError || clash;
    return html`
      <section class="editor-box" aria-labelledby="panel-request">
        <h3 id="panel-request">Request</h3>
        <p class="editor-sub">Matched against what edge receives, relative to the resource URI.</p>
        <div class="req-grid">
          <div class="form-group">
            <label for="endpoint-method">Method</label>
            <select id="endpoint-method" .value=${draft.method}
              @change=${(e: Event) => this.patch(draft.key, { method: (e.target as HTMLSelectElement).value })}>
              ${METHODS.map(method => html`<option value=${method} ?selected=${draft.method === method}>${method}</option>`)}
            </select>
          </div>
          <div class="form-group">
            <label for="endpoint-path">Relative path</label>
            <input id="endpoint-path" type="text" class="mono ${error ? 'input-error' : ''}" placeholder="/users" autocomplete="off" spellcheck="false"
              .value=${draft.path} aria-invalid=${error ? 'true' : 'false'} aria-describedby="endpoint-path-hint"
              @input=${(e: Event) => this.patch(draft.key, { path: (e.target as HTMLInputElement).value })} />
            ${error
              ? html`<div class="error-message" id="endpoint-path-hint" role="alert">${error}</div>`
              : html`<div class="hint" id="endpoint-path-hint">Each {name} segment becomes <code>request.path.params.name</code>.</div>`}
          </div>
        </div>
        <label class="toggle-row">
          <span class="toggle"><input type="checkbox" role="switch" aria-describedby="fetch-hint" .checked=${draft.fetchUserInfo}
            @change=${(e: Event) => this.patch(draft.key, { fetchUserInfo: (e.target as HTMLInputElement).checked })} /></span>
          <span>Fetch userinfo</span>
        </label>
        <div class="hint" id="fetch-hint">Edge resolves the caller's claims first and passes them to the rules as <code>user</code>.</div>
      </section>`;
  }

  private renderWho(draft: Draft) {
    const permissions = draft.id !== null ? this.permissionsByEndpoint.get(draft.id) ?? [] : [];
    return html`
      <section class="editor-box" aria-labelledby="panel-who">
        <h3 id="panel-who">Who may call</h3>
        <p class="editor-sub">Every rule must hold for edge to forward the call.</p>
        <div class="form-group">
          <span class="kicker">Required permission</span>
          <div class="perm-line">
            ${permissions.length
              ? permissions.map((id, index) => html`${index > 0 ? html`<span class="or">or</span>` : nothing}<span class="tag">${id}</span>`)
              : html`<span class="muted">${draft.id === null ? 'None yet. Link it from Permissions once the endpoint is saved.' : 'None yet. Link it from Permissions.'}</span>`}
            ${permissions.length ? html`<span class="muted">Linked from Permissions; any one of them is enough.</span>` : nothing}
          </div>
        </div>
        <div class="form-group">
          <div class="label-row">
            <span class="kicker" id="allow-label">Allow (CEL)</span>
            <div class="option-info" @click=${(e: Event) => e.stopPropagation()}>
              <button type="button" class="option-info-button" aria-label="Allow expression info" aria-expanded=${this.infoOpen ? 'true' : 'false'}
                @click=${() => { this.infoOpen = !this.infoOpen; }}>i</button>
              ${this.infoOpen ? html`<div class="option-tooltip" role="tooltip"><div class="option-tooltip-title">Allow expression</div><div class="option-tooltip-copy">${this.renderAllowInfo()}</div></div>` : nothing}
            </div>
          </div>
          <versola-cel-editor multiline .rows=${this.celRows(draft.allow, 2)} .value=${draft.allow} ?disabled=${this.saving}
            placeholder="leave empty for no authorization check" aria-label="Allow expression"
            @cel-input=${(e: CustomEvent<{ value: string }>) => this.patch(draft.key, { allow: e.detail.value })}></versola-cel-editor>
          <div class="hint">Must evaluate to <code>true</code>. Leave empty for no extra check. Variables: <code>token</code>, <code>user</code>, <code>request</code>.</div>
        </div>
      </section>`;
  }

  private renderStepUp(draft: Draft) {
    const selected = acrValues(draft.stepUpAcr);
    const choices = [...new Set([...Object.keys(this.acrVocabulary), ...selected])];
    const maxAgeError = this.maxAgeError(draft);
    const seconds = parseInt(draft.maxAge, 10);
    const human = Number.isFinite(seconds) && !maxAgeError && seconds > 0 ? (() => { const { value, unit } = splitDuration(seconds); return `${value} ${value === 1 ? unit.replace(/s$/, '') : unit}`; })() : '';
    return html`
      <section class="editor-box" aria-labelledby="panel-stepup">
        <h3 id="panel-stepup">Step-up</h3>
        <p class="editor-sub">Ask for a stronger sign-in when the condition holds.</p>
        <div class="form-group">
          <span class="kicker">Condition (CEL)</span>
          <versola-cel-editor multiline .rows=${this.celRows(draft.stepUpCondition, 1)} .value=${draft.stepUpCondition} ?disabled=${this.saving}
            placeholder="e.g. true or request.body.amount > 1000" aria-label="Step-up condition"
            @cel-input=${(e: CustomEvent<{ value: string }>) => this.patch(draft.key, { stepUpCondition: e.detail.value })}></versola-cel-editor>
          <div class="hint">The ACR below is asked for only when this is <code>true</code>; use <code>true</code> to always ask. Empty switches step-up off. Max auth age applies regardless.</div>
        </div>
        <div class="form-group">
          <span class="kicker" id="acr-label">Required ACR</span>
          ${choices.length ? html`
            <div class="acr-list" role="group" aria-labelledby="acr-label">
              ${choices.map(value => html`
                <label>
                  <input type="checkbox" .checked=${selected.includes(value)}
                    @change=${(e: Event) => {
                      const next = (e.target as HTMLInputElement).checked ? [...selected, value] : selected.filter(candidate => candidate !== value);
                      this.patch(draft.key, { stepUpAcr: next.join(' ') });
                    }} />
                  ${value}${value in this.acrVocabulary ? '' : html` <span class="muted">(not registered)</span>`}
                </label>`)}
            </div>
            <div class="hint">The access token must carry at least one of the chosen values in its <code>acr</code> claim.</div>`
          : html`<div class="muted">No ACR values registered. Add them under Security › Step-up (ACR).</div>`}
        </div>
        <div class="form-group">
          <label for="endpoint-max-age">Max auth age (seconds)</label>
          <input id="endpoint-max-age" type="number" min="0" step="1" class="compact-input ${maxAgeError ? 'input-error' : ''}" placeholder="e.g. 3600"
            .value=${draft.maxAge} aria-invalid=${maxAgeError ? 'true' : 'false'} aria-describedby="endpoint-max-age-hint"
            @input=${(e: Event) => this.patch(draft.key, { maxAge: (e.target as HTMLInputElement).value })} />
          ${maxAgeError
            ? html`<div class="error-message" id="endpoint-max-age-hint" role="alert">${maxAgeError}</div>`
            : html`<div class="hint" id="endpoint-max-age-hint">${human ? `${human}. ` : ''}Edge refuses a token whose sign-in is older than this, by its <code>auth_time</code>. Empty switches the check off.</div>`}
        </div>
      </section>`;
  }

  private renderInject(draft: Draft) {
    const errors = this.injectErrors(draft);
    return html`
      <section class="editor-box" aria-labelledby="panel-inject">
        <div class="panel-head">
          <div>
            <h3 id="panel-inject">Inject</h3>
            <p class="editor-sub">Values added to the upstream request. They overwrite what the client sent. Target is header, query or body (top-level JSON only); the expression is CEL over token, user and request.</p>
          </div>
          <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.saving}
            @click=${() => this.patch(draft.key, { inject: [...draft.inject, { target: 'header', name: '', expression: '' }] })}>Add rule</button>
        </div>
        ${draft.inject.length === 0 ? html`<div class="muted">No inject rules yet.</div>` : draft.inject.map((rule, index) => html`
          <div class="inject-row">
            <div class="form-group">
              <label for=${`inject-target-${index}`}>Target</label>
              <select id=${`inject-target-${index}`} .value=${rule.target}
                @change=${(e: Event) => this.patchInject(draft.key, index, { target: (e.target as HTMLSelectElement).value as InjectTarget })}>
                ${INJECT_TARGETS.map(target => html`<option value=${target} ?selected=${rule.target === target}>${target}</option>`)}
              </select>
            </div>
            <div class="form-group">
              <label for=${`inject-name-${index}`}>Name</label>
              <input id=${`inject-name-${index}`} type="text" class=${errors[index] ? 'input-error' : ''} autocomplete="off" spellcheck="false"
                placeholder=${rule.target === 'header' ? 'X-User-Id' : rule.target === 'query' ? 'tenant' : 'userId'}
                .value=${rule.name} aria-invalid=${errors[index] ? 'true' : 'false'} aria-describedby=${errors[index] ? `inject-name-error-${index}` : nothing}
                @input=${(e: Event) => this.patchInject(draft.key, index, { name: (e.target as HTMLInputElement).value })} />
              ${errors[index] ? html`<div class="error-message" id=${`inject-name-error-${index}`} role="alert">${errors[index]}</div>` : nothing}
            </div>
            <div class="form-group">
              <span class="kicker">Expression (CEL)</span>
              <versola-cel-editor required .value=${rule.expression} ?disabled=${this.saving} placeholder="token.sub" aria-label="Inject expression"
                @cel-input=${(e: CustomEvent<{ value: string }>) => this.patchInject(draft.key, index, { expression: e.detail.value })}></versola-cel-editor>
            </div>
            <button type="button" class="icon-x" aria-label=${`Remove inject rule ${index + 1}`} title="Remove rule" ?disabled=${this.saving}
              @click=${() => this.patch(draft.key, { inject: draft.inject.filter((_, i) => i !== index) })}>${statusIcon('x', 'icon')}</button>
          </div>`)}
      </section>`;
  }

  private renderDetail(draft: Draft | null) {
    if (!draft) {
      return html`<div class="detail"><div class="detail-empty">${this.drafts.length === 0
        ? 'This resource has no endpoints yet. Add one to start.'
        : 'Select an endpoint to edit it.'}</div></div>`;
    }
    return html`
      <div class="detail" role="group" aria-label=${`Endpoint ${endpointLabel(draft)}`}>
        <div class="detail-head">
          <h2 class="detail-title">${methodBadge(draft.method)}<span class="ep-path">${breakPath(draft.path || '/')}</span></h2>
          <button type="button" class="btn btn-danger-secondary btn-sm" ?disabled=${this.saving}
            aria-label=${`Remove endpoint ${endpointLabel(draft)}`} @click=${() => this.removeEndpoint(draft)}>Remove endpoint</button>
        </div>
        ${this.renderRequest(draft)}
        ${this.renderWho(draft)}
        ${this.renderStepUp(draft)}
        ${this.renderInject(draft)}
      </div>`;
  }

  render() {
    const visible = this.visible;
    const pending = [
      ...this.drafts.filter(draft => this.statusOf(draft) !== 'same').map(draft => ({ label: endpointLabel(draft), what: this.statusOf(draft) })),
      ...this.removed.map(endpoint => ({ label: endpoint.label, what: 'removed' })),
    ];
    return html`
      <div class="form-header">
        <div class="form-header-lead">
          <versola-nav-toggle></versola-nav-toggle>
          <div class="title-stack">
            <h1 class="form-title">Edit endpoints</h1>
            <div class="entity-id-meta">${this.resourceId} <span class="host">· ${this.resourceUri}</span></div>
          </div>
        </div>
      </div>
      ${this.error ? html`<div class="editor-error" role="alert">${this.error}</div>` : nothing}
      ${this.problem ? html`<div class="editor-error" role="alert">${this.problem.message}</div>` : nothing}
      <div class="card split">
        <div class="master">
          <div class="master-tools">
            <input type="search" aria-label="Filter endpoints" placeholder="Filter endpoints" autocomplete="off"
              .value=${this.filter} @input=${(e: Event) => { this.filter = (e.target as HTMLInputElement).value; }} />
            <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.saving} @click=${() => this.add()}>Add</button>
          </div>
          ${this.drafts.length === 0 ? html`<div class="master-empty">No endpoints yet.</div>`
            : visible.length === 0 ? html`<div class="master-empty">No endpoints match.</div>`
            : html`<ul class="master-list" aria-label="Endpoints">${visible.map(draft => this.renderRow(draft))}</ul>`}
        </div>
        ${this.renderDetail(this.selected)}
      </div>
      <div class="bar">
        <div class="bar-note" role="status">
          ${pending.length === 0 ? 'No changes yet.' : html`Not saved yet: ${pending.map((item, index) => html`${index > 0 ? ', ' : nothing}<b>${item.label}</b> (${item.what})`)}`}
        </div>
        <div class="bar-actions">
          <button type="button" class="btn btn-secondary" ?disabled=${this.saving} @click=${() => this.cancel()}>Cancel</button>
          <button type="button" class="btn btn-primary" ?disabled=${this.saving || !this.dirty} @click=${() => this.save()}>${this.saving ? 'Saving…' : 'Save endpoints'}</button>
        </div>
      </div>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-resource-endpoints-editor': VersolaResourceEndpointsEditor;
  }
}
