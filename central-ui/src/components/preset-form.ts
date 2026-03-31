import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import { AuthorizationPreset, OAuthClient, OAuthScope } from '../types';
import { validateClientId } from '../utils/validators';
import { getLocalizedDescription } from '../utils/helpers';

@customElement('versola-preset-form')
export class VersolaPresetForm extends LitElement {
  @property({ type: Object }) preset: AuthorizationPreset | null = null;
  @property({ type: Object }) client: OAuthClient | null = null;
  @property({ attribute: false }) availableScopes: OAuthScope[] = [];

  @state() private formData: Partial<AuthorizationPreset> = {
    id: '',
    description: '',
    redirectUri: '',
    scope: [],
    responseType: 'code',
    uiLocales: [],
    customParameters: {},
  };

  @state() private uiLocaleInput = '';
  @state() private customParamKey = '';
  @state() private customParamValue = '';
  @state() private presetIdError = '';
  @state() private uiLocaleError = '';
  @state() private customParamError = '';
  @state() private openInfoKey: string | null = null;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
        --compact-field-width: min(100%, var(--compact-field-max-width));
        --inline-action-button-width: 2.5rem;
        --compact-inline-row-width: min(100%, calc(var(--compact-field-max-width) + var(--inline-action-button-width) + 0.5rem));
      }

      .form-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: var(--spacing-lg);
      }

      .form-title {
        font-size: 1.25rem;
        font-weight: 600;
        color: var(--text-primary);
        margin: 0;
      }

      .form-grid {
        display: grid;
        gap: var(--spacing-lg);
      }

      .form-actions {
        display: flex;
        gap: var(--spacing-sm);
        justify-content: flex-end;
        margin-top: var(--spacing-lg);
        padding-top: var(--spacing-lg);
        border-top: 1px solid var(--border-dark);
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

      .array-input-group {
        display: flex;
        gap: 0.5rem;
        align-items: flex-start;
      }

      .array-input-group input {
        flex: 1 1 auto;
        min-width: 0;
        box-sizing: border-box;
      }

      .array-input-group.compact-inline-row > input {
        flex: 0 0 var(--compact-field-width);
        width: var(--compact-field-width);
        max-width: var(--compact-field-width);
      }

      .inline-action-button {
        flex: 0 0 var(--inline-action-button-width);
        width: var(--inline-action-button-width);
        min-width: var(--inline-action-button-width);
      }

      .custom-param-input-row {
        display: flex;
        gap: 0.5rem;
        align-items: flex-start;
      }

      .custom-param-input-row input {
        min-width: 0;
        box-sizing: border-box;
      }

      .checkbox-group {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
        gap: 0.75rem;
        margin-top: 0.5rem;
        max-height: 16rem;
        overflow-y: auto;
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

      .input-error {
        border-color: var(--error, #ef4444);
      }

      .error-text {
        color: var(--error, #ef4444);
        font-size: 0.75rem;
        margin-top: 0.25rem;
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

      .option-claim-list {
        display: grid;
        gap: 0.375rem;
      }

      .option-claim-row {
        border: 1px solid var(--border-dark);
        border-radius: var(--radius-sm);
        background: rgba(255, 255, 255, 0.03);
        padding: 0.625rem 0.75rem;
      }

      .option-claim-id {
        font-size: 0.75rem;
        font-weight: 600;
        font-family: var(--font-mono);
        margin-bottom: 0.25rem;
        overflow-wrap: anywhere;
        color: var(--accent);
      }

      .option-claim-description {
        color: var(--text-secondary);
        font-size: 0.75rem;
        line-height: 1.4;
        overflow-wrap: anywhere;
      }

      .option-tooltip-empty {
        color: var(--text-secondary);
        font-size: 0.75rem;
      }

      .tag-list {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        margin-top: 0.5rem;
        align-items: flex-start;
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
        font-family: var(--font-mono);
      }

      .tag-remove {
        display: inline-flex;
        align-items: center;
        padding: 0;
        background: none;
        border: none;
        cursor: pointer;
        color: var(--danger);
        font-size: 1rem;
        transition: transform var(--transition-fast);
      }

      .tag-remove:hover {
        transform: scale(1.15);
      }

      .icon-action {
        background: none;
        border: none;
        padding: 0;
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

      .icon-action.danger:hover {
        color: var(--danger);
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    if (this.preset) {
      this.formData = {
        ...this.preset,
        customParameters: this.preset.customParameters || {},
      };
    } else {
      // Initialize with empty form for new preset
      this.formData = {
        id: '',
        description: '',
        redirectUri: this.client?.redirectUris[0] || '',
        scope: [],
        responseType: 'code',
        uiLocales: [],
        customParameters: {},
      };
    }
  }

  private generateUUID() {
    // Generate a random preset ID in the format: preset-xxxxx
    const randomPart = Math.random().toString(36).substring(2, 10);
    this.formData = {
      ...this.formData,
      id: `preset-${randomPart}`,
    };
    this.validatePresetId();
  }

  private validatePresetId() {
    const id = this.formData.id?.trim() || '';
    if (!id) {
      this.presetIdError = 'Preset ID is required';
      return false;
    }
    // Use same validation as client ID: lowercase letters, numbers, hyphen, start with letter
    const isValid = validateClientId(id);
    if (!isValid) {
      this.presetIdError = 'Must start with letter, use only lowercase letters, numbers, and hyphens';
      return false;
    }
    this.presetIdError = '';
    return true;
  }



  private handleSubmit(e: Event) {
    e.preventDefault();

    // Validate before submitting
    const isPresetIdValid = this.validatePresetId();

    if (!isPresetIdValid) {
      return;
    }

    const preset: AuthorizationPreset = {
      id: this.formData.id!,
      description: this.formData.description!,
      redirectUri: this.formData.redirectUri!,
      scope: this.formData.scope || [],
      responseType: this.formData.responseType as 'code' | 'code id_token',
      uiLocales: (this.formData.uiLocales && this.formData.uiLocales.length > 0) ? this.formData.uiLocales : undefined,
      customParameters: this.formData.customParameters || {},
    };

    this.dispatchEvent(new CustomEvent('submit', {
      detail: { preset },
      bubbles: true,
      composed: true,
    }));
  }

  private handleClose() {
    this.dispatchEvent(new CustomEvent('close', {
      bubbles: true,
      composed: true,
    }));
  }

  private toggleScope(scopeId: string) {
    const currentScopes = this.formData.scope || [];
    const newScopes = currentScopes.includes(scopeId)
      ? currentScopes.filter(s => s !== scopeId)
      : [...currentScopes, scopeId];

    this.formData = { ...this.formData, scope: newScopes };
  }

  private addUiLocale() {
    const locale = this.uiLocaleInput.trim();
    if (!locale) return;

    // Basic locale validation: lowercase letters, optionally with hyphen (e.g., "en", "en-us")
    const localeRegex = /^[a-z]{2}(-[a-z]{2})?$/i;
    if (!localeRegex.test(locale)) {
      this.uiLocaleError = 'Invalid locale format. Use format like "en", "fr", "en-US"';
      return;
    }

    const currentLocales = this.formData.uiLocales || [];
    if (currentLocales.includes(locale)) {
      this.uiLocaleError = 'This locale is already added';
      return;
    }

    this.formData = {
      ...this.formData,
      uiLocales: [...currentLocales, locale],
    };
    this.uiLocaleInput = '';
    this.uiLocaleError = '';
  }

  private removeUiLocale(locale: string) {
    this.formData = {
      ...this.formData,
      uiLocales: (this.formData.uiLocales || []).filter(l => l !== locale),
    };
  }

  private addCustomParameter() {
    const key = this.customParamKey.trim();
    const value = this.customParamValue.trim();

    if (!key || !value) {
      this.customParamError = 'Both key and value are required';
      return;
    }

    const currentParams = this.formData.customParameters || {};
    const existingValues = currentParams[key] || [];

    this.formData = {
      ...this.formData,
      customParameters: {
        ...currentParams,
        [key]: [...existingValues, value],
      },
    };

    this.customParamValue = '';
    this.customParamError = '';
  }

  private removeCustomParameterValue(key: string, value: string) {
    const currentParams = this.formData.customParameters || {};
    const values = (currentParams[key] || []).filter(v => v !== value);

    if (values.length === 0) {
      // Remove the key if no values left
      const { [key]: _, ...rest } = currentParams;
      this.formData = {
        ...this.formData,
        customParameters: rest,
      };
    } else {
      this.formData = {
        ...this.formData,
        customParameters: {
          ...currentParams,
          [key]: values,
        },
      };
    }
  }

  private toggleInfo(key: string) {
    this.openInfoKey = this.openInfoKey === key ? null : key;
  }

  private getScopeInfoItems(scope: OAuthScope) {
    return scope.claims.map(claim => ({
      id: claim.id,
      description: getLocalizedDescription(claim.description),
    }));
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

  render() {
    if (!this.client) {
      return html`<div>Error: No client provided</div>`;
    }

    return html`
      <div class="form-header">
        <h2 class="form-title">
          ${this.preset ? 'Edit Preset' : 'Add Preset'}
        </h2>
      </div>

      <div class="card">
        <form @submit=${this.handleSubmit}>
          <div class="form-grid">
            <div class="form-group">
              <label for="preset-id">Preset ID *</label>
              <div class="array-input-group compact-inline-row">
                <input
                  type="text"
                  id="preset-id"
                  class="${this.presetIdError ? 'input-error' : ''}"
                  .value=${this.formData.id || ''}
                  @input=${(e: Event) => {
                    this.formData = { ...this.formData, id: (e.target as HTMLInputElement).value };
                    this.validatePresetId();
                  }}
                  required
                  placeholder="web-login"
                />
                <button type="button" class="btn btn-secondary inline-action-button" @click=${this.generateUUID} title="Generate random ID" aria-label="Generate random ID">
                  ↻
                </button>
              </div>
              ${this.presetIdError ? html`<div class="error-text">${this.presetIdError}</div>` : html`
                <div class="hint">Lowercase letters, numbers, hyphen, start with letter</div>
              `}
            </div>

            <div class="form-group">
              <label for="preset-description">Description *</label>
              <input
                type="text"
                id="preset-description"
                class="form-input compact-input"
                .value=${this.formData.description || ''}
                @input=${(e: Event) => this.formData = { ...this.formData, description: (e.target as HTMLInputElement).value }}
                required
                placeholder="e.g., Web Login"
              />
            </div>

            <div class="form-group">
              <label for="preset-redirect-uri">Redirect URI *</label>
              <select
                id="preset-redirect-uri"
                class="form-select compact-input"
                .value=${this.formData.redirectUri || ''}
                @change=${(e: Event) => this.formData = { ...this.formData, redirectUri: (e.target as HTMLSelectElement).value }}
                required
              >
                <option value="">Select redirect URI</option>
                ${this.client.redirectUris.map(uri => html`
                  <option value=${uri} ?selected=${this.formData.redirectUri === uri}>${uri}</option>
                `)}
              </select>
              <div class="hint">Must be one of the client's allowed redirect URIs</div>
            </div>

            <div class="form-group">
              <label>Scopes *</label>
              <div class="checkbox-group">
                ${this.client.scope.map(scopeId => {
                  const scopeData = this.availableScopes.find(s => s.id === scopeId);
                  return html`
                    <div class="checkbox-item">
                      <input
                        type="checkbox"
                        id="scope-${scopeId}"
                        .checked=${(this.formData.scope || []).includes(scopeId)}
                        @change=${() => this.toggleScope(scopeId)}
                      />
                      <div class="checkbox-content">
                        <label for="scope-${scopeId}">${scopeId}</label>
                        ${scopeData ? this.renderScopeInfo(scopeData) : ''}
                      </div>
                    </div>
                  `;
                })}
              </div>
              ${this.client.scope.length === 0 ? html`
                <div class="hint">No scopes configured for this client</div>
              ` : ''}
            </div>

            <div class="form-group">
              <label for="preset-response-type">Response Type *</label>
              <select
                id="preset-response-type"
                class="form-select compact-input"
                .value=${this.formData.responseType || 'code'}
                @change=${(e: Event) => this.formData = { ...this.formData, responseType: (e.target as HTMLSelectElement).value as 'code' | 'code id_token' }}
                required
              >
                <option value="code">code</option>
                <option value="code id_token">code id_token</option>
              </select>
            </div>

            <div class="form-group">
              <label>UI Locales (optional)</label>
              <div class="array-input-group">
                <input
                  type="text"
                  class="compact-input ${this.uiLocaleError ? 'input-error' : ''}"
                  .value=${this.uiLocaleInput}
                  @input=${(e: Event) => {
                    this.uiLocaleInput = (e.target as HTMLInputElement).value;
                    this.uiLocaleError = '';
                  }}
                  @keydown=${(e: KeyboardEvent) => e.key === 'Enter' && (e.preventDefault(), this.addUiLocale())}
                  placeholder="e.g., en, fr, en-US"
                />
                <button type="button" class="btn btn-secondary" @click=${this.addUiLocale}>
                  Add
                </button>
              </div>
              ${this.uiLocaleError ? html`
                <div class="error-message" style="margin-top: 0.5rem;">${this.uiLocaleError}</div>
              ` : ''}
              ${(this.formData.uiLocales || []).length > 0 ? html`
                <div class="tag-list">
                  ${this.formData.uiLocales!.map(locale => html`
                    <div class="tag">
                      <span>${locale}</span>
                      <button type="button" class="icon-action danger tag-remove" @click=${() => this.removeUiLocale(locale)} title="Remove locale" aria-label=${`Remove locale ${locale}`}>✕</button>
                    </div>
                  `)}
                </div>
              ` : ''}
            </div>

            <div class="form-group">
              <label>Custom Parameters (optional)</label>
              <div class="custom-param-input-row">
                <input
                  type="text"
                  class="compact-input ${this.customParamError ? 'input-error' : ''}"
                  .value=${this.customParamKey}
                  @input=${(e: Event) => {
                    this.customParamKey = (e.target as HTMLInputElement).value;
                    this.customParamError = '';
                  }}
                  placeholder="Parameter key"
                />
                <input
                  type="text"
                  class="compact-input ${this.customParamError ? 'input-error' : ''}"
                  .value=${this.customParamValue}
                  @input=${(e: Event) => {
                    this.customParamValue = (e.target as HTMLInputElement).value;
                    this.customParamError = '';
                  }}
                  @keydown=${(e: KeyboardEvent) => e.key === 'Enter' && (e.preventDefault(), this.addCustomParameter())}
                  placeholder="Parameter value"
                />
                <button type="button" class="btn btn-secondary" @click=${this.addCustomParameter}>
                  Add
                </button>
              </div>
              ${this.customParamError ? html`
                <div class="error-message" style="margin-top: 0.5rem;">${this.customParamError}</div>
              ` : ''}
              ${Object.keys(this.formData.customParameters || {}).length > 0 ? html`
                <div class="custom-params-list" style="margin-top: 0.5rem;">
                  ${Object.entries(this.formData.customParameters || {}).map(([key, values]) => html`
                    <div class="custom-param-group" style="margin-bottom: 0.75rem;">
                      <div style="font-size: 0.8125rem; color: var(--text-secondary); margin-bottom: 0.25rem; font-family: var(--font-mono);">
                        ${key}
                      </div>
                      <div class="tag-list">
                        ${values.map(value => html`
                          <div class="tag">
                            <span>${value}</span>
                            <button type="button" class="icon-action danger tag-remove" @click=${() => this.removeCustomParameterValue(key, value)} title="Remove value" aria-label=${`Remove value ${value}`}>✕</button>
                          </div>
                        `)}
                      </div>
                    </div>
                  `)}
                </div>
              ` : ''}
            </div>
          </div>

          <div class="form-actions">
            <button type="button" class="btn btn-secondary" @click=${this.handleClose}>
              Cancel
            </button>
            <button type="submit" class="btn btn-primary">
              ${this.preset ? 'Update Preset' : 'Create Preset'}
            </button>
          </div>
        </form>
      </div>
    `;
  }
}