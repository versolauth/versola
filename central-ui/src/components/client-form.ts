import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles, iconActionStyles } from '../styles/components';
import { OAuthClient, OAuthScope, Permission, Resource } from '../types';
import { getLocalizedDescription, resolvePermissionEndpointGroups } from '../utils/helpers';
import {
  validateClientId,
  validateRedirectUri,
  ttlToSeconds,
  secondsToTtl,
} from '../utils/validators';

@customElement('versola-client-form')
export class VersolaClientForm extends LitElement {
  @property({ type: Object }) client: OAuthClient | null = null;
  @property({ attribute: false }) availableScopes: OAuthScope[] = [];
  @property({ attribute: false }) availablePermissions: Permission[] = [];
  @property({ attribute: false }) availableResources: Resource[] = [];
  @property({ attribute: false }) availableClientIds: string[] = [];
  
  @state() private formData: Partial<OAuthClient> = {
    id: '',
    clientName: '',
    redirectUris: [],
    scope: [],
    externalAudience: [],
    accessTokenTtl: 3600,
    permissions: [],
  };

  @state() private redirectUriInput = '';
  @state() private audienceInput = '';
  @state() private ttlValue = 1;
  @state() private ttlUnit: 'minutes' | 'hours' = 'hours';
  @state() private redirectUriError = '';
  @state() private audienceError = '';
  @state() private audienceSuggestionsOpen = false;
  @state() private openInfoKey: string | null = null;

  private audienceBlurTimeout: number | null = null;
  private handleDocumentClick = () => {
    this.openInfoKey = null;
  };

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    iconActionStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
        --compact-field-width: min(100%, var(--compact-field-max-width));
        --inline-action-button-width: 5.25rem;
        --compact-inline-row-width: min(100%, calc(var(--compact-field-max-width) + var(--inline-action-button-width) + 0.5rem));
      }

      .form-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: var(--spacing-xl);
      }

      .form-title {
        font-size: 2rem;
        font-weight: 700;
        color: var(--text-primary);
        margin: 0;
      }

      .form-grid {
        display: grid;
        gap: var(--spacing-lg);
      }

      .array-input-group {
        display: flex;
        gap: 0.5rem;
        align-items: flex-start;
      }

      .compact-input {
        display: block;
        width: 100%;
        max-width: var(--compact-field-width);
        box-sizing: border-box;
      }

      .compact-inline-row {
        width: 100%;
        max-width: var(--compact-inline-row-width);
      }

      .array-input-group input {
        flex: 1 1 auto;
        min-width: 0;
        box-sizing: border-box;
      }

      .array-input-group.compact-inline-row > input,
      .array-input-group.compact-inline-row > .inline-action-field {
        flex: 0 0 var(--compact-field-width);
        width: var(--compact-field-width);
        max-width: var(--compact-field-width);
      }

      .autocomplete-wrapper {
        min-width: 0;
        width: 100%;
      }

      .inline-action-field {
        min-width: 0;
      }

      .inline-action-button {
        flex: 0 0 var(--inline-action-button-width);
        width: var(--inline-action-button-width);
        min-width: var(--inline-action-button-width);
      }

      .autocomplete-wrapper input {
        width: 100%;
        box-sizing: border-box;
      }

      .autocomplete-dropdown {
        width: 100%;
        box-sizing: border-box;
        background: var(--bg-dark);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35), 0 0 0 1px rgba(88, 166, 255, 0.08);
        overflow: hidden;
        max-height: 220px;
        overflow-y: auto;
      }

      .compact-autocomplete-dropdown {
        width: 100%;
        max-width: var(--compact-field-width);
        margin-top: 0.375rem;
      }

      .autocomplete-option {
        width: 100%;
        border: none;
        background: transparent;
        color: var(--text-primary);
        padding: 0.625rem 0.875rem;
        text-align: left;
        cursor: pointer;
        font-size: 0.875rem;
        font-family: var(--font-mono);
        overflow-wrap: anywhere;
        transition: background var(--transition-fast), color var(--transition-fast);
      }

      .autocomplete-option:hover {
        background: rgba(88, 166, 255, 0.12);
        color: var(--accent);
      }

      .autocomplete-empty {
        padding: 0.75rem 0.875rem;
        color: var(--text-secondary);
        font-size: 0.8125rem;
      }

      .tag-list {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        margin-top: 0.5rem;
      }

      .tag {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.375rem 0.75rem;
        background: rgba(88, 166, 255, 0.15);
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-md);
        color: var(--accent);
        font-size: 0.875rem;
      }

      .tag-remove {
        display: inline-flex;
        align-items: center;
      }

      .tag-remove:hover {
        transform: scale(1.15);
      }

      .checkbox-group {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
        gap: 0.75rem;
        margin-top: 0.5rem;
      }

      .checkbox-item {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        padding: 0.5rem;
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-sm);
        cursor: pointer;
        transition: all var(--transition-fast);
      }

      .checkbox-item:hover {
        border-color: var(--accent);
        background: rgba(88, 166, 255, 0.05);
      }

      .checkbox-item input[type="checkbox"] {
        cursor: pointer;
      }

      .checkbox-content {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.5rem;
        min-width: 0;
        flex: 1;
      }

      .checkbox-item label {
        cursor: pointer;
        margin: 0;
        font-weight: normal;
        text-transform: none;
        letter-spacing: normal;
        font-size: 0.875rem;
        color: var(--text-primary);
        font-family: var(--font-mono);
        user-select: none;
        -webkit-user-select: none;
        -moz-user-select: none;
        min-width: 0;
        overflow-wrap: anywhere;
      }

      .option-info-button {
        flex: none;
        border: 1px solid rgba(88, 166, 255, 0.4);
        border-radius: 999px;
        background: rgba(88, 166, 255, 0.12);
        color: var(--accent);
        font-size: 0.75rem;
        font-weight: 700;
        line-height: 1;
        padding: 0.25rem 0.45rem;
        cursor: pointer;
        font-family: var(--font-family);
      }

      .option-info {
        position: relative;
        display: inline-flex;
        align-items: center;
        flex: none;
      }

      .option-info-button:hover {
        background: rgba(88, 166, 255, 0.18);
        border-color: rgba(88, 166, 255, 0.55);
      }

      .option-info-button:focus-visible {
        outline: none;
        box-shadow: 0 0 0 2px rgba(88, 166, 255, 0.2);
      }

      .option-tooltip {
        position: absolute;
        right: 0;
        top: calc(100% + 0.4rem);
        z-index: 20;
        min-width: 18rem;
        max-width: min(28rem, 75vw);
        max-height: 18rem;
        overflow: auto;
        padding: 0.75rem;
        border: 1px solid rgba(88, 166, 255, 0.28);
        border-radius: var(--radius-md);
        background: linear-gradient(180deg, rgba(22, 27, 34, 0.98), rgba(13, 17, 23, 0.98));
        box-shadow: 0 10px 24px rgba(0, 0, 0, 0.35);
        display: none;
      }

      .option-info.option-info-open .option-tooltip {
        display: block;
      }

      .option-tooltip-title {
        margin-bottom: 0.5rem;
        color: var(--accent);
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .option-tooltip-list,
      .option-tooltip-groups,
      .option-claim-list,
      .option-endpoint-list {
        display: grid;
        gap: 0.375rem;
      }

      .option-tooltip-item,
      .option-claim-description,
      .option-endpoint-path {
        color: var(--text-primary);
        font-size: 0.75rem;
        line-height: 1.4;
        overflow-wrap: anywhere;
      }

      .option-tooltip-empty {
        color: var(--text-secondary);
        font-size: 0.75rem;
      }

      .option-claim-row,
      .option-tooltip-group {
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-sm);
        background: rgba(255, 255, 255, 0.03);
        padding: 0.625rem 0.75rem;
      }

      .option-claim-id,
      .option-tooltip-group-title {
        font-size: 0.75rem;
        font-weight: 600;
        font-family: var(--font-mono);
        margin-bottom: 0.25rem;
        overflow-wrap: anywhere;
      }

      .option-claim-id {
        color: var(--accent);
      }

      .option-claim-description {
        color: var(--text-secondary);
      }

      .option-tooltip-group-title {
        color: var(--accent);
      }

      .option-endpoint-row {
        color: var(--text-primary);
        font-size: 0.75rem;
        line-height: 1.4;
        font-family: var(--font-mono);
        overflow-wrap: anywhere;
      }

      .option-endpoint-method {
        color: var(--text-primary);
        font-weight: 600;
      }

      /* Remove number input arrows */
      input[type="number"]::-webkit-inner-spin-button,
      input[type="number"]::-webkit-outer-spin-button {
        -webkit-appearance: none;
        margin: 0;
      }

      input[type="number"] {
        -moz-appearance: textfield;
      }

      /* Custom select styling - remove arrow, add custom indicator */
      .ttl-unit-select {
        appearance: none;
        -webkit-appearance: none;
        -moz-appearance: none;
        cursor: pointer;
        padding-right: 2.5rem;
        background: rgba(0, 0, 0, 0.2);
        position: relative;
      }

      .ttl-unit-select:hover {
        background: rgba(88, 166, 255, 0.1);
      }

      .ttl-unit-select:focus {
        background: rgba(0, 0, 0, 0.3);
      }

      /* Custom dropdown indicator using pseudo-element */
      .ttl-select-wrapper {
        position: relative;
        flex: 0 0 120px;
      }

      .ttl-select-wrapper::after {
        content: '▼';
        position: absolute;
        right: 0.75rem;
        top: 50%;
        transform: translateY(-50%);
        color: var(--accent);
        font-size: 0.625rem;
        pointer-events: none;
      }

      .form-actions {
        display: flex;
        gap: 1rem;
        justify-content: flex-end;
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-dark);
      }

      .secondary-action-button {
        margin-right: auto;
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    document.addEventListener('click', this.handleDocumentClick);
    if (this.client) {
      this.formData = { ...this.client };
      // Convert TTL from seconds to value + unit
      const { value, unit } = secondsToTtl(this.client.accessTokenTtl);
      this.ttlValue = value;
      this.ttlUnit = unit;
    } else {
      // Defaults: 1 hour
      this.ttlValue = 1;
      this.ttlUnit = 'hours';
    }
  }

  disconnectedCallback() {
    document.removeEventListener('click', this.handleDocumentClick);
    this.clearAudienceBlurTimeout();
    super.disconnectedCallback();
  }

  private handleSubmit(e: Event) {
    e.preventDefault();

    // Validate client ID only when creating a new client
    if (!this.client) {
      if (!validateClientId((this.formData.id || '').trim())) {
        return;
      }
    }

    const audienceError = this.getAudienceValidationError(this.formData.externalAudience || []);
    if (audienceError) {
      this.audienceError = audienceError;
      return;
    }

    this.audienceError = '';

    const client: OAuthClient = {
      id: this.formData.id!,
      clientName: this.formData.clientName!,
      redirectUris: this.formData.redirectUris || [],
      scope: this.formData.scope || [],
      externalAudience: this.formData.externalAudience || [],
      hasPreviousSecret: false,
      accessTokenTtl: ttlToSeconds(this.ttlValue, this.ttlUnit),
      permissions: this.formData.permissions || [],
    };

    this.dispatchEvent(new CustomEvent('submit', {
      detail: { client },
      bubbles: true,
      composed: true,
    }));
  }

  private handleClientIdInput(e: Event) {
    const value = (e.target as HTMLInputElement).value;
    this.formData = { ...this.formData, id: value };
  }

  private get isClientIdInvalid() {
    const clientId = (this.formData.id || '').trim();
    return !this.client && clientId.length > 0 && !validateClientId(clientId);
  }

  private get isRedirectUriInvalid() {
    const redirectUri = this.redirectUriInput.trim();
    return redirectUri.length > 0 && !validateRedirectUri(redirectUri).valid;
  }

  private handleTtlUnitChange(e: Event) {
    const newUnit = (e.target as HTMLSelectElement).value as 'minutes' | 'hours';
    this.ttlUnit = newUnit;
    // Set default values when switching units
    if (newUnit === 'hours' && this.ttlValue < 1) {
      this.ttlValue = 1;
    } else if (newUnit === 'minutes' && this.ttlValue === 0) {
      this.ttlValue = 10;
    }
  }

  private handleClose() {
    this.dispatchEvent(new CustomEvent('close', {
      bubbles: true,
      composed: true,
    }));
  }

  private handleRotateSecret() {
    if (!this.client) {
      return;
    }

    this.dispatchEvent(new CustomEvent('rotate-secret', {
      detail: {
        clientId: this.client.id,
        clientName: this.formData.clientName || this.client.clientName,
      },
      bubbles: true,
      composed: true,
    }));
  }

  private handleDeleteOldSecret() {
    if (!this.client) {
      return;
    }

    this.dispatchEvent(new CustomEvent('delete-previous-secret', {
      detail: {
        clientId: this.client.id,
        clientName: this.formData.clientName || this.client.clientName,
      },
      bubbles: true,
      composed: true,
    }));
  }

  private addRedirectUri() {
    const uri = this.redirectUriInput.trim();
    if (!uri) return;

    const validation = validateRedirectUri(uri);
    if (!validation.valid) {
      this.redirectUriError = validation.error || 'Invalid redirect URI';
      return;
    }

    this.formData = {
      ...this.formData,
      redirectUris: [...(this.formData.redirectUris || []), uri],
    };
    this.redirectUriInput = '';
    this.redirectUriError = '';
  }

  private handleRedirectUriInput(e: Event) {
    this.redirectUriInput = (e.target as HTMLInputElement).value;
    if (this.redirectUriError) {
      this.redirectUriError = '';
    }
  }

  private removeRedirectUri(uri: string) {
    this.formData = {
      ...this.formData,
      redirectUris: (this.formData.redirectUris || []).filter(u => u !== uri),
    };
  }

  private addAudience() {
    this.addAudienceValue(this.audienceInput);
  }

  private addAudienceValue(value: string) {
    const aud = value.trim();
    if (!aud) return;

    const audienceError = this.getAudienceEntryValidationError(aud);
    if (audienceError) {
      this.audienceError = audienceError;
      return;
    }

    this.formData = {
      ...this.formData,
      externalAudience: [...(this.formData.externalAudience || []), aud],
    };
    this.audienceInput = '';
    this.audienceError = '';
    this.audienceSuggestionsOpen = false;
  }

  private isExistingAudience(audience: string) {
    return this.availableClientIds.includes(audience);
  }

  private getAudienceValidationError(audiences: string[]) {
    const seen = new Set<string>();

    for (const audience of audiences) {
      const error = this.getAudienceReferenceValidationError(audience);
      if (error) {
        return error;
      }

      if (seen.has(audience)) {
        return 'Audience has already been added';
      }

      seen.add(audience);
    }

    return '';
  }

  private getAudienceEntryValidationError(audience: string) {
    const referenceError = this.getAudienceReferenceValidationError(audience);
    if (referenceError) {
      return referenceError;
    }

    if ((this.formData.externalAudience || []).includes(audience)) {
      return 'Audience has already been added';
    }

    return '';
  }

  private getAudienceReferenceValidationError(audience: string) {
    if (audience === this.formData.id?.trim()) {
      return 'Audience cannot reference the client itself';
    }

    if (!this.isExistingAudience(audience)) {
      return 'Audience must reference an existing client in this tenant';
    }

    return '';
  }

  private handleAudienceInput(e: Event) {
    this.audienceInput = (e.target as HTMLInputElement).value;
    this.clearAudienceBlurTimeout();
    this.audienceSuggestionsOpen = true;
    if (this.audienceError) {
      this.audienceError = '';
    }
  }

  private handleAudienceFocus() {
    this.clearAudienceBlurTimeout();
    this.audienceSuggestionsOpen = true;
  }

  private handleAudienceBlur() {
    this.clearAudienceBlurTimeout();
    this.audienceBlurTimeout = window.setTimeout(() => {
      this.audienceSuggestionsOpen = false;
      this.audienceBlurTimeout = null;
    }, 120);
  }

  private clearAudienceBlurTimeout() {
    if (this.audienceBlurTimeout !== null) {
      window.clearTimeout(this.audienceBlurTimeout);
      this.audienceBlurTimeout = null;
    }
  }

  private selectAudience(audience: string) {
    this.clearAudienceBlurTimeout();
    this.addAudienceValue(audience);
  }

  private get filteredAudienceOptions() {
    const selectedAudiences = new Set(this.formData.externalAudience || []);
    const currentClientId = this.formData.id?.trim() || '';
    const query = this.audienceInput.trim().toLowerCase();

    return this.availableClientIds
      .filter(audience => {
        if (selectedAudiences.has(audience) || audience === currentClientId) {
          return false;
        }

        return !query || audience.toLowerCase().includes(query);
      })
      .sort((a, b) => a.localeCompare(b));
  }

  private removeAudience(aud: string) {
    this.formData = {
      ...this.formData,
      externalAudience: (this.formData.externalAudience || []).filter(a => a !== aud),
    };
  }

  private getScopeInfoItems(scope: OAuthScope) {
    return scope.claims.map(claim => ({
      id: claim.id,
      description: getLocalizedDescription(claim.description),
    }));
  }

  private getPermissionInfoGroups(permission: Permission) {
    return resolvePermissionEndpointGroups(permission, this.availableResources);
  }

  private toggleInfo(key: string) {
    this.openInfoKey = this.openInfoKey === key ? null : key;
  }

  private renderOptionInfo(key: string, title: string, content: unknown, ariaLabel: string) {
    return html`
      <div class=${`option-info ${this.openInfoKey === key ? 'option-info-open' : ''}`} @click=${(e: Event) => e.stopPropagation()}>
        <button
          type="button"
          class="option-info-button"
          aria-label=${ariaLabel}
          aria-expanded=${this.openInfoKey === key ? 'true' : 'false'}
          @click=${() => this.toggleInfo(key)}
        >i</button>
        <div class="option-tooltip" role="tooltip">
          <div class="option-tooltip-title">${title}</div>
          ${content}
        </div>
      </div>
    `;
  }

  private renderScopeInfo(scope: OAuthScope) {
    const claims = this.getScopeInfoItems(scope);

    return this.renderOptionInfo(
      `scope:${scope.id}`,
      'Claims',
      claims.length > 0 ? html`
        <div class="option-claim-list">
          ${claims.map(claim => html`
            <div class="option-claim-row">
              <div class="option-claim-id">${claim.id}</div>
              ${claim.description && claim.description !== claim.id ? html`
                <div class="option-claim-description">${claim.description}</div>
              ` : ''}
            </div>
          `)}
        </div>
      ` : html`<div class="option-tooltip-empty">No claims</div>`,
      `Show claims for scope ${scope.id}`,
    );
  }

  private renderPermissionInfo(permission: Permission) {
    const groups = this.getPermissionInfoGroups(permission);

    return this.renderOptionInfo(
      `permission:${permission.id}`,
      'Resources & endpoints',
      groups.length > 0 ? html`
        <div class="option-tooltip-groups">
          ${groups.map(group => html`
            <div class="option-tooltip-group">
              <div class="option-tooltip-group-title">${group.title}</div>
              <div class="option-endpoint-list">
                ${group.endpoints.map(endpoint => html`
                  <div class="option-endpoint-row">
                    <span class="option-endpoint-method">${endpoint.method}</span>
                    <span>${endpoint.path}</span>
                  </div>
                `)}
              </div>
            </div>
          `)}
        </div>
      ` : html`<div class="option-tooltip-empty">No endpoints</div>`,
      `Show endpoints for permission ${permission.id}`,
    );
  }

  private toggleScope(scope: string) {
    const scopes = this.formData.scope || [];
    this.formData = {
      ...this.formData,
      scope: scopes.includes(scope)
        ? scopes.filter(s => s !== scope)
        : [...scopes, scope],
    };
  }

  private togglePermission(permission: string) {
    const permissions = this.formData.permissions || [];
    this.formData = {
      ...this.formData,
      permissions: permissions.includes(permission)
        ? permissions.filter(p => p !== permission)
        : [...permissions, permission],
    };
  }

  render() {
    const filteredAudienceOptions = this.filteredAudienceOptions;
    const showAudienceSuggestions = this.audienceSuggestionsOpen && (!!this.audienceInput.trim() || filteredAudienceOptions.length > 0);

    return html`
      <div class="form-header">
        <div class="title-stack">
          <h1 class="form-title">
            ${this.client ? 'Edit Client' : 'Create New Client'}
          </h1>
          ${this.client ? html`<div class="entity-id-meta">${this.formData.id || '—'}</div>` : ''}
        </div>
      </div>

      <div class="card">
        <form @submit=${this.handleSubmit}>
          <div class="form-grid">
            ${!this.client ? html`
              <div class="form-group">
                <label for="client-id">Client ID *</label>
                <input
                  type="text"
                  id="client-id"
                  class="compact-input ${this.isClientIdInvalid ? 'input-error' : ''}"
                  .value=${this.formData.id || ''}
                  @input=${this.handleClientIdInput}
                  required
                  placeholder="e.g., web-app"
                />
                <div class="hint">Lowercase letters, numbers, hyphen, start with letter</div>
              </div>
            ` : ''}

            <div class="form-group">
              <label for="client-name">Client Name *</label>
              <input
                type="text"
                id="client-name"
                class="compact-input"
                .value=${this.formData.clientName || ''}
                @input=${(e: Event) => this.formData = { ...this.formData, clientName: (e.target as HTMLInputElement).value }}
                required
                placeholder="e.g., My Web Application"
              />
            </div>

            <div class="form-group">
              <label>Redirect URIs</label>
              <div class="array-input-group compact-inline-row">
                <input
                  type="text"
                  class="${this.isRedirectUriInvalid || this.redirectUriError ? 'input-error' : ''}"
                  .value=${this.redirectUriInput}
                  @input=${this.handleRedirectUriInput}
                  @keydown=${(e: KeyboardEvent) => e.key === 'Enter' && (e.preventDefault(), this.addRedirectUri())}
                  placeholder="https://app.example.com/callback"
                />
                <button type="button" class="btn btn-secondary inline-action-button" @click=${this.addRedirectUri}>
                  Add
                </button>
              </div>
              ${this.redirectUriError ? html`
                <div class="error-message" style="margin-top: 0.5rem;">${this.redirectUriError}</div>
              ` : ''}
              ${(this.formData.redirectUris || []).length > 0 ? html`
                <div class="tag-list">
                  ${this.formData.redirectUris!.map(uri => html`
                    <div class="tag">
                      <span>${uri}</span>
                      <button type="button" class="icon-action danger tag-remove" @click=${() => this.removeRedirectUri(uri)} title="Remove redirect URI" aria-label=${`Remove redirect URI ${uri}`}>✕</button>
                    </div>
                  `)}
                </div>
              ` : ''}
            </div>

            <div class="form-group">
              <label for="external-audience">External Audience</label>
              <div class="autocomplete-wrapper compact-input">
                <input
                  type="text"
                  id="external-audience"
                  class="${this.audienceError ? 'input-error' : ''}"
                  .value=${this.audienceInput}
                  @input=${this.handleAudienceInput}
                  @focus=${this.handleAudienceFocus}
                  @blur=${this.handleAudienceBlur}
                  @keydown=${(e: KeyboardEvent) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      this.addAudience();
                    }

                    if (e.key === 'Escape') {
                      this.audienceSuggestionsOpen = false;
                    }
                  }}
                  aria-haspopup="listbox"
                  aria-expanded=${showAudienceSuggestions ? 'true' : 'false'}
                  aria-controls="external-audience-options"
                  placeholder="api-service"
                  autocomplete="off"
                />
              </div>
              ${showAudienceSuggestions ? html`
                <div class="autocomplete-dropdown compact-autocomplete-dropdown" id="external-audience-options" role="listbox" aria-label="Available client audiences">
                  ${filteredAudienceOptions.length > 0 ? filteredAudienceOptions.map(audience => html`
                    <button
                      type="button"
                      class="autocomplete-option"
                      @mousedown=${(e: MouseEvent) => e.preventDefault()}
                      @click=${() => this.selectAudience(audience)}
                      aria-label=${`Select audience ${audience}`}
                    >${audience}</button>
                  `) : html`
                    <div class="autocomplete-empty">No matching clients</div>
                  `}
                </div>
              ` : ''}
              ${this.audienceError ? html`
                <div class="error-message" style="margin-top: 0.5rem;">${this.audienceError}</div>
              ` : ''}
              ${(this.formData.externalAudience || []).length > 0 ? html`
                <div class="tag-list">
                  ${this.formData.externalAudience!.map(aud => html`
                    <div class="tag">
                      <span>${aud}</span>
                      <button type="button" class="icon-action danger tag-remove" @click=${() => this.removeAudience(aud)} title="Remove audience" aria-label=${`Remove audience ${aud}`}>✕</button>
                    </div>
                  `)}
                </div>
              ` : ''}
            </div>

            <div class="form-group">
              <label for="ttl">Access Token TTL *</label>
              <div class="array-input-group compact-inline-row">
                <input
                  type="number"
                  id="ttl"
                  .value=${String(this.ttlValue)}
                  @input=${(e: Event) => this.ttlValue = parseInt((e.target as HTMLInputElement).value) || 1}
                  required
                  min="1"
                  placeholder="${this.ttlUnit === 'hours' ? '1' : '10'}"
                  style="flex: 0 0 120px;"
                />
                <div class="ttl-select-wrapper">
                  <select
                    class="ttl-unit-select"
                    .value=${this.ttlUnit}
                    @change=${this.handleTtlUnitChange}
                  >
                    <option value="minutes">Minutes</option>
                    <option value="hours">Hours</option>
                  </select>
                </div>
              </div>
              <div class="hint">${ttlToSeconds(this.ttlValue, this.ttlUnit)} seconds</div>
            </div>

            <div class="form-group">
              <label>OAuth Scopes</label>
              <div class="checkbox-group">
                ${this.availableScopes.map(scope => html`
                  <div class="checkbox-item" @click=${() => this.toggleScope(scope.id)}>
                    <input
                      type="checkbox"
                      id="scope-${scope.id}"
                      .checked=${(this.formData.scope || []).includes(scope.id)}
                      @click=${(e: Event) => e.stopPropagation()}
                      @change=${() => this.toggleScope(scope.id)}
                    />
                    <div class="checkbox-content">
                      <label for="scope-${scope.id}" @click=${(e: Event) => e.preventDefault()}>${scope.id}</label>
                      ${this.renderScopeInfo(scope)}
                    </div>
                  </div>
                `)}
              </div>
              ${this.availableScopes.length === 0 ? html`<div class="helper-text">No scopes available for this tenant yet.</div>` : ''}
            </div>

            <div class="form-group">
              <label>Permissions</label>
              <div class="checkbox-group">
                ${this.availablePermissions.map(perm => html`
                  <div class="checkbox-item" @click=${() => this.togglePermission(perm.id)}>
                    <input
                      type="checkbox"
                      id="perm-${perm.id}"
                      .checked=${(this.formData.permissions || []).includes(perm.id)}
                      @click=${(e: Event) => e.stopPropagation()}
                      @change=${() => this.togglePermission(perm.id)}
                    />
                    <div class="checkbox-content">
                      <label for="perm-${perm.id}" @click=${(e: Event) => e.preventDefault()}>${perm.id}</label>
                      ${this.renderPermissionInfo(perm)}
                    </div>
                  </div>
                `)}
              </div>
              ${this.availablePermissions.length === 0 ? html`<div class="helper-text">No permissions available for this tenant yet.</div>` : ''}
            </div>
          </div>

          <div class="form-actions">
            ${this.client ? html`
              ${this.client.hasPreviousSecret ? html`
                <button
                  type="button"
                  class="btn btn-secondary btn-sm secondary-action-button"
                  @click=${this.handleDeleteOldSecret}
                  title="Delete old secret"
                  aria-label="Delete old secret"
                >Delete old secret</button>
              ` : html`
                <button
                  type="button"
                  class="btn btn-secondary secondary-action-button"
                  @click=${this.handleRotateSecret}
                >Rotate Secret</button>
              `}
            ` : ''}
            <button type="button" class="btn btn-secondary" @click=${this.handleClose}>
              Cancel
            </button>
            <button type="submit" class="btn btn-primary">
              ${this.client ? 'Update Client' : 'Create Client'}
            </button>
          </div>
        </form>
      </div>
    `;
  }
}

