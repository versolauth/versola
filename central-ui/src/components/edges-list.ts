import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../styles/components';
import type { AccessLogPolicy, Edge, OverloadPolicy, RateLimitPolicies, Tenant } from '../types';
import type { EdgePolicySection } from '../utils/central-api';
import { deleteEdge, deleteOldEdgeKey, fetchEdges, fetchTenants, isPolicyConflict, patchEdgePolicies, registerEdge, rotateEdgeKey, setEdgeDpopNonce } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { copyToClipboard } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { validateEdgeId } from '../utils/validators';
import {
  draftFromPolicy, emptyDraft, isEmptyPolicy, modeLabel, policyFromDraft, setSummary, validateDraft, type OverloadDraft,
} from '../utils/overload-policy';
import {
  draftFromPolicy as accessLogDraftFromPolicy, emptyDraft as emptyAccessLogDraft, isEmptyPolicy as isEmptyAccessLogPolicy,
  modeLabel as accessLogModeLabel, policyFromDraft as accessLogPolicyFromDraft, setSummary as accessLogSetSummary,
  validateDraft as validateAccessLogDraft, type AccessLogDraft,
} from '../utils/access-log-policy';
import {
  countText as rateLimitCountText, podsText as rateLimitPodsText, draftFromPolicies as rateLimitDraftFromPolicies, emptyDraft as emptyRateLimitDraft, isEmptyPolicies as isEmptyRateLimitPolicies,
  policiesFromDraft as rateLimitPoliciesFromDraft, summary as rateLimitSummary, validateDraft as validateRateLimitDraft, type RateLimitDraft,
} from '../utils/rate-limit-policies';
import type { VersolaOverloadPolicyEditor } from './overload-policy-editor';
import type { VersolaAccessLogPolicyEditor } from './access-log-policy-editor';
import type { VersolaRateLimitPoliciesEditor } from './rate-limit-policies-editor';
import { statusIcon } from './status-icon';
import { valueChips } from './value-chips';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from './data-table';
import './content-header';
import './danger-card';
import './data-table';
import './empty-state';
import './error-card';
import './filter-bar';
import './loading-cards';
import './overload-policy-editor';
import './access-log-policy-editor';
import './rate-limit-policies-editor';
import './record-card';
import './record-header';
import './side-panel';

type EdgeFilter = 'all' | 'rotation' | 'no-tenants';

/** The edge with the revisions central answered a save with; absent revisions leave the ones it had. */
function withRevisions(edge: Edge, revisions: Record<string, number> | undefined): Edge {
  if (!revisions) return edge;
  const { policyRevisions: _previous, ...rest } = edge;
  return Object.keys(revisions).length > 0 ? { ...rest, policyRevisions: revisions } : rest;
}
type GeneratedKey = { edgeId: string; keyId: string; privateKey: string; action: 'created' | 'rotated' };

/**
 * Edges: the table of every registered edge and, for the one selected, the side panel with its
 * signing key (rotate, retire the old one), the DPoP nonce requirement, the tenants bound to it,
 * the Fixed group and the danger card. Registering an edge happens in the same panel and ends
 * on the new record with the one-time private-key banner, which is never shown again.
 */
@customElement('versola-edges-list')
export class VersolaEdgesList extends LitElement {
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private edges: Edge[] = [];
  @state() private tenants: Tenant[] = [];
  @state() private searchQuery = '';
  @state() private filter: EdgeFilter = 'all';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  /** The private key central answered register or rotate with; shown once, on that edge's panel. */
  @state() private generatedKey: GeneratedKey | null = null;
  @state() private copyFeedback = '';
  @state() private isWorking = false;
  @state() private draftId = '';
  @state() private draftDpop = true;
  @state() private draftOverload: OverloadDraft = emptyDraft();
  @state() private draftAccessLog: AccessLogDraft = emptyAccessLogDraft();
  @state() private draftRateLimit: RateLimitDraft = emptyRateLimitDraft();
  /** What the last save or reset said, shown on that edge's panel until it is left. */
  @state() private policyNotice: { edgeId: string; text: string } | null = null;
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @query('versola-data-table') private table?: VersolaDataTable<Edge>;
  @query('versola-overload-policy-editor') private overloadEditor?: VersolaOverloadPolicyEditor;
  @query('versola-access-log-policy-editor') private accessLogEditor?: VersolaAccessLogPolicyEditor;
  @query('versola-rate-limit-policies-editor') private rateLimitEditor?: VersolaRateLimitPoliciesEditor;
  private draftKey = '';

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    iconButtonStyles,
    recordStyles,
    css`
      :host {
        display: block;
      }

      .list-toolbar {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-md);
        align-items: center;
        margin-bottom: var(--spacing-lg);
      }

      .search-input {
        flex: 1 1 14rem;
        max-width: 28rem;
        min-width: 0;
      }

      .muted {
        color: var(--text-secondary);
      }

      .mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
      }

      .row-title.mono {
        font-size: var(--text-base);
      }

      .key-banner {
        display: block;
      }

      .key-banner .banner-text {
        flex: 1 1 100%;
      }

      .secret-rows {
        display: grid;
        gap: 0.5rem;
        margin: 0.75rem 0;
      }

      .secret-label {
        font-size: var(--text-xs);
        color: var(--text-secondary);
        margin-bottom: 0.125rem;
      }

      .secret-value {
        margin: 0;
        padding: 0.625rem 0.75rem;
        background: var(--bg-page);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        line-height: 1.5;
        white-space: pre-wrap;
        word-break: break-all;
      }

      .banner-actions {
        display: flex;
        flex-wrap: wrap;
        gap: 0.75rem;
        align-items: center;
      }

      .copy-feedback {
        font-size: var(--text-sm);
        color: var(--success-text);
      }

      .key-actions {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
      }

      .input-with-prefix {
        display: flex;
        align-items: stretch;
        max-width: min(100%, 22.8rem);
      }

      .input-prefix {
        display: flex;
        align-items: center;
        padding: 0 0.75rem;
        background: var(--surface-inset);
        border: 1px solid var(--border-input);
        border-right: 0;
        border-radius: var(--radius-md) 0 0 var(--radius-md);
        color: var(--text-secondary);
        font-family: var(--font-mono);
        font-size: var(--text-base);
        user-select: none;
      }

      .input-with-prefix input {
        border-radius: 0 var(--radius-md) var(--radius-md) 0;
        flex: 1;
        min-width: 0;
        max-width: none;
      }

      .toggle-row {
        display: flex;
        gap: 0.625rem;
        align-items: flex-start;
        font-size: var(--text-base);
        color: var(--text-primary);
        cursor: pointer;
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
        margin: 0;
      }

      .toggle-row input {
        margin: 0.2rem 0 0;
        width: 1rem;
        height: 1rem;
        flex: none;
        accent-color: var(--accent);
      }

      .toggle-row input:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      .footer-reset {
        margin-right: auto;
      }

      .explain {
        display: grid;
        gap: 0.5rem;
        margin: var(--spacing-md) 0 0;
        padding: 0;
        list-style: none;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .explain code {
        font-family: var(--font-mono);
      }

      .record-page .record-card {
        margin-bottom: var(--spacing-md);
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('beforeunload', this.handleBeforeUnload);
    void this.loadData();
  }

  disconnectedCallback() {
    window.removeEventListener('beforeunload', this.handleBeforeUnload);
    super.disconnectedCallback();
  }

  private readonly handleBeforeUnload = (event: BeforeUnloadEvent) => {
    if (this.generatedKey) event.preventDefault();
  };

  updated(changed: Map<string, unknown>) {
    if (changed.has('route') || changed.has('edges')) {
      const mode = this.editorMode;
      const draftKey = mode === 'view' ? '' : `${mode}:${this.route.id ?? ''}:${this.route.edit ?? ''}`;
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (mode !== 'view') this.resetDraft();
      }
      if (changed.has('route') && this.policyNotice && this.route.id !== this.policyNotice.edgeId) this.policyNotice = null;
      // The key is for the edge it was issued to; leaving that edge's panel is the one chance gone.
      const previous = changed.get('route') as RecordRoute | undefined;
      if (this.generatedKey && previous?.id === this.generatedKey.edgeId && this.route.id !== this.generatedKey.edgeId) this.dismissKey();
    }
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [edges, tenants] = await Promise.all([fetchEdges(), fetchTenants().catch(() => [] as Tenant[])]);
      this.edges = edges;
      this.tenants = tenants;
    } catch (error) {
      this.edges = [];
      this.tenants = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load edges';
    } finally {
      this.isLoading = false;
    }
  }

  // ── routing ──

  private get openEdge(): Edge | null {
    return this.route.id ? this.edges.find(edge => edge.id === this.route.id) ?? null : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.editTarget ? 'edit' : 'view';
  }

  /** Which group of the open edge is being edited, if any. */
  private get editTarget(): 'dpop' | 'overload' | 'access-log' | 'rate-limits' | null {
    return this.openEdge && (this.route.edit === 'dpop' || this.route.edit === 'overload' || this.route.edit === 'access-log' || this.route.edit === 'rate-limits') ? this.route.edit : null;
  }

  /** The private key is shown once: leaving its edge asks first. */
  private async confirmLeaveKey(nextId: string | null): Promise<boolean> {
    const key = this.generatedKey;
    if (!key || nextId === key.edgeId) return true;
    return confirmDestructiveAction({
      title: 'Leave without saving the private key?',
      message: 'The private key is shown only once. After you leave it cannot be retrieved; you would have to rotate the key.',
      confirmLabel: 'Leave',
      cancelLabel: 'Stay',
    });
  }

  private async openView(edge: Edge) {
    if (!await this.confirmLeaveKey(edge.id)) return;
    dispatchRecordRoute(this, { id: edge.id, edit: null, create: false });
  }

  private async closePanel() {
    const key = this.route.id;
    if (!await this.confirmLeaveKey(null)) return;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private openEditor(target: 'dpop' | 'overload' | 'access-log' | 'rate-limits' = 'dpop') {
    dispatchRecordRoute(this, { edit: target, create: false });
  }

  private draftBaseline = '';

  private get draftSnapshot(): string {
    // The overload draft is compared by what it would send, so `0.50` for `0.5` is not a change.
    return JSON.stringify([this.draftId, this.draftDpop, policyFromDraft(this.draftOverload), accessLogPolicyFromDraft(this.draftAccessLog), rateLimitPoliciesFromDraft(this.draftRateLimit)]);
  }

  /** Closing an editor with unsaved changes asks first; the draft is dropped only on a yes. */
  private async cancelEditor() {
    if (this.draftSnapshot !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  private handleCreateClick() {
    dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null });
  }

  // ── draft ──

  private resetDraft() {
    this.draftId = '';
    this.draftDpop = this.openEdge?.requireDpopNonce ?? true;
    this.draftOverload = draftFromPolicy(this.openEdge?.overloadPolicy);
    this.draftAccessLog = accessLogDraftFromPolicy(this.openEdge?.accessLogPolicy);
    this.draftRateLimit = rateLimitDraftFromPolicies(this.openEdge?.rateLimitPolicies);
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
    this.draftBaseline = this.draftSnapshot;
  }

  private get draftIdError(): string {
    const suffix = this.draftId.trim();
    if (!suffix) return this.submitted ? 'An edge ID is required.' : '';
    const id = `edge-${suffix}`;
    if (!validateEdgeId(id)) return 'Lowercase letters, digits and dashes.';
    if (this.edges.some(edge => edge.id === id)) return 'An edge with this ID already exists.';
    return '';
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.submitted = true;
    const mode = this.editorMode;
    if (mode === 'create' && this.draftIdError) return;

    this.isSaving = true;
    this.formError = '';
    try {
      if (mode === 'create') {
        const id = `edge-${this.draftId.trim()}`;
        const key = await registerEdge(id);
        this.edges = [...this.edges, { id, hasOldKey: false, requireDpopNonce: true }].sort((a, b) => a.id.localeCompare(b.id));
        this.generatedKey = { edgeId: id, keyId: key.keyId, privateKey: key.privateKey, action: 'created' };
        this.copyFeedback = '';
        dispatchRecordRoute(this, { id, edit: null, create: false });
      } else if (this.editTarget === 'overload') {
        const edge = this.openEdge!;
        if (Object.keys(validateDraft(this.draftOverload)).length > 0) {
          this.isSaving = false;
          this.overloadEditor?.focusFirstError();
          return;
        }
        const policy = policyFromDraft(this.draftOverload);
        const saved = await patchEdgePolicies(edge.id, { overload: isEmptyPolicy(policy) ? null : policy, expectedRevisions: { overload: this.revisionOf(edge, 'overload') } });
        this.applyOverloadPolicy(edge.id, policy, saved.revisions);
        dispatchRecordRoute(this, { edit: null, create: false });
      } else if (this.editTarget === 'access-log') {
        const edge = this.openEdge!;
        if (Object.keys(validateAccessLogDraft(this.draftAccessLog)).length > 0) {
          this.isSaving = false;
          this.accessLogEditor?.focusFirstError();
          return;
        }
        const policy = accessLogPolicyFromDraft(this.draftAccessLog);
        const saved = await patchEdgePolicies(edge.id, { accessLog: isEmptyAccessLogPolicy(policy) ? null : policy, expectedRevisions: { accessLog: this.revisionOf(edge, 'accessLog') } });
        this.applyAccessLogPolicy(edge.id, policy, saved.revisions);
        dispatchRecordRoute(this, { edit: null, create: false });
      } else if (this.editTarget === 'rate-limits') {
        const edge = this.openEdge!;
        if (Object.keys(validateRateLimitDraft(this.draftRateLimit)).length > 0) {
          this.isSaving = false;
          this.rateLimitEditor?.focusFirstError();
          return;
        }
        const policies = rateLimitPoliciesFromDraft(this.draftRateLimit);
        const saved = await patchEdgePolicies(edge.id, { rateLimits: isEmptyRateLimitPolicies(policies) ? null : policies, expectedRevisions: { rateLimits: this.revisionOf(edge, 'rateLimits') } });
        this.applyRateLimitPolicies(edge.id, policies, saved.revisions);
        dispatchRecordRoute(this, { edit: null, create: false });
      } else {
        const edge = this.openEdge!;
        await setEdgeDpopNonce(edge.id, this.draftDpop);
        this.edges = this.edges.map(candidate => candidate.id === edge.id ? { ...candidate, requireDpopNonce: this.draftDpop } : candidate);
        dispatchRecordRoute(this, { edit: null, create: false });
      }
    } catch (error) {
      this.formError = await this.policySaveError(error, 'Failed to save edge');
    } finally {
      this.isSaving = false;
    }
  }

  /** The revision of a policy section as the console read it; a section never saved is at 0. */
  private revisionOf(edge: Edge, section: EdgePolicySection): number {
    return edge.policyRevisions?.[section] ?? 0;
  }

  /** What to tell the operator about a failed save of a policy section. Central answers `400` and `409` with no body (a body would be a contract),
   *  so the words are the console's. The console validates what it sends, so a `400` means the rules of central and the console have moved
   *  apart, and the reason is in central's log. */
  private async policySaveError(error: unknown, fallback: string): Promise<string> {
    if (isPolicyConflict(error)) return this.handlePolicyConflict();
    if ((error as { status?: number } | undefined)?.status === 400) {
      return 'Central did not accept these values, so nothing was saved. Check them and try again; central\'s log says what it refused.';
    }
    return error instanceof Error ? error.message : fallback;
  }

  /** A save that lost to someone else's change of the same section: nothing was written. The edges are read again so the editor, reopened,
   *  shows what is stored now. */
  private async handlePolicyConflict(): Promise<string> {
    try {
      this.edges = await fetchEdges();
    } catch {
      // the message below still tells the operator what to do
    }
    return 'Someone else changed this section after you opened it, so nothing was saved. Close the editor and open it again to see their change.';
  }

  private applyOverloadPolicy(edgeId: string, policy: OverloadPolicy, revisions?: Record<string, number>) {
    const empty = isEmptyPolicy(policy);
    this.edges = this.edges.map(candidate => {
      if (candidate.id !== edgeId) return candidate;
      const { overloadPolicy: _previous, ...rest } = candidate;
      return withRevisions(empty ? rest : { ...rest, overloadPolicy: policy }, revisions);
    });
    this.policyNotice = {
      edgeId,
      text: empty
        ? 'Overload protection returned to the deployment defaults. The edge applies it on its next configuration sync.'
        : 'Overload protection saved. The edge applies it on its next configuration sync.',
    };
  }

  private applyAccessLogPolicy(edgeId: string, policy: AccessLogPolicy, revisions?: Record<string, number>) {
    const empty = isEmptyAccessLogPolicy(policy);
    this.edges = this.edges.map(candidate => {
      if (candidate.id !== edgeId) return candidate;
      const { accessLogPolicy: _previous, ...rest } = candidate;
      return withRevisions(empty ? rest : { ...rest, accessLogPolicy: policy }, revisions);
    });
    this.policyNotice = {
      edgeId,
      text: empty
        ? 'Access log returned to the defaults. The edge applies it on its next configuration sync.'
        : 'Access log saved. The edge applies it on its next configuration sync.',
    };
  }

  private applyRateLimitPolicies(edgeId: string, policies: RateLimitPolicies, revisions?: Record<string, number>) {
    const empty = isEmptyRateLimitPolicies(policies);
    this.edges = this.edges.map(candidate => {
      if (candidate.id !== edgeId) return candidate;
      const { rateLimitPolicies: _previous, ...rest } = candidate;
      return withRevisions(empty ? rest : { ...rest, rateLimitPolicies: policies }, revisions);
    });
    this.policyNotice = {
      edgeId,
      text: empty
        ? 'Rate limit policies removed. The edge applies no rate limit after its next configuration sync.'
        : 'Rate limit policies saved. The edge applies them on its next configuration sync.',
    };
  }

  /** Removes every policy: the edge applies no rate limit. */
  private async handleResetRateLimits(edge: Edge) {
    const confirmed = await confirmDestructiveAction({
      title: 'Remove all rate limit policies',
      messagePrefix: 'Remove every rate limit policy of edge ',
      messageSubject: edge.id,
      messageSuffix: '? The edge applies no rate limit after its next configuration sync.',
      confirmLabel: 'Remove all',
    });
    if (!confirmed) return;
    if (this.isSaving) return;
    this.isSaving = true;
    this.formError = '';
    this.errorMessage = '';
    try {
      const saved = await patchEdgePolicies(edge.id, { rateLimits: null, expectedRevisions: { rateLimits: this.revisionOf(edge, 'rateLimits') } });
      this.applyRateLimitPolicies(edge.id, { policies: [] }, saved.revisions);
      this.draftRateLimit = emptyRateLimitDraft();
      this.draftBaseline = this.draftSnapshot;
      if (this.editTarget === 'rate-limits') dispatchRecordRoute(this, { edit: null, create: false });
    } catch (error) {
      const message = await this.policySaveError(error, 'Failed to remove the rate limit policies');
      if (this.editTarget === 'rate-limits') this.formError = message;
      else this.errorMessage = message;
    } finally {
      this.isSaving = false;
    }
  }

  /** Removes the policy: the edge goes back to its built-in defaults. */
  private async handleResetAccessLog(edge: Edge) {
    const confirmed = await confirmDestructiveAction({
      title: 'Reset access log to defaults',
      messagePrefix: 'Remove the access-log policy of edge ',
      messageSubject: edge.id,
      messageSuffix: '? It goes back to its built-in defaults: failures and slow requests only.',
      confirmLabel: 'Reset',
    });
    if (!confirmed) return;
    if (this.isSaving) return;
    this.isSaving = true;
    this.formError = '';
    this.errorMessage = '';
    try {
      const saved = await patchEdgePolicies(edge.id, { accessLog: null, expectedRevisions: { accessLog: this.revisionOf(edge, 'accessLog') } });
      this.applyAccessLogPolicy(edge.id, {}, saved.revisions);
      this.draftAccessLog = emptyAccessLogDraft();
      this.draftBaseline = this.draftSnapshot;
      if (this.editTarget === 'access-log') dispatchRecordRoute(this, { edit: null, create: false });
    } catch (error) {
      const message = await this.policySaveError(error, 'Failed to reset the access log');
      if (this.editTarget === 'access-log') this.formError = message;
      else this.errorMessage = message;
    } finally {
      this.isSaving = false;
    }
  }

  /** Removes the policy: the edge goes back to the values of its own deployment. */
  private async handleResetOverload(edge: Edge) {
    const confirmed = await confirmDestructiveAction({
      title: 'Reset to deployment defaults',
      messagePrefix: 'Remove the overload policy of edge ',
      messageSubject: edge.id,
      messageSuffix: '? Every field goes back to what the edge starts with: its HTTP_ADMISSION_* and HTTP_CLIENT_GUARD_* environment values, otherwise the built-in defaults.',
      confirmLabel: 'Reset',
    });
    if (!confirmed) return;
    if (this.isSaving) return;
    this.isSaving = true;
    this.formError = '';
    this.errorMessage = '';
    try {
      const saved = await patchEdgePolicies(edge.id, { overload: null, expectedRevisions: { overload: this.revisionOf(edge, 'overload') } });
      this.applyOverloadPolicy(edge.id, {}, saved.revisions);
      this.draftOverload = emptyDraft();
      this.draftBaseline = this.draftSnapshot;
      if (this.editTarget === 'overload') dispatchRecordRoute(this, { edit: null, create: false });
    } catch (error) {
      const message = await this.policySaveError(error, 'Failed to reset overload protection');
      if (this.editTarget === 'overload') this.formError = message;
      else this.errorMessage = message;
    } finally {
      this.isSaving = false;
    }
  }

  private async handleRotateKey(edge: Edge) {
    const confirmed = await confirmDestructiveAction({
      title: 'Rotate edge key',
      messagePrefix: 'Rotate the key of edge ',
      messageSubject: edge.id,
      messageSuffix: '? Both keys verify until the old one is retired. Put the new private key into every edge instance.',
      confirmLabel: 'Rotate key',
    });
    if (!confirmed) return;
    this.isWorking = true;
    this.errorMessage = '';
    try {
      const key = await rotateEdgeKey(edge.id);
      this.edges = this.edges.map(candidate => candidate.id === edge.id ? { ...candidate, hasOldKey: true } : candidate);
      this.generatedKey = { edgeId: edge.id, keyId: key.keyId, privateKey: key.privateKey, action: 'rotated' };
      this.copyFeedback = '';
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to rotate the key';
    } finally {
      this.isWorking = false;
    }
  }

  private async handleRetireOldKey(edge: Edge) {
    const confirmed = await confirmDestructiveAction({
      title: 'Retire old key',
      messagePrefix: 'Retire the old key of edge ',
      messageSubject: edge.id,
      messageSuffix: '? Only the new key verifies afterwards. Every edge instance must already run the new one.',
      confirmLabel: 'Retire old key',
    });
    if (!confirmed) return;
    this.isWorking = true;
    this.errorMessage = '';
    try {
      await deleteOldEdgeKey(edge.id);
      this.edges = this.edges.map(candidate => candidate.id === edge.id ? { ...candidate, hasOldKey: false } : candidate);
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to retire the old key';
    } finally {
      this.isWorking = false;
    }
  }

  private async handleDelete(edge: Edge) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete edge',
      messagePrefix: 'Delete edge ',
      messageSubject: edge.id,
      messageSuffix: '? Tenants bound to it are no longer served by any edge until they are moved.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      await deleteEdge(edge.id);
      this.edges = this.edges.filter(candidate => candidate.id !== edge.id);
      if (this.route.id === edge.id) dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to delete edge';
    }
  }

  private dismissKey() {
    this.generatedKey = null;
    this.copyFeedback = '';
  }

  private async handleCopyKey() {
    if (!this.generatedKey) return;
    const copied = await copyToClipboard(this.generatedKey.privateKey);
    this.copyFeedback = copied ? 'Private key copied.' : 'Could not copy the private key.';
  }

  // ── list ──

  private linkedTenants(edge: Edge): Tenant[] {
    return this.tenants.filter(tenant => tenant.edgeId === edge.id);
  }

  private matchesFilter(edge: Edge, filter: EdgeFilter = this.filter): boolean {
    switch (filter) {
      case 'rotation': return !!edge.hasOldKey;
      case 'no-tenants': return this.linkedTenants(edge).length === 0;
      default: return true;
    }
  }

  private get filteredEdges(): Edge[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.edges.filter(edge => this.matchesFilter(edge) && (!query || edge.id.toLowerCase().includes(query)));
  }

  private keyChip(edge: Edge) {
    return edge.hasOldKey
      ? html`<span class="chip warn">Key rotation</span>`
      : html`<span class="chip ok">${statusIcon('check', 'icon', 14)}Key current</span>`;
  }

  private renderToolbar() {
    const options: Array<{ id: EdgeFilter; label: string }> = [
      { id: 'all', label: 'All' },
      { id: 'rotation', label: 'Key rotation' },
      ...(this.tenants.length ? [{ id: 'no-tenants' as const, label: 'No tenants' }] : []),
    ];
    return html`
      <div class="list-toolbar">
        <input class="search-input" type="search" aria-label="Search edges" placeholder="Search edges by ID"
          .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
        <versola-filter-bar label="Filter edges"
          .options=${options}
          .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => this.filter = e.detail.id as EdgeFilter}></versola-filter-bar>
      </div>
    `;
  }

  private renderTable() {
    const columns: DataTableColumn<Edge>[] = [
      { id: 'edge', label: 'Edge', cell: edge => html`
        <button type="button" class="row-link" aria-label=${`Open edge ${edge.id}`}>
          <span class="row-title">${edge.id}</span>
        </button>` },
      ...(this.tenants.length ? [{ id: 'tenants', label: 'Tenants', cell: (edge: Edge) => {
        return valueChips(this.linkedTenants(edge).map(tenant => tenant.id), { empty: 'None' });
      } }] : []),
      { id: 'key', label: 'Key', cell: edge => html`<div class="chips">${this.keyChip(edge)}</div>` },
      { id: 'dpop', label: 'DPoP nonce', class: 'muted', cell: edge => edge.requireDpopNonce ? 'Required' : 'Not required' },
    ];
    const menuItems = (edge: Edge): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(edge) },
      { label: 'Copy edge ID', onSelect: () => void copyToClipboard(edge.id) },
      ...(this.canManage ? [{ label: 'Delete edge', danger: true, ariaLabel: `Delete edge ${edge.id}`, onSelect: () => void this.handleDelete(edge) }] : []),
    ];
    return html`
      <versola-data-table selectable
        .columns=${columns}
        .rows=${this.filteredEdges}
        .rowKey=${(edge: Edge) => edge.id}
        .selectedKey=${this.editorMode === 'view' ? this.route.id : null}
        .menuItems=${menuItems}
        .menuLabel=${(edge: Edge) => `Actions for ${edge.id}`}
        @row-select=${(e: CustomEvent<{ row: Edge }>) => this.openView(e.detail.row)}
      ></versola-data-table>
    `;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private renderGroups(edge: Edge) {
    const linked = this.linkedTenants(edge);
    return html`
      <versola-record-card heading="Signing key" subheading="Central keeps the public half; the private key was shown once." .body=${html`
        ${this.row('Status', edge.hasOldKey ? 'Rotation open — the old key still verifies' : 'Current')}
        ${this.canManage ? html`
          <versola-record-row .value=${html`<div class="key-actions">
            <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.isWorking} @click=${() => this.handleRotateKey(edge)}>Rotate key</button>
            ${edge.hasOldKey ? html`<button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.isWorking} @click=${() => this.handleRetireOldKey(edge)}>Retire old key</button>` : ''}
          </div>`}></versola-record-row>` : ''}
      `}></versola-record-card>

      <versola-record-card heading="DPoP nonce" subheading="RFC 9449 section 9 on the calls this edge proxies." ?editable=${this.canManage} .body=${html`
        ${this.row('Proxied calls', edge.requireDpopNonce ? 'Required on every proxied call' : 'Not required')}
      `} @edit=${() => this.openEditor()}></versola-record-card>

      <versola-record-card heading="Overload protection" subheading="What this edge does when it is asked for more than it can do. Reaches the edge on its next configuration sync." ?editable=${this.canManage} .body=${html`
        ${this.row('Inbound', modeLabel(edge.overloadPolicy?.inbound?.mode))}
        ${this.row('Outbound', modeLabel(edge.overloadPolicy?.outbound?.mode))}
        ${this.row('Set here', valueChips(setSummary(edge.overloadPolicy), { empty: 'Nothing: every value comes from the deployment' }))}
        ${this.canManage && !isEmptyPolicy(edge.overloadPolicy) ? html`
          <versola-record-row .value=${html`<div class="key-actions">
            <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.isSaving} @click=${() => this.handleResetOverload(edge)}>Reset to deployment defaults</button>
          </div>`}></versola-record-row>` : ''}
      `} @edit=${() => this.openEditor('overload')}></versola-record-card>

      <versola-record-card heading="Access log" subheading="Which requests this edge writes a log line for. Auth and central always log every request. Reaches the edge on its next configuration sync." ?editable=${this.canManage} .body=${html`
        ${this.row('Requests logged', accessLogModeLabel(edge.accessLogPolicy?.mode))}
        ${this.row('Set here', valueChips(accessLogSetSummary(edge.accessLogPolicy), { empty: 'Nothing: the edge uses its defaults' }))}
        ${this.canManage && !isEmptyAccessLogPolicy(edge.accessLogPolicy) ? html`
          <versola-record-row .value=${html`<div class="key-actions">
            <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.isSaving} @click=${() => this.handleResetAccessLog(edge)}>Reset to defaults</button>
          </div>`}></versola-record-row>` : ''}
      `} @edit=${() => this.openEditor('access-log')}></versola-record-card>

      <versola-record-card heading="Rate limits" subheading="Who may send how much through this edge. Start in shadow (counts only), then enforce. Reaches the edge on its next configuration sync." ?editable=${this.canManage} .body=${html`
        ${this.row('Policies', rateLimitCountText(edge.rateLimitPolicies))}
        ${this.row('Edge pods', rateLimitPodsText(edge.rateLimitPolicies))}
        ${(edge.rateLimitPolicies?.policies ?? []).slice(0, 5).map(policy => this.row(policy.name, rateLimitSummary(policy)))}
        ${(edge.rateLimitPolicies?.policies.length ?? 0) > 5 ? this.row('', `and ${edge.rateLimitPolicies!.policies.length - 5} more`) : ''}
        ${this.canManage && !isEmptyRateLimitPolicies(edge.rateLimitPolicies) ? html`
          <versola-record-row .value=${html`<div class="key-actions">
            <button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.isSaving} @click=${() => this.handleResetRateLimits(edge)}>Remove all policies</button>
          </div>`}></versola-record-row>` : ''}
      `} @edit=${() => this.openEditor('rate-limits')}></versola-record-card>

      <versola-record-card heading="Linked tenants" subheading="Bind a tenant to this edge from the tenant." .body=${html`
        ${this.row('Tenants', linked.length ? html`<div class="tag-list">${linked.map(tenant => html`<span class="tag">${tenant.id}</span>`)}</div>`
          : html`<span class="muted">No tenants assigned to this edge.</span>`)}
      `}></versola-record-card>

      <versola-record-card heading="Fixed" fixedLabel="Fixed" .body=${html`
        ${this.row('Edge ID', html`<span class="mono">${edge.id}</span>`, 'Set at registration')}
      `}></versola-record-card>

      ${this.canManage ? html`
        <versola-danger-card heading="Delete edge" text="Tenants bound to it are no longer served by any edge until they are moved. This can't be undone."
          actionLabel="Delete edge" .actionAriaLabel=${`Delete edge ${edge.id}`}
          @action=${() => this.handleDelete(edge)}></versola-danger-card>` : ''}
    `;
  }

  private renderBanners(edge: Edge) {
    const key = this.generatedKey?.edgeId === edge.id ? this.generatedKey : null;
    return html`
      ${key ? html`
        <div class="banner ok key-banner" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('check')}Edge key ${key.action === 'created' ? 'generated' : 'rotated'}</div>
            <div>Save the private key now. It is not shown again.</div>
            <div class="secret-rows">
              <div><div class="secret-label">Edge ID</div><pre class="secret-value">${key.edgeId}</pre></div>
              <div><div class="secret-label">Key ID</div><pre class="secret-value">${key.keyId}</pre></div>
              <div><div class="secret-label">Private key</div><pre class="secret-value">${key.privateKey}</pre></div>
            </div>
            <div class="banner-actions">
              <button type="button" class="btn btn-primary btn-sm" @click=${this.handleCopyKey}>Copy private key</button>
              <button type="button" class="btn btn-secondary btn-sm" @click=${this.dismissKey}>I've saved it</button>
              ${this.copyFeedback ? html`<span class="copy-feedback" role="status">${this.copyFeedback}</span>` : ''}
            </div>
          </div>
        </div>` : ''}
      ${this.policyNotice?.edgeId === edge.id ? html`
        <div class="banner ok" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('check')}${this.policyNotice.text}</div>
          </div>
        </div>` : ''}
      ${edge.hasOldKey ? html`
        <div class="banner warn" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('alert')}A key rotation is open: both keys verify</div>
            <div>Retire the old key once every edge instance runs the new one.</div>
          </div>
        </div>` : ''}
    `;
  }

  private renderMeta(edge: Edge) {
    return html`${this.keyChip(edge)}`;
  }

  private renderRecordPage(edge: Edge) {
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Edges', onSelect: () => this.closePanel() }, { label: edge.id }]}
        .heading=${edge.id} .recordId=${edge.id} idLabel="edge ID"
        .meta=${html`<span class="chip">Edge</span>${this.renderMeta(edge)}`}
      ></versola-record-header>
      ${this.renderBanners(edge)}
      <div class="record-page">${this.renderGroups(edge)}</div>
    `;
  }

  // ── editor ──

  private renderEditor() {
    const mode = this.editorMode;
    const idError = this.draftIdError;
    return html`
      <form id="edge-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${mode === 'create' ? html`
          <div class="editor-box">
            <h3>Registration</h3>
            <p class="editor-sub">Central answers with the edge's private key, once. The DPoP nonce requirement starts on and can be changed afterwards.</p>
            <div class="form-group">
              <label for="edge-id">Edge ID *</label>
              <div class="input-with-prefix">
                <span class="input-prefix" aria-hidden="true">edge-</span>
                <input type="text" id="edge-id" class="compact-input mono ${idError ? 'input-error' : ''}" .value=${this.draftId} required
                  aria-invalid=${idError ? 'true' : nothing} aria-describedby="edge-id-hint" placeholder="eu-west-1"
                  @input=${(e: Event) => this.draftId = (e.target as HTMLInputElement).value} />
              </div>
              ${idError ? html`<div class="error-message" id="edge-id-hint" role="alert">${idError}</div>`
                : html`<div class="hint" id="edge-id-hint">Registered as <span class="mono">edge-${this.draftId.trim() || '…'}</span>. Lowercase letters, digits and dashes.</div>`}
            </div>
          </div>
        ` : this.editTarget === 'rate-limits' ? html`
          <versola-rate-limit-policies-editor .draft=${this.draftRateLimit} ?disabled=${this.isSaving} ?submitted=${this.submitted}
            @draft-change=${(e: CustomEvent<{ draft: RateLimitDraft }>) => this.draftRateLimit = e.detail.draft}></versola-rate-limit-policies-editor>
        ` : this.editTarget === 'access-log' ? html`
          <versola-access-log-policy-editor .draft=${this.draftAccessLog} ?disabled=${this.isSaving}
            @draft-change=${(e: CustomEvent<{ draft: AccessLogDraft }>) => this.draftAccessLog = e.detail.draft}></versola-access-log-policy-editor>
        ` : this.editTarget === 'overload' ? html`
          <versola-overload-policy-editor .draft=${this.draftOverload} ?disabled=${this.isSaving}
            @draft-change=${(e: CustomEvent<{ draft: OverloadDraft }>) => this.draftOverload = e.detail.draft}></versola-overload-policy-editor>
        ` : html`
          <div class="editor-box">
            <h3>DPoP nonce</h3>
            <p class="editor-sub">Whether a DPoP proof on a call this edge proxies must carry a nonce it issued.</p>
            <label class="toggle-row">
              <input type="checkbox" .checked=${this.draftDpop} @change=${(e: Event) => this.draftDpop = (e.target as HTMLInputElement).checked} />
              <span>Required on every proxied call</span>
            </label>
            <ul class="explain">
              <li>A DPoP nonce (RFC 9449 section 9) is a short-lived value this edge issues and the client must sign into its next proof. It bounds how long a captured proof stays usable on a proxied call.</li>
              <li>On, a call without one is answered with <code>use_dpop_nonce</code> and a fresh nonce in the <code>DPoP-Nonce</code> header, which the client retries over: one extra round trip per client per nonce lifetime. Every client calling through this edge with DPoP must implement that retry.</li>
              <li>Off, the claim is not read and no nonce is issued; it takes effect on this edge's next configuration sync.</li>
              <li>Governs this edge only. Auth's own <code>/token</code> and <code>/userinfo</code> follow the tenant's setting under Security › Tokens &amp; keys.</li>
            </ul>
          </div>
        `}
      </form>
    `;
  }

  private renderPanel(edge: Edge | null) {
    const mode = this.editorMode;
    if (mode === 'view' && !edge) return '';
    const overload = this.editTarget === 'overload';
    const accessLog = this.editTarget === 'access-log';
    const rateLimits = this.editTarget === 'rate-limits';
    const heading = mode === 'create' ? 'Register edge' : mode === 'edit' ? (overload ? 'Edit overload protection' : accessLog ? 'Edit access log' : rateLimits ? 'Edit rate limits' : 'Edit DPoP nonce') : edge!.id;
    const submitLabel = mode === 'create' ? 'Register edge' : overload ? 'Save overload protection' : accessLog ? 'Save access log' : rateLimits ? 'Save rate limits' : 'Save DPoP nonce';
    return html`
      <versola-side-panel kind="Edge" .heading=${heading} .recordId=${edge?.id ?? ''} idLabel="edge ID" .mode=${mode} ?wide=${overload || rateLimits}
        .parentLabel=${edge?.id ?? ''} .subtitle=${mode === 'create' ? 'The ID is fixed once registered.' : ''}
        .meta=${edge && mode === 'view' ? this.renderMeta(edge) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()}
        @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">
          ${mode === 'view' ? html`${this.renderBanners(edge!)}${this.renderGroups(edge!)}` : this.renderEditor()}
        </div>
        ${mode !== 'view' ? html`
          <div slot="footer">
            ${overload && edge ? html`<button type="button" class="btn btn-secondary footer-reset" ?disabled=${this.isSaving || isEmptyPolicy(edge.overloadPolicy)} @click=${() => this.handleResetOverload(edge)}>Reset to deployment defaults</button>` : ''}
            ${accessLog && edge ? html`<button type="button" class="btn btn-secondary footer-reset" ?disabled=${this.isSaving || isEmptyAccessLogPolicy(edge.accessLogPolicy)} @click=${() => this.handleResetAccessLog(edge)}>Reset to defaults</button>` : ''}
            ${rateLimits && edge ? html`<button type="button" class="btn btn-secondary footer-reset" ?disabled=${this.isSaving || isEmptyRateLimitPolicies(edge.rateLimitPolicies)} @click=${() => this.handleResetRateLimits(edge)}>Remove all policies</button>` : ''}
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="edge-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : submitLabel}</button>
          </div>` : ''}
      </versola-side-panel>
    `;
  }

  render() {
    const edge = this.openEdge;
    const panel = this.renderPanel(edge);
    if (edge && this.route.layout === 'page') {
      return html`${this.renderRecordPage(edge)}${this.editorMode === 'view' ? '' : panel}`;
    }

    return html`
      <content-header title="Edges" description="The authenticating reverse proxies that serve tenants and sync their configuration from central.">
        ${this.edges.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Edge</button>` : ''}
      </content-header>

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load edges" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.edges.length === 0 ? html`
        <versola-empty-state heading="No edges yet" text=${this.canManage ? 'Create your first edge to get started' : 'No edges available.'}>
          ${this.canManage ? html`<button class="btn btn-primary" @click=${this.handleCreateClick}>+ Create Edge</button>` : ''}
        </versola-empty-state>
      ` : html`
        ${this.renderToolbar()}
        ${this.filteredEdges.length === 0 ? html`<versola-empty-state heading="No edges match"></versola-empty-state>` : this.renderTable()}
      `}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-edges-list': VersolaEdgesList;
  }
}
