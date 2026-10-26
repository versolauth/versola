import { LitElement, html, css } from 'lit';
import { declaredSummary } from '../utils/declared-rate-limit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { badgeStyles, buttonStyles, cardStyles, formStyles, recordStyles } from '../styles/components';
import {accessSectionTitle} from '../utils/client-presets';
import {AuthFlow, AuthorizationPreset, FormPreview, FormPreviewScenario, FormRecord, Locale, OAuthClient, OAuthScope, OtpTemplateRecord, Permission, Resource, Role, SecurityProfile, ThemeOverrides, ThemePreset, ThemeRecord} from '../types';
import {
  createClient,
  deleteClient,
  deletePreviousClientSecret,
  fetchAllClients,
  fetchClientPresets,
  fetchChallengeSettings,
  fetchFormPreviews,
  fetchForms,
  fetchLocales,
  fetchOtpTemplates,
  fetchThemePresets,
  fetchThemes,
  getPermissions,
  getResources,
  getRoles,
  getScopes,
  rotateClientSecret,
  saveClientPresets,
  updateClient,
} from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { ClientCredentialKind, clientCredentialKind, describeJwks } from '../utils/validators';
import {
  CLIENT_KINDS,
  ClientKind,
  clientKindOf,
  clientLocks,
  credentialLabel,
  fapiConformance,
  profilePosture,
} from '../utils/client-presets';
import type { ClientEditSection } from './client-form';
import { pickConsentScenario } from '../utils/consent-preview';
import { copyToClipboard, formatDuration, getLocalizedDescription, isPartnerClient } from '../utils/helpers';
import './client-form';
import './content-header';
import './danger-card';
import './data-table';
import './empty-state';
import './error-card';
import './filter-bar';
import './loading-cards';
import './preset-form';
import './record-card';
import './record-header';
import type { DataTableColumn, RowMenuItem } from './data-table';

/** What the create banner says instead of offering a secret, for a client whose secret is
 *  not the credential it authenticates with. `null` for one whose secret is. */
/** What the client changes in its theme, in a line: `brand #112233, sharp corners`; "Same as the theme" when nothing. */
function themeOverridesSummary(overrides: ThemeOverrides | null | undefined): string {
  const parts = [
    overrides?.brand && `brand ${overrides.brand}`,
    overrides?.surface && `${overrides.surface} page`,
    overrides?.background && `${overrides.background} background`,
    overrides?.shape && `${overrides.shape} corners`,
    overrides?.mode && `${overrides.mode} scheme`,
  ].filter(Boolean);
  return parts.length ? parts.join(', ') : 'Same as the theme';
}

function secretlessText(credential: ClientCredentialKind | undefined): string | null {
  switch (credential) {
    case 'public':
      return 'This is a native (public) client, so no secret was issued and none can be added later.';
    case 'mtls':
      return 'This client authenticates with its certificate, so no secret was generated for it.';
    case 'private_key_jwt':
      return 'This client authenticates with an assertion signed by its registered keys, so no secret '
        + 'was generated for it.';
    default:
      return null;
  }
}

@customElement('versola-clients-list')
export class VersolaClientsList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: String }) expandClientId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ type: Boolean }) canManageSecrets = false;

  @state() private clients: OAuthClient[] = [];
  @state() private searchQuery = '';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private showCreateForm = false;
  @state() private editingClient: OAuthClient | null = null;
  @state() private expandedClients: Set<string> = new Set();
  @state() private expandedPresets: Set<string> = new Set(); // Track which clients have presets section expanded
  @state() private expandedFlows: Set<string> = new Set(); // Track which clients have authorization flows section expanded
  @state() private expandedPresetCards: Set<string> = new Set(); // Track which individual preset cards are expanded
  @state() private availableScopes: OAuthScope[] = [];
  @state() private availablePermissions: Permission[] = [];
  @state() private availableResources: Resource[] = [];
  @state() private availableThemes: ThemeRecord[] = [];
  @state() private availableThemePresets: ThemePreset[] = [];
  @state() private consentPreviewForm: FormRecord | null = null;
  @state() private consentPreviewScenario: FormPreviewScenario | null = null;
  @state() private availableOtpTemplates: OtpTemplateRecord[] = [];
  @state() private availableRoles: Role[] = [];
  @state() private availableLocales: Locale[] = [];
  @state() private availablePostLogoutRedirectUris: string[] = [];
  @state() private mtlsCertificateHeader: string | null = null;
  @state() private securityProfile: SecurityProfile = 'fapi2';
  /** The client whose page is open, and the group of its settings being edited on it. */
  @state() private viewingClientId: string | null = null;
  @state() private editSection: ClientEditSection | null = null;
  @state() private typeFilter: 'all' | ClientKind = 'all';
  @state() private isPreparingForm = false;
  // `secret` is null whenever the created client does not authenticate with one - a native
  // client is issued none, and a client that registered an mTLS credential or a key set
  // has its generated secret refused at the token endpoint. The banner then confirms the
  // creation and says what the client authenticates with instead.
  @state() private createdSecret: { clientName: string; secret: string | null; action: 'created' | 'rotated'; credential: ClientCredentialKind } | null = null;
  @state() private copyFeedback = '';
  @state() private editingPresetsForClient: OAuthClient | null = null;
  @state() private presetDrafts: AuthorizationPreset[] = [];
  @state() private expandedPresetEditors: Set<string> = new Set();
  @state() private showPresetForm = false;
  @state() private editingPreset: AuthorizationPreset | null = null;
  @state() private isSavingPresets = false;
  private loadRequestId = 0;
  private formOptionsTenantId: string | null = null;

  updated(changedProperties: Map<string, unknown>) {
    if (changedProperties.has('tenantId')) {
      this.expandedClients = new Set();
      this.viewingClientId = null;
      this.editSection = null;
      this.availableScopes = [];
      this.availablePermissions = [];
      this.availableResources = [];
      this.availableRoles = [];
      this.availableLocales = [];
      this.availablePostLogoutRedirectUris = [];
      this.mtlsCertificateHeader = null;
      this.securityProfile = 'fapi2';
      this.createdSecret = null;
      this.copyFeedback = '';
      this.formOptionsTenantId = null;
    }

    if (changedProperties.has('tenantId')) {
      void this.loadData();
    }

    if (changedProperties.has('expandClientId') && this.expandClientId) {
      this.expandedClients = new Set([...this.expandedClients, this.expandClientId]);
      this.expandedFlows = new Set([...this.expandedFlows, this.expandClientId]);
    }
  }

  private async loadData() {
    if (!this.tenantId) {
      this.clients = [];
      this.errorMessage = '';
      return;
    }

    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';

    try {
      const [result, settings] = await Promise.all([
        fetchAllClients(this.tenantId),
        // The tenant's profile decides what a row says about security; a tenant whose
        // settings cannot be read is shown as the default one.
        fetchChallengeSettings(this.tenantId).catch(() => null),
      ]);
      if (requestId !== this.loadRequestId) return;
      this.clients = result;
      this.securityProfile = settings?.securityProfile ?? 'fapi2';
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.clients = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load clients';
    } finally {
      if (requestId === this.loadRequestId) {
        this.isLoading = false;
      }
    }
  }

  static styles = [
    theme,
    badgeStyles,
    buttonStyles,
    cardStyles,
    formStyles,
    recordStyles,
    css`
      :host {
        display: block;
      }

      .icon-action {
        background: none;
        border: none;
        min-width: 1.5rem;
        min-height: 1.5rem;
        padding: 0.25rem;
        cursor: pointer;
        color: var(--text-secondary);
        font-size: 1.125rem;
        transition: all var(--transition-fast);
        line-height: 1;
      }

      .icon-action:hover {
        color: var(--accent);
        transform: scale(1.15);
      }







      .client-link {
        background: none;
        border: none;
        padding: 0;
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--accent);
        cursor: pointer;
        text-align: left;
        word-break: break-all;
      }

      .client-link:hover {
        text-decoration: underline;
      }

      .uri-list, .scope-list, .permission-list {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
      }

      .uri-item {
        font-family: var(--font-mono);
        font-size: 0.8125rem;
        color: var(--accent);
        word-break: break-all;
      }

      .tag-list {
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem;
      }

      .tag {
        padding: 0.25rem 0.5rem;
        background: var(--accent-subtle);
        color: var(--accent);
        border-radius: var(--radius-sm);
        font-size: 0.75rem;
        font-family: var(--font-mono);
      }

      .flow-section {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(min(300px, 100%), 1fr));
        gap: var(--spacing-lg);
      }

      .auth-flow-settings {
        margin-top: var(--spacing-lg);
        border-top: 1px solid var(--border-default);
        padding-top: var(--spacing-lg);
      }

      .auth-flow-settings-list {
        display: grid;
        gap: var(--spacing-md);
      }

      .auth-flow-setting-row {
        display: grid;
        grid-template-columns: minmax(10rem, 0.3fr) minmax(0, 1fr);
        align-items: start;
        gap: var(--spacing-lg);
        padding: 0.875rem 0;
      }

      .auth-flow-setting-label {
        color: var(--text-secondary);
        font-size: 0.8125rem;
        font-weight: 600;
      }

      .auth-flow-setting-value {
        min-width: 0;
        color: var(--text-primary);
        font-size: 0.875rem;
      }

      .auth-flow-setting-stack {
        display: grid;
        gap: 0.625rem;
      }

      .auth-flow-setting-detail {
        display: grid;
        grid-template-columns: minmax(7rem, auto) minmax(0, 1fr);
        gap: 0.75rem;
        align-items: baseline;
      }

      .auth-flow-setting-detail-label {
        color: var(--text-secondary);
        font-size: 0.75rem;
      }

      .logout-session-note {
        color: var(--text-secondary);
        font-size: 0.8125rem;
      }

      .logout-claim-key {
        padding: 0.1rem 0.3rem;
        border-radius: var(--radius-sm);
        background: var(--accent-subtle);
        color: var(--accent);
        font-family: var(--font-mono);
        font-size: 0.75rem;
      }

      @media (max-width: 640px) {
        .auth-flow-setting-row,
        .auth-flow-setting-detail {
          grid-template-columns: 1fr;
          gap: 0.35rem;
        }
      }

      .flow-chain {
        display: flex;
        flex-direction: column;
        gap: 0;
        align-self: start;
        min-width: 0;
        max-width: 100%;
      }

      .flow-step {
        min-width: 0;
        overflow-wrap: anywhere;
        background: var(--bg-page);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        padding: var(--spacing-md);
      }

      .flow-step-label {
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--text-secondary);
        margin-bottom: 0.5rem;
      }

      .flow-step-values {
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem;
      }

      .flow-step-value {
        font-size: 0.875rem;
        font-family: var(--font-mono);
        color: var(--text-primary);
      }

      .flow-step-value.muted {
        color: var(--text-secondary);
        font-style: italic;
      }

      .flow-arrow {
        display: flex;
        justify-content: center;
        padding: 0.25rem 0;
        width: 50%;
        margin: 0 auto;
        color: var(--text-secondary);
        font-size: 1rem;
      }

      .secret-banner {
        margin-bottom: var(--spacing-lg);
        border-color: var(--success-border);
        background: var(--success-subtle);
      }

      .secret-banner-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: var(--spacing-md);
        margin-bottom: var(--spacing-md);
      }

      .secret-banner-title {
        margin: 0;
        font-size: 1rem;
        color: var(--text-primary);
      }

      .secret-banner-text {
        margin: 0.35rem 0 0;
        color: var(--text-secondary);
        font-size: 0.875rem;
      }

      .secret-value {
        margin: 0;
        padding: 0.875rem 1rem;
        background: var(--bg-page);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-family: var(--font-mono);
        font-size: 0.875rem;
        line-height: 1.5;
        word-break: break-all;
      }

      .secret-banner-actions {
        display: flex;
        gap: 0.75rem;
        align-items: center;
        margin-top: var(--spacing-md);
      }

      .copy-feedback {
        font-size: 0.8125rem;
        color: var(--success);
      }

      .btn-ghost {
        background: transparent;
        color: var(--text-secondary);
        border-color: var(--border-input);
      }

      .btn-ghost:not(:disabled):hover {
        color: var(--text-primary);
        border-color: var(--text-secondary);
      }

      /* Authorization Presets */
      .presets-section {
        margin-top: var(--spacing-lg);
        border-top: 1px solid var(--border-default);
        padding-top: var(--spacing-lg);
      }

      .presets-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        cursor: pointer;
        user-select: none;
        padding: var(--spacing-sm) 0;
      }

      .presets-header:hover .presets-title {
        color: var(--accent);
      }

      .presets-title {
        display: flex;
        align-items: center;
        gap: var(--spacing-sm);
        font-weight: 600;
        font-size: 0.9375rem;
        color: var(--text-primary);
        transition: color var(--transition-fast);
      }

      .expand-icon {
        font-size: 0.75rem;
        color: var(--text-secondary);
      }

      .presets-body {
        padding-top: var(--spacing-md);
      }

      .auth-flow-body {
        padding-left: 1.25rem;
      }

      .auth-flow-body > .flow-section {
        margin-left: -1.25rem;
      }

      .empty-presets {
        padding: var(--spacing-lg);
        text-align: center;
        color: var(--text-secondary);
        font-size: 0.875rem;
        background: var(--bg-page);
        border-radius: var(--radius-md);
      }

      .presets-list {
        display: grid;
        gap: var(--spacing-md);
      }

      .preset-card {
        background: var(--bg-page);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        transition: border-color var(--transition-fast);
        overflow: hidden;
      }

      .preset-card:hover {
        border-color: var(--accent);
      }

      .preset-card-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--spacing-md);
        cursor: pointer;
        user-select: none;
        transition: background var(--transition-fast);
      }

      .preset-card-header:hover {
        background: var(--accent-subtle);
      }

      .preset-info {
        flex: 1;
        min-width: 0;
      }

      .preset-name {
        font-weight: 600;
        font-size: 0.9375rem;
        color: var(--text-primary);
        margin-bottom: 0.25rem;
      }

      .preset-id {
        font-size: 0.8125rem;
        color: var(--accent);
        font-family: var(--font-mono);
      }

      .preset-actions {
        display: flex;
        gap: var(--spacing-sm);
      }

      .preset-body {
        padding: 0 var(--spacing-md) var(--spacing-md);
        border-top: 1px solid var(--border-default);
      }

      .preset-details {
        display: grid;
        grid-template-columns: max-content 1fr;
        gap: var(--spacing-sm) var(--spacing-md);
        padding-top: var(--spacing-md);
        font-size: 0.8125rem;
        align-items: baseline;
      }

      .preset-row {
        display: contents;
      }

      .preset-label {
        color: var(--text-secondary);
        font-weight: 500;
      }

      .preset-value {
        color: var(--text-primary);
        font-family: var(--font-mono);
        word-break: break-all;
      }

      .preset-value-empty {
        color: var(--text-secondary);
        font-style: italic;
      }

      .preset-divider {
        grid-column: 1 / -1;
        border-top: 1px solid var(--border-default);
        margin: var(--spacing-xs) 0;
      }

      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0 0 0 0);
        white-space: nowrap;
      }
      .icon {
        flex: none;
        vertical-align: -3px;
      }

      /* ── list ── */
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

      /* ── client page ── */
      .banner {
        border: 1px solid var(--border-default);
        border-radius: var(--radius-inner);
        padding: var(--spacing-md) var(--spacing-lg);
        margin-bottom: var(--spacing-lg);
        background: var(--surface);
        color: var(--text-primary);
      }
      .banner.ok {
        border-color: var(--success);
        background: var(--success-subtle);
      }
      .banner-title {
        display: flex;
        gap: 0.5rem;
        align-items: center;
        font-weight: 600;
        margin-bottom: 0.375rem;
      }
      .banner.ok .banner-title .icon,
      .banner.ok li .icon {
        color: var(--success);
      }
      .banner ul {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        gap: 0.25rem;
        font-size: 0.875rem;
      }
      .banner li {
        display: flex;
        gap: 0.5rem;
        align-items: baseline;
      }
      .banner p {
        margin: 0.625rem 0 0;
        font-size: 0.8125rem;
        color: var(--text-secondary);
      }
      /* --info is the accent hue, so a link in a banner is told apart by its underline. */
      .banner a {
        text-decoration: underline;
      }
      .view-flow {
        padding: var(--spacing-md) var(--spacing-lg);
      }
      .muted {
        color: var(--text-secondary);
      }
      .deviation {
        display: flex;
        gap: var(--spacing-md);
        align-items: center;
        flex-wrap: wrap;
      }
      .presets-card {
        padding: 0;
        margin-bottom: var(--spacing-md);
      }
      .presets-card .presets-section {
        margin: 0;
        border: 0;
        padding: var(--spacing-md) var(--spacing-lg);
      }
    `,
  ];

  private get filteredClients(): OAuthClient[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.clients.filter(client =>
      (this.typeFilter === 'all' || clientKindOf(client) === this.typeFilter)
      && (!query
        || client.id.toLowerCase().includes(query)
        || getLocalizedDescription(client.clientName).toLowerCase().includes(query)));
  }

  private async openView(client: OAuthClient) {
    this.viewingClientId = client.id;
    this.editSection = null;
    await this.loadPresetsForClient(client.id);
    window.scrollTo({ top: 0 });
  }

  private closeView() {
    this.viewingClientId = null;
    this.editSection = null;
  }

  private openSection(client: OAuthClient, section: ClientEditSection) {
    this.editSection = section;
    void this.openForm(client);
  }

  private async copyClientId(client: OAuthClient) {
    await copyToClipboard(client.id);
  }

  private togglePresetExpand(presetId: string) {
    if (this.expandedPresetCards.has(presetId)) {
      this.expandedPresetCards.delete(presetId);
    } else {
      this.expandedPresetCards.add(presetId);
    }
    this.requestUpdate();
  }

  private async loadPresetsForClient(clientId: string) {
    if (!this.tenantId) return;

    const client = this.clients.find(c => c.id === clientId);
    if (!client) return;

    // Skip if already loaded
    if (client.authorizationPresets !== undefined) return;

    try {
      const presets = await fetchClientPresets(clientId);
      // Update the client with loaded presets
      this.clients = this.clients.map(c =>
        c.id === clientId ? { ...c, authorizationPresets: presets } : c
      );
    } catch (error) {
      console.error(`Failed to load presets for client ${clientId}:`, error);
      // Set empty array on error to avoid retrying
      this.clients = this.clients.map(c =>
        c.id === clientId ? { ...c, authorizationPresets: [] } : c
      );
    }
  }

  private handleCreateClick() {
    this.editSection = null;
    this.viewingClientId = null;
    void this.openForm(null);
  }

  private async ensureFormOptionsLoaded() {
    const tenantId = this.tenantId;

    if (!tenantId || this.formOptionsTenantId === tenantId) {
      return;
    }

    const [scopes, permissions, resources, themes, themePresets, forms, previews, otpTemplates, challengeSettings, roles, locales] = await Promise.all([
      getScopes(tenantId),
      getPermissions(tenantId),
      getResources(tenantId),
      fetchThemes(tenantId),
      // A central that has no built-in themes answers with an error: the client's own appearance is then not offered.
      fetchThemePresets().catch(() => [] as ThemePreset[]),
      // The appearance block draws the consent form. An older central, or an operator who may not read forms, has no
      // such preview: the block says so and the rest of the form is unaffected.
      fetchForms().catch(() => [] as FormRecord[]),
      fetchFormPreviews().catch(() => [] as FormPreview[]),
      fetchOtpTemplates(tenantId),
      fetchChallengeSettings(tenantId),
      getRoles(tenantId),
      fetchLocales(),
    ]);

    if (this.tenantId === tenantId) {
      this.availableScopes = scopes;
      this.availablePermissions = permissions;
      this.availableResources = resources;
      this.availableThemes = themes;
      this.availableThemePresets = themePresets;
      this.consentPreviewForm = forms.find(f => f.id === 'consent' && f.active && f.jsCompiled) ?? null;
      this.consentPreviewScenario = pickConsentScenario(previews.find(p => p.formId === 'consent')?.scenarios ?? []);
      this.availableOtpTemplates = otpTemplates;
      this.availableRoles = roles;
      this.availableLocales = locales;
      this.availablePostLogoutRedirectUris = challengeSettings?.postLogoutRedirectUris ?? [];
      this.mtlsCertificateHeader = challengeSettings?.mtlsCertificateHeader ?? null;
      this.securityProfile = challengeSettings?.securityProfile ?? 'fapi2';
      this.formOptionsTenantId = tenantId;
    }
  }

  private async openForm(client: OAuthClient | null) {
    if (!this.tenantId) {
      this.errorMessage = 'Select a tenant first to manage OAuth clients.';
      return;
    }

    this.createdSecret = null;
    this.copyFeedback = '';
    this.isPreparingForm = true;

    try {
      await this.ensureFormOptionsLoaded();
      this.editingClient = client;
      this.showCreateForm = true;
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load form options';
    } finally {
      this.isPreparingForm = false;
    }
  }

  private async handleDeleteClick(client: OAuthClient) {
    if (!this.tenantId) {
      return;
    }

    const confirmed = await confirmDestructiveAction({
      title: 'Delete client',
      messagePrefix: 'Delete client ',
      messageSubject: client.id,
      messageSuffix: '?',
      confirmLabel: 'Delete',
    });

    if (!confirmed) {
      return;
    }

    try {
      await deleteClient(this.tenantId, client.id);
      this.removeClientFromState(client.id);
      if (this.viewingClientId === client.id) this.closeView();
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to delete client');
    }
  }

  private handleFormClose() {
    this.showCreateForm = false;
    this.editingClient = null;
    this.editSection = null;
    this.isPreparingForm = false;
    this.requestUpdate();
  }

  private dismissCreatedSecret() {
    this.createdSecret = null;
    this.copyFeedback = '';
  }

  private async handleCopySecret() {
    if (!this.createdSecret?.secret) {
      return;
    }

    const copied = await copyToClipboard(this.createdSecret.secret);
    this.copyFeedback = copied ? 'Secret copied to clipboard.' : 'Could not copy secret.';
  }

  private updateClientSecretState(clientId: string, hasPreviousSecret: boolean) {
    this.clients = this.clients.map(client =>
      client.id === clientId ? { ...client, hasPreviousSecret } : client,
    );

    if (this.editingClient?.id === clientId) {
      this.editingClient = { ...this.editingClient, hasPreviousSecret };
    }
  }

  private updateClientInState(client: OAuthClient, previousId: string) {
    const existingClient = this.clients.find(current => current.id === previousId);
    const updatedClient: OAuthClient = {
      ...existingClient,
      ...client,
      hasPreviousSecret: existingClient?.hasPreviousSecret ?? this.editingClient?.hasPreviousSecret ?? false,
      tenantId: existingClient?.tenantId ?? this.tenantId ?? undefined,
    };

    this.clients = this.clients.map(current =>
      current.id === previousId ? updatedClient : current,
    );

    if (this.editingClient?.id === previousId) {
      this.editingClient = updatedClient;
    }
  }

  private addClientToState(client: OAuthClient) {
    const createdClient: OAuthClient = {
      ...client,
      hasPreviousSecret: client.hasPreviousSecret ?? false,
      tenantId: client.tenantId ?? this.tenantId ?? undefined,
    };

    this.clients = [createdClient, ...this.clients];
    this.expandedClients = new Set([...this.expandedClients, createdClient.id]);
  }

  private removeClientFromState(clientId: string) {
    this.clients = this.clients.filter(client => client.id !== clientId);

    const expandedClients = new Set(this.expandedClients);
    expandedClients.delete(clientId);
    this.expandedClients = expandedClients;

    if (this.editingClient?.id === clientId) {
      this.editingClient = null;
      this.showCreateForm = false;
    }
  }

  private async handleRotateSecret(e: CustomEvent<{ clientId: string; clientName: string }>) {
    e.stopPropagation();

    if (!this.tenantId) {
      return;
    }

    const { clientId, clientName } = e.detail;

    try {
      const secret = await rotateClientSecret(this.tenantId, clientId);
      this.updateClientSecretState(clientId, true);
      this.handleFormClose();

      this.createdSecret = {
        clientName,
        secret,
        action: 'rotated',
        credential: 'secret',
      };
      this.copyFeedback = '';
      this.expandedClients = new Set([...this.expandedClients, clientId]);

      await this.updateComplete;
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to rotate client secret');
    }
  }

  private async handleDeletePreviousSecret(e: CustomEvent<{ clientId: string; clientName: string }>) {
    e.stopPropagation();

    if (!this.tenantId) {
      return;
    }

    const { clientId, clientName } = e.detail;
    const confirmed = await confirmDestructiveAction({
      title: 'Delete old secret',
      messagePrefix: 'Delete the old secret for ',
      messageSubject: clientId,
      messageSuffix: '? This action cannot be undone.',
      confirmLabel: 'Delete',
    });

    if (!confirmed) {
      return;
    }

    try {
      await deletePreviousClientSecret(this.tenantId, clientId);
      this.updateClientSecretState(clientId, false);
      this.handleFormClose();
      this.expandedClients = new Set([...this.expandedClients, clientId]);
    } catch (error) {
      alert(error instanceof Error ? error.message : `Failed to delete old secret for ${clientName}`);
    }
  }

  private async handleFormSubmit(e: CustomEvent) {
    if (!this.tenantId) {
      return;
    }

    const client = e.detail.client as OAuthClient;

    try {
      let created = false;
      let generatedSecret: string | null = null;

      if (this.editingClient) {
        const previousId = this.editingClient.id;
        await updateClient(this.tenantId, this.editingClient, client);
        this.updateClientInState(client, previousId);
      } else {
        const registration = await createClient(this.tenantId, client);
        generatedSecret = registration.secret;
        // Dated by central, which settles the registration time - the submitted client
        // carries none, and a date this browser made up would differ from the stored one.
        this.addClientToState({ ...client, createdAt: registration.createdAt });
        created = true;
      }

      this.handleFormClose();

      if (created) {
        const credential = clientCredentialKind(client);
        this.createdSecret = {
          clientName: getLocalizedDescription(client.clientName),
          secret: credential === 'secret' ? generatedSecret : null,
          action: 'created',
          credential,
        };
        this.copyFeedback = '';
        this.viewingClientId = client.id;
        this.expandedFlows = new Set([...this.expandedFlows, client.id]);
      }
    } catch (error) {
      // Check if it's a 409 Conflict (duplicate client)
      if (error && typeof error === 'object' && 'status' in error && error.status === 409) {
        alert(`Client ID "${client.id}" already exists. Please choose a different ID.`);
      } else {
        const errorMessage = error instanceof Error ? error.message : 'Failed to save client';
        alert(errorMessage);
      }
    }
  }

  render() {
    const secretTitle = this.createdSecret?.action === 'rotated'
      ? `Secret rotated: ${this.createdSecret.clientName}`
      : this.createdSecret
        ? `Client created: ${this.createdSecret.clientName}`
        : '';
    const secretText = this.createdSecret?.action === 'rotated'
      ? 'Copy the new client secret now. It may not be shown again.'
      : secretlessText(this.createdSecret?.credential)
        ?? 'Copy this secret now. It may not be shown again.';

    if (this.isPreparingForm && !this.showCreateForm) {
      return html`
        <content-header
          title="OAuth Clients"
        ></content-header>
        <versola-empty-state heading="Loading form options…" text="Fetching scopes and permissions for the selected tenant."></versola-empty-state>
      `;
    }

    if (this.showPresetForm && this.editingPresetsForClient) {
      return html`
        <content-header title="OAuth Clients"></content-header>
        <versola-preset-form
          .preset=${this.editingPreset}
          .client=${this.editingPresetsForClient}
          .availableScopes=${this.availableScopes}
          .availablePostLogoutRedirectUris=${this.availablePostLogoutRedirectUris}
          @close=${this.handlePresetFormClose}
          @submit=${this.handlePresetFormSubmit}
        ></versola-preset-form>
      `;
    }

    if (this.editingPresetsForClient) {
      return html`
        <content-header title="OAuth Clients"></content-header>
        ${this.renderPresetsEditForm()}
      `;
    }

    if (this.showCreateForm) {
      return html`
        ${this.createdSecret ? html`
          <div class="card secret-banner">
            <div class="secret-banner-header">
              <div>
                <h3 class="secret-banner-title">${secretTitle}</h3>
                <p class="secret-banner-text">${secretText}</p>
              </div>
              <button class="btn btn-ghost btn-sm" @click=${this.dismissCreatedSecret}>Dismiss</button>
            </div>

            ${this.createdSecret.secret ? html`
              <pre class="secret-value">${this.createdSecret.secret}</pre>

              <div class="secret-banner-actions">
                <button class="btn btn-primary btn-sm" @click=${this.handleCopySecret}>Copy secret</button>
                ${this.copyFeedback ? html`<span class="copy-feedback">${this.copyFeedback}</span>` : ''}
              </div>
            ` : ''}
          </div>
        ` : ''}
        <versola-client-form
          .client=${this.editingClient}
          .editSection=${this.editSection}
          .availableScopes=${this.availableScopes}
          .availablePermissions=${this.availablePermissions}
          .availableResources=${this.availableResources}
          .availableThemes=${this.availableThemes}
          .availableThemePresets=${this.availableThemePresets}
          .consentPreviewForm=${this.consentPreviewForm}
          .consentPreviewScenario=${this.consentPreviewScenario}
          .availableOtpTemplates=${this.availableOtpTemplates}
          .availableRoles=${this.availableRoles}
          .locales=${this.availableLocales}
          .canManageSecrets=${this.canManageSecrets}
          .mtlsCertificateHeader=${this.mtlsCertificateHeader}
          .securityProfile=${this.securityProfile}
          @close=${this.handleFormClose}
          @delete-previous-secret=${this.handleDeletePreviousSecret}
          @rotate-secret=${this.handleRotateSecret}
          @submit=${this.handleFormSubmit}
        ></versola-client-form>
      `;
    }

    const viewing = this.viewingClientId ? this.clients.find(client => client.id === this.viewingClientId) : null;
    if (viewing) {
      return this.renderClientView(viewing, secretTitle, secretText);
    }

    return html`
      <content-header
        title="OAuth Clients"
      >
        ${this.clients.length > 0 && this.canManage ? html`
          <button slot="actions" class="btn btn-primary" @click=${this.handleCreateClick} ?disabled=${this.isPreparingForm || !this.tenantId}>
            + Create Client
          </button>
        ` : ''}
      </content-header>

      ${this.createdSecret && this.canManageSecrets ? html`
        <div class="card secret-banner">
          <div class="secret-banner-header">
            <div>
              <h3 class="secret-banner-title">${secretTitle}</h3>
              <p class="secret-banner-text">${secretText}</p>
            </div>
            <button class="btn btn-ghost btn-sm" @click=${this.dismissCreatedSecret}>Dismiss</button>
          </div>

          ${this.createdSecret.secret ? html`
            <pre class="secret-value">${this.createdSecret.secret}</pre>

            <div class="secret-banner-actions">
              <button class="btn btn-primary btn-sm" @click=${this.handleCopySecret}>Copy secret</button>
              ${this.copyFeedback ? html`<span class="copy-feedback">${this.copyFeedback}</span>` : ''}
            </div>
          ` : ''}
        </div>
      ` : ''}

      ${this.isLoading ? html`
        <versola-loading-cards .count=${3}></versola-loading-cards>
      ` : this.errorMessage ? html`
        <versola-error-card heading="Could not load OAuth clients" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>
      ` : this.clients.length === 0 ? html`
        <versola-empty-state heading="No OAuth clients yet" text="Create your first OAuth client to get started">
          ${this.canManage ? html`
            <button class="btn btn-primary" @click=${this.handleCreateClick} ?disabled=${this.isPreparingForm || !this.tenantId}>
              + Create Client
            </button>` : ''}
        </versola-empty-state>
      ` : html`
        ${this.renderListToolbar()}
        ${this.filteredClients.length === 0 ? html`
          <versola-empty-state heading="No clients match"></versola-empty-state>
        ` : this.renderClientTable()}
      `}
    `;
  }

  private createdAgo(createdAt: string): string {
    const days = Math.floor((Date.now() - new Date(createdAt).getTime()) / 86_400_000);
    return `Created ${days < 1 ? 'today' : days === 1 ? 'yesterday' : `${days} days ago`}`;
  }

  private kindName(client: OAuthClient): string {
    return CLIENT_KINDS.find(kind => kind.id === clientKindOf(client))?.name ?? 'Client';
  }

  private kindIcon(kind: ClientKind) {
    const paths: Record<ClientKind, string> = {
      web: 'M3 5h18v14H3zM3 9h18',
      device: 'M7 3h10v18H7zM11 18h2',
      service: 'M4 4h16v7H4zM4 13h16v7H4zM8 7.5h.01M8 16.5h.01',
    };
    return html`<svg class="icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor"
      stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d=${paths[kind]}></path></svg>`;
  }

  private partnerChip(client: OAuthClient) {
    return isPartnerClient(client)
      ? html`<span class="chip" title="Asks users for consent: a partner's app signing in your users">Partner</span>`
      : '';
  }

  private securityChip(client: OAuthClient) {
    const { conformant } = fapiConformance(client);
    return conformant
      ? html`<span class="chip ok" title="Meets the FAPI 2.0 security profile">${this.icon('shield')}FAPI 2.0</span>`
      : html`<span class="chip">Standard</span>`;
  }

  private icon(name: 'shield' | 'lock' | 'check' | 'x' | 'dots' | 'copy', className = '') {
    const paths: Record<string, string> = {
      shield: 'M12 3l8 3v6c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6zM8.5 12l2.5 2.5L15.5 10',
      lock: 'M7 11V8a5 5 0 0 1 10 0v3M6 11h12v9H6z',
      check: 'M5 12.5l4.5 4.5L19 7.5',
      x: 'M6 6l12 12M18 6L6 18',
      dots: 'M5 12h.01M12 12h.01M19 12h.01',
      copy: 'M8 8h12v12H8zM16 8V4H4v12h4',
    };
    return html`<svg class="icon ${className}" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor"
      stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d=${paths[name]}></path></svg>`;
  }

  private renderListToolbar() {
    const filters: Array<{ id: 'all' | ClientKind; label: string }> = [
      { id: 'all', label: 'All' },
      ...CLIENT_KINDS.map(kind => ({ id: kind.id, label: kind.name })),
    ];
    return html`
      <div class="list-toolbar">
        <input class="form-input search-input" type="search" aria-label="Search clients" .value=${this.searchQuery}
          @input=${(e: Event) => this.searchQuery = (e.target as HTMLInputElement).value}
          placeholder="Search by name or client ID" />
        <versola-filter-bar label="Filter by type"
          .options=${filters.map(filter => ({ id: filter.id, label: filter.label }))}
          .value=${this.typeFilter}
          @change=${(e: CustomEvent<{ id: string }>) => this.typeFilter = e.detail.id as 'all' | ClientKind}
        ></versola-filter-bar>
      </div>
    `;
  }

  private renderClientTable() {
    const columns: DataTableColumn<OAuthClient>[] = [
      { id: 'client', label: 'Client', cell: client => html`
        <button type="button" class="row-link" @click=${() => this.openView(client)} aria-label=${`Open client ${getLocalizedDescription(client.clientName)} ${client.id}`}>
          <span class="row-title">${getLocalizedDescription(client.clientName)}</span>
          <span class="row-id">${client.id}</span>
        </button>` },
      { id: 'type', label: 'Type', cell: client => html`<span class="type-label">${this.kindIcon(clientKindOf(client))}${this.kindName(client)}</span>` },
      { id: 'credential', label: 'Authenticates with', class: 'muted', cell: client => credentialLabel(client) },
      { id: 'security', label: 'Security', cell: client => html`
        <div class="chips">${this.partnerChip(client)}${this.securityChip(client)}${client.hasPreviousSecret ? html`<span class="chip warn">Secret rotation</span>` : ''}</div>` },
    ];
    const menuItems = (client: OAuthClient): RowMenuItem[] => [
      { label: 'Open', onSelect: () => void this.openView(client) },
      { label: 'Copy client ID', onSelect: () => void this.copyClientId(client) },
      ...(this.canManage ? [{ label: 'Delete client', danger: true, ariaLabel: `Delete client ${client.id}`, onSelect: () => void this.handleDeleteClick(client) }] : []),
    ];
    return html`
      <versola-data-table
        .columns=${columns}
        .rows=${this.filteredClients}
        .rowKey=${(client: OAuthClient) => client.id}
        .menuItems=${menuItems}
        .menuLabel=${(client: OAuthClient) => `Actions for ${client.id}`}
      ></versola-data-table>
    `;
  }

  private viewRow(label: string, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private viewSection(title: string, subtitle: string, section: ClientEditSection | null, client: OAuthClient, body: unknown, fixedLabel = 'Fixed') {
    return html`
      <versola-record-card .heading=${title} .subheading=${subtitle}
        ?editable=${!!section && this.canManage} ?editDisabled=${this.isPreparingForm}
        .fixedLabel=${section ? '' : fixedLabel}
        .body=${body}
        @edit=${() => { if (section) this.openSection(client, section); }}
      ></versola-record-card>
    `;
  }

  private tokenBinding(client: OAuthClient): string {
    if (client.dpopBoundAccessTokens) return 'Bound to a DPoP key';
    if (client.certificateBoundAccessTokens || client.authMethod === 'tls_client_auth' || client.authMethod === 'self_signed_tls_client_auth') {
      return client.clientType === 'native' ? 'Bound to a certificate' : "Bound to the client's certificate";
    }
    return 'Bearer tokens, not bound to the client';
  }

  private renderClientView(client: OAuthClient, secretTitle: string, secretText: string) {
    const kind = clientKindOf(client);
    const descriptor = CLIENT_KINDS.find(candidate => candidate.id === kind);
    const locks = clientLocks(kind, this.securityProfile, client.authMethod === 'private_key_jwt' ? 'private-key-jwt'
      : client.authMethod === 'client_secret' ? 'secret'
      : client.authMethod === 'self_signed_tls_client_auth' ? 'mtls-self-signed'
      : client.authMethod === 'tls_client_auth' && client.clientType !== 'native' && client.mtlsAuth ? 'mtls' : 'edge');
    const lockedValue = (lock: typeof locks.dpop) => (lock.locked ? lock.value : null);
    const deviations = [
      [lockedValue(locks.dpop), !!client.dpopBoundAccessTokens],
      // A certificate credential binds the tokens whatever the stored flag says, so it is no deviation.
      [locks.boundByCertificate ? null : lockedValue(locks.certificateBound), !!client.certificateBoundAccessTokens],
      [lockedValue(locks.par), !!client.requirePushedAuthorizationRequests],
    ].some(([required, actual]) => required !== null && required !== actual);
    const conformance = fapiConformance(client);
    const posture = profilePosture(client, 'fapi2');
    const hasKeys = client.authMethod === 'private_key_jwt' || client.authMethod === 'self_signed_tls_client_auth';
    const signsUsersIn = kind !== 'service';
    const keys = describeJwks(client.jwks ?? undefined);
    const offline = client.scope.includes('offline_access');
    const logoutUris = [client.frontChannelLogoutUri, client.backChannelLogoutUri].filter(Boolean) as string[];
    const par = locks.par.locked && !locks.par.value ? 'Not used' : client.requirePushedAuthorizationRequests ? 'Pushed through edge (PAR)' : 'Not required';
    const jar = locks.jar.locked && !locks.jar.value ? 'Not used' : client.requireSignedRequestObject ? 'Required' : 'Not required';
    const why = (lock: typeof locks.dpop) => (lock.locked ? 'By type' : '');
    const bindingEditable = [locks.dpop, locks.certificateBound, locks.par, locks.jar].some(lock => !lock.locked);

    return html`
      ${this.createdSecret && this.canManageSecrets ? html`
        <div class="card secret-banner">
          <div class="secret-banner-header">
            <div>
              <h3 class="secret-banner-title">${secretTitle}</h3>
              <p class="secret-banner-text">${secretText}</p>
            </div>
            <button class="btn btn-ghost btn-sm" @click=${this.dismissCreatedSecret}>Dismiss</button>
          </div>
          ${this.createdSecret.secret ? html`
            <pre class="secret-value">${this.createdSecret.secret}</pre>
            <div class="secret-banner-actions">
              <button class="btn btn-primary btn-sm" @click=${this.handleCopySecret}>Copy secret</button>
              ${this.copyFeedback ? html`<span class="copy-feedback">${this.copyFeedback}</span>` : ''}
            </div>
          ` : ''}
        </div>
      ` : ''}

      <versola-record-header
        .crumbs=${[{ label: 'Clients', onSelect: () => this.closeView() }, { label: getLocalizedDescription(client.clientName) }]}
        .heading=${getLocalizedDescription(client.clientName)}
        .recordId=${client.id}
        idLabel="client ID"
        .meta=${html`
          <span class="chip">${descriptor ? this.kindIcon(kind) : ''}${descriptor?.name ?? 'Client'}</span>
          ${this.partnerChip(client)}
          ${this.securityChip(client)}
          ${client.hasPreviousSecret ? html`<span class="chip warn">Secret rotation</span>` : ''}
          ${client.createdAt ? html`<span class="muted created">${this.createdAgo(client.createdAt)}</span>` : ''}
        `}
      ></versola-record-header>

      ${conformance.conformant ? html`
        <div class="banner ok" role="status">
          <div class="banner-title">${this.icon('shield')}Meets FAPI 2.0</div>
          <ul>${posture.map(check => html`<li>${this.icon('check')}${check.text}</li>`)}</ul>
        </div>
      ` : html`
        <div class="banner info" role="status">
          <div class="banner-title">Standard profile client, not FAPI 2.0</div>
          <ul>${conformance.gaps.map(gap => html`<li>${this.icon('x')}${gap.problem ?? gap.text}</li>`)}</ul>
          <p>How a client authenticates is fixed when it is created. To meet FAPI 2.0, create a new client of this type and move traffic to it.</p>
        </div>
      `}

      ${this.viewSection('Type', 'Chosen at creation and never changed.', null, client, html`
        ${this.viewRow('Kind', html`${descriptor?.name}. ${descriptor?.blurb}`)}
        ${this.viewRow('Authenticates with', credentialLabel(client), 'Fixed at creation')}
      `)}

      ${this.viewSection('Token binding and requests', bindingEditable ? 'What this client\'s type leaves to you.' : 'Set by the type of client.', bindingEditable || deviations ? 'type' : null, client, html`
        ${this.viewRow('Token binding', this.tokenBinding(client), why(locks.dpop.locked ? locks.dpop : locks.certificateBound))}
        ${this.viewRow('Authorization requests', par, why(locks.par))}
        ${this.viewRow('Request signing (JAR)', html`<span class="muted">${jar}</span>`, why(locks.jar))}
        ${deviations && this.canManage ? html`
          <versola-record-row .value=${html`<div class="deviation">Some of these differ from what this type requires.
              <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.openSection(client, 'type')}>Review</button></div>`}
          ></versola-record-row>` : ''}
      `)}

      ${this.viewSection('General', '', 'general', client, html`
        ${this.viewRow('Name', getLocalizedDescription(client.clientName))}
        ${signsUsersIn ? this.viewRow('Redirect URIs', client.redirectUris.length
          ? html`<div class="uri-list">${client.redirectUris.map(uri => html`<div class="uri-item">${uri}</div>`)}</div>` : '—') : ''}
      `)}

      ${this.viewSection(accessSectionTitle(kind), '', 'access', client, html`
        ${signsUsersIn ? this.viewRow('Scopes', client.scope.length ? html`<div class="tag-list">${client.scope.map(scope => html`<span class="tag">${scope}</span>`)}</div>` : '—') : ''}
        ${signsUsersIn ? '' : this.viewRow('Permissions', client.permissions.length ? html`<div class="tag-list">${client.permissions.map(permission => html`<span class="tag">${permission}</span>`)}</div>` : '—')}
      `)}

      ${signsUsersIn ? this.viewSection('Sign-in', '', 'signin', client, html`
        <div class="view-flow">
          ${client.authFlow ? this.renderAuthFlow(client.authFlow) : html`<div class="muted">No sign-in flow configured.</div>`}
        </div>
      `) : ''}

      ${this.viewSection('Tokens', '', 'tokens', client, html`
        ${this.viewRow('Access token', formatDuration(client.accessTokenTtl))}
        ${offline && signsUsersIn
          ? this.viewRow('Refresh token', `${formatDuration(client.refreshTokenTtl ?? 180 * 24 * 60 * 60)}, counted from login`)
          : this.viewRow('Refresh token', html`<span class="muted">Not issued</span>`,
              signsUsersIn ? 'Needs offline_access' : 'By type')}
        ${client.dpopBoundAccessTokens ? this.viewRow('DPoP proof keys', (client.dpopSigningAlgs ?? []).length ? (client.dpopSigningAlgs ?? []).join(', ') : 'Any the server accepts') : ''}
      `)}

      ${this.viewSection('Rate limit', '', 'ratelimit', client, html`
        ${this.viewRow('Calls with this client’s tokens', client.rateLimit ? declaredSummary(client.rateLimit, 'client') : html`<span class="muted">No limit</span>`,
          client.rateLimit ? '' : 'Edge policies still apply')}
      `)}

      ${hasKeys ? this.viewSection('Keys', 'Public keys the client signs with', 'keys', client, html`
        ${keys.length === 0 ? this.viewRow('Key set', '—') : keys.map(key => this.viewRow(
          key.kind, html`<span class="mono">${key.kid ? `kid ${key.kid}` : 'no kid'}${key.alg ? ` · ${key.alg}` : ''}</span>`))}
      `) : ''}

      ${client.authMethod === 'client_secret' && this.canManageSecrets ? this.viewSection('Client secret', '', null, client, html`
        ${this.viewRow('Secret', 'Set. It is shown once, when it is created or rotated.')}
        <versola-record-row .value=${client.hasPreviousSecret
              ? html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => this.handleDeletePreviousSecret(new CustomEvent('x', { detail: { clientId: client.id, clientName: getLocalizedDescription(client.clientName) } }))}>Delete old secret</button>`
              : html`<button type="button" class="btn btn-secondary btn-sm" @click=${() => this.handleRotateSecret(new CustomEvent('x', { detail: { clientId: client.id, clientName: getLocalizedDescription(client.clientName) } }))}>Rotate secret</button>`}
        ></versola-record-row>
      `, '') : ''}

      ${signsUsersIn ? this.viewSection('Ownership', '', 'registration', client, html`
        ${this.viewRow('Ownership', client.consentFlow ? 'Partner app (users are asked for consent, cannot be turned off)' : 'First party app (no consent prompt)')}
      `) : ''}

      ${signsUsersIn ? this.viewSection('Branding and logout', '', 'branding', client, html`
        ${this.viewRow('Forms theme', client.theme ?? 'default')}
        ${client.consentFlow ? this.viewRow('Appearance for this app', themeOverridesSummary(client.themeOverrides)) : ''}
        ${this.viewRow('Logout', logoutUris.length
          ? html`<div class="uri-list">${logoutUris.map(uri => html`<div class="uri-item">${uri}</div>`)}</div>` : 'None')}
      `) : ''}

      ${signsUsersIn ? html`<section class="card presets-card" aria-label="Authorization presets">${this.renderAuthorizationPresets(client)}</section>` : ''}

      ${this.canManage ? html`
        <versola-danger-card heading="Delete client" text="Sessions end and tokens stop working. This can't be undone."
          actionLabel="Delete client" .actionAriaLabel=${`Delete client ${client.id}`}
          @action=${() => this.handleDeleteClick(client)}></versola-danger-card>` : ''}
    `;
  }

  private togglePresetsExpand(clientId: string) {
    const newExpanded = new Set(this.expandedPresets);
    if (newExpanded.has(clientId)) {
      newExpanded.delete(clientId);
    } else {
      newExpanded.add(clientId);
    }
    this.expandedPresets = newExpanded;
  }

  private renderAuthorizationPresets(client: OAuthClient) {
    const presets = client.authorizationPresets || [];
    const isPresetsExpanded = this.expandedPresets.has(client.id);

    return html`
      <div class="presets-section">
        <div
          class="presets-header"
          @click=${() => this.togglePresetsExpand(client.id)}
        >
          <div class="presets-title">
            <span class="expand-icon">${isPresetsExpanded ? '▼' : '▶'}</span>
            Authorization Presets
          </div>
          ${this.canManage ? html`
          <button
            type="button"
            class="icon-action"
            @click=${(e: Event) => {
              e.stopPropagation();
              this.handleEditPresets(client);
            }}
            title="Edit presets"
            aria-label="Edit presets for ${client.id}"
          >
            ✎
          </button>` : ''}
        </div>

        ${isPresetsExpanded ? html`
          <div class="presets-body">
            ${presets.length === 0 ? html`
              <div class="empty-presets">
                No authorization presets configured. Add a preset to simplify OAuth login flows.
              </div>
            ` : html`
              <div class="presets-list">
                ${presets.map(preset => this.renderPresetCard(preset))}
              </div>
            `}
          </div>
        ` : ''}
      </div>
    `;
  }


  private renderPresetsEditForm() {
    if (!this.editingPresetsForClient) return '';

    return html`
      <div class="card">
        <h2>Edit Authorization Presets</h2>
        <p style="color: var(--text-secondary); margin-bottom: 1.5rem;">
          Manage authorization presets for ${getLocalizedDescription(this.editingPresetsForClient.clientName)}
        </p>

        ${this.presetDrafts.length === 0 ? html`
          <div class="empty-presets" style="text-align: center; padding: 2rem;">
            <p>No presets yet. Add your first preset.</p>
          </div>
        ` : html`
          <div class="presets-list">
            ${this.presetDrafts.map(preset => this.renderEditablePresetCard(preset))}
          </div>
        `}

        <div style="margin-top: 1rem;">
          <button
            type="button"
            class="btn btn-secondary"
            @click=${this.handleAddPreset}
            ?disabled=${this.isSavingPresets}
          >
            + Add Preset
          </button>
        </div>

        <div style="display: flex; gap: 1rem; justify-content: flex-end; margin-top: 1.5rem; padding-top: 1.5rem; border-top: 1px solid var(--border);">
          <button
            type="button"
            class="btn btn-secondary"
            @click=${this.handleCancelPresetsEdit}
            ?disabled=${this.isSavingPresets}
          >
            Cancel
          </button>
          <button
            type="button"
            class="btn btn-primary"
            @click=${this.handlePresetsUpdate}
            ?disabled=${this.isSavingPresets}
          >
            ${this.isSavingPresets ? 'Saving...' : 'Save Presets'}
          </button>
        </div>
      </div>
    `;
  }

  private renderAuthFlow(flow: AuthFlow) {
    const multiPrimary = flow.primaryCredentials.length > 1;
    const primaryPart = flow.primaryCredentials.join(' or ');
    const groupedPrimary = multiPrimary ? `(${primaryPart})` : primaryPart;

    // Build the credential+password expression (without passkey — passkey is a separate flow)
    let credExpr: string;
    if (flow.primaryCredentials.length === 0) {
      credExpr = '';
    } else if (flow.inlinePassword) {
      const withPassword = `${groupedPrimary} + password`;
      // Wrap in parens when there is a second factor (login+password case)
      credExpr = flow.factors.length > 0 ? `(${withPassword})` : withPassword;
    } else {
      credExpr = groupedPrimary;
    }

    return html`
      <div class="flow-section">
        ${credExpr ? this.renderFlowChain(credExpr, flow.factors) : ''}
        ${flow.passkey ? this.renderFlowChain('passkey', flow.passkeyFactors ?? []) : ''}
      </div>
    `;
  }

  private renderFlowChain(credExpr: string, factors: AuthFlow['factors']) {
    return html`
      <div class="flow-chain">
        <div class="flow-step">
          <div class="flow-step-label">credential</div>
          <div class="flow-step-values">
            <span class="flow-step-value">${credExpr}</span>
          </div>
        </div>
        ${factors.map(factor => html`
          <div class="flow-arrow">↓</div>
          <div class="flow-step">
            <div class="flow-step-label">${factor.type === 'passkeyEnroll' ? 'offer' : 'next factor'}</div>
            <div class="flow-step-values">
              <span class="flow-step-value">${factor.type === 'passkeyEnroll' ? 'passkey enrollment' : factor.type}</span>
            </div>
          </div>
        `)}
      </div>
    `;
  }

  private renderEditablePresetCard(preset: AuthorizationPreset) {
    const isExpanded = this.expandedPresetEditors.has(preset.id);

    return html`
      <div class="preset-card">
        <div class="preset-card-header" style="cursor: default;">
          <button
            type="button"
            style="all: unset; display: flex; align-items: center; gap: 0.75rem; flex: 1; cursor: pointer;"
            @click=${() => this.togglePresetEditor(preset.id)}
          >
            <span style="color: var(--text-secondary); font-size: 0.875rem;">${isExpanded ? '▾' : '▸'}</span>
            <div class="preset-info">
              <div class="preset-name">${preset.description}</div>
              <div class="preset-id">${preset.id}</div>
            </div>
          </button>
          <div class="preset-actions" style="display: flex; gap: 0.5rem;">
            <button
              type="button"
              class="icon-action"
              @click=${() => this.handleEditPreset(preset)}
              title="Edit preset"
              aria-label="Edit preset"
              ?disabled=${this.isSavingPresets}
            >
              ✎
            </button>
            <button
              type="button"
              class="icon-action danger"
              @click=${() => this.handleDeletePreset(preset)}
              title="Delete preset"
              aria-label="Delete preset"
              ?disabled=${this.isSavingPresets}
            >
              ✕
            </button>
          </div>
        </div>

        ${isExpanded ? html`
          <div class="preset-body">
            <div class="preset-details">
              ${this.renderPresetDetailRows(preset)}
            </div>
          </div>
        ` : ''}
      </div>
    `;
  }


  private renderPresetDetailRows(preset: AuthorizationPreset) {
    const emptyValue = html`<span class="preset-value preset-value-empty">Not set</span>`;
    return html`
      <div class="preset-row">
        <span class="preset-label">Redirect URI:</span>
        <span class="preset-value">${preset.redirectUri}</span>
      </div>
      <div class="preset-row">
        <span class="preset-label">Scope:</span>
        <span class="preset-value">${preset.scope.join(', ')}</span>
      </div>
      <div class="preset-row">
        <span class="preset-label">Response Type:</span>
        <span class="preset-value">${preset.responseType}</span>
      </div>
      ${preset.uiLocales && preset.uiLocales.length > 0 ? html`
        <div class="preset-row">
          <span class="preset-label">UI Locales:</span>
          <span class="preset-value">${preset.uiLocales.join(', ')}</span>
        </div>
      ` : ''}
      ${preset.customParameters && Object.keys(preset.customParameters).length > 0 ? html`
        <div class="preset-row">
          <span class="preset-label">Custom Parameters:</span>
          <div class="preset-value">
            ${Object.entries(preset.customParameters).map(([key, values]) => html`
              <div style="margin-bottom: 0.25rem;">
                <span style="color: var(--accent); font-weight: 600;">${key}:</span>
                <span>${values.join(', ')}</span>
              </div>
            `)}
          </div>
        </div>
      ` : ''}

      <div class="preset-divider"></div>

      <div class="preset-row">
        <span class="preset-label">Post-login redirect URI:</span>
        <span class="preset-value">${preset.postLoginRedirectUri}</span>
      </div>
      <div class="preset-row">
        <span class="preset-label">Post-logout redirect URI:</span>
        ${preset.postLogoutRedirectUri
          ? html`<span class="preset-value">${preset.postLogoutRedirectUri}</span>`
          : emptyValue}
      </div>
      <div class="preset-row">
        <span class="preset-label">Cookie domain:</span>
        ${preset.cookieDomain
          ? html`<span class="preset-value">${preset.cookieDomain}</span>`
          : emptyValue}
      </div>
      <div class="preset-row">
        <span class="preset-label">Cookie path:</span>
        ${preset.cookiePath
          ? html`<span class="preset-value">${preset.cookiePath}</span>`
          : emptyValue}
      </div>
    `;
  }

  private renderPresetCard(preset: AuthorizationPreset) {
    const isExpanded = this.expandedPresetCards.has(preset.id);

    return html`
      <div class="preset-card">
        <div class="preset-card-header" @click=${() => this.togglePresetExpand(preset.id)}>
          <div class="preset-info">
            <div class="preset-name">${preset.description}</div>
            <div class="preset-id">${preset.id}</div>
          </div>
        </div>

        ${isExpanded ? html`
          <div class="preset-body">
            <div class="preset-details">
              ${this.renderPresetDetailRows(preset)}
            </div>
          </div>
        ` : ''}
      </div>
    `;
  }

  private async handleEditPresets(client: OAuthClient) {
    // Ensure scopes are loaded before opening preset editor
    await this.ensureFormOptionsLoaded();
    this.editingPresetsForClient = client;
    this.presetDrafts = [...(client.authorizationPresets || [])];
    this.expandedPresetEditors = new Set(); // Start with all cards collapsed
  }

  private handleCancelPresetsEdit() {
    this.editingPresetsForClient = null;
    this.presetDrafts = [];
    this.expandedPresetEditors = new Set();
  }

  private handleAddPreset() {
    this.editingPreset = null;
    this.showPresetForm = true;
  }

  private handleEditPreset(preset: AuthorizationPreset) {
    this.editingPreset = preset;
    this.showPresetForm = true;
  }

  private handleDeletePreset(preset: AuthorizationPreset) {
    this.presetDrafts = this.presetDrafts.filter(p => p.id !== preset.id);
  }

  private handlePresetFormClose() {
    this.showPresetForm = false;
    this.editingPreset = null;
  }

  private handlePresetFormSubmit(e: CustomEvent) {
    const { preset } = e.detail as { preset: AuthorizationPreset };

    if (this.editingPreset) {
      // Editing existing preset
      this.presetDrafts = this.presetDrafts.map(p =>
        p.id === preset.id ? preset : p
      );
    } else {
      // Adding new preset
      this.presetDrafts = [...this.presetDrafts, preset];
    }

    this.handlePresetFormClose();
  }

  private togglePresetEditor(presetId: string) {
    if (this.expandedPresetEditors.has(presetId)) {
      this.expandedPresetEditors.delete(presetId);
    } else {
      this.expandedPresetEditors.add(presetId);
    }
    this.requestUpdate();
  }

  private async handlePresetsUpdate() {
    if (!this.editingPresetsForClient || !this.tenantId) return;

    this.isSavingPresets = true;

    try {
      await saveClientPresets(this.editingPresetsForClient.id, this.presetDrafts);
      const updatedClient = {
        ...this.editingPresetsForClient,
        authorizationPresets: this.presetDrafts,
      };

      this.updateClientInState(updatedClient, updatedClient.id);
      this.handleCancelPresetsEdit();
    } catch (error) {
      alert(error instanceof Error ? error.message : 'Failed to save presets');
    } finally {
      this.isSavingPresets = false;
    }
  }

}

