import { LitElement, html, css } from 'lit';
import { customElement, property, query, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../styles/components';
import { codeHighlightStyles } from '../styles/code-highlight';
import type { JwksKeySummary, Tenant } from '../types';
import { createJwk, deleteJwk, fetchJwks, fetchJwksKeys, fetchServerMetadata, fetchTenants, generateJwk, updateJwk, upsertServerMetadata } from '../utils/central-api';
import { tokenize } from '../utils/code-highlight';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { copyToClipboard, validateJsonObject } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import type { NavItem } from './navigation';
import { statusIcon } from './status-icon';
import type { DataTableColumn, RowMenuItem, VersolaDataTable } from './data-table';
import './code-editor';
import './content-header';
import './danger-card';
import './data-table';
import './empty-state';
import './error-card';
import './filter-bar';
import './loading-cards';
import './record-card';
import './record-header';
import './side-panel';

type KeyFilter = 'all' | 'sign' | 'verify';
/** `jwk` edits a key's JSON; `metadata` edits the server metadata overrides; `add` is the paste-a-JWK create. */
type KeysEditor = 'jwk' | 'metadata' | 'add';

type StoredKey = { kid: string; jwk: Record<string, unknown>; summary: JwksKeySummary | null };

// RFC 7517: 'kty' is the only strictly required JWK member; 'kid' is required here so keys can
// be identified and managed individually.
const REQUIRED_JWK_FIELDS = ['kid', 'kty'];

// RFC 8414: the minimal set of members discovery needs end to end.
const REQUIRED_METADATA_FIELDS = ['issuer', 'authorization_endpoint', 'token_endpoint', 'jwks_uri', 'response_types_supported'];

// The algorithms a key can be generated for. ES256 leads (small keys, FAPI 2.0 permits it);
// RS256 is last: FAPI disallows its PKCS#1 v1.5 padding, so only a standard-profile tenant may
// sign under it, but OpenID Connect makes it the algorithm every OP must support.
const ALGORITHMS: Array<{ id: string; title: string; text: string }> = [
  { id: 'ES256', title: 'ES256 (recommended)', text: 'ECDSA on P-256: small keys and signatures, allowed by every profile.' },
  { id: 'PS256', title: 'PS256', text: 'RSASSA-PSS with SHA-256: an RSA key, allowed by every profile.' },
  { id: 'RS256', title: 'RS256', text: 'RSASSA-PKCS1-v1_5: the algorithm every OpenID provider must support, but not selectable by FAPI 2.0 tenants.' },
];

/**
 * Keys & metadata: the published JWKS as a table and, for the one selected, the side panel with
 * the tenants that sign with it, its public JWK, the Fixed group and the danger card; below the
 * table, the RFC 8414 server metadata overrides as a record card. Generate (the one primary
 * action) and Add key open the panel in create mode; the metadata editor is a page.
 */
@customElement('versola-keys-metadata')
export class VersolaKeysMetadata extends LitElement {
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private keys: StoredKey[] = [];
  @state() private tenants: Tenant[] | null = null;
  @state() private tenantsError = '';
  @state() private metadata: Record<string, unknown> | null = null;
  @state() private metadataError = '';
  @state() private filter: KeyFilter = 'all';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private generatedKid: string | null = null;
  @state() private draftAlgorithm = ALGORITHMS[0].id;
  @state() private draftJson = '';
  @state() private formError = '';
  @state() private isSaving = false;
  @query('versola-data-table') private table?: VersolaDataTable<StoredKey>;
  private loadRequestId = 0;
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
    codeHighlightStyles,
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

      .muted {
        color: var(--text-secondary);
      }

      .mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
      }

      .key-json {
        margin: 0;
        padding: var(--spacing-md);
        background: var(--code-bg);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        line-height: 1.6;
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        color: var(--text-primary);
      }

      .json-block {
        padding: var(--spacing-md) var(--spacing-lg) var(--spacing-lg);
      }

      .json-block .btn {
        margin-top: 0.75rem;
      }

      .record-compact .json-block {
        padding: var(--spacing-md);
      }

      .metadata-card {
        margin-top: var(--spacing-xl);
      }

      .editor-card {
        max-width: 54rem;
      }

      .form-actions {
        display: flex;
        justify-content: flex-end;
        gap: 0.75rem;
        margin-top: var(--spacing-lg);
        padding-top: var(--spacing-lg);
        border-top: 1px solid var(--border-default);
      }

      .record-page .record-card {
        margin-bottom: var(--spacing-md);
      }

      /* A link inside a banner is underlined: the banner's hue is the accent's. */
      .banner-link {
        background: none;
        border: 0;
        padding: 0;
        font: inherit;
        color: var(--accent);
        text-decoration: underline;
        cursor: pointer;
      }

      .banner-link:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
        border-radius: var(--radius-sm);
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('route') || changed.has('keys') || changed.has('metadata')) {
      const mode = this.editorMode;
      const editor = this.editor;
      const draftKey = mode === 'view' && !editor ? '' : `${mode}:${editor ?? ''}:${this.route.id ?? ''}`;
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (draftKey) this.resetDraft();
      }
    }
  }

  private async loadData() {
    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const [jwks, summaries, metadata] = await Promise.all([
        fetchJwks(),
        fetchJwksKeys(),
        fetchServerMetadata().then(value => value ?? null, (error: unknown) => { this.metadataError = error instanceof Error ? error.message : 'Failed to load server metadata'; return null; }),
      ]);
      if (requestId !== this.loadRequestId) return;
      this.keys = (jwks.keys ?? []).map(jwk => {
        const kid = typeof jwk.kid === 'string' ? jwk.kid : '';
        return { kid, jwk, summary: summaries.find(summary => summary.kid === kid) ?? null };
      }).sort((a, b) => a.kid.localeCompare(b.kid));
      this.metadata = metadata && Object.keys(metadata).length ? metadata : null;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load the keys';
    } finally {
      if (requestId === this.loadRequestId) this.isLoading = false;
    }
    void this.loadTenants();
  }

  /** "Used by" comes from each tenant's `signingKeyId`; an admin without tenants:read sees a note instead. */
  private async loadTenants() {
    this.tenantsError = '';
    try {
      this.tenants = await fetchTenants();
    } catch (error) {
      this.tenants = null;
      this.tenantsError = error instanceof Error ? error.message : 'Could not load tenants';
    }
  }

  private usedBy(kid: string): Tenant[] {
    return (this.tenants ?? []).filter(tenant => tenant.signingKeyId === kid);
  }

  // ── routing ──

  private get openKey(): StoredKey | null {
    return this.route.id ? this.keys.find(key => key.kid === this.route.id) ?? null : null;
  }

  private get editor(): KeysEditor | null {
    if (!this.canManage) return null;
    if (this.route.create) return this.route.edit === 'add' ? 'add' : null;
    if (this.route.edit === 'metadata') return 'metadata';
    if (this.route.edit === 'jwk' && this.openKey) return 'jwk';
    return null;
  }

  private get editorMode(): 'view' | 'edit' | 'create' {
    return this.route.create ? 'create' : this.editor === 'jwk' ? 'edit' : 'view';
  }

  private openView(key: StoredKey) {
    dispatchRecordRoute(this, { id: key.kid, edit: null, create: false });
  }

  private closePanel() {
    const key = this.route.id;
    dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    if (key) void this.updateComplete.then(() => this.table?.focusRow(key));
  }

  private async cancelEditor() {
    if (this.draftSnapshot !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null, create: false });
  }

  private navigateTo(view: NavItem, id: string | null = null) {
    this.dispatchEvent(new CustomEvent('navigate-view', { detail: { view, id: id ?? undefined }, bubbles: true, composed: true }));
  }

  // ── draft ──

  private get draftSnapshot(): string {
    return JSON.stringify([this.draftAlgorithm, this.draftJson]);
  }

  private resetDraft() {
    const editor = this.editor;
    this.draftAlgorithm = ALGORITHMS[0].id;
    this.draftJson = editor === 'jwk' ? JSON.stringify(this.openKey!.jwk, null, 2) : editor === 'metadata' ? JSON.stringify(this.metadata ?? {}, null, 2) : '';
    this.formError = '';
    this.isSaving = false;
    this.draftBaseline = this.draftSnapshot;
  }

  private get draftJsonError(): string {
    const editor = this.editor;
    if (!this.draftJson.trim()) return '';
    const error = validateJsonObject(this.draftJson, editor === 'metadata' ? REQUIRED_METADATA_FIELDS : REQUIRED_JWK_FIELDS);
    if (error) return error;
    if (editor === 'jwk' && (JSON.parse(this.draftJson) as Record<string, unknown>).kid !== this.openKey?.kid) {
      return "The 'kid' cannot change: delete this key and add a new one instead.";
    }
    if (editor === 'add' && this.keys.some(key => key.kid === (JSON.parse(this.draftJson) as Record<string, unknown>).kid)) {
      return 'A key with this kid already exists.';
    }
    return '';
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    const editor = this.editor;
    if (!editor) return;
    this.isSaving = true;
    this.formError = '';
    try {
      if (editor === 'add' && this.route.create) {
        const error = this.draftJson.trim() ? this.draftJsonError : 'The JWK is required.';
        if (error) { this.formError = error; return; }
        const jwk = JSON.parse(this.draftJson) as Record<string, unknown>;
        await createJwk(jwk);
        await this.loadData();
        dispatchRecordRoute(this, { id: String(jwk.kid), edit: null, create: false });
      } else if (editor === 'jwk') {
        const error = this.draftJson.trim() ? this.draftJsonError : 'The JWK is required.';
        if (error) { this.formError = error; return; }
        const jwk = JSON.parse(this.draftJson) as Record<string, unknown>;
        await updateJwk(jwk);
        this.keys = this.keys.map(key => key.kid === jwk.kid ? { ...key, jwk } : key);
        dispatchRecordRoute(this, { edit: null, create: false });
      } else if (editor === 'metadata') {
        const error = this.draftJson.trim() ? this.draftJsonError : 'The overrides are a JSON object.';
        if (error) { this.formError = error; return; }
        const metadata = JSON.parse(this.draftJson) as Record<string, unknown>;
        await upsertServerMetadata(metadata);
        this.metadata = metadata;
        dispatchRecordRoute(this, { edit: null, create: false });
      }
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save';
    } finally {
      this.isSaving = false;
    }
  }

  private async handleGenerate(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    this.isSaving = true;
    this.formError = '';
    try {
      const generated = await generateJwk(this.draftAlgorithm);
      await this.loadData();
      this.generatedKid = generated.kid;
      dispatchRecordRoute(this, { id: generated.kid, edit: null, create: false });
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to generate the key';
    } finally {
      this.isSaving = false;
    }
  }

  private async handleDelete(key: StoredKey) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete key',
      messagePrefix: 'Delete key ',
      messageSubject: key.kid,
      messageSuffix: '? Verifiers stop trusting it on their next sync; tokens signed with it fail.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    try {
      await deleteJwk(key.kid);
      this.keys = this.keys.filter(candidate => candidate.kid !== key.kid);
      if (this.route.id === key.kid) dispatchRecordRoute(this, { id: null, edit: null, create: false, layout: null });
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to delete the key';
    }
  }

  // ── list ──

  private canSign(key: StoredKey): boolean {
    return key.summary?.canSign ?? false;
  }

  private typeLabel(key: StoredKey): string {
    const kty = key.summary?.keyType ?? (typeof key.jwk.kty === 'string' ? key.jwk.kty : '—');
    const curve = key.summary?.curve ?? (typeof key.jwk.crv === 'string' ? key.jwk.crv : null);
    return curve ? `${kty} · ${curve}` : kty;
  }

  private algorithm(key: StoredKey): string {
    return key.summary?.algorithm ?? (typeof key.jwk.alg === 'string' ? key.jwk.alg : '');
  }

  private matchesFilter(key: StoredKey, filter: KeyFilter = this.filter): boolean {
    return filter === 'all' || (filter === 'sign' ? this.canSign(key) : !this.canSign(key));
  }

  private get filteredKeys(): StoredKey[] {
    return this.keys.filter(key => this.matchesFilter(key));
  }

  private count(filter: KeyFilter): number {
    return this.keys.filter(key => this.matchesFilter(key, filter)).length;
  }

  private usageChip(key: StoredKey) {
    return this.canSign(key) ? html`<span class="chip ok">Can sign</span>` : html`<span class="chip">Verify only</span>`;
  }

  private usedByCell(key: StoredKey) {
    if (this.tenantsError) return html`<span class="muted" title=${this.tenantsError}>—</span>`;
    if (this.tenants === null) return html`<span class="muted">…</span>`;
    const tenants = this.usedBy(key.kid);
    return tenants.length ? html`<span title=${tenants.map(tenant => tenant.id).join(', ')}>${tenants.length} ${tenants.length === 1 ? 'tenant' : 'tenants'}</span>` : html`<span class="muted">—</span>`;
  }

  private renderTable() {
    const columns: DataTableColumn<StoredKey>[] = [
      { id: 'kid', label: 'Key ID', cell: key => html`
        <button type="button" class="row-link" aria-label=${`Open key ${key.kid}`}><span class="row-id">${key.kid}</span></button>` },
      { id: 'type', label: 'Type', cell: key => html`<span style="white-space: nowrap">${this.typeLabel(key)}</span>` },
      { id: 'alg', label: 'Algorithm', cell: key => this.algorithm(key) || html`<span class="muted">—</span>` },
      { id: 'usage', label: 'Usage', cell: key => this.usageChip(key) },
      { id: 'used-by', label: 'Used by', cell: key => this.usedByCell(key) },
    ];
    const menuItems = (key: StoredKey): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.openView(key) },
      { label: 'Copy key ID', onSelect: () => void copyToClipboard(key.kid) },
      ...(this.canManage ? [{ label: 'Delete key', danger: true, ariaLabel: `Delete key ${key.kid}`, disabledReason: this.deleteReason(key), onSelect: () => void this.handleDelete(key) }] : []),
    ];
    return html`
      <versola-data-table selectable
        .columns=${columns}
        .rows=${this.filteredKeys}
        .rowKey=${(key: StoredKey) => key.kid}
        .selectedKey=${this.route.id}
        .menuItems=${menuItems}
        .menuLabel=${(key: StoredKey) => `Actions for ${key.kid}`}
        @row-select=${(e: CustomEvent<{ row: StoredKey }>) => this.openView(e.detail.row)}
      ></versola-data-table>
    `;
  }

  private renderToolbar() {
    const options: Array<{ id: KeyFilter; label: string }> = [{ id: 'all', label: 'All' }, { id: 'sign', label: 'Can sign' }, { id: 'verify', label: 'Verify only' }];
    return html`
      <div class="list-toolbar">
        <versola-filter-bar label="Filter keys"
          .options=${options.map(option => ({ ...option, count: this.count(option.id) }))}
          .value=${this.filter}
          @change=${(e: CustomEvent<{ id: string }>) => this.filter = e.detail.id as KeyFilter}></versola-filter-bar>
      </div>
    `;
  }

  // ── record ──

  private row(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private renderJson(value: Record<string, unknown>, label: string) {
    const json = JSON.stringify(value, null, 2);
    return html`<pre class="key-json" aria-label=${label}>${tokenize(json, 'json').map(token => html`<span class=${`ch-tok ch-${token.kind}`}>${token.value}</span>`)}</pre>`;
  }

  private deleteReason(key: StoredKey): string {
    const count = this.usedBy(key.kid).length;
    return count ? `Still selected by ${count} ${count === 1 ? 'tenant' : 'tenants'}. Move them to another key first.` : '';
  }

  private renderGroups(key: StoredKey) {
    const tenants = this.usedBy(key.kid);
    return html`
      <versola-record-card heading="Used by tenants" subheading="Tenants whose signing key is this one. Change it from the tenant's Security › Tokens & keys." .body=${html`
        ${this.row('Signs tokens for', this.tenantsError
          ? html`<span class="muted">Could not load tenants.</span> <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.loadTenants()}>Retry</button>`
          : this.tenants === null ? html`<span class="muted">Loading…</span>`
          : tenants.length ? html`<div class="tag-list">${tenants.map(tenant => html`<button type="button" class="tag" @click=${() => this.navigateTo('tenants', tenant.id)}>${tenant.id}</button>`)}</div>`
          : html`<span class="muted">No tenant signs with this key.</span>`)}
      `}></versola-record-card>

      <versola-record-card heading="Public key" subheading=${this.canSign(key) ? 'Central holds the private half and signs with it when a tenant selects the key.' : 'Central holds no private half: it can verify, no tenant can sign with it.'}
        ?editable=${this.canManage} .body=${html`
          <div class="json-block">
            ${this.renderJson(key.jwk, 'Public JWK')}
            <button type="button" class="btn btn-secondary btn-sm" @click=${() => void copyToClipboard(JSON.stringify(key.jwk, null, 2))}>Copy JWK</button>
          </div>`}
        @edit=${() => dispatchRecordRoute(this, { edit: 'jwk', create: false })}></versola-record-card>

      <versola-record-card heading="Fixed" fixedLabel="Fixed" .body=${html`
        ${this.row('Key ID', html`<span class="mono">${key.kid}</span>`, 'Part of the key; replace = delete and add')}
        ${this.row('Algorithm', this.algorithm(key) || html`<span class="muted">Not stated</span>`, 'From the JWK')}
      `}></versola-record-card>

      ${this.canManage ? html`
        <versola-danger-card heading="Delete key" text="Verifiers stop trusting it on their next sync; tokens signed with it fail."
          actionLabel="Delete key" .actionAriaLabel=${`Delete key ${key.kid}`} .disabledReason=${this.deleteReason(key)}
          @action=${() => this.handleDelete(key)}></versola-danger-card>` : ''}
    `;
  }

  private renderBanner(key: StoredKey) {
    if (this.generatedKid === key.kid) {
      return html`
        <div class="banner ok" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('check')}Key generated and published</div>
            <div>Select it as a tenant's signing key under Security › Tokens &amp; keys to start signing with it.</div>
          </div>
        </div>`;
    }
    if (!this.canSign(key)) {
      return html`
        <div class="banner info" role="status">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('info')}Verify only</div>
            <div>Central holds no private half for this key. It can verify, but no tenant can sign with it.</div>
          </div>
        </div>`;
    }
    return '';
  }

  private renderMeta(key: StoredKey) {
    const kty = key.summary?.keyType ?? (typeof key.jwk.kty === 'string' ? key.jwk.kty : '');
    const alg = this.algorithm(key);
    return html`<span class="chip">${[kty, alg].filter(Boolean).join(' · ')}</span>${this.usageChip(key)}`;
  }

  private renderRecordPage(key: StoredKey) {
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Keys & metadata', onSelect: () => this.closePanel() }, { label: key.kid }]}
        .heading=${key.kid}
        .recordId=${key.kid} idLabel="key ID"
        .meta=${this.renderMeta(key)}
      ></versola-record-header>
      ${this.renderBanner(key)}
      <div class="record-page">${this.renderGroups(key)}</div>
    `;
  }

  // ── editors ──

  private renderJsonField(id: string, label: string, hint: string) {
    const error = this.formError || (this.draftJson.trim() ? this.draftJsonError : '');
    return html`
      <div class="form-group">
        <label for=${id}>${label}</label>
        <versola-code-editor id=${id} label=${label} language="json" .rows=${14} .value=${this.draftJson} .invalid=${!!error}
          @code-input=${(e: CustomEvent<{ value: string }>) => { this.draftJson = e.detail.value; this.formError = ''; }}></versola-code-editor>
        ${error ? html`<div class="error-message" role="alert">${error}</div>` : html`<div class="hint">${hint}</div>`}
      </div>
    `;
  }

  private renderGenerateEditor() {
    return html`
      <form id="keys-editor" novalidate @submit=${(e: Event) => this.handleGenerate(e)}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        <div class="editor-box">
          <h3>Algorithm</h3>
          <p class="editor-sub">Central keeps the private half and publishes the public one. The key signs nothing until a tenant selects it, so every verifier learns it first.</p>
          <div class="option-cards" role="group" aria-label="Algorithm">
            ${ALGORITHMS.map(algorithm => html`
              <button type="button" class="option-card" aria-pressed=${this.draftAlgorithm === algorithm.id ? 'true' : 'false'} @click=${() => this.draftAlgorithm = algorithm.id}>
                <span class="option-card-title">${algorithm.title}</span>
                <span class="option-card-text">${algorithm.text}</span>
              </button>`)}
          </div>
        </div>
      </form>
    `;
  }

  private renderJwkEditor() {
    return html`
      <form id="keys-editor" novalidate @submit=${(e: Event) => this.handleSubmit(e)}>
        <div class="editor-box">
          <h3>${this.editor === 'add' ? 'Public JWK' : 'JWK'}</h3>
          <p class="editor-sub">${this.editor === 'add'
            ? 'A key another system signs with, pasted as a JWK (RFC 7517) with its kid. Central can verify with it, not sign.'
            : 'The stored JWK. The kid is part of the key; to change it, delete this key and add a new one.'}</p>
          ${this.renderJsonField('jwk-json', 'JWK JSON *', 'A JSON object with at least kty and kid.')}
        </div>
      </form>
    `;
  }

  private renderMetadataPage() {
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Keys & metadata', onSelect: () => void this.cancelEditor() }, { label: 'Server metadata' }]}
        .heading=${'Edit server metadata overrides'}
        .meta=${html`<span class="muted">Merged over the RFC 8414 document auth computes; the result must still carry the members discovery needs.</span>`}
      ></versola-record-header>
      <form class="card editor-card" novalidate @submit=${(e: Event) => this.handleSubmit(e)}>
        <div class="editor-box">
          <h3>Overrides</h3>
          <p class="editor-sub">Members here replace the computed ones; <code>issuer</code>, the endpoints, <code>jwks_uri</code> and <code>response_types_supported</code> must be present.</p>
          ${this.renderJsonField('metadata-json', 'Metadata JSON *', 'A JSON object.')}
        </div>
        <div class="form-actions">
          <button type="button" class="btn btn-secondary" ?disabled=${this.isSaving} @click=${() => void this.cancelEditor()}>Cancel</button>
          <button type="submit" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : 'Save server metadata'}</button>
        </div>
      </form>
    `;
  }

  private renderMetadataCard() {
    return html`
      <versola-record-card class="metadata-card" id="metadata" heading="Server metadata overrides" subheading="Merged over the RFC 8414 document auth computes." ?editable=${this.canManage && !this.metadataError}
        .body=${this.metadataError
          ? html`<div class="record-row"><div class="record-label"></div><div class="record-value"><span class="muted">Could not load the overrides: ${this.metadataError}</span> <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.loadData()}>Retry</button></div></div>`
          : this.metadata ? html`<div class="json-block">${this.renderJson(this.metadata, 'Server metadata overrides')}</div>`
          : html`<div class="record-row"><div class="record-label"></div><div class="record-value muted">No overrides set.</div></div>`}
        @edit=${() => dispatchRecordRoute(this, { id: null, edit: 'metadata', create: false, layout: null })}></versola-record-card>
    `;
  }

  private renderPanel(key: StoredKey | null) {
    const mode = this.editorMode;
    const editor = this.editor;
    if (mode === 'view' && !key) return '';
    const generate = mode === 'create' && editor !== 'add';
    const heading = generate ? 'Generate key' : editor === 'add' ? 'Add key' : mode === 'edit' ? 'Edit public key' : key!.kid;
    return html`
      <versola-side-panel kind="Signing key" .heading=${heading} .recordId=${key?.kid ?? ''} idLabel="key ID" .mode=${mode} ?wide=${mode !== 'view'}
        .parentLabel=${key?.kid ?? ''} .subtitle=${generate ? 'Published to /.well-known/jwks.json at once; selected per tenant.' : editor === 'add' ? 'A public key pasted as a JWK; verify only.' : ''}
        .meta=${key && mode === 'view' ? this.renderMeta(key) : null}
        @close=${() => mode === 'view' ? this.closePanel() : this.cancelEditor()}
        @back=${() => this.cancelEditor()}
        @open-page=${() => dispatchRecordRoute(this, { layout: 'page' })}>
        <div class="record-compact">
          ${mode === 'view' ? html`${this.renderBanner(key!)}${this.renderGroups(key!)}` : generate ? this.renderGenerateEditor() : this.renderJwkEditor()}
        </div>
        ${mode !== 'view' ? html`
          <div slot="footer">
            <button type="button" class="btn btn-secondary" @click=${() => this.cancelEditor()} ?disabled=${this.isSaving}>Cancel</button>
            <button type="submit" form="keys-editor" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Working…' : generate ? 'Generate key' : editor === 'add' ? 'Add key' : 'Save public key'}</button>
          </div>` : ''}
      </versola-side-panel>
    `;
  }

  render() {
    if (this.editor === 'metadata') return this.renderMetadataPage();

    const key = this.openKey;
    const panel = this.renderPanel(key);
    if (key && this.route.layout === 'page') {
      return html`${this.renderRecordPage(key)}${this.editorMode === 'view' ? '' : panel}`;
    }

    return html`
      <content-header title="Keys & metadata" description="The published JWKS and the RFC 8414 server metadata overrides.">
        ${this.canManage ? html`
          <button slot="actions" class="btn btn-secondary" @click=${() => dispatchRecordRoute(this, { id: null, edit: 'add', create: true, layout: null })}>Add key (paste JWK)</button>
          <button slot="actions" class="btn btn-primary" @click=${() => dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null })}>Generate key</button>` : ''}
      </content-header>

      ${this.canManage ? html`
        <div class="banner info" role="note">
          <div class="banner-text">A generated key is published for verification immediately but signs nothing until a tenant selects it under <button type="button" class="banner-link" @click=${() => this.navigateTo('security')}>Security › Tokens &amp; keys</button>, so every verifier learns the key before anything issues tokens under it.</div>
        </div>` : ''}

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load the keys" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.keys.length === 0 ? html`
        <versola-empty-state heading="No keys yet" text="Generate a key to publish it in the JWKS, or add one another system signs with.">
          ${this.canManage ? html`<button class="btn btn-primary" @click=${() => dispatchRecordRoute(this, { id: null, edit: null, create: true, layout: null })}>Generate key</button>` : ''}
        </versola-empty-state>
      ` : html`
        ${this.renderToolbar()}
        ${this.filteredKeys.length === 0 ? html`<versola-empty-state heading="No keys match"></versola-empty-state>` : this.renderTable()}
      `}

      ${this.isLoading || this.errorMessage ? '' : this.renderMetadataCard()}
      ${panel}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-keys-metadata': VersolaKeysMetadata;
  }
}
