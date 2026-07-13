import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import type { SystemSettingsRecord } from '../types';
import { fetchSystemSettings, upsertSystemSettings } from '../utils/central-api';
import './error-card';
import './loading-cards';

@customElement('versola-system-settings')
export class VersolaSystemSettings extends LitElement {
  @property({ type: Boolean }) canManage = false;

  @state() private settings: SystemSettingsRecord | null = null;
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private editing = false;
  @state() private saving = false;
  @state() private editError = '';

  @state() private editPasswordRegex = '';
  @state() private editPasswordHistorySize = 5;
  @state() private editPasswordNumDifferent = 3;

  static styles = [
    theme,
    cardStyles,
    buttonStyles,
    formStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
        --number-field-max-width: 8rem;
      }
      .page-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: var(--spacing-xl);
      }
      .page-title {
        font-size: 1.625rem;
        font-weight: 700;
        letter-spacing: -0.025em;
        color: var(--text-primary);
        margin: 0;
      }
      .prop-row {
        display: flex;
        gap: var(--spacing-md);
        padding: var(--spacing-sm) 0;
        border-bottom: 1px solid var(--border-dark);
      }
      .prop-row:last-child { border-bottom: none; }
      .prop-label {
        flex: 0 0 220px;
        color: var(--text-secondary);
        font-size: 0.875rem;
      }
      .prop-value { color: var(--text-primary); font-size: 0.875rem; }
      .form-grid { display: grid; gap: var(--spacing-lg); }
      .number-input {
        display: block;
        width: 100%;
        max-width: var(--number-field-max-width);
        box-sizing: border-box;
      }
      .form-actions { display: flex; gap: var(--spacing-sm); margin-top: var(--spacing-lg); }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      this.settings = await fetchSystemSettings();
    } catch (e) {
      this.errorMessage = e instanceof Error ? e.message : 'Failed to load system settings';
    } finally {
      this.isLoading = false;
    }
  }

  private startEdit() {
    this.editPasswordRegex = this.settings?.passwordRegex ?? '';
    this.editPasswordHistorySize = this.settings?.passwordHistorySize ?? 5;
    this.editPasswordNumDifferent = this.settings?.passwordNumDifferent ?? 3;
    this.editError = '';
    this.editing = true;
  }

  private cancelEdit() {
    this.editing = false;
    this.editError = '';
  }

  private async save() {
    const regex = this.editPasswordRegex.trim();
    if (!regex) {
      this.editError = 'Password regex is required.';
      return;
    }
    try { new RegExp(regex); } catch {
      this.editError = 'Invalid regular expression.';
      return;
    }
    this.saving = true;
    this.editError = '';
    try {
      const updated: SystemSettingsRecord = {
        passwordRegex: regex,
        passwordHistorySize: this.editPasswordHistorySize,
        passwordNumDifferent: this.editPasswordNumDifferent,
      };
      await upsertSystemSettings(updated);
      this.settings = updated;
      this.editing = false;
    } catch (e) {
      this.editError = e instanceof Error ? e.message : 'Failed to save settings';
    } finally {
      this.saving = false;
    }
  }

  render() {
    return html`
      <div class="page-header">
        <h1 class="page-title">System Settings</h1>
        ${this.canManage && !this.editing && this.settings !== null
          ? html`<button class="btn btn-primary" @click=${() => this.startEdit()}>Edit</button>`
          : ''}
      </div>

      ${this.errorMessage ? html`<versola-error-card .message=${this.errorMessage}></versola-error-card>` : ''}
      ${this.isLoading ? html`<versola-loading-cards></versola-loading-cards>` : ''}

      ${this.settings !== null && !this.editing ? this.renderView() : ''}
      ${this.editing ? this.renderForm() : ''}
    `;
  }

  private renderView() {
    const s = this.settings!;
    return html`
      <div class="card">
        <label>Password Policy</label>
        <div class="prop-row">
          <span class="prop-label">Regex</span>
          <span class="prop-value">${s.passwordRegex ?? '—'}</span>
        </div>
        <div class="prop-row">
          <span class="prop-label">History size</span>
          <span class="prop-value">${s.passwordHistorySize}</span>
        </div>
        <div class="prop-row">
          <span class="prop-label">Different passwords required</span>
          <span class="prop-value">${s.passwordNumDifferent}</span>
        </div>
      </div>
    `;
  }

  private renderForm() {
    return html`
      <div class="card">
        <div class="form-grid">
          <div class="form-group">
            <label>Password Regex</label>
            <input class="compact-input" type="text"
              .value=${this.editPasswordRegex}
              placeholder="^(?=.*[A-Za-z])(?=.*\\d).{8,}$"
              @input=${(e: Event) => { this.editPasswordRegex = (e.target as HTMLInputElement).value; }} />
            <div class="hint">Passwords must match this pattern.</div>
          </div>

          <div class="form-group">
            <label>Password History Size</label>
            <input class="number-input" type="number" min="0"
              .value=${String(this.editPasswordHistorySize)}
              @input=${(e: Event) => { this.editPasswordHistorySize = Math.max(0, parseInt((e.target as HTMLInputElement).value) || 0); }} />
            <div class="hint">Number of previous passwords kept (including the current one).</div>
          </div>

          <div class="form-group">
            <label>Different Passwords Required</label>
            <input class="number-input" type="number" min="0"
              .value=${String(this.editPasswordNumDifferent)}
              @input=${(e: Event) => { this.editPasswordNumDifferent = Math.max(0, parseInt((e.target as HTMLInputElement).value) || 0); }} />
            <div class="hint">How many distinct passwords before an old one can be reused.</div>
          </div>
        </div>

        ${this.editError ? html`<div class="error-message">${this.editError}</div>` : ''}
        <div class="form-actions">
          <button class="btn btn-primary" ?disabled=${this.saving} @click=${() => this.save()}>
            ${this.saving ? 'Saving…' : 'Save'}
          </button>
          <button class="btn btn-secondary" ?disabled=${this.saving} @click=${() => this.cancelEdit()}>Cancel</button>
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-system-settings': VersolaSystemSettings;
  }
}
