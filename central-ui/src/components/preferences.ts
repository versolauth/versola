import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, recordStyles } from '../styles/components';
import { fetchMyPreferences, saveMyZoneinfo } from '../utils/users-api';
import { localZone, offsetLabel } from '../utils/date-format';
import { clearStoredZoneChoice, setProfileZone, usableZone } from '../utils/zone-preference';
import { zoneOptions, type ZoneOption } from '../utils/zone-options';
import { statusIcon } from './status-icon';
import './error-card';
import './loading-cards';
import './record-card';
import './record-header';
import './typeahead-select';

/**
 * The administrator's own preferences, the same on every browser they sign in from: today the time zone the console shows times in
 * (the Activity tab of a user), kept as the `zoneinfo` claim of their profile. One record card with its Edit, which opens the group
 * as a page like the other settings pages. Empty means the browser's own zone, and the card says which that is.
 */
@customElement('versola-preferences')
export class VersolaPreferences extends LitElement {
  @state() private zone: string | null = null;
  @state() private isLoading = true;
  @state() private errorMessage = '';
  @state() private editing = false;
  @state() private draft = '';
  @state() private isSaving = false;
  @state() private formError = '';
  @state() private saved = false;
  private options: ZoneOption[] = [];

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    recordStyles,
    css`
      :host {
        display: block;
      }

      .muted {
        color: var(--text-secondary);
      }

      .mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
      }

      .editor-card {
        max-width: 54rem;
      }

      versola-typeahead-select {
        width: 24rem;
      }

      .form-actions {
        display: flex;
        justify-content: flex-end;
        gap: 0.75rem;
        margin-top: var(--spacing-lg);
        padding-top: var(--spacing-lg);
        border-top: 1px solid var(--border-default);
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.load();
  }

  private async load() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      const preferences = await fetchMyPreferences();
      this.zone = usableZone(preferences.zoneinfo);
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Could not load your preferences.';
    } finally {
      this.isLoading = false;
    }
  }

  private openEditor() {
    this.options = zoneOptions();
    this.draft = this.zone ?? '';
    this.formError = '';
    this.saved = false;
    this.editing = true;
  }

  private closeEditor() {
    this.editing = false;
    this.formError = '';
  }

  private async save(event: Event) {
    event.preventDefault();
    if (this.isSaving) return;
    const next = this.draft || null;
    this.isSaving = true;
    this.formError = '';
    try {
      await saveMyZoneinfo(next);
      this.zone = next;
      // What is saved shows at once, and replaces an earlier switch to Local or UTC in this browser.
      setProfileZone(next);
      clearStoredZoneChoice();
      this.saved = true;
      this.editing = false;
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Could not save the time zone.';
    } finally {
      this.isSaving = false;
    }
  }

  private renderZoneValue() {
    if (!this.zone) {
      return html`<span class="muted">Not set. Times follow this browser's time zone, ${localZone()}.</span>`;
    }
    return html`<span class="mono">${this.zone}</span><span class="muted"> · ${offsetLabel(Date.now(), this.zone)} now</span>`;
  }

  private renderView() {
    return html`
      ${this.saved ? html`
        <div class="banner ok" role="status">
          <div class="banner-text"><div class="banner-title">${statusIcon('check')}${this.zone ? 'Time zone saved' : 'Time zone removed'}</div>
            ${this.zone ? 'Activity reports show times in it on every browser you sign in from.' : "Times follow each browser's own time zone again."}</div>
        </div>` : nothing}
      <versola-record-card heading="Time zone" subheading="The zone the console shows times in, such as a user's Activity. Stored as the zoneinfo claim of your profile."
        editable editLabel="Edit time zone"
        .body=${html`<versola-record-row label="Time zone" .value=${this.renderZoneValue()}></versola-record-row>`}
        @edit=${() => this.openEditor()}></versola-record-card>
    `;
  }

  private renderEditor() {
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Preferences', onSelect: () => this.closeEditor() }, { label: 'Time zone' }]}
        .heading=${'Edit time zone'}
        .meta=${html`<span class="muted">Applies wherever you sign in to the console.</span>`}
      ></versola-record-header>
      <form class="card editor-card" novalidate @submit=${(e: Event) => void this.save(e)}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : nothing}
        <div class="editor-box">
          <h3>Time zone</h3>
          <p class="editor-sub">A name of the IANA time zone database. Times in the Activity tab read in it; the report's own data stays in UTC, and an export is UTC.</p>
          <div class="form-group">
            <versola-typeahead-select label="Time zone" placeholder="Use this browser's time zone"
              .options=${this.options} .value=${this.draft}
              @change=${(e: CustomEvent<{ id: string }>) => { this.draft = e.detail.id; }}></versola-typeahead-select>
            <div class="hint">Start typing a city or region. Leave it empty to use each browser's own zone${this.draft ? '' : `, now ${localZone()}`}.</div>
          </div>
        </div>
        <div class="form-actions">
          <button type="button" class="btn btn-secondary" ?disabled=${this.isSaving} @click=${() => this.closeEditor()}>Cancel</button>
          <button type="submit" class="btn btn-primary" ?disabled=${this.isSaving}>${this.isSaving ? 'Saving…' : 'Save time zone'}</button>
        </div>
      </form>
    `;
  }

  render() {
    if (this.editing) return this.renderEditor();
    return html`
      <versola-record-header .heading=${'Preferences'} .meta=${html`<span class="chip">Yours</span><span class="muted">Settings that follow you to every browser.</span>`}></versola-record-header>
      ${this.isLoading ? html`<versola-loading-cards .count=${1}></versola-loading-cards>`
      : this.errorMessage ? html`<versola-error-card heading="Could not load your preferences" .message=${this.errorMessage} @retry=${() => void this.load()}></versola-error-card>`
      : this.renderView()}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-preferences': VersolaPreferences;
  }
}
