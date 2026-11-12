import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { evaluate } from '@marcbachmann/cel-js';
import { theme } from '../../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../../styles/components';
import { statusIcon } from '../../components/status-icon';
import { valueChips } from '../../components/value-chips';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from '../../components/data-table';
import type { EndpointsSaveDetail } from '../../components/resource-endpoints-editor';
import type { ResourceEndpoint } from '../../types';
import { confirmDestructiveAction } from '../../utils/confirm-dialog';
import { confirmDiscardDraft } from '../../utils/draft-guard';
import { copyToClipboard } from '../../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../../utils/record-route';
import { validateResourceId, validateResourceUri } from '../../utils/validators';
import { createResource, deleteResource, fetchClients, fetchResources, rotateResourceSecret, updateResource, writeErrorMessage, readErrorMessage } from '../partner-api';
import type { PartnerClient, PartnerMe, PartnerResource } from '../partner-types';
import { partnerViewStyles } from '../partner-styles';
import '../../components/content-header';
import '../../components/danger-card';
import '../../components/data-table';
import '../../components/empty-state';
import '../../components/error-card';
import '../../components/loading-cards';
import '../../components/record-card';
import '../../components/record-header';
import '../../components/resource-endpoints-editor';
import '../../components/resource-endpoints-table';
import '../../components/side-panel';

type EditTarget = 'general' | 'endpoints';
type Draft = { resourceId: string; resource: string; audience: string[] };
type IssuedSecret = { resourceId: string; secret: string; action: 'created' | 'rotated' };

const SAMPLE_TOKEN = `{
  "token": { "sub": "u_7f3a9c2e", "scope": ["openid", "profile", "email"], "amr": ["otp"], "acr": "sensitive" },
  "user": { "email_verified": true, "phone_number": "+7701…" },
  "request": { "body": { "amount": 12000 }, "query": {} }
}`;

/**
 * Resources, the partner's own APIs behind its edge: the list, the record with its endpoints
 * (the shared endpoints table), the audience picker over the partner's clients only, the secret,
 * `Edit endpoints` on the shared editor with a "test with a sample token" box beside it, and the
 * limits from the operator (rate policy, the `sensitive` ACR).
 */
@customElement('versola-partner-resources')
export class VersolaPartnerResources extends LitElement {
  @property({ attribute: false }) me!: PartnerMe;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private resources: PartnerResource[] = [];
  @state() private clients: PartnerClient[] = [];
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private searchQuery = '';
  @state() private draft: Draft = { resourceId: '', resource: '', audience: [] };
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private submitted = false;
  @state() private issued: IssuedSecret | null = null;
  @state() private copyFeedback = '';
  @state() private sampleToken = SAMPLE_TOKEN;
  @state() private sampleEndpoint = '';
  @state() private sampleResult: { ok: boolean; text: string } | null = null;
  @query('versola-data-table') private table?: VersolaDataTable<PartnerResource>;
  private draftKey = '';
  private draftBaseline = '';

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
    partnerViewStyles,
    css`
      .endpoint-layout { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--spacing-lg); align-items: start; }
      .endpoint-aside { display: grid; grid-template-columns: repeat(auto-fit, minmax(22rem, 1fr)); gap: var(--spacing-md); align-items: start; }
      .test-box textarea { font-family: var(--font-mono); font-size: var(--text-sm); min-height: 9rem; max-width: 100%; width: 100%; box-sizing: border-box; }
      .test-box select { max-width: none; }
      .result-ok { color: var(--success-text); font-weight: 600; }
      .result-no { color: var(--danger-text); font-weight: 600; }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('route') || changed.has('resources')) {
      const mode = this.editorMode;
      const key = mode === 'view' ? '' : `${mode}:${this.route.id ?? ''}:${this.route.edit ?? ''}`;
      if (key !== this.draftKey) {
        this.draftKey = key;
        if (mode !== 'view') this.resetDraft();
      }
      if (changed.has('route') && this.issued && this.route.id !== this.issued.resourceId) this.issued = null;
    }
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [resources, clients] = await Promise.all([fetchResources(), fetchClients().catch(() => [] as PartnerClient[])]);
      this.resources = resources;
      this.clients = clients;
    } catch (error) {
      this.resources = [];
      this.errorMessage = readErrorMessage(error, 'Failed to load resources');
    } finally {
      this.isLoading = false;
    }
  }

  private get openResource(): PartnerResource | null {
    return this.route.id ? this.resources.find(resource => resource.resourceId === this.route.id) ?? null : null;
  }

  private get editTarget(): EditTarget | null {
    return this.openResource && (this.route.edit === 'general' || this.route.edit === 'endpoints') ? this.route.edit : null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.editTarget ? 'edit' : 'view';
  }

  private openView(resource: PartnerResource) {
    dispatchRecordRoute(this, { id: resource.resourceId, edit: null, create: false });
  }

  private closePanel() {
    const key = this.route.id;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private async cancelEditor() {
    if (JSON.stringify(this.draft) !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  private resetDraft() {
    const resource = this.openResource;
    this.draft = this.editorMode === 'create' || !resource ? { resourceId: '', resource: '', audience: [] } : { resourceId: resource.resourceId, resource: resource.resource, audience: [...resource.audience] };
    this.formError = '';
    this.submitted = false;
    this.isSaving = false;
    this.draftBaseline = JSON.stringify(this.draft);
  }

  private get draftErrors(): Partial<Record<keyof Draft, string>> {
    const errors: Partial<Record<keyof Draft, string>> = {};
    if (this.editorMode === 'create') {
      const id = validateResourceId(this.draft.resourceId.trim());
      if (!id.valid) errors.resourceId = id.error ?? 'Invalid resource ID.';
      else if (this.resources.some(resource => resource.resourceId === this.draft.resourceId.trim())) errors.resourceId = 'You already have a resource with this ID.';
    }
    const uri = validateResourceUri(this.draft.resource.trim());
    if (!uri.valid) errors.resource = uri.error ?? 'Invalid upstream URI.';
    return errors;
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.submitted = true;
    if (Object.keys(this.draftErrors).length > 0) return;
    this.isSaving = true;
    this.formError = '';
    try {
      if (this.editorMode === 'create') {
        const { resource, secret } = await createResource({ resourceId: this.draft.resourceId.trim(), resource: this.draft.resource.trim(), audience: this.draft.audience, endpoints: [] });
        this.resources = [resource, ...this.resources];
        this.issued = { resourceId: resource.resourceId, secret, action: 'created' };
        this.copyFeedback = '';
        dispatchRecordRoute(this, { id: resource.resourceId, edit: null, create: false });
      } else {
        const resource = this.openResource!;
        const saved = await updateResource({ resourceId: resource.resourceId, resource: this.draft.resource.trim(), audience: this.draft.audience });
        this.resources = this.resources.map(candidate => candidate.resourceId === resource.resourceId ? saved : candidate);
        dispatchRecordRoute(this, { edit: null, create: false });
      }
    } catch (error) {
      this.formError = writeErrorMessage(error);
    } finally {
      this.isSaving = false;
    }
  }

  private async handleEndpointsSave(resource: PartnerResource, detail: EndpointsSaveDetail) {
    if (this.isSaving) return;
    this.isSaving = true;
    this.formError = '';
    const replaced = new Set<string | number>([...detail.deleteEndpoints, ...detail.createEndpoints.map(endpoint => endpoint.id).filter((id): id is string | number => id !== undefined)]);
    const endpoints: ResourceEndpoint[] = [
      ...resource.endpoints.filter(endpoint => !replaced.has(endpoint.id)),
      ...detail.createEndpoints.map((endpoint, index) => ({ ...endpoint, id: endpoint.id ?? `new-${Date.now()}-${index}`, allow: endpoint.allow ?? undefined, stepUpCondition: endpoint.stepUpCondition ?? undefined, stepUpAcr: endpoint.stepUpAcr ?? undefined, maxAge: endpoint.maxAge ?? undefined, rateLimit: endpoint.rateLimit ?? undefined } as ResourceEndpoint)),
    ];
    try {
      const saved = await updateResource({ resourceId: resource.resourceId, resource: resource.resource, audience: resource.audience, endpoints });
      this.resources = this.resources.map(candidate => candidate.resourceId === resource.resourceId ? saved : candidate);
      dispatchRecordRoute(this, { edit: null, create: false });
    } catch (error) {
      this.formError = writeErrorMessage(error);
    } finally {
      this.isSaving = false;
    }
  }

  private async handleRotate(resource: PartnerResource) {
    const confirmed = await confirmDestructiveAction({
      title: 'Rotate secret',
      messagePrefix: 'Rotate the secret of ',
      messageSubject: resource.resourceId,
      messageSuffix: '? Your edges authenticate to the upstream with the new one after their next sync; the previous one keeps working until you retire it.',
      confirmLabel: 'Rotate',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      const { secret } = await rotateResourceSecret(resource.resourceId);
      this.resources = this.resources.map(candidate => candidate.resourceId === resource.resourceId ? { ...candidate, secretRotation: true } : candidate);
      this.issued = { resourceId: resource.resourceId, secret, action: 'rotated' };
      this.copyFeedback = '';
    } catch (error) {
      this.errorMessage = writeErrorMessage(error);
    }
  }

  private async handleDelete(resource: PartnerResource) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete resource',
      messagePrefix: 'Delete resource ',
      messageSubject: resource.resourceId,
      messageSuffix: '? Your edges stop proxying it at the next sync. Tokens already issued are unaffected.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    this.errorMessage = '';
    try {
      await deleteResource(resource.resourceId);
      this.resources = this.resources.filter(candidate => candidate.resourceId !== resource.resourceId);
      if (this.route.id === resource.resourceId) this.closePanel();
    } catch (error) {
      this.errorMessage = writeErrorMessage(error);
    }
  }

  private async copySecret() {
    if (!this.issued) return;
    this.copyFeedback = (await copyToClipboard(this.issued.secret)) ? 'Secret copied.' : 'Could not copy the secret.';
  }

  /** Evaluates the saved `allow` and step-up rules of one endpoint against the sample. CEL as the edge runs it, on the sample only. */
  private runSample(resource: PartnerResource) {
    const endpoint = resource.endpoints.find(candidate => String(candidate.id) === this.sampleEndpoint) ?? resource.endpoints[0];
    if (!endpoint) {
      this.sampleResult = { ok: false, text: 'No endpoint to test.' };
      return;
    }
    let context: Record<string, unknown>;
    try {
      context = JSON.parse(this.sampleToken) as Record<string, unknown>;
    } catch {
      this.sampleResult = { ok: false, text: 'The sample is not valid JSON.' };
      return;
    }
    try {
      const allowed = endpoint.allow?.trim() ? evaluate(endpoint.allow, context) === true : true;
      const stepUp = endpoint.stepUpCondition?.trim() ? evaluate(endpoint.stepUpCondition, context) === true : false;
      const acr = (context.token as { acr?: string } | undefined)?.acr;
      const needsStepUp = stepUp && endpoint.stepUpAcr && acr !== endpoint.stepUpAcr;
      this.sampleResult = {
        ok: allowed && !needsStepUp,
        text: !allowed ? `Refused: ${endpoint.method} ${endpoint.path} · allow is false for this token`
          : needsStepUp ? `Step-up required: ${endpoint.method} ${endpoint.path} asks for acr ${endpoint.stepUpAcr}, the token has ${acr ?? 'none'}`
            : `Allowed: ${endpoint.method} ${endpoint.path}${stepUp ? ' · step-up satisfied' : ' · step-up not required'}`,
      };
    } catch (error) {
      this.sampleResult = { ok: false, text: `The rule failed to evaluate: ${error instanceof Error ? error.message : String(error)}` };
    }
  }

  // ── list ──

  private get filtered(): PartnerResource[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.resources.filter(resource => !query || resource.resourceId.toLowerCase().includes(query) || resource.resource.toLowerCase().includes(query));
  }

  private renderTable() {
    const columns: DataTableColumn<PartnerResource>[] = [
      { id: 'resource', label: 'Resource', cell: resource => html`
        <button type="button" class="row-link" aria-label=${`Open resource ${resource.resourceId} ${resource.resource}`}>
          <span class="row-title">${resource.resourceId}</span>
          <span class="row-id">${resource.resource}</span>
        </button>` },
      { id: 'audience', label: 'Audience', cell: resource => valueChips(resource.audience, { empty: 'No client' }) },
      { id: 'endpoints', label: 'Endpoints', cell: resource => valueChips(resource.endpoints.map(endpoint => `${endpoint.method} ${endpoint.path}`), { max: 2, empty: 'None yet' }) },
      { id: 'secret', label: 'Secret', cell: resource => resource.secretRotation ? html`<span class="chip warn">Previous secret kept</span>` : html`<span class="muted">current</span>` },
    ];
    const menuItems = (resource: PartnerResource): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(resource) },
      { label: 'Copy resource ID', onSelect: () => void copyToClipboard(resource.resourceId) },
      ...(this.canManage ? [{ label: 'Delete resource', danger: true, ariaLabel: `Delete resource ${resource.resourceId}`, onSelect: () => void this.handleDelete(resource) }] : []),
    ];
    return html`
      <versola-data-table selectable .columns=${columns} .rows=${this.filtered} .rowKey=${(resource: PartnerResource) => resource.resourceId}
        .selectedKey=${this.editorMode === 'view' ? this.route.id : null} .menuItems=${menuItems} .menuLabel=${(resource: PartnerResource) => `Actions for ${resource.resourceId}`}
        @row-select=${(e: CustomEvent<{ row: PartnerResource }>) => this.openView(e.detail.row)}></versola-data-table>`;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private renderBanners(resource: PartnerResource) {
    const issued = this.issued?.resourceId === resource.resourceId ? this.issued : null;
    return issued ? html`
      <div class="banner ok key-banner" role="status">
        <div class="banner-text">
          <div class="banner-title">${statusIcon('check')}Secret ${issued.action}. Save it now: it is not shown again.</div>
          <div>Your edges authenticate to <span class="mono">${resource.resource}</span> with it.</div>
          <div class="secret-rows"><div><div class="secret-label">Secret</div><pre class="secret-value">${issued.secret}</pre></div></div>
          <div class="banner-actions">
            <button type="button" class="btn btn-primary btn-sm" @click=${() => this.copySecret()}>Copy secret</button>
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.issued = null; }}>I've saved it</button>
            ${this.copyFeedback ? html`<span class="copy-feedback" role="status">${this.copyFeedback}</span>` : ''}
          </div>
        </div>
      </div>` : '';
  }

  private renderGroups(resource: PartnerResource) {
    const me = this.me;
    const edit = this.canManage;
    return html`
      <versola-record-card heading="General" ?editable=${edit} .body=${html`
        ${this.row('Upstream', html`<span class="mono">${resource.resource}</span>`)}
        ${this.row('Reached at', html`<span class="mono">${me.edge.publicUrl ?? ''}/resources/${resource.resourceId}/…</span>`)}
        ${this.row('Audience', resource.audience.length ? html`<div class="tag-list">${resource.audience.map(id => html`<span class="tag">${id}</span>`)}</div>` : html`<span class="muted">No client may call it yet.</span>`)}
      `} @edit=${() => dispatchRecordRoute(this, { edit: 'general', create: false })}></versola-record-card>

      <versola-record-card heading="Secret" subheading="Your edge authenticates to the upstream with it (Basic auth)." .body=${html`
        ${this.row('Status', resource.secretRotation ? html`<span class="chip warn">Previous secret kept</span>` : html`Current`)}
        ${edit ? html`<versola-record-row .value=${html`<div class="key-actions"><button type="button" class="btn btn-secondary btn-sm" @click=${() => this.handleRotate(resource)}>Rotate secret</button></div>`}></versola-record-row>` : ''}
      `}></versola-record-card>

      <versola-record-card heading="Endpoints" subheading="Rules see the token's claims and scopes and, where enabled, /userinfo. Roles are the operator's, so rules are written on scopes and claims." ?editable=${edit} editLabel="Edit endpoints" .body=${html`
        ${resource.endpoints.length ? html`<versola-resource-endpoints-table .endpoints=${resource.endpoints} .resourceId=${resource.resourceId}></versola-resource-endpoints-table>` : this.row('Endpoints', html`<span class="muted">No endpoints yet.</span>`)}
      `} @edit=${() => dispatchRecordRoute(this, { edit: 'endpoints', create: false })}></versola-record-card>

      <versola-record-card heading="Limits from the operator" .body=${html`
        ${this.row('Edge rate policy', html`<span class="mono">${me.partner.limits.rateLimitPolicy}</span>: checked at your edge together with your own endpoint limits; the smaller wins`, 'Operator')}
        ${this.row('Step-up ACR', html`<span class="mono">sensitive</span> available`, 'Operator')}
      `}></versola-record-card>

      ${edit ? html`
        <versola-danger-card heading="Delete resource" text="Your edges stop proxying it at the next sync. Tokens already issued are unaffected."
          actionLabel="Delete resource" .actionAriaLabel=${`Delete resource ${resource.resourceId}`} @action=${() => this.handleDelete(resource)}></versola-danger-card>` : ''}
    `;
  }

  private renderMeta(resource: PartnerResource) {
    return html`<span class="chip">Internal</span>${resource.secretRotation ? html`<span class="chip warn">Secret rotation</span>` : ''}`;
  }

  private renderEditor(resource: PartnerResource | null) {
    const errors = this.submitted ? this.draftErrors : {};
    const create = this.editorMode === 'create';
    return html`
      <form id="resource-editor" novalidate @submit=${this.handleSubmit}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        <div class="editor-box">
          <h3>${create ? 'New resource' : 'General'}</h3>
          <p class="editor-sub">Stored under ${this.me.partner.displayName} and synced only to your edges; the operator sees the audience, not your rules.</p>
          ${create ? html`
            <div class="form-group">
              <label for="resource-id">Resource ID *</label>
              <input type="text" id="resource-id" class="compact-input mono ${errors.resourceId ? 'input-error' : ''}" .value=${this.draft.resourceId} placeholder="pilot-orders"
                aria-invalid=${errors.resourceId ? 'true' : nothing} aria-describedby="resource-id-hint"
                @input=${(e: Event) => this.draft = { ...this.draft, resourceId: (e.target as HTMLInputElement).value }} />
              ${errors.resourceId ? html`<div class="error-message" id="resource-id-hint" role="alert">${errors.resourceId}</div>` : html`<div class="hint" id="resource-id-hint">The path on your edge: <span class="mono">${this.me.edge.publicUrl}/resources/${this.draft.resourceId || '…'}/</span>. Fixed once created.</div>`}
            </div>` : ''}
          <div class="form-group">
            <label for="resource-uri">Upstream URI *</label>
            <input type="url" id="resource-uri" class="compact-input mono ${errors.resource ? 'input-error' : ''}" .value=${this.draft.resource} placeholder="http://orders.internal:8080"
              aria-invalid=${errors.resource ? 'true' : nothing} aria-describedby="resource-uri-hint"
              @input=${(e: Event) => this.draft = { ...this.draft, resource: (e.target as HTMLInputElement).value }} />
            ${errors.resource ? html`<div class="error-message" id="resource-uri-hint" role="alert">${errors.resource}</div>` : html`<div class="hint" id="resource-uri-hint">Where your edge forwards the request.</div>`}
          </div>
          <div class="form-group">
            <span class="checkbox-list" role="group" aria-labelledby="resource-audience-label">
              <label id="resource-audience-label">Audience</label>
              ${this.clients.length ? this.clients.map(client => html`
                <label class="checkbox-row">
                  <input type="checkbox" .checked=${this.draft.audience.includes(client.id)}
                    @change=${(e: Event) => {
                      const checked = (e.target as HTMLInputElement).checked;
                      this.draft = { ...this.draft, audience: checked ? [...this.draft.audience, client.id] : this.draft.audience.filter(id => id !== client.id) };
                    }} />
                  <span class="mono">${client.id}</span><span class="muted small">${client.clientName}${client.approval.status !== 'approved' ? ' · not approved yet' : ''}</span>
                </label>`) : html`<span class="muted small">No clients yet.</span>`}
            </span>
            <div class="hint">Your clients only; tokens issued to them may call this resource.</div>
          </div>
        </div>
        ${resource ? '' : html`<div class="hint">The secret your edge authenticates to the upstream with is generated now and shown once on the record.</div>`}
      </form>`;
  }

  private renderEndpointsEditor(resource: PartnerResource) {
    const endpoints = resource.endpoints;
    return html`
      <div class="endpoint-layout">
        <versola-resource-endpoints-editor .endpoints=${endpoints} .resourceId=${resource.resourceId} .resourceUri=${resource.resource}
          .acrVocabulary=${{}} ?saving=${this.isSaving} .error=${this.formError}
          @save=${(e: CustomEvent<EndpointsSaveDetail>) => this.handleEndpointsSave(resource, e.detail)}
          @cancel=${() => dispatchRecordRoute(this, { edit: null, create: false })}></versola-resource-endpoints-editor>
        <div class="endpoint-aside record-compact">
          <versola-record-card heading="Test with a sample token" subheading="Evaluates the saved allow and step-up rules of an endpoint against this sample, as your edge would. Save first to test a new rule." .body=${html`
            <div class="record-list test-box">
              <div class="form-group">
                <label for="sample-endpoint">Endpoint</label>
                <select id="sample-endpoint" .value=${this.sampleEndpoint} @change=${(e: Event) => this.sampleEndpoint = (e.target as HTMLSelectElement).value}>
                  ${endpoints.map(endpoint => html`<option value=${String(endpoint.id)}>${endpoint.method} ${endpoint.path}</option>`)}
                </select>
              </div>
              <div class="form-group">
                <label for="sample-token">Sample: token, user, request</label>
                <textarea id="sample-token" .value=${this.sampleToken} spellcheck="false" @input=${(e: Event) => this.sampleToken = (e.target as HTMLTextAreaElement).value}></textarea>
                <div class="hint">The variables a rule sees: <span class="mono">token</span>, <span class="mono">user</span> (when /userinfo is fetched) and <span class="mono">request</span>.</div>
              </div>
              <div class="key-actions"><button type="button" class="btn btn-secondary btn-sm" @click=${() => this.runSample(resource)}>Evaluate</button></div>
              ${this.sampleResult ? html`<div role="status" class=${this.sampleResult.ok ? 'result-ok' : 'result-no'}>${this.sampleResult.text}</div>` : ''}
            </div>
          `}></versola-record-card>
          <versola-record-card heading="Applies" .body=${html`
            ${this.row('Saved with the operator', html`<span class="chip ok">at once</span>`)}
            ${this.row('Live on your edges', html`<span class="chip">next sync</span>`)}
            ${this.row('Step-up ACR', html`<span class="mono">sensitive</span>, the operator's built-in`, 'Operator')}
          `}></versola-record-card>
        </div>
      </div>
    `;
  }

  private renderPanel(resource: PartnerResource | null) {
    const mode = this.editorMode;
    if (mode === 'view' && !resource) return '';
    if (mode === 'edit' && this.editTarget === 'endpoints') return '';
    const heading = mode === 'create' ? 'Add resource' : mode === 'edit' ? 'Edit general' : resource!.resourceId;
    return html`
      <versola-side-panel kind="Resource" .heading=${heading} .recordId=${resource?.resourceId ?? ''} idLabel="resource ID" .mode=${mode} .parentLabel=${resource?.resourceId ?? ''}
        .meta=${resource && mode === 'view' ? this.renderMeta(resource) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()} @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">${mode === 'view' ? html`${this.renderBanners(resource!)}${this.renderGroups(resource!)}` : this.renderEditor(resource)}</div>
        ${mode !== 'view' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="resource-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : mode === 'create' ? 'Add resource' : 'Save general'}</button>
          </div>` : ''}
      </versola-side-panel>`;
  }

  render() {
    const resource = this.openResource;
    if (resource && this.editTarget === 'endpoints') return this.renderEndpointsEditor(resource);
    const panel = this.renderPanel(resource);
    if (resource && this.route.layout === 'page') {
      return html`
        <versola-record-header .crumbs=${[{ label: 'Resources', onSelect: () => this.closePanel() }, { label: resource.resourceId }]}
          .heading=${resource.resourceId} .recordId=${resource.resourceId} idLabel="resource ID" idAsTitle .meta=${this.renderMeta(resource)}></versola-record-header>
        ${this.renderBanners(resource)}
        <div class="record-page">${this.renderGroups(resource)}</div>
        ${this.editorMode === 'view' ? '' : panel}`;
    }
    return html`
      <content-header title="Resources" description="Your own APIs behind your edge. Stored with the operator under your organisation and synced only to your edges; the operator sees the audience, not your rules.">
        ${this.resources.length > 0 && this.canManage ? html`<button slot="actions" class="btn btn-primary" @click=${() => dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null })}>+ Add resource</button>` : ''}
      </content-header>
      ${this.errorMessage && this.resources.length > 0 ? html`<div class="banner warn" role="alert"><div class="banner-text"><div class="banner-title">${statusIcon('alert')}${this.errorMessage}</div></div></div>` : ''}
      ${this.isLoading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
        : this.errorMessage && this.resources.length === 0 ? html`<versola-error-card heading="Could not load resources" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>`
          : this.resources.length === 0 ? html`
            <versola-empty-state heading="No resources yet" text=${this.canManage ? 'Add an API your edge proxies and protects.' : 'No resources registered.'}>
              ${this.canManage ? html`<button class="btn btn-primary" @click=${() => dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null })}>+ Add resource</button>` : ''}
            </versola-empty-state>`
            : html`
              <div class="list-toolbar">
                <input class="search-input" type="search" aria-label="Search resources" placeholder="Search resources" .value=${this.searchQuery} @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value} />
              </div>
              ${this.filtered.length === 0 ? html`<versola-empty-state heading="No resources match"></versola-empty-state>` : this.renderTable()}`}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-partner-resources': VersolaPartnerResources;
  }
}
