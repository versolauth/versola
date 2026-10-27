import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, toggleStyles } from '../styles/components';
import type { DeliverySettingsRecord, DeliverySettingsUpdate, DeliveryTestRequest } from '../types';
import { deleteDeliverySettings, fetchDeliverySettings, sendDeliveryTest, upsertDeliverySettings, type ApiError } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import {
  OTP_METHODS,
  otpProviderDraftFrom,
  otpProviderFromDraft,
  passwordChange,
  smtpDraftFrom,
  smtpFromDraft,
  validateOtpProviderDraft,
  validateSmtpDraft,
  validateTestRecipient,
  type FieldErrors,
  type OtpProviderDraft,
  type SmtpDraft,
} from '../utils/delivery-settings';
import { statusIcon } from './status-icon';
import './error-card';
import './loading-cards';
import './record-card';
import './record-header';
import './danger-card';

type Group = 'smtp' | 'otp-provider';

/** What removing the record does; the danger card and its confirmation say it in the same words. */
const REMOVE_EFFECT = "The email server, the SMS provider and their saved passwords are deleted from central. Delivery goes back to the settings in auth's own configuration file. This can't be undone.";
const NOT_CONFIGURED = 'Not configured here. Auth uses its own configuration file for this, if it has a setting there.';

/**
 * How one-time codes and temporary passwords reach users, for the whole deployment: the SMTP server for email and the
 * HTTP provider for SMS. Two record cards, each with its own Edit (a page), and a danger card that takes the saved
 * record away. A password is write-only: the page shows whether one is saved, never what it is, and a save with the
 * field left empty keeps it.
 */
@customElement('versola-delivery-settings')
export class VersolaDeliverySettings extends LitElement {
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private record: DeliverySettingsRecord | null = null;
  @state() private isLoading = false;
  @state() private errorMessage = '';

  @state() private smtpDraft: SmtpDraft = smtpDraftFrom(undefined);
  @state() private otpDraft: OtpProviderDraft = otpProviderDraftFrom(undefined);
  @state() private fieldErrors: FieldErrors = {};
  @state() private formError = '';
  @state() private isSaving = false;
  @state() private actionError = '';
  @state() private isRemoving = false;
  @state() private testTo = '';
  @state() private isTesting = false;
  @state() private testResult: { ok: boolean; text: string } | null = null;
  // The draft the result is about: edit the page afterwards and the result no longer describes it, so it is not shown.
  private testResultFor = '';
  private editorSession = 0;
  private draftKey = '';
  private draftBaseline = '';

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    toggleStyles,
    iconButtonStyles,
    recordStyles,
    css`
      :host {
        display: block;
      }

      .muted {
        color: var(--text-secondary);
      }

      input.mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
      }

      .mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        overflow-wrap: anywhere;
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

      .pair {
        display: grid;
        margin-bottom: var(--spacing-lg);
        grid-template-columns: minmax(0, 1fr) 8rem;
        gap: var(--spacing-md);
        align-items: start;
      }

      @media (max-width: 720px) {
        .pair {
          grid-template-columns: minmax(0, 1fr);
        }
      }

      .pair .form-group,
      .entry-row .form-group {
        margin: 0;
      }

      .entry-row {
        display: grid;
        grid-template-columns: minmax(0, 12rem) minmax(0, 1fr) auto;
        gap: var(--spacing-sm);
        align-items: center;
        margin-bottom: var(--spacing-sm);
      }

      @media (max-width: 720px) {
        .entry-row {
          grid-template-columns: minmax(0, 1fr) auto;
        }

        .entry-row .entry-name {
          grid-column: 1;
          grid-row: 1;
        }

        .entry-row .icon-button {
          grid-column: 2;
          grid-row: 1;
        }

        .entry-row .entry-value {
          grid-column: 1 / -1;
          grid-row: 2;
        }
      }

      .body-label {
        font-size: var(--text-xs);
        font-weight: 700;
        color: var(--text-secondary);
        text-transform: uppercase;
        letter-spacing: 0.08em;
      }

      .entries-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--spacing-md);
        margin-bottom: var(--spacing-sm);
      }

      .narrow {
        max-width: 8rem;
      }

      .check-row {
        min-height: 1.5rem;
        display: flex;
        gap: var(--spacing-sm);
        align-items: flex-start;
        margin: var(--spacing-sm) 0 0;
        font-size: var(--text-base);
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
        color: var(--text-primary);
        cursor: pointer;
      }

      .check-row input {
        width: 1rem;
        height: 1rem;
        margin: 0.2rem 0 0;
        flex: none;
        accent-color: var(--accent);
      }

      .check-row input:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      .editor-box .banner {
        margin: var(--spacing-md) 0 0;
      }

      .body-entries {
        display: grid;
        gap: 0.25rem;
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    void this.loadData();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('route') || changed.has('record') || changed.has('canManage')) {
      const group = this.editingGroup;
      const draftKey = group ? `${group}:${this.record !== null}` : '';
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        this.editorSession++;
        // A password typed in an editor that was left (cancelled, discarded, or switched away from by the URL) goes with it.
        this.smtpDraft = { ...this.smtpDraft, password: '', clearPassword: false };
        this.otpDraft = { ...this.otpDraft, password: '', clearPassword: false };
        this.actionError = '';
        this.testTo = '';
        this.testResult = null;
        this.isTesting = false;
        if (group) this.resetDraft(group);
      }
    }
  }

  private async loadData() {
    this.isLoading = true;
    this.errorMessage = '';
    try {
      this.record = await fetchDeliverySettings();
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load the delivery settings';
    } finally {
      this.isLoading = false;
    }
  }

  private get editingGroup(): Group | null {
    if (!this.canManage) return null;
    return this.route.edit === 'smtp' || this.route.edit === 'otp-provider' ? this.route.edit : null;
  }

  private get draftSnapshot(): string {
    return JSON.stringify(this.editingGroup === 'smtp' ? this.smtpDraft : this.otpDraft);
  }

  private resetDraft(group: Group) {
    if (group === 'smtp') this.smtpDraft = smtpDraftFrom(this.record?.smtp);
    else this.otpDraft = otpProviderDraftFrom(this.record?.otpProvider);
    this.fieldErrors = {};
    this.formError = '';
    this.draftBaseline = this.draftSnapshot;
  }

  private async cancelEditor() {
    if (this.isSaving || this.isTesting) return;
    if (this.draftSnapshot !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null });
  }

  /** The mistakes the draft has, with what is already saved to compare the password rule against. */
  private draftErrors(group: Group): FieldErrors {
    return group === 'smtp'
      ? validateSmtpDraft(this.smtpDraft, this.record?.smtp ? { settings: this.record.smtp, hasPassword: this.record.hasSmtpPassword } : undefined)
      : validateOtpProviderDraft(this.otpDraft, this.record?.otpProvider ? { settings: this.record.otpProvider, hasPassword: this.record.hasOtpProviderPassword } : undefined);
  }

  /** Sends one message through what is on the page, saved or not. The password typed goes with it; left empty, central uses
   * the saved one for the server it was saved for and refuses otherwise, which the form has already said beside the field. */
  private async sendTest() {
    const group = this.editingGroup;
    if (!group || this.isTesting || this.isSaving) return;
    const channel = group === 'smtp' ? 'email' : 'sms';
    const errors = this.draftErrors(group);
    const recipientError = validateTestRecipient(channel, this.testTo);
    this.fieldErrors = recipientError ? { ...errors, testTo: recipientError } : errors;
    this.testResult = null;
    if (Object.keys(this.fieldErrors).length) {
      await this.updateComplete;
      this.renderRoot.querySelector<HTMLElement>('[aria-invalid="true"], .input-error')?.focus();
      return;
    }
    const session = this.editorSession;
    const snapshot = this.draftSnapshot;
    const to = this.testTo.trim();
    const body: DeliveryTestRequest = group === 'smtp'
      ? { channel, to, smtp: smtpFromDraft(this.smtpDraft), password: this.smtpDraft.password || undefined }
      : { channel, to, otpProvider: otpProviderFromDraft(this.otpDraft), password: this.otpDraft.password || undefined };
    this.isTesting = true;
    try {
      const result = await sendDeliveryTest(body);
      if (this.editorSession !== session) return;
      this.testResultFor = snapshot;
      this.testResult = result.delivered
        ? { ok: true, text: channel === 'email' ? `Sent to ${to}. Check that inbox, the spam folder included.` : `Sent to ${to}. Check that phone.` }
        : { ok: false, text: result.message ?? 'The message could not be sent.' };
    } catch (error) {
      if (this.editorSession !== session) return;
      const status = (error as ApiError | undefined)?.status;
      this.testResultFor = snapshot;
      this.testResult = { ok: false, text: status === 429 ? 'Too many test messages. Try again in a minute.' : error instanceof Error ? error.message : 'The test could not be made.' };
    } finally {
      if (this.editorSession === session) this.isTesting = false;
    }
  }

  /** A save replaces both blocks, so the block that is not being edited is sent back as it is stored, its password
   * left out (kept). */
  private async handleSubmit(event: Event) {
    event.preventDefault();
    const group = this.editingGroup;
    if (this.isSaving || this.isTesting || !group) return;
    const errors = this.draftErrors(group);
    this.fieldErrors = errors;
    if (Object.keys(errors).length) {
      await this.updateComplete;
      this.renderRoot.querySelector<HTMLElement>('[aria-invalid="true"], .input-error')?.focus();
      return;
    }
    // What was typed is taken now: the route can change during the awaits below (Back, another editor), and a draft
    // read afterwards would be a different one.
    const smtpDraft = this.smtpDraft;
    const otpDraft = this.otpDraft;
    const session = this.editorSession;
    this.isSaving = true;
    this.formError = '';
    try {
      // The other block is read again right before the save, past this page's 60 s read cache: a save replaces both
      // blocks, so a copy another admin or tab has changed since would put the old block back. This narrows that window
      // to the moment between this read and the PUT (the contract has no version to check); central's own cache is
      // refreshed by its change notification a moment after a write.
      const current = await fetchDeliverySettings({ fresh: true });
      const update: DeliverySettingsUpdate = group === 'smtp'
        ? { smtp: smtpFromDraft(smtpDraft), smtpPassword: passwordChange(smtpDraft), otpProvider: current.otpProvider }
        : { smtp: current.smtp, otpProvider: otpProviderFromDraft(otpDraft), otpProviderPassword: passwordChange(otpDraft) };
      await upsertDeliverySettings(update);
    } catch (error) {
      // A failure that arrives after the operator has left this editor is not shown in the next one they open.
      if (this.editorSession === session) this.formError = error instanceof Error ? error.message : 'Failed to save the delivery settings';
      this.isSaving = false;
      return;
    }
    // The save is done: a password typed for it leaves memory, and a reload that fails is a load error, not a save error.
    if (this.editorSession === session) {
      if (group === 'smtp') this.smtpDraft = { ...this.smtpDraft, password: '', clearPassword: false };
      else this.otpDraft = { ...this.otpDraft, password: '', clearPassword: false };
    }
    await this.reload();
    this.isSaving = false;
    // Only the editor that was saved is closed: one the operator has moved to, or opened again, meanwhile keeps its draft.
    if (this.editorSession !== session || this.editingGroup !== group) return;
    this.draftBaseline = this.draftSnapshot;
    this.draftKey = '';
    dispatchRecordRoute(this, { edit: null });
  }

  /** Reads the record again after a write; a failure shows the load error card with Retry. */
  private async reload() {
    try {
      this.record = await fetchDeliverySettings();
      this.errorMessage = '';
    } catch (error) {
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load the delivery settings';
    }
  }

  private async removeSettings() {
    if (this.isRemoving) return;
    this.isRemoving = true;
    try {
      await this.confirmAndRemove();
    } finally {
      this.isRemoving = false;
    }
  }

  private async confirmAndRemove() {
    const confirmed = await confirmDestructiveAction({
      title: 'Remove delivery settings',
      messagePrefix: 'Remove the saved ',
      messageSubject: 'delivery settings',
      messageSuffix: `? ${REMOVE_EFFECT}`,
      confirmLabel: 'Remove settings',
    });
    if (!confirmed) return;
    this.actionError = '';
    try {
      await deleteDeliverySettings();
    } catch (error) {
      this.actionError = error instanceof Error ? error.message : 'Failed to remove the delivery settings';
      return;
    }
    await this.reload();
  }

  // ── read view ──

  private row(label: string, value: unknown) {
    return html`<versola-record-row .label=${label} .value=${value}></versola-record-row>`;
  }

  private statusChip(enabled: boolean) {
    return enabled ? html`<span class="chip ok">Enabled</span>` : html`<span class="chip">Disabled</span>`;
  }

  private passwordChip(isSet: boolean) {
    return isSet ? html`<span class="chip ok">Saved</span>` : html`<span class="chip">Not set</span>`;
  }

  private renderSmtpCard(record: DeliverySettingsRecord) {
    const smtp = record.smtp;
    return html`
      <versola-record-card id="smtp" heading="Email" subheading="The SMTP server one-time codes and temporary passwords are sent from."
        ?editable=${this.canManage} .editLabel=${smtp ? 'Edit email' : 'Configure email'} .body=${smtp ? html`
          ${this.row('Status', this.statusChip(smtp.enabled))}
          ${this.row('Server', html`<span class="mono">${smtp.host}:${smtp.port}</span>`)}
          ${this.row('Connection', smtp.startTls ? 'STARTTLS' : 'Plain, no encryption')}
          ${this.row('Username', html`<span class="mono">${smtp.username}</span>`)}
          ${this.row('Password', this.passwordChip(record.hasSmtpPassword))}
          ${this.row('From', html`<span class="mono">${smtp.from}</span>`)}
          ${this.row('Subject', smtp.subject)}
        ` : html`${this.row('Status', html`<span class="muted">${NOT_CONFIGURED}</span>`)}`}
        @edit=${() => dispatchRecordRoute(this, { id: null, edit: 'smtp', create: false, layout: null })}></versola-record-card>
    `;
  }

  private renderOtpProviderCard(record: DeliverySettingsRecord) {
    const provider = record.otpProvider;
    const entries = provider ? Object.entries(provider.body) : [];
    return html`
      <versola-record-card id="otp-provider" heading="SMS" subheading="The HTTP service that delivers text messages."
        ?editable=${this.canManage} .editLabel=${provider ? 'Edit SMS' : 'Configure SMS'} .body=${provider ? html`
          ${this.row('Status', this.statusChip(provider.enabled))}
          ${this.row('Request', html`<span class="mono">${provider.method} ${provider.url}</span>`)}
          ${this.row('Username', provider.username ? html`<span class="mono">${provider.username}</span>` : html`<span class="muted">—</span>`)}
          ${this.row('Password', this.passwordChip(record.hasOtpProviderPassword))}
          ${this.row('Body', entries.length
            ? html`<div class="body-entries">${entries.map(([key, value]) => html`<span class="mono">${key} = ${value}</span>`)}</div>`
            : html`<span class="muted">Empty</span>`)}
        ` : html`${this.row('Status', html`<span class="muted">${NOT_CONFIGURED}</span>`)}`}
        @edit=${() => dispatchRecordRoute(this, { id: null, edit: 'otp-provider', create: false, layout: null })}></versola-record-card>
    `;
  }

  // ── editors ──

  private field(id: string, label: string, control: unknown, hint: string) {
    const error = this.fieldErrors[id];
    return html`
      <div class="form-group">
        <label for=${id}>${label}</label>
        ${control}
        ${error ? html`<div class="error-message" id=${`${id}-error`} role="alert">${error}</div>` : html`<div class="hint" id=${`${id}-hint`}>${hint}</div>`}
      </div>
    `;
  }

  private textInput(id: string, value: string, onInput: (value: string) => void, options: { mono?: boolean; placeholder?: string; type?: string; autocomplete?: string } = {}) {
    const invalid = !!this.fieldErrors[id];
    return html`
      <input type=${options.type ?? 'text'} id=${id} class="compact-input ${options.mono ? 'mono' : ''} ${invalid ? 'input-error' : ''}" .value=${value}
        placeholder=${options.placeholder ?? ''} autocomplete=${options.autocomplete ?? 'off'}
        aria-invalid=${invalid ? 'true' : nothing} aria-describedby=${invalid ? `${id}-error` : `${id}-hint`}
        @input=${(e: Event) => onInput((e.target as HTMLInputElement).value)} />
    `;
  }

  private switchRow(id: string, label: string, hint: string, checked: boolean, onChange: (value: boolean) => void) {
    return html`
      <div class="form-group">
        <label class="toggle-row" for=${id}>
          <span class="toggle">
            <input type="checkbox" role="switch" id=${id} .checked=${checked} aria-describedby=${`${id}-hint`}
              @change=${(e: Event) => onChange((e.target as HTMLInputElement).checked)} />
          </span>
          <span>${label}</span>
        </label>
        <div class="hint" id=${`${id}-hint`}>${hint}</div>
      </div>
    `;
  }

  /** The write-only password: empty keeps the saved one, typing replaces it, the box removes it. */
  private passwordField(id: string, draft: { password: string; clearPassword: boolean }, isSet: boolean, onChange: (password: string, clear: boolean) => void) {
    return html`
      ${this.field(id, 'Password', this.textInput(id, draft.password, value => onChange(value, draft.clearPassword && value === ''), { type: 'password', autocomplete: 'new-password' }),
        isSet ? 'A password is saved. Leave this empty to keep it.' : 'Optional. Stored encrypted and never shown again.')}
      ${isSet ? html`
        <label class="check-row">
          <input type="checkbox" .checked=${draft.clearPassword} ?disabled=${draft.password !== ''}
            aria-describedby=${draft.password !== '' ? `${id}-clear-hint` : nothing}
            @change=${(e: Event) => onChange(draft.password, (e.target as HTMLInputElement).checked)} />
          <span>Remove the saved password</span>
        </label>
        ${draft.password !== '' ? html`<div class="hint" id=${`${id}-clear-hint`}>The password typed above replaces the saved one. Clear that field to remove the saved password instead.</div>` : ''}` : ''}
    `;
  }

  private editorHeader(group: string, heading: string, meta: string) {
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Delivery', onSelect: () => void this.cancelEditor() }, { label: group }]}
        .heading=${heading}
        .meta=${html`<span class="muted">${meta}</span>`}
      ></versola-record-header>
    `;
  }

  /** The test of what is on the page. It has its own recipient field, so Enter there sends the test and never saves. */
  private renderTestBox(group: Group) {
    const channel = group === 'smtp' ? 'email' : 'sms';
    const draft = group === 'smtp' ? this.smtpDraft : this.otpDraft;
    const invalid = !!this.fieldErrors.testTo;
    const removing = draft.clearPassword;
    const result = this.testResult && this.testResultFor === this.draftSnapshot ? this.testResult : null;
    return html`
      <div class="editor-box">
        <h3>Send a test message</h3>
        <p class="editor-sub">Tries what is on this page, saved or not, so a wrong server or address shows up before it is saved. The saved password is used unless you typed one above.</p>
        <div class="form-group">
          <label for="testTo">Send to *</label>
          <input type="text" id="testTo" class="compact-input mono ${invalid ? 'input-error' : ''}" .value=${this.testTo} autocomplete="off"
            placeholder=${channel === 'email' ? 'you@example.com' : '+12025551234'}
            aria-invalid=${invalid ? 'true' : nothing} aria-describedby=${invalid ? 'testTo-error' : 'testTo-hint'}
            @input=${(e: Event) => {
              this.testTo = (e.target as HTMLInputElement).value;
              if (this.fieldErrors.testTo) this.fieldErrors = Object.fromEntries(Object.entries(this.fieldErrors).filter(([field]) => field !== 'testTo'));
            }}
            @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); void this.sendTest(); } }} />
          ${invalid
            ? html`<div class="error-message" id="testTo-error" role="alert">${this.fieldErrors.testTo}</div>`
            : html`<div class="hint" id="testTo-hint">${channel === 'email' ? 'An email address you can read.' : 'A phone number with its country code. The message costs what the provider charges.'}</div>`}
        </div>
        <button type="button" class="btn btn-secondary" ?disabled=${this.isTesting || this.isSaving || removing} aria-busy=${this.isTesting ? 'true' : 'false'}
          aria-describedby=${removing ? 'test-removing-hint' : nothing} @click=${() => void this.sendTest()}>${this.isTesting ? 'Sending…' : 'Send test message'}</button>
        ${removing ? html`<div class="hint" id="test-removing-hint">Save first: the test would use the saved password that is being removed.</div>` : ''}
        <div role="status">
          ${result ? html`
            <div class="banner ${result.ok ? 'ok' : 'warn'}">
              <div class="banner-text">
                <div class="banner-title">${statusIcon(result.ok ? 'check' : 'alert')}${result.ok ? 'The test message was sent' : 'The test message was not sent'}</div>
                ${result.text}
              </div>
            </div>` : ''}
        </div>
      </div>
    `;
  }

  private formActions(label: string) {
    return html`
      <div class="form-actions">
        <button type="button" class="btn btn-secondary" ?disabled=${this.isSaving || this.isTesting} @click=${() => void this.cancelEditor()}>Cancel</button>
        <button type="submit" class="btn btn-primary" ?disabled=${this.isSaving || this.isTesting}>${this.isSaving ? 'Saving…' : label}</button>
      </div>
    `;
  }

  private renderSmtpEditor() {
    const draft = this.smtpDraft;
    const set = (patch: Partial<SmtpDraft>) => { this.smtpDraft = { ...this.smtpDraft, ...patch }; };
    return html`
      ${this.editorHeader('Email', this.record?.smtp ? 'Edit email delivery' : 'Configure email delivery', 'Applies to every tenant once auth picks the change up.')}
      <form class="card editor-card" novalidate @submit=${(e: Event) => this.handleSubmit(e)}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        <div class="editor-box">
          <h3>SMTP server</h3>
          <p class="editor-sub">Where the messages are handed over.</p>
          <div class="pair">
            ${this.field('host', 'Server address *', this.textInput('host', draft.host, value => set({ host: value }), { mono: true, placeholder: 'smtp.example.com' }), 'The host name of the SMTP server.')}
            ${this.field('port', 'Port *', this.textInput('port', draft.port, value => set({ port: value }), { mono: true }), 'Usually 587.')}
          </div>
          ${this.switchRow('startTls', 'Encrypt with STARTTLS', 'Turn off only for a trusted internal relay: the password then travels unencrypted.', draft.startTls, value => set({ startTls: value }))}
          ${this.field('username', 'Username *', this.textInput('username', draft.username, value => set({ username: value }), { mono: true }), 'The account the server signs in.')}
          ${this.passwordField('password', draft, this.record?.hasSmtpPassword ?? false, (password, clearPassword) => set({ password, clearPassword }))}
        </div>
        <div class="editor-box">
          <h3>Message</h3>
          <p class="editor-sub">What the recipient sees.</p>
          ${this.field('from', 'From *', this.textInput('from', draft.from, value => set({ from: value }), { mono: true, placeholder: 'noreply@example.com' }), 'The sender address.')}
          ${this.field('subject', 'Subject *', this.textInput('subject', draft.subject, value => set({ subject: value })), 'One line, the same for every message.')}
        </div>
        <div class="editor-box">
          <h3>Status</h3>
          ${this.switchRow('enabled', 'Enabled', 'Off keeps the settings saved but sends nothing through this server.', draft.enabled, value => set({ enabled: value }))}
        </div>
        ${this.renderTestBox('smtp')}
        ${this.formActions('Save email settings')}
      </form>
    `;
  }

  private setEntry(index: number, patch: Partial<{ key: string; value: string }>) {
    this.otpDraft = { ...this.otpDraft, body: this.otpDraft.body.map((entry, i) => i === index ? { ...entry, ...patch } : entry) };
  }

  /** A body entry is at fault when its name is empty or another entry has the same one (names are compared trimmed). */
  private bodyEntryInvalid(index: number): boolean {
    if (!this.fieldErrors.body) return false;
    const key = this.otpDraft.body[index].key.trim();
    return !key || this.otpDraft.body.filter(entry => entry.key.trim() === key).length > 1;
  }

  private async addEntry() {
    this.otpDraft = { ...this.otpDraft, body: [...this.otpDraft.body, { key: '', value: '' }] };
    await this.updateComplete;
    const names = this.renderRoot.querySelectorAll<HTMLElement>('.entry-row input.entry-name');
    names[names.length - 1]?.focus();
  }

  private async removeEntry(index: number) {
    this.otpDraft = { ...this.otpDraft, body: this.otpDraft.body.filter((_, i) => i !== index) };
    await this.updateComplete;
    // The button that had focus is gone: focus goes to the entry that took its place, or to Add entry when none is left.
    const names = this.renderRoot.querySelectorAll<HTMLElement>('.entry-row input.entry-name');
    (names[Math.min(index, names.length - 1)] ?? this.renderRoot.querySelector<HTMLElement>('#add-entry'))?.focus();
  }

  private renderBodyEntries() {
    const draft = this.otpDraft;
    return html`
      <div class="form-group" role="group" aria-labelledby="body-label" aria-describedby="body-hint">
        <div class="entries-head">
          <span class="body-label" id="body-label">Body</span>
          <button type="button" id="add-entry" class="btn btn-secondary btn-sm" @click=${() => void this.addEntry()}>+ Add entry</button>
        </div>
        ${draft.body.map((entry, index) => {
          const invalid = this.bodyEntryInvalid(index);
          return html`
          <div class="entry-row">
            <input type="text" class="compact-input mono entry-name ${invalid ? 'input-error' : ''}" aria-label=${`Body entry ${index + 1} name`} .value=${entry.key} placeholder="name"
              aria-invalid=${invalid ? 'true' : nothing} aria-describedby=${invalid ? 'body-error' : nothing}
              @input=${(e: Event) => this.setEntry(index, { key: (e.target as HTMLInputElement).value })} />
            <input type="text" class="compact-input mono entry-value" aria-label=${`Body entry ${index + 1} value`} .value=${entry.value} placeholder="value"
              @input=${(e: Event) => this.setEntry(index, { value: (e.target as HTMLInputElement).value })} />
            <button type="button" class="icon-button" aria-label=${`Remove body entry ${index + 1}`} title="Remove entry"
              @click=${() => void this.removeEntry(index)}>${statusIcon('x')}</button>
          </div>`;
        })}
        ${this.fieldErrors.body
          ? html`<div class="error-message" id="body-error" role="alert">${this.fieldErrors.body}</div>`
          : nothing}
        <div class="hint" id="body-hint">Sent as a JSON object. <span class="mono">{{phone}}</span> and <span class="mono">{{message}}</span> are replaced for each message. These values are stored and shown as typed, so keep secrets out of them. The password below is sent as HTTP Basic, together with the username.</div>
      </div>
    `;
  }

  private renderOtpProviderEditor() {
    const draft = this.otpDraft;
    const set = (patch: Partial<OtpProviderDraft>) => { this.otpDraft = { ...this.otpDraft, ...patch }; };
    const methodInvalid = !!this.fieldErrors.method;
    return html`
      ${this.editorHeader('SMS', this.record?.otpProvider ? 'Edit SMS delivery' : 'Configure SMS delivery', 'Applies to every tenant once auth picks the change up.')}
      <form class="card editor-card" novalidate @submit=${(e: Event) => this.handleSubmit(e)}>
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        <div class="editor-box">
          <h3>Provider request</h3>
          <p class="editor-sub">One HTTP request is sent for each text message.</p>
          ${this.field('method', 'Method *', html`
            <select id="method" class="compact-input narrow ${methodInvalid ? 'input-error' : ''}" .value=${draft.method}
              aria-invalid=${methodInvalid ? 'true' : nothing} aria-describedby=${methodInvalid ? 'method-error' : 'method-hint'}
              @change=${(e: Event) => set({ method: (e.target as HTMLSelectElement).value })}>
              ${OTP_METHODS.map(method => html`<option value=${method} ?selected=${draft.method === method}>${method}</option>`)}
            </select>`, 'The HTTP method of the request.')}
          ${this.field('url', 'Address *', this.textInput('url', draft.url, value => set({ url: value }), { mono: true, placeholder: 'https://sms.example.com/send' }), 'An absolute http:// or https:// address, without a username or password.')}
          ${this.renderBodyEntries()}
        </div>
        <div class="editor-box">
          <h3>Credentials</h3>
          <p class="editor-sub">Sent as HTTP Basic when both a username and a password are saved.</p>
          ${this.field('username', 'Username', this.textInput('username', draft.username, value => set({ username: value }), { mono: true }), 'Optional.')}
          ${this.passwordField('password', draft, this.record?.hasOtpProviderPassword ?? false, (password, clearPassword) => set({ password, clearPassword }))}
        </div>
        <div class="editor-box">
          <h3>Status</h3>
          ${this.switchRow('enabled', 'Enabled', 'Off keeps the settings saved but sends no text messages through this provider.', draft.enabled, value => set({ enabled: value }))}
        </div>
        ${this.renderTestBox('otp-provider')}
        ${this.formActions('Save SMS settings')}
      </form>
    `;
  }

  // ── render ──

  private renderBody(record: DeliverySettingsRecord) {
    const stored = !!record.smtp || !!record.otpProvider;
    return html`
      ${stored ? '' : html`
        <div class="banner info">
          <div class="banner-text">
            <div class="banner-title">${statusIcon('info')}Nothing is saved here yet</div>
            Until a server is saved, delivery keeps using the email and SMS settings in auth's own configuration file, if it has any.
          </div>
        </div>`}
      ${this.actionError ? html`<div class="editor-error" role="alert">${this.actionError}</div>` : ''}
      ${this.renderSmtpCard(record)}
      ${this.renderOtpProviderCard(record)}
      ${stored && this.canManage ? html`
        <versola-danger-card heading="Remove delivery settings"
          .text=${REMOVE_EFFECT}
          .actionLabel=${this.isRemoving ? 'Removing…' : 'Remove settings'} @action=${() => void this.removeSettings()}></versola-danger-card>` : ''}
    `;
  }

  render() {
    const group = this.editingGroup;
    if (group && this.record) return group === 'smtp' ? this.renderSmtpEditor() : this.renderOtpProviderEditor();
    return html`
      <versola-record-header .heading=${'Delivery'} .meta=${html`<span class="chip">Global</span><span class="muted">How one-time codes and temporary passwords reach users: an email server and an SMS provider.</span>`}></versola-record-header>
      ${this.isLoading ? html`<versola-loading-cards .count=${2}></versola-loading-cards>`
      : this.errorMessage ? html`<versola-error-card heading="Could not load the delivery settings" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>`
      : this.record ? this.renderBody(this.record) : ''}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-delivery-settings': VersolaDeliverySettings;
  }
}
