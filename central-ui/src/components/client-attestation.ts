import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { buttonStyles, cardStyles, chipStyles, formStyles, recordStyles, segmentedStyles } from '../styles/components';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import {
  MODES, MODE_LABEL, MODE_TEXT, MAX_APPS, describePolicy, draftFromPolicy, hasErrors, policyFromDraft, validateDraft,
  type AttestationDraft, type AttestationErrors, type ClientAttestationPolicy, type IosEnvironment,
} from '../utils/client-attestation';
import { deleteClientAttestation, fetchClientAttestation, saveClientAttestation } from '../utils/central-api';
import './record-card';

/**
 * The app attestation policy of a native client that edge fronts (#423): a record card that says whether edge asks the app to
 * prove it is the genuine build on a genuine device when a sign-in starts, and an editor for it in the same card. The policy
 * has its own endpoint in central (`/configuration/clients/attestation`), so this card loads and saves it itself rather than
 * through the client's form.
 *
 * Off is what a client has until a policy is set. The editor opens on Report, the mode that refuses nothing, and the text under
 * the chosen mode says what it does. Central's own refusals are shown as it words them; what is checked here is what it would
 * refuse, said before it is asked.
 */
@customElement('versola-client-attestation')
export class VersolaClientAttestation extends LitElement {
  @property() clientId = '';
  @property({ type: Boolean }) canManage = false;

  /** undefined while loading; otherwise what central holds (`off` over no builds for a client with no policy). */
  @state() private policy: ClientAttestationPolicy | undefined;
  @state() private loadError = '';
  /** The draft while the editor is open. */
  @state() private draft: AttestationDraft | null = null;
  @state() private submitted = false;
  @state() private saving = false;
  @state() private saveError = '';

  static styles = [
    theme, buttonStyles, cardStyles, chipStyles, formStyles, recordStyles, segmentedStyles,
    css`
      :host { display: block; }
      .editor { display: grid; gap: var(--spacing-md); padding: var(--spacing-md) var(--spacing-lg) var(--spacing-lg); border-top: 1px solid var(--border-default); }
      .editor .form-group { margin: 0; }
      .editor textarea, .editor input[type='text'], .editor select { width: 100%; max-width: none; box-sizing: border-box; }
      .editor textarea { min-height: 4.5rem; font-family: var(--font-mono, monospace); font-size: var(--text-sm); }
      .text-line { margin: 0; font-size: var(--text-sm); color: var(--text-secondary); }
      .android-row { display: grid; gap: 0.5rem; padding: var(--spacing-sm) 0; border-top: 1px solid var(--border-default); }
      .android-row:first-of-type { border-top: 0; }
      .check-row { display: flex; align-items: center; gap: 0.4rem; font-size: var(--text-sm); color: var(--text-primary); cursor: pointer; }
      .check-row input[type='checkbox'] { cursor: pointer; }
      .actions { display: flex; flex-wrap: wrap; gap: var(--spacing-sm); align-items: center; }
      .actions .spacer { flex: 1; }
      .lines { margin: 0; padding: 0; list-style: none; display: grid; gap: 0.25rem; font-family: var(--font-mono, monospace); font-size: var(--text-sm); }
      fieldset { border: 0; margin: 0; padding: 0; min-width: 0; display: grid; gap: var(--spacing-md); }
    `,
  ];

  protected willUpdate(changed: Map<string, unknown>) {
    if (changed.has('clientId') && this.clientId) void this.load();
  }

  private async load() {
    this.policy = undefined;
    this.loadError = '';
    this.draft = null;
    try {
      this.policy = await fetchClientAttestation(this.clientId);
    } catch (error) {
      this.loadError = error instanceof Error ? error.message : 'Could not load the attestation policy.';
    }
  }

  private get hasPolicy(): boolean {
    const policy = this.policy;
    return !!policy && (policy.mode !== 'off' || !!policy.ios || policy.android.length > 0);
  }

  private edit() {
    this.draft = draftFromPolicy(this.hasPolicy ? this.policy! : null);
    this.submitted = false;
    this.saveError = '';
  }

  private cancel() {
    this.draft = null;
    this.saveError = '';
  }

  private patch(patch: Partial<AttestationDraft>) {
    if (this.draft) this.draft = { ...this.draft, ...patch };
  }

  private patchAndroid(index: number, patch: Partial<AttestationDraft['android'][number]>) {
    if (this.draft) this.patch({ android: this.draft.android.map((row, i) => i === index ? { ...row, ...patch } : row) });
  }

  private async save(event: Event) {
    event.preventDefault();
    if (!this.draft || this.saving) return;
    this.submitted = true;
    if (hasErrors(validateDraft(this.draft))) {
      void this.updateComplete.then(() => this.renderRoot.querySelector<HTMLElement>('[aria-invalid="true"], .builds-error')?.focus());
      return;
    }
    this.saving = true;
    this.saveError = '';
    try {
      this.policy = await saveClientAttestation(this.clientId, policyFromDraft(this.draft));
      this.draft = null;
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'The policy was not saved.';
    } finally {
      this.saving = false;
    }
  }

  private async removePolicy() {
    const confirmed = await confirmDestructiveAction({
      title: 'Remove attestation policy',
      messagePrefix: 'Edge stops asking the app of ',
      messageSubject: this.clientId,
      messageSuffix: ' to prove what it is, and the builds listed are forgotten. Remove the policy?',
      confirmLabel: 'Remove policy',
    });
    if (!confirmed) return;
    this.saving = true;
    this.saveError = '';
    try {
      await deleteClientAttestation(this.clientId);
      this.policy = { mode: 'off', android: [] };
      this.draft = null;
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'The policy was not removed.';
    } finally {
      this.saving = false;
    }
  }

  render() {
    return html`
      <versola-record-card heading="App attestation" subheading="Whether edge asks the app to prove it is the genuine build"
        ?editable=${this.canManage && !!this.policy && !this.draft}
        .editLabel=${this.hasPolicy ? 'Edit' : 'Set a policy'}
        .body=${this.draft ? this.renderEditor(this.draft) : this.renderView()}
        @edit=${() => this.edit()}></versola-record-card>
    `;
  }

  private renderView() {
    if (this.loadError) {
      return html`<div class="banner" role="alert">${this.loadError} <button type="button" class="btn btn-secondary btn-sm" @click=${() => void this.load()}>Retry</button></div>`;
    }
    const policy = this.policy;
    if (!policy) return html`<versola-record-row .value=${html`<span class="text-line" role="status">Loading</span>`}></versola-record-row>`;
    if (!this.hasPolicy) {
      return html`
        <versola-record-row .label=${'Mode'} .value=${html`<span class="chip">Off</span> No policy: edge does not ask the app for anything.`}></versola-record-row>`;
    }
    const summary = describePolicy(policy);
    return html`
      <versola-record-row .label=${'Mode'} .value=${html`<span class="chip ${policy.mode === 'enforce' ? 'warn' : ''}">${MODE_LABEL[policy.mode]}</span> ${MODE_TEXT[policy.mode]}`}></versola-record-row>
      <versola-record-row .label=${'iOS'} .value=${summary.ios}></versola-record-row>
      <versola-record-row .label=${'Android'} .value=${summary.android.length > 0
        ? html`<ul class="lines">${summary.android.map(line => html`<li>${line}</li>`)}</ul>` : 'None accepted'}></versola-record-row>
      <versola-record-row .value=${html`<span class="text-line">Edge picks a change up with its next client sync.</span>`}></versola-record-row>`;
  }

  private renderEditor(draft: AttestationDraft) {
    const errors: AttestationErrors = this.submitted ? validateDraft(draft) : { android: [] };
    return html`
      <form class="editor" novalidate @submit=${(e: Event) => void this.save(e)}>
        <fieldset ?disabled=${this.saving}>
          <div class="form-group">
            <span class="ctrl-label" id="attestation-mode-label">Mode</span>
            <div class="seg-control" role="group" aria-labelledby="attestation-mode-label">
              ${MODES.map(mode => html`
                <button type="button" class="seg-btn ${draft.mode === mode ? 'seg-active' : ''}" aria-pressed=${draft.mode === mode ? 'true' : 'false'}
                  @click=${() => this.patch({ mode })}>${MODE_LABEL[mode]}</button>`)}
            </div>
            <p class="text-line">${MODE_TEXT[draft.mode]}</p>
          </div>

          <div class="form-group">
            <label for="attestation-ios-ids">iOS App IDs</label>
            <textarea id="attestation-ios-ids" class=${errors.ios ? 'input-error' : ''} spellcheck="false" autocomplete="off" placeholder="ABCDE12345.com.example.app"
              aria-invalid=${errors.ios ? 'true' : nothing} aria-describedby="attestation-ios-hint" .value=${draft.iosAppIds}
              @input=${(e: Event) => this.patch({ iosAppIds: (e.target as HTMLTextAreaElement).value })}></textarea>
            ${errors.ios ? html`<div class="error-message" role="alert">${errors.ios}</div>` : nothing}
            <div class="hint" id="attestation-ios-hint">One per line: your Team ID, a dot, the bundle id. Leave empty to accept no iOS build.</div>
          </div>

          <div class="form-group">
            <label for="attestation-ios-environment">App Attest environment</label>
            <select id="attestation-ios-environment" .value=${draft.iosEnvironment}
              @change=${(e: Event) => this.patch({ iosEnvironment: (e.target as HTMLSelectElement).value as IosEnvironment })}>
              <option value="production" ?selected=${draft.iosEnvironment === 'production'}>Production</option>
              <option value="development" ?selected=${draft.iosEnvironment === 'development'}>Development</option>
            </select>
            <div class="hint">Development keys come from builds signed for development and prove nothing about an App Store build.</div>
          </div>

          <div class="form-group">
            <span class="ctrl-label" id="attestation-android-label">Android apps</span>
            ${draft.android.map((row, index) => this.renderAndroid(row, index, errors.android[index]))}
            ${errors.builds ? html`<div class="error-message builds-error" role="alert" tabindex="-1">${errors.builds}</div>` : nothing}
            <div>
              <button type="button" class="btn btn-secondary btn-sm" ?disabled=${draft.android.length >= MAX_APPS}
                @click=${() => this.patch({ android: [...draft.android, { packageName: '', digests: '' }] })}>Add Android app</button>
            </div>
          </div>
        </fieldset>

        ${this.saveError ? html`<div class="banner" role="alert"><span class="banner-text">${this.saveError}</span></div>` : nothing}
        <div class="actions">
          <button type="submit" class="btn btn-primary" ?disabled=${this.saving}>${this.saving ? 'Saving' : 'Save attestation'}</button>
          <button type="button" class="btn btn-secondary" ?disabled=${this.saving} @click=${() => this.cancel()}>Cancel</button>
          <span class="spacer"></span>
          ${this.hasPolicy ? html`<button type="button" class="btn btn-danger-secondary" ?disabled=${this.saving} @click=${() => void this.removePolicy()}>Remove policy</button>` : nothing}
        </div>
      </form>`;
  }

  private renderAndroid(row: AttestationDraft['android'][number], index: number, error: string | undefined) {
    const nameId = `attestation-android-${index}-package`;
    const digestsId = `attestation-android-${index}-digests`;
    return html`
      <div class="android-row" role="group" aria-label=${`Android app ${index + 1}`}>
        <div class="form-group">
          <label for=${nameId}>Package name</label>
          <input type="text" id=${nameId} class=${error ? 'input-error' : ''} spellcheck="false" autocomplete="off" placeholder="com.example.app"
            aria-invalid=${error ? 'true' : nothing} .value=${row.packageName}
            @input=${(e: Event) => this.patchAndroid(index, { packageName: (e.target as HTMLInputElement).value })} />
        </div>
        <div class="form-group">
          <label for=${digestsId}>Signing certificate SHA-256</label>
          <textarea id=${digestsId} class=${error ? 'input-error' : ''} spellcheck="false" autocomplete="off" aria-invalid=${error ? 'true' : nothing}
            .value=${row.digests} @input=${(e: Event) => this.patchAndroid(index, { digests: (e.target as HTMLTextAreaElement).value })}></textarea>
          ${error ? html`<div class="error-message" role="alert">${error}</div>` : nothing}
          <div class="hint">One per line, 64 hexadecimal digits; colons are fine. List the old and the new one while a signing key rotates.</div>
        </div>
        <div class="form-group">
          <label class="check-row">
            <input type="checkbox" .checked=${!!row.playIntegrity}
              @change=${(e: Event) => this.patchAndroid(index, { playIntegrity: (e.target as HTMLInputElement).checked })} />
            Require a Play Integrity verdict
          </label>
          <div class="hint">The Play Store's word that this is the genuine app on a genuine device. Needs an edge set up for Play Integrity and an app configured with a Google Cloud project number; without them, requests for this build are refused.</div>
        </div>
        <div>
          <button type="button" class="btn btn-secondary btn-sm" aria-label=${`Remove Android app ${index + 1}`}
            @click=${() => this.patch({ android: this.draft!.android.filter((_, i) => i !== index) })}>Remove app</button>
        </div>
      </div>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-client-attestation': VersolaClientAttestation;
  }
}
