import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, formStyles } from '../styles/components';
import type { ThemeRecord } from '../types';
import { createTheme, updateTheme, deleteTheme } from '../utils/central-api';
import './code-editor';

@customElement('versola-theme-edit')
export class VersolaThemeEdit extends LitElement {
  @property({ type: Object }) theme!: ThemeRecord;
  @property({ type: String }) tenantId: string | null = null;

  @state() private editId = '';
  @state() private editCss = '';
  @state() private scope: 'global' | 'tenant' = 'global';
  @state() private saving = false;
  @state() private saveError = '';
  @state() private saveSuccess = '';

  willUpdate(changed: Map<string, unknown>) {
    if (changed.has('theme') && this.theme) {
      this.editId = this.theme.id;
      this.editCss = this.theme.css;
      this.scope = this.theme.tenantId ? 'tenant' : 'global';
    }
  }

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    css`
      :host { display: block; }
      .form-title { font-size: 1.625rem; font-weight: 700; letter-spacing: -0.025em; color: var(--text-primary); margin: 0; }
      .title-stack { display: flex; flex-direction: column; gap: 0.5rem; }
      .field-label { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-secondary); margin-bottom: 0.5rem; }
      .hint { font-size: 0.75rem; color: var(--text-secondary); margin-bottom: 0.5rem; }
      .kv-value { width: 100%; padding: 0.5rem var(--spacing-md); background: rgba(0,0,0,0.25); border: 1px solid var(--border-dark); border-radius: var(--radius-md); font-size: 0.8125rem; color: var(--text-primary); box-sizing: border-box; }
      .kv-value:focus { outline: none; border-color: var(--accent); }
      .form-actions { display: flex; align-items: center; gap: 1rem; justify-content: flex-end; margin-top: var(--spacing-xl); padding-top: var(--spacing-xl); border-top: 1px solid var(--border-dark); }
      .form-actions-left { margin-right: auto; }
      .save-msg { font-size: 0.875rem; }
      .save-msg.success { color: var(--success, #3fb950); }
      .save-msg.error { color: var(--danger); }
    `,
  ];

  private async handleSave() {
    this.saving = true;
    this.saveSuccess = '';
    this.saveError = '';
    try {
      const isNew = this.theme.id === '';
      const tenantId = isNew
        ? (this.scope === 'tenant' ? this.tenantId : null)
        : this.theme.tenantId;
      const record: ThemeRecord = {
        id: this.editId.trim(),
        css: this.editCss,
        tenantId,
      };
      if (isNew) {
        await createTheme(record);
      } else {
        await updateTheme(record);
      }
      this.saveSuccess = this.theme.id === '' ? 'Created' : 'Saved';
      setTimeout(() => { this.saveSuccess = ''; }, 2000);
      this.dispatchEvent(new CustomEvent('submit', { detail: { theme: record }, bubbles: true, composed: true }));
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'Save failed';
    } finally {
      this.saving = false;
    }
  }

  private async handleDelete() {
    try {
      await deleteTheme(this.theme.id);
      this.dispatchEvent(new CustomEvent('delete', { detail: { id: this.theme.id }, bubbles: true, composed: true }));
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'Delete failed';
    }
  }

  private handleCancel() {
    this.dispatchEvent(new CustomEvent('cancel', { bubbles: true, composed: true }));
  }

  render() {
    const isNew = this.theme?.id === '';
    const canSave = this.editId.trim().length > 0;
    return html`
      <div class="title-stack" style="margin-bottom: var(--spacing-lg)">
        <h1 class="form-title">${isNew ? 'New Theme' : 'Edit Theme'}</h1>
        ${isNew ? html`
          <div>
            <label class="field-label">Theme ID</label>
            <input class="kv-value" style="max-width:20rem" placeholder="e.g. default"
              .value=${this.editId}
              @input=${(e: Event) => { this.editId = (e.target as HTMLInputElement).value.trim(); }} />
          </div>
        ` : html`
          <div style="font-family: var(--font-mono); color: var(--accent); font-size: 1rem">${this.theme?.id}</div>
        `}
        <div>
          <label class="field-label">Scope</label>
          ${isNew ? html`
            <select class="kv-value" style="max-width:20rem"
              .value=${this.scope}
              @change=${(e: Event) => { this.scope = (e.target as HTMLSelectElement).value as 'global' | 'tenant'; }}>
              <option value="global" ?selected=${this.scope === 'global'}>Global</option>
              ${this.tenantId ? html`<option value="tenant" ?selected=${this.scope === 'tenant'}>This tenant (${this.tenantId})</option>` : nothing}
            </select>
          ` : html`
            <div class="kv-value" style="max-width:20rem">${this.theme.tenantId ? `This tenant (${this.theme.tenantId})` : 'Global'}</div>
          `}
        </div>
      </div>
      <div class="card">
        <div class="field-label">CSS</div>
        <div class="hint">Applied to all forms using this theme.</div>
        <versola-code-editor language="css" rows="20" .value=${this.editCss}
          @code-input=${(e: CustomEvent<{ value: string }>) => { this.editCss = e.detail.value; }}
        ></versola-code-editor>
        <div class="form-actions">
          ${!isNew && this.theme.id !== 'default' ? html`<div class="form-actions-left"><button class="btn btn-danger" ?disabled=${this.saving} @click=${() => this.handleDelete()}>Delete</button></div>` : nothing}
          <button class="btn btn-secondary" ?disabled=${this.saving} @click=${() => this.handleCancel()}>Cancel</button>
          <button class="btn btn-primary" ?disabled=${this.saving || !canSave} @click=${() => this.handleSave()}>
            ${this.saving ? 'Saving…' : isNew ? 'Create' : 'Save'}
          </button>
          ${this.saveSuccess ? html`<span class="save-msg success">${this.saveSuccess}</span>` : nothing}
          ${this.saveError ? html`<span class="save-msg error">${this.saveError}</span>` : nothing}
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-theme-edit': VersolaThemeEdit;
  }
}
