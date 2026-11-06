import { LitElement, html, css, nothing, type PropertyValues, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, recordStyles, tagStyles } from '../styles/components';
import type { OAuthClient, Resource, ResourceEndpointId } from '../types';
import { deletePreviousResourceSecret, deleteResource, rotateResourceSecret, updateResource } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { declaredFromDraft, declaredSummary, draftFromDeclared, hasErrors as declaredHasErrors, type AudienceRateLimit, type DeclaredDraft } from '../utils/declared-rate-limit';
import { copyToClipboard } from '../utils/helpers';
import { dispatchRecordRoute } from '../utils/record-route';
import { validateResourceUri } from '../utils/validators';
import { breakHost } from './break-text';
import { statusIcon } from './status-icon';
import type { EndpointsSaveDetail } from './resource-endpoints-editor';
import './client-picker';
import './danger-card';
import './declared-rate-limit-editor';
import './nav-toggle';
import './record-card';
import './record-header';
import './resource-endpoints-editor';
import './resource-endpoints-table';

export type ResourceEditGroup = 'general' | 'audience' | 'endpoints';
export type IssuedSecret = { resourceId: string; secret: string; action: 'created' | 'rotated' };

/** The permissions linked to any endpoint of `resource`: they lose it when the resource goes. */
export function linkedPermissionsOf(resource: Resource, permissionsByEndpoint: Map<ResourceEndpointId, string[]>): string[] {
  const ids = new Set<string>();
  for (const endpoint of resource.endpoints) for (const id of permissionsByEndpoint.get(endpoint.id) ?? []) ids.add(id);
  return [...ids].sort((a, b) => a.localeCompare(b));
}

/** The D5 confirm of Delete resource: names the resource, the route that stops and the permissions that detach. */
export function confirmResourceDelete(resource: Resource, permissionsByEndpoint: Map<ResourceEndpointId, string[]>): Promise<boolean> {
  const linked = linkedPermissionsOf(resource, permissionsByEndpoint);
  const detach = linked.length
    ? ` ${linked.join(', ')} ${linked.length === 1 ? 'is' : 'are'} linked to its endpoints and ${linked.length === 1 ? 'becomes' : 'become'} detached.`
    : ' No permission is linked to its endpoints.';
  return confirmDestructiveAction({
    title: 'Delete resource',
    messagePrefix: 'Delete resource ',
    messageSubject: resource.resourceId,
    messageSuffix: `? Edge stops forwarding /resources/${resource.resourceId}/*.${detach} This can't be undone.`,
    confirmLabel: 'Delete',
  });
}

const EDIT_GROUPS: readonly ResourceEditGroup[] = ['general', 'audience', 'endpoints'];
const EDIT_TITLES: Record<Exclude<ResourceEditGroup, 'endpoints'>, string> = { general: 'Edit general', audience: 'Edit audience' };

/**
 * One resource's page: where edge forwards its requests (URI and the route that reaches it), the
 * clients that may ask for tokens for it, its endpoints with the rules each one carries, the secret
 * edge authenticates with (internal resources), what is fixed, and delete. Each group that can change
 * has its own Edit that opens only that group as a page (`&edit=general|audience|endpoints`); the
 * endpoints open the master / detail editor. The page has no primary action. A secret issued by
 * create or rotate is shown once, in a banner at the top.
 *
 * The record does not fetch: the list owns the loaded set and passes the resource in; the record
 * asks for a change with `resource-updated`, `resource-deleted`, `secret-issued` and `secret-dismiss`.
 */
@customElement('versola-resource-record')
export class VersolaResourceRecord extends LitElement {
  @property({ attribute: false }) resource!: Resource;
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ type: String }) edit: string | null = null;
  @property({ attribute: false }) clients: OAuthClient[] = [];
  @property({ attribute: false }) permissionsByEndpoint: Map<ResourceEndpointId, string[]> = new Map();
  @property({ attribute: false }) acrVocabulary: Record<string, string[]> = {};
  /** A secret issued for this resource and not yet dismissed. */
  @property({ attribute: false }) issuedSecret: IssuedSecret | null = null;

  @state() private uriDraft = '';
  @state() private audienceDraft: string[] = [];
  /** What the resource sets for each audience client, as being edited; a client with no entry (or null) has no limit. */
  @state() private limitDrafts: Record<string, DeclaredDraft | null> = {};
  @state() private limitsSubmitted = false;
  @state() private saving = false;
  @state() private saveError = '';
  @state() private actionError = '';
  @state() private working = false;
  @state() private copyFeedback = '';
  @state() private submitted = false;
  private editorFor: string | null = null;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    recordStyles,
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

      section.record-card,
      versola-record-card {
        margin-bottom: var(--spacing-md);
      }

      .limits-box {
        margin-top: var(--spacing-md);
      }

      details.client-limit {
        padding: 0.75rem 0;
        border-top: 1px solid var(--border-subtle);
      }

      details.client-limit > versola-declared-rate-limit-editor {
        margin-top: var(--spacing-sm);
      }

      .limit-lines {
        display: grid;
        gap: 0.25rem;
        margin-top: var(--spacing-sm);
        font-size: var(--text-sm);
      }

      .limit-line {
        display: flex;
        flex-wrap: wrap;
        gap: 0.125rem 0.75rem;
      }

      .grid-2 {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: var(--spacing-md);
        margin-bottom: var(--spacing-md);
        align-items: stretch;
      }

      .grid-2 > section.record-card,
      .grid-2 > versola-record-card {
        margin-bottom: 0;
      }

      .grid-2 > versola-record-card > .card {
        height: 100%;
      }

      /* Two cards side by side leave each about 550px: a narrower label column keeps a host on one line. */
      @media (min-width: 721px) {
        .grid-2 .record-row {
          grid-template-columns: 8rem minmax(0, 1fr) auto;
          gap: 0.25rem var(--spacing-md);
        }
      }

      .grid-2 > .full {
        grid-column: 1 / -1;
      }

      .mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: normal;
        word-break: normal;
      }

      .muted {
        color: var(--text-secondary);
      }

      .pad {
        padding: var(--spacing-md) var(--spacing-lg);
      }

      .empty-note {
        padding: var(--spacing-lg);
        color: var(--text-secondary);
      }

      .table-wrap {
        overflow-x: auto;
      }

      .secret-banner {
        display: block;
      }

      .secret-banner .banner-text {
        flex: 1 1 100%;
      }

      .secret-value {
        margin: var(--spacing-md) 0 0;
        padding: 0.625rem 0.75rem;
        background: var(--bg-page);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        line-height: 1.5;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
      }

      .banner-actions {
        display: flex;
        flex-wrap: wrap;
        gap: 0.75rem;
        align-items: center;
        margin-top: var(--spacing-md);
      }

      .copy-feedback {
        font-size: var(--text-sm);
        color: var(--success-text);
      }

      .head-actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        justify-content: flex-end;
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

      .form-actions {
        display: flex;
        gap: 1rem;
        justify-content: flex-end;
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-default);
      }

      .editor-error {
        margin: 0 0 var(--spacing-md);
        padding: 0.75rem var(--spacing-md);
        border: 1px solid var(--danger);
        border-radius: var(--radius-inner);
        background: var(--danger-subtle);
        color: var(--danger-text);
        font-size: var(--text-sm);
      }

      input.mono {
        font-size: var(--text-base);
        font-family: var(--font-mono);
      }

      /* The sidebar takes 250px: below 1180px of window the two cards would get under 440px each. */
      @media (max-width: 1180px) {
        .grid-2 {
          grid-template-columns: minmax(0, 1fr);
        }
      }

      @media (max-width: 720px) {
        .form-actions {
          flex-wrap: wrap;
        }
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('beforeunload', this.handleBeforeUnload);
  }

  disconnectedCallback() {
    window.removeEventListener('beforeunload', this.handleBeforeUnload);
    super.disconnectedCallback();
  }

  /** A secret is shown once: closing the tab with it unsaved asks first. */
  private readonly handleBeforeUnload = (event: BeforeUnloadEvent) => {
    if (this.issuedSecret) event.preventDefault();
  };

  protected willUpdate(changed: PropertyValues) {
    if (changed.has('edit') || changed.has('resource')) this.syncEditor();
  }

  // ── routing ─────────────────────────────────────────────────────────────

  private get editGroup(): ResourceEditGroup | null {
    const group = EDIT_GROUPS.find(candidate => candidate === this.edit);
    return group && this.canManage ? group : null;
  }

  private syncEditor() {
    const group = this.editGroup;
    if (!group) {
      this.editorFor = null;
      return;
    }
    if (this.editorFor === `${this.resource.resourceId}:${group}`) return;
    this.editorFor = `${this.resource.resourceId}:${group}`;
    this.saveError = '';
    this.saving = false;
    this.submitted = false;
    this.uriDraft = this.resource.resource;
    this.audienceDraft = [...this.resource.audience];
    this.limitDrafts = Object.fromEntries((this.resource.audienceRateLimits ?? []).map(limit => [limit.clientId, draftFromDeclared(limit.rateLimit)]));
    this.limitsSubmitted = false;
  }

  private openEditor(group: ResourceEditGroup) {
    dispatchRecordRoute(this, { edit: group });
  }

  private closeEditor() {
    dispatchRecordRoute(this, { edit: null });
  }

  private get editorDirty(): boolean {
    if (this.editGroup === 'general') return this.uriDraft.trim() !== this.resource.resource;
    if (this.editGroup === 'audience') {
      return this.audienceDraft.length !== this.resource.audience.length || this.audienceDraft.some(id => !this.resource.audience.includes(id))
        || this.limitsChanged;
    }
    return false;
  }

  private async cancelEditor() {
    if (this.editorDirty && !await confirmDiscardDraft()) return;
    this.closeEditor();
  }

  /** The secret is shown once: leaving the page asks first. */
  private async closeRecord() {
    if (this.issuedSecret) {
      const leave = await confirmDestructiveAction({
        title: 'Leave without saving the secret?',
        message: 'The secret is shown only once. After you leave it cannot be retrieved; you would have to rotate it.',
        confirmLabel: 'Leave',
        cancelLabel: 'Stay',
      });
      if (!leave) return;
      this.dispatchEvent(new CustomEvent('secret-dismiss', { bubbles: true, composed: true }));
    }
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
  }

  private openClient(clientId: string) {
    this.dispatchEvent(new CustomEvent('navigate-view', { detail: { view: 'clients', id: clientId }, bubbles: true, composed: true }));
  }

  // ── saves ───────────────────────────────────────────────────────────────

  private notifyUpdated(resource: Resource) {
    this.dispatchEvent(new CustomEvent<{ resource: Resource }>('resource-updated', { detail: { resource }, bubbles: true, composed: true }));
  }

  private get uriError(): string {
    const uri = this.uriDraft.trim();
    if (!uri && !this.submitted) return '';
    return validateResourceUri(uri).error ?? '';
  }

  private async saveGeneral(event: Event) {
    event.preventDefault();
    if (this.saving) return;
    this.submitted = true;
    if (this.uriError) return;
    const resource = this.uriDraft.trim();
    this.saving = true;
    this.saveError = '';
    try {
      await updateResource(this.resource.resourceId, { resource });
      this.notifyUpdated({ ...this.resource, resource });
      this.closeEditor();
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'Failed to save the resource';
    } finally {
      this.saving = false;
    }
  }

  /** The limits as typed, for the clients that are in the audience being saved: a client taken out of it keeps none. */
  private get draftedLimits(): AudienceRateLimit[] {
    return this.audienceDraft.flatMap(clientId => {
      const rateLimit = declaredFromDraft(this.limitDrafts[clientId] ?? null);
      return rateLimit ? [{ clientId, rateLimit }] : [];
    });
  }

  private get limitsChanged(): boolean {
    // both sides through the same normalisation, so the order of the members and the defaults left out do not count as a change
    const normalised = (limit: AudienceRateLimit) => ({ clientId: limit.clientId, rateLimit: declaredFromDraft(draftFromDeclared(limit.rateLimit)) });
    const stored = JSON.stringify((this.resource.audienceRateLimits ?? []).map(normalised).sort((a, b) => a.clientId.localeCompare(b.clientId)));
    const typed = JSON.stringify([...this.draftedLimits].sort((a, b) => a.clientId.localeCompare(b.clientId)));
    return stored !== typed;
  }

  private async saveAudience() {
    if (this.saving) return;
    const wrong = this.audienceDraft.filter(clientId => declaredHasErrors(this.limitDrafts[clientId] ?? null, 'audience'));
    if (wrong.length > 0) {
      this.limitsSubmitted = true;
      void this.updateComplete.then(() => {
        const details = this.renderRoot.querySelector<HTMLDetailsElement>('details.limits');
        if (details) details.open = true;
        this.renderRoot.querySelector<HTMLDetailsElement>(`details[data-client="${wrong[0]}"]`)?.setAttribute('open', '');
        void this.updateComplete.then(() => this.renderRoot.querySelector<HTMLElement & { focusFirstError(): void }>(`details[data-client="${wrong[0]}"] versola-declared-rate-limit-editor`)?.focusFirstError());
      });
      return;
    }
    this.saving = true;
    this.saveError = '';
    try {
      const limits = this.draftedLimits;
      await updateResource(this.resource.resourceId, {
        audience: { existing: this.resource.audience, next: this.audienceDraft },
        ...(this.limitsChanged ? { audienceRateLimits: limits } : {}),
      });
      this.notifyUpdated({ ...this.resource, audience: [...this.audienceDraft], audienceRateLimits: limits });
      this.closeEditor();
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'Failed to save the audience';
    } finally {
      this.saving = false;
    }
  }

  private async saveEndpoints(event: CustomEvent<EndpointsSaveDetail>) {
    if (this.saving) return;
    const { deleteEndpoints, createEndpoints } = event.detail;
    this.saving = true;
    this.saveError = '';
    try {
      const written = await updateResource(this.resource.resourceId, { deleteEndpoints, createEndpoints });
      const removed = new Set(deleteEndpoints);
      const replaced = new Set(written.map(endpoint => endpoint.id));
      const kept = this.resource.endpoints.filter(endpoint => !removed.has(endpoint.id) && !replaced.has(endpoint.id));
      const saved = written.map(endpoint => ({
        id: endpoint.id,
        method: endpoint.method,
        path: endpoint.path,
        fetchUserInfo: endpoint.fetchUserInfo,
        ...(endpoint.allow != null ? { allow: endpoint.allow } : {}),
        inject: endpoint.inject.map(rule => ({ ...rule })),
        ...(endpoint.stepUpCondition != null ? { stepUpCondition: endpoint.stepUpCondition } : {}),
        ...(endpoint.stepUpAcr != null ? { stepUpAcr: endpoint.stepUpAcr } : {}),
        ...(endpoint.maxAge != null ? { maxAge: endpoint.maxAge } : {}),
        ...(endpoint.rateLimit ? { rateLimit: endpoint.rateLimit } : {}),
      }));
      // The record lists them in the order the editor had them: the old position, then the new ones.
      const order = new Map(this.resource.endpoints.map((endpoint, index) => [endpoint.id, index]));
      const endpoints = [...kept, ...saved].sort((a, b) => (order.get(a.id) ?? Infinity) - (order.get(b.id) ?? Infinity));
      this.notifyUpdated({ ...this.resource, endpoints });
      this.closeEditor();
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'Failed to save the endpoints';
    } finally {
      this.saving = false;
    }
  }

  // ── secret ──────────────────────────────────────────────────────────────

  private async rotateSecret() {
    if (this.working) return;
    this.working = true;
    this.actionError = '';
    try {
      const secret = await rotateResourceSecret(this.resource.resourceId);
      this.copyFeedback = '';
      this.notifyUpdated({ ...this.resource, hasSecret: true, hasPreviousSecret: true });
      this.dispatchEvent(new CustomEvent<IssuedSecret>('secret-issued', {
        detail: { resourceId: this.resource.resourceId, secret, action: 'rotated' }, bubbles: true, composed: true,
      }));
    } catch (error) {
      this.actionError = error instanceof Error ? error.message : 'Failed to rotate the secret';
    } finally {
      this.working = false;
    }
  }

  private async activateNewSecret() {
    if (this.working) return;
    const confirmed = await confirmDestructiveAction({
      title: 'Activate new secret',
      messagePrefix: 'Activate the new secret for ',
      messageSubject: this.resource.resourceId,
      messageSuffix: '? Do this only after the upstream accepts the new secret. Edge will switch after its next sync.',
      confirmLabel: 'Activate',
    });
    if (!confirmed) return;
    this.working = true;
    this.actionError = '';
    try {
      await deletePreviousResourceSecret(this.resource.resourceId);
      this.notifyUpdated({ ...this.resource, hasPreviousSecret: false });
    } catch (error) {
      this.actionError = error instanceof Error ? error.message : 'Failed to activate the new secret';
    } finally {
      this.working = false;
    }
  }

  private async copySecret() {
    if (!this.issuedSecret) return;
    const copied = await copyToClipboard(this.issuedSecret.secret);
    this.copyFeedback = copied ? 'Secret copied to clipboard.' : 'Could not copy the secret.';
  }

  private dismissSecret() {
    this.copyFeedback = '';
    this.dispatchEvent(new CustomEvent('secret-dismiss', { bubbles: true, composed: true }));
  }

  // ── delete ──────────────────────────────────────────────────────────────

  private async deleteThis() {
    const resource = this.resource;
    if (!await confirmResourceDelete(resource, this.permissionsByEndpoint)) return;
    this.actionError = '';
    try {
      await deleteResource(resource.resourceId);
      this.dispatchEvent(new CustomEvent('resource-deleted', { detail: { resourceId: resource.resourceId }, bubbles: true, composed: true }));
    } catch (error) {
      this.actionError = error instanceof Error ? error.message : 'Failed to delete the resource';
    }
  }

  // ── rendering ───────────────────────────────────────────────────────────

  private row(label: string, value: unknown) {
    return html`<versola-record-row .label=${label} .value=${value}></versola-record-row>`;
  }

  /** A card with its actions in the head: Edit, or Rotate / Activate for the secret. */
  private card(id: string, heading: string, subheading: string, actions: unknown, body: unknown) {
    return html`
      <section class="card record-card" id=${id} aria-label=${heading}>
        <div class="record-card-head">
          <div>
            <h2>${heading}</h2>
            ${subheading ? html`<div class="record-card-sub">${subheading}</div>` : nothing}
          </div>
          ${actions ? html`<div class="head-actions">${actions}</div>` : nothing}
        </div>
        ${body}
      </section>`;
  }

  private renderBanners(resource: Resource): TemplateResult {
    const issued = this.issuedSecret?.resourceId === resource.resourceId ? this.issuedSecret : null;
    return html`
      ${this.actionError ? html`
        <div class="banner warn" role="alert">
          <div class="banner-text"><div class="banner-title">${statusIcon('alert')}Something went wrong</div><div>${this.actionError}</div></div>
          <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.actionError = ''; }}>Dismiss</button>
        </div>` : nothing}
      ${issued ? html`
        <div class="banner ok secret-banner" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('check')}${issued.action === 'rotated' ? `Secret rotated: ${issued.resourceId}` : `Resource created: ${issued.resourceId}`}</div>
            <div>${issued.action === 'rotated'
              ? 'Copy the new secret now. It is shown only once, and the old one keeps working until you activate the new one.'
              : 'Copy this secret now. It is shown only once.'}</div>
            <pre class="secret-value">${issued.secret}</pre>
            <div class="banner-actions">
              <button type="button" class="btn btn-primary btn-sm" @click=${this.copySecret}>Copy secret</button>
              <button type="button" class="btn btn-secondary btn-sm" @click=${this.dismissSecret}>I've saved it</button>
              ${this.copyFeedback ? html`<span class="copy-feedback" role="status">${this.copyFeedback}</span>` : nothing}
            </div>
          </div>
        </div>` : nothing}
      ${resource.hasPreviousSecret ? html`
        <div class="banner warn" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('alert')}A new secret was issued</div>
            <div>Activate it once the upstream accepts it; edge switches to it after its next sync.</div>
          </div>
          ${this.canManage ? html`<button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.working}
            @click=${this.activateNewSecret}>Activate new secret</button>` : nothing}
        </div>` : nothing}`;
  }

  private renderBody(resource: Resource): TemplateResult {
    const clientIds = new Set(this.clients.map(client => client.id));
    const typeText = resource.hasSecret ? 'Internal: edge authenticates with its secret' : "Public: edge forwards the caller's token";
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Resources', onSelect: () => void this.closeRecord() }, { label: resource.resourceId }]}
        .heading=${resource.resourceId} .recordId=${resource.resourceId} idLabel="resource ID" idAsTitle
        .meta=${html`<span class="chip">${resource.hasSecret ? 'Internal' : 'Public'}</span>${resource.hasPreviousSecret ? html`<span class="chip warn">Secret rotation</span>` : nothing}`}
      ></versola-record-header>

      ${this.renderBanners(resource)}

      <div class="grid-2">
        <versola-record-card heading="General" subheading="Where edge forwards requests for this resource." ?editable=${this.canManage}
          @edit=${() => this.openEditor('general')} .body=${html`
          ${this.row('Resource URI', html`<span class="mono">${breakHost(resource.resource)}</span>`)}
          ${this.row('Reached at', html`<code class="mono">/resources/${resource.resourceId}/*</code>`)}
        `}></versola-record-card>

        <versola-record-card heading="Audience" subheading="Clients that may request tokens for this resource." ?editable=${this.canManage}
          @edit=${() => this.openEditor('audience')} .body=${resource.audience.length === 0
            ? html`<div class="pad muted">No clients are assigned to this resource.</div>`
            : html`<div class="pad"><div class="tag-list">
              ${resource.audience.map(id => clientIds.has(id)
                ? html`<button type="button" class="tag" aria-label=${`Open client ${id}`} @click=${() => this.openClient(id)}>${id}</button>`
                : html`<span class="tag quiet" title="This client no longer exists in the tenant">${id}</span>`)}
            </div>
            ${this.renderAudienceLimits(resource)}</div>`}></versola-record-card>
      </div>

      <versola-record-card heading="Endpoints" subheading="What edge forwards for this resource and the rules it applies on the way."
        editLabel="Edit endpoints" ?editable=${this.canManage} @edit=${() => this.openEditor('endpoints')} .body=${resource.endpoints.length === 0
          ? html`<div class="empty-note">No endpoints yet.${this.canManage ? ' Add the first one with Edit endpoints.' : ''}</div>`
          : html`<versola-resource-endpoints-table .endpoints=${resource.endpoints} .permissionsByEndpoint=${this.permissionsByEndpoint}
              .resourceId=${resource.resourceId}></versola-resource-endpoints-table>`}></versola-record-card>

      <div class="grid-2">
        ${resource.hasSecret ? this.card('secret', 'Secret', 'Edge authenticates to this resource with it.',
          // While a new secret waits, the banner above carries the one action (Activate new secret).
          this.canManage && !resource.hasPreviousSecret
            ? html`<button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.working} @click=${this.rotateSecret}>${this.working ? 'Rotating…' : 'Rotate secret'}</button>` : nothing,
          html`
            ${this.row('Current secret', html`<span class="chip ok">In use</span> <span class="muted">Shown once, when it is created or rotated.</span>`)}
            ${resource.hasPreviousSecret ? this.row('New secret', html`<span class="chip warn">Waiting for activation</span>`) : nothing}`) : nothing}
        <versola-record-card class=${resource.hasSecret ? '' : 'full'} heading="Fixed" fixedLabel="Set at creation" .body=${html`
          <versola-record-row label="Resource ID" .value=${html`<span class="mono">${resource.resourceId}</span>`}></versola-record-row>
          <versola-record-row label="Type" .value=${typeText}></versola-record-row>
        `}></versola-record-card>
      </div>

      ${this.canManage ? html`
        <versola-danger-card heading="Delete resource" text="Permissions linked to its endpoints become detached. This can't be undone."
          actionLabel="Delete resource" .actionAriaLabel=${`Delete resource ${resource.resourceId}`}
          @action=${this.deleteThis}></versola-danger-card>` : nothing}`;
  }

  /** What the resource sets for its clients, one line per client that has a limit; nothing when it sets none. */
  private renderAudienceLimits(resource: Resource) {
    const limits = (resource.audienceRateLimits ?? []).filter(limit => resource.audience.includes(limit.clientId));
    if (limits.length === 0) return nothing;
    return html`
      <div class="limit-lines" role="list" aria-label="Rate limits of audience clients">
        ${limits.map(limit => html`
          <div class="limit-line" role="listitem"><span class="mono">${limit.clientId}</span><span class="muted">${declaredSummary(limit.rateLimit, 'audience')}</span></div>`)}
      </div>`;
  }

  /** Rarely changed, so it starts folded, and so does each client: the summaries say what is set. */
  private renderLimitsEditor() {
    const set = this.audienceDraft.filter(clientId => this.limitDrafts[clientId]).length;
    return html`
      <section class="editor-box limits-box" aria-labelledby="audience-limits-title">
        <details class="fold limits">
          <summary><h3 id="audience-limits-title">Rate limits</h3><span class="fold-hint">${set === 0 ? 'None set' : `${set} of ${this.audienceDraft.length} clients`}</span></summary>
          <p class="editor-sub">How much each of these clients may take from this resource. Each limit applies to that client’s calls to the resource, together with the edge’s own rate limit policies, and the smaller limit wins.</p>
          ${this.audienceDraft.length === 0 ? html`<div class="muted">Add clients to the audience to set a limit for them.</div>` : this.audienceDraft.map(clientId => {
            const draft = this.limitDrafts[clientId] ?? null;
            const wrong = this.limitsSubmitted && declaredHasErrors(draft, 'audience');
            return html`
              <details class="fold client-limit" data-client=${clientId} ?open=${wrong}>
                <summary><span class="mono">${clientId}</span><span class="fold-hint">${draft ? declaredSummary(declaredFromDraft(draft), 'audience') : 'No limit'}</span></summary>
                <versola-declared-rate-limit-editor owner="audience" fieldId=${`audience-rl-${clientId}`} .draft=${draft} ?disabled=${this.saving} ?submitted=${this.limitsSubmitted}
                  @draft-change=${(e: CustomEvent<{ draft: DeclaredDraft | null }>) => { this.limitDrafts = { ...this.limitDrafts, [clientId]: e.detail.draft }; }}></versola-declared-rate-limit-editor>
              </details>`;
          })}
        </details>
      </section>`;
  }

  private renderEditorHeader(title: string, resource: Resource) {
    return html`
      <div class="form-header">
        <div class="form-header-lead">
          <versola-nav-toggle></versola-nav-toggle>
          <div class="title-stack">
            <h1 class="form-title">${title}</h1>
            <div class="entity-id-meta">${resource.resourceId}</div>
          </div>
        </div>
      </div>`;
  }

  private renderEditor(group: ResourceEditGroup, resource: Resource): TemplateResult {
    if (group === 'endpoints') {
      return html`
        <versola-resource-endpoints-editor .endpoints=${resource.endpoints} .resourceId=${resource.resourceId} .resourceUri=${resource.resource}
          .permissionsByEndpoint=${this.permissionsByEndpoint} .acrVocabulary=${this.acrVocabulary}
          .saving=${this.saving} .error=${this.saveError}
          @save=${this.saveEndpoints} @cancel=${() => this.closeEditor()}></versola-resource-endpoints-editor>`;
    }
    const error = this.saveError ? html`<div class="editor-error" role="alert">${this.saveError}</div>` : nothing;
    if (group === 'audience') {
      return html`
        ${this.renderEditorHeader(EDIT_TITLES.audience, resource)}
        <div class="card">
          ${error}
          <versola-client-picker label="Audience" .heading=${`Clients in ${this.tenantId ?? 'this tenant'}`} .clients=${this.clients} .value=${this.audienceDraft}
            @change=${(e: CustomEvent<{ value: string[] }>) => { this.audienceDraft = e.detail.value; }}></versola-client-picker>
          ${this.renderLimitsEditor()}
          <div class="form-actions">
            <button type="button" class="btn btn-secondary" ?disabled=${this.saving} @click=${() => this.cancelEditor()}>Cancel</button>
            <button type="button" class="btn btn-primary" ?disabled=${this.saving} @click=${() => this.saveAudience()}>Save audience</button>
          </div>
        </div>`;
    }
    const uriError = this.uriError;
    return html`
      ${this.renderEditorHeader(EDIT_TITLES.general, resource)}
      <div class="card">
        ${error}
        <form @submit=${this.saveGeneral} novalidate>
          <div class="form-group">
            <label for="resource-uri">Absolute resource URI</label>
            <input id="resource-uri" type="url" class="compact-input ${uriError ? 'input-error' : ''}" autocomplete="off" spellcheck="false"
              placeholder="https://api.example.com" .value=${this.uriDraft} ?disabled=${this.saving}
              aria-invalid=${uriError ? 'true' : 'false'} aria-describedby="resource-uri-hint"
              @input=${(e: Event) => { this.uriDraft = (e.target as HTMLInputElement).value; }} />
            ${uriError
              ? html`<div class="error-message" id="resource-uri-hint" role="alert">${uriError}</div>`
              : html`<div class="hint" id="resource-uri-hint">Scheme and host only, no path. Edge forwards <code>/resources/${resource.resourceId}/*</code> to it.</div>`}
          </div>
          <div class="form-actions">
            <button type="button" class="btn btn-secondary" ?disabled=${this.saving} @click=${() => this.cancelEditor()}>Cancel</button>
            <button type="submit" class="btn btn-primary" ?disabled=${this.saving}>Save general</button>
          </div>
        </form>
      </div>`;
  }

  render() {
    const group = this.editGroup;
    return group ? this.renderEditor(group, this.resource) : this.renderBody(this.resource);
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-resource-record': VersolaResourceRecord;
  }
}
