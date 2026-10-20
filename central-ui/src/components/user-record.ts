import { LitElement, html, svg, css, nothing, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { parsePhoneNumberFromString } from 'libphonenumber-js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, formStyles, recordStyles, tagStyles } from '../styles/components';
import type { OAuthClient, PasskeyInfo, Role, User, UserDevice, UserRefreshToken, UserSession } from '../types';
import { fetchAllClients, getRoles } from '../utils/central-api';
import { confirmDestructiveAction } from '../utils/confirm-dialog';
import { copyToClipboard, getLocalizedDescription } from '../utils/helpers';
import { dispatchRecordRoute } from '../utils/record-route';
import { buildDevices, deviceLabel, platformLabel, userDisplayName, userLabel, type Device } from '../utils/user-display';
import {
  DEFAULT_TEMPORARY_PASSWORD_TTL_HOURS,
  deletePasskey,
  deleteUser,
  endUserSession,
  fetchUserRefreshTokens,
  fetchUserRoles,
  fetchUserDevices,
  forgetUserAgent,
  invalidateUserSession,
  listPasskeys,
  patchUserClaims,
  renamePasskey,
  resetPassword,
  resetUserLimits,
  revokeUserRefreshTokens,
  searchUsers,
  updateUser,
  updateUserRoles,
} from '../utils/users-api';
import './claim-edit';
import './danger-card';
import './empty-state';
import './error-card';
import './loading-cards';
import './nav-toggle';
import './record-card';
import './record-header';
import './role-picker';
import { statusIcon } from './status-icon';

export type UserEditGroup = 'identifiers' | 'roles' | 'claims';

const EDIT_GROUPS: readonly UserEditGroup[] = ['identifiers', 'roles', 'claims'];
const EDIT_TITLES: Record<UserEditGroup, string> = { identifiers: 'Edit identifiers', roles: 'Edit roles', claims: 'Edit claims' };

/** One part of the record that loads on its own: a failure in it leaves the rest of the page usable. */
type Part<T> = { status: 'loading' | 'ready' | 'error'; data: T; error: string };

function loading<T>(data: T): Part<T> {
  return { status: 'loading', data, error: '' };
}

function ready<T>(data: T): Part<T> {
  return { status: 'ready', data, error: '' };
}

function failed<T>(data: T, error: unknown, fallback: string): Part<T> {
  return { status: 'error', data, error: error instanceof Error ? error.message : fallback };
}

function when(value: string | undefined | null, withTime = true): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const sameYear = date.getFullYear() === new Date().getFullYear();
  return date.toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    ...(sameYear && withTime ? {} : { year: 'numeric' }),
    ...(withTime ? { hour: '2-digit', minute: '2-digit' } : {}),
  });
}

/**
 * One user's page: who they are (identifiers, roles in the selected tenant, claims), where they
 * are signed in (devices, as auth stores them: user agent, at most one SSO session, the clients
 * entered during it), which apps hold offline access, their passkeys, the security actions, and
 * delete. Each group that can change has its own Edit that opens only that group as a page
 * (`?view=users&id=<id>&edit=<group>`). The page loads the user by ID, so it opens from a reload
 * and from a link as well as from a search result.
 */
@customElement('versola-user-record')
export class VersolaUserRecord extends LitElement {
  @property({ type: String }) userId = '';
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  /** Whether the generated password may be shown instead of delivered: non-prod only. */
  @property({ type: Boolean }) canRevealPassword = false;
  @property({ type: String }) edit: string | null = null;

  @state() private user: User | null = null;
  @state() private userState: 'loading' | 'ready' | 'error' | 'missing' = 'loading';
  @state() private userError = '';
  @state() private roles: Part<string[]> = loading([]);
  @state() private devices: Part<UserDevice[]> = loading([]);
  @state() private tokens: Part<UserRefreshToken[]> = loading([]);
  @state() private passkeys: Part<PasskeyInfo[]> = loading([]);
  @state() private clientNames = new Map<string, string>();
  @state() private availableRoles: Role[] = [];
  @state() private actionError = '';
  @state() private busy = new Set<string>();

  @state() private form = { email: '', phone: '', login: '' };
  @state() private phoneError = '';
  @state() private rolesDraft: string[] = [];
  @state() private saving = false;
  @state() private saveError = '';

  @state() private limitsDone = false;
  @state() private passwordDone = false;
  @state() private pendingReset = false;
  @state() private resetChannel: 'email' | 'sms' | 'show' | null = null;
  @state() private resetTtlHours = DEFAULT_TEMPORARY_PASSWORD_TTL_HOURS;
  @state() private revealed: { password: string; ttlHours: number } | null = null;
  @state() private copyFeedback = '';

  private loadSeq = 0;
  private editorFor: string | null = null;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
    recordStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
      }

      section.record-card {
        margin-bottom: var(--spacing-md);
      }

      .head-actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        justify-content: flex-end;
      }

      .btn-danger-outline {
        color: var(--danger-text);
        border-color: var(--danger);
      }

      .btn-danger-outline:not(:disabled):hover {
        background: var(--danger-subtle);
        border-color: var(--danger);
        color: var(--danger-text);
      }

      .mono {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
      }

      .muted {
        color: var(--text-secondary);
      }

      .part-note {
        padding: var(--spacing-md) var(--spacing-lg);
        color: var(--text-secondary);
        font-size: var(--text-base);
      }

      .part-note.error {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--spacing-sm) var(--spacing-md);
        color: var(--text-primary);
      }

      .row-actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        justify-content: flex-end;
      }

      .record-label {
        overflow-wrap: anywhere;
      }

      .status-line {
        color: var(--success-text);
        font-size: var(--text-sm);
      }

      .claim-key {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .claim-json {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
      }

      .row-id {
        font-family: var(--font-mono);
        font-size: var(--text-xs);
        color: var(--accent);
        overflow-wrap: normal;
        word-break: normal;
      }

      .name-id {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 0.125rem var(--spacing-sm);
      }

      .name-id.stacked {
        flex-direction: column;
        gap: 0.125rem;
      }

      .client-name {
        font-weight: 600;
        color: var(--text-primary);
      }

      /* Devices. The nested tables of every device share one column grid, so the dates of one
         device sit exactly under the dates of the next. */
      .device {
        padding: var(--spacing-md) var(--spacing-lg);
        border-bottom: 1px solid var(--border-subtle);
      }

      .device:last-child {
        border-bottom: 0;
      }

      .device {
        display: grid;
        grid-template-columns: 2.25rem minmax(0, 1fr) auto;
        grid-template-areas:
          'icon main actions'
          '. clients clients';
        gap: var(--spacing-md);
        align-items: start;
      }

      .device-icon {
        grid-area: icon;
      }

      .device-main {
        grid-area: main;
        min-width: 0;
      }

      .device-actions {
        grid-area: actions;
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        justify-content: flex-end;
      }

      /* Short label, value: one per line on a phone, in a row from 721px. */
      .device-facts {
        display: flex;
        flex-wrap: wrap;
        gap: 0.125rem var(--spacing-lg);
        margin: 0.25rem 0 0;
        font-size: var(--text-sm);
      }

      .device-facts div {
        display: flex;
        gap: 0 var(--spacing-sm);
      }

      .device-facts dt {
        color: var(--text-secondary);
      }

      .device-facts dd {
        margin: 0;
        color: var(--text-primary);
      }

      .passkey {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        gap: var(--spacing-sm) var(--spacing-md);
        align-items: start;
        padding: var(--spacing-md) var(--spacing-lg);
        border-bottom: 1px solid var(--border-subtle);
      }

      .passkey:last-child {
        border-bottom: 0;
      }

      .passkey-name {
        font-weight: 600;
        color: var(--text-primary);
        overflow-wrap: anywhere;
      }

      .passkey-chips {
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem var(--spacing-sm);
        align-items: center;
        margin-top: 0.375rem;
      }

      .passkey-when {
        margin-top: 0.375rem;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .passkey .row-actions {
        grid-column: 2;
        grid-row: 1 / span 3;
      }

      .device-icon {
        width: 2.25rem;
        height: 2.25rem;
        border-radius: var(--radius-md);
        border: 1px solid var(--border-subtle);
        background: var(--surface-inset);
        color: var(--text-secondary);
        display: flex;
        align-items: center;
        justify-content: center;
      }

      .device-icon svg {
        width: 1.125rem;
        height: 1.125rem;
      }

      .device-name {
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem var(--spacing-sm);
        align-items: center;
        font-weight: 600;
        color: var(--text-primary);
      }

      .device-sub {
        margin-top: 0.125rem;
        font-size: var(--text-sm);
        color: var(--text-secondary);
      }

      .device-actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        justify-content: flex-end;
      }

      .device-clients {
        --device-grid: minmax(0, 1fr) 10rem 11rem 11.5rem;
        grid-area: clients;
        border: 1px solid var(--border-subtle);
        border-radius: var(--radius-md);
        background: var(--surface-inset);
        overflow: hidden;
      }

      .dc {
        display: grid;
        grid-template-columns: var(--device-grid);
        gap: var(--spacing-md);
        align-items: center;
        padding: 0.5rem var(--spacing-md);
        border-top: 1px solid var(--border-subtle);
        font-size: var(--text-sm);
      }

      .dc:first-child {
        border-top: 0;
      }

      .dc.h,
      .app.h {
        font-size: var(--text-xs);
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: var(--text-secondary);
      }

      .dc-marker {
        display: flex;
        flex-wrap: wrap;
        gap: 0.25rem var(--spacing-sm);
        align-items: center;
      }

      .down {
        background: none;
        border: 0;
        padding: 0 0.375rem;
        min-width: 1.5rem;
        min-height: 1.5rem;
        font: inherit;
        font-weight: 600;
        color: var(--text-secondary);
        cursor: pointer;
        border-radius: var(--radius-sm);
      }

      .down:hover {
        color: var(--accent);
      }

      .down:focus-visible,
      .btn:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      /* Apps with offline access: one row per refresh token. */
      .app {
        display: grid;
        grid-template-columns: minmax(0, 1.3fr) minmax(0, 1.5fr) 7.5rem 7.5rem minmax(0, 1fr) 5.5rem;
        gap: var(--spacing-md);
        align-items: center;
        padding: 0.75rem var(--spacing-lg);
        border-top: 1px solid var(--border-subtle);
        font-size: var(--text-sm);
      }

      .app.h {
        padding-top: 0.625rem;
        padding-bottom: 0.625rem;
        background: var(--surface-inset);
        border-top: 0;
      }

      .app .tag-list {
        align-items: center;
      }

      .app .revoke {
        justify-self: end;
      }

      .app-device {
        color: var(--text-secondary);
      }

      /* Editors and dialogs. */
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

      .form-grid {
        display: grid;
        gap: var(--spacing-lg);
      }

      .form-actions {
        display: flex;
        gap: 1rem;
        justify-content: flex-end;
        margin-top: var(--spacing-xl);
        padding-top: var(--spacing-xl);
        border-top: 1px solid var(--border-default);
      }

      dialog.modal {
        width: min(100% - 2rem, 28rem);
        box-sizing: border-box;
        padding: var(--spacing-xl);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-lg);
        background: var(--surface);
        color: var(--text-primary);
        box-shadow: var(--shadow-card);
      }

      dialog.modal::backdrop {
        background: var(--backdrop);
      }

      dialog.modal h2 {
        margin: 0 0 var(--spacing-md);
        font-size: var(--text-lg);
      }

      dialog.modal .lead {
        margin: 0 0 var(--spacing-lg);
        font-size: var(--text-base);
        color: var(--text-secondary);
      }

      dialog.modal .modal-actions {
        display: flex;
        justify-content: flex-end;
        gap: var(--spacing-sm);
        margin-top: var(--spacing-lg);
      }

      dialog.modal fieldset {
        border: 0;
        padding: 0;
        margin: 0 0 var(--spacing-lg);
      }

      dialog.modal legend {
        padding: 0;
        margin-bottom: var(--spacing-sm);
        font-size: var(--text-base);
      }

      dialog.modal .radio {
        display: flex;
        gap: var(--spacing-sm);
        align-items: center;
        margin-bottom: var(--spacing-xs);
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
        font-size: var(--text-base);
        color: var(--text-primary);
      }

      .ttl-input {
        width: 8rem;
        max-width: 100%;
        box-sizing: border-box;
      }

      .password-value {
        margin: 0;
        padding: 0.875rem 1rem;
        background: var(--surface-inset);
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        font-family: var(--font-mono);
        font-size: var(--text-base);
        overflow-wrap: anywhere;
        white-space: pre-wrap;
      }

      .copy-feedback {
        font-size: var(--text-sm);
        color: var(--success-text);
      }

      @media (max-width: 960px) {
        .app {
          grid-template-columns: minmax(0, 1fr) auto;
        }

        .app.h {
          display: none;
        }

        .app > *:nth-child(n + 2):not(.revoke) {
          grid-column: 1 / -1;
        }

        .app [data-label]::before {
          content: attr(data-label) ': ';
          color: var(--text-secondary);
          font-size: var(--text-xs);
          font-weight: 700;
          text-transform: uppercase;
          letter-spacing: 0.08em;
        }

        .app .revoke {
          grid-row: 1;
          grid-column: 2;
        }
      }

      @media (max-width: 720px) {
        .device {
          padding: var(--spacing-md);
          grid-template-columns: minmax(0, 1fr);
          grid-template-areas:
            'main'
            'clients'
            'actions';
          gap: var(--spacing-sm);
        }

        .device-icon {
          display: none;
        }

        .device-facts {
          display: grid;
          gap: 0.125rem;
        }

        .device-facts div {
          display: grid;
          grid-template-columns: 9.5rem minmax(0, 1fr);
        }

        .device-actions {
          display: grid;
          justify-content: stretch;
          gap: var(--spacing-sm);
        }

        .device-actions .btn,
        .passkey .row-actions .btn {
          width: 100%;
          justify-content: center;
        }

        .dc.h {
          display: none;
        }

        /* A client: name and ID, then entered and access token until, then the chip. */
        .dc {
          display: block;
          padding: var(--spacing-sm) var(--spacing-md);
        }

        .dc > * + * {
          display: block;
          margin-top: 0.25rem;
        }

        .dc > [data-label] {
          display: inline-block;
          margin-right: var(--spacing-md);
        }

        .dc [data-label]::before {
          content: attr(data-label) ' ';
          color: var(--text-secondary);
          font-weight: 600;
        }

        .passkey {
          grid-template-columns: minmax(0, 1fr);
          padding: var(--spacing-md);
        }

        .passkey .row-actions {
          grid-column: 1;
          grid-row: auto;
          display: grid;
          grid-template-columns: 1fr 1fr;
          justify-content: stretch;
          margin-top: var(--spacing-xs);
        }

        .record-card-head {
          flex-wrap: wrap;
        }

        .form-actions {
          flex-wrap: wrap;
        }
      }
    `,
  ];

  /** A dialog is modal (Esc, focus trap, backdrop): the native element does that once it is shown. */
  protected updated() {
    for (const dialog of this.renderRoot.querySelectorAll<HTMLDialogElement>('dialog.modal:not([open])')) dialog.showModal();
  }

  // ── loading ────────────────────────────────────────────────────────────

  willUpdate(changed: Map<PropertyKey, unknown>) {
    if (changed.has('userId')) {
      this.user = null;
      this.userState = 'loading';
      this.limitsDone = false;
      this.passwordDone = false;
      this.actionError = '';
      this.editorFor = null;
      void this.loadAll();
    } else if (changed.has('tenantId')) {
      this.availableRoles = [];
      void this.loadRoles();
      void this.loadClientNames();
    }

    if ((changed.has('edit') || changed.has('user') || changed.has('canManage')) && this.user) {
      this.syncEditor();
    }
  }

  private async loadAll() {
    const seq = ++this.loadSeq;
    this.roles = loading([]);
    this.devices = loading([]);
    this.tokens = loading([]);
    this.passkeys = loading([]);
    await this.loadUser(seq);
    if (seq !== this.loadSeq || !this.user) return;
    void this.loadRoles();
    void this.loadDevices();
    void this.loadTokens();
    void this.loadPasskeys();
    void this.loadClientNames();
  }

  private async loadUser(seq = this.loadSeq) {
    this.userError = '';
    try {
      const [found] = await searchUsers('id', this.userId);
      if (seq !== this.loadSeq) return;
      this.user = found ?? null;
      this.userState = found ? 'ready' : 'missing';
    } catch (error) {
      if (seq !== this.loadSeq) return;
      this.user = null;
      this.userState = 'error';
      this.userError = error instanceof Error ? error.message : 'Failed to load user';
    }
  }

  private async loadRoles() {
    const tenantId = this.tenantId;
    if (!tenantId) {
      this.roles = ready([]);
      return;
    }
    this.roles = loading([]);
    const seq = this.loadSeq;
    try {
      const assigned = await fetchUserRoles(this.userId, tenantId);
      if (seq !== this.loadSeq || tenantId !== this.tenantId) return;
      this.roles = ready(assigned.map(assignment => assignment.roleId));
      if (this.editorFor === 'roles') this.rolesDraft = [...this.roles.data];
    } catch (error) {
      if (seq !== this.loadSeq || tenantId !== this.tenantId) return;
      this.roles = failed([], error, 'Failed to load roles');
    }
  }

  private async loadDevices() {
    const seq = this.loadSeq;
    this.devices = loading(this.devices.data);
    try {
      const devices = await fetchUserDevices(this.userId);
      if (seq === this.loadSeq) this.devices = ready(devices);
    } catch (error) {
      if (seq === this.loadSeq) this.devices = failed([], error, 'Failed to load devices');
    }
  }

  private async loadTokens() {
    const seq = this.loadSeq;
    this.tokens = loading(this.tokens.data);
    try {
      const tokens = await fetchUserRefreshTokens(this.userId);
      if (seq === this.loadSeq) this.tokens = ready(tokens);
    } catch (error) {
      if (seq === this.loadSeq) this.tokens = failed([], error, 'Failed to load offline access');
    }
  }

  private async loadPasskeys() {
    const seq = this.loadSeq;
    this.passkeys = loading(this.passkeys.data);
    try {
      const passkeys = await listPasskeys(this.userId);
      if (seq === this.loadSeq) this.passkeys = ready(passkeys);
    } catch (error) {
      if (seq === this.loadSeq) this.passkeys = failed([], error, 'Failed to load passkeys');
    }
  }

  /** Names for the client IDs on the page, when a tenant is selected and its clients can be read;
   *  an ID the tenant does not know is shown as the ID alone. */
  private async loadClientNames() {
    const tenantId = this.tenantId;
    if (!tenantId) {
      this.clientNames = new Map();
      return;
    }
    try {
      const clients: OAuthClient[] = await fetchAllClients(tenantId);
      if (tenantId !== this.tenantId) return;
      this.clientNames = new Map(clients.map(client => [client.id, getLocalizedDescription(client.clientName)]));
    } catch {
      this.clientNames = new Map();
    }
  }

  private async ensureAvailableRoles() {
    const tenantId = this.tenantId;
    if (!tenantId || this.availableRoles.length > 0) return;
    try {
      const roles = await getRoles(tenantId);
      if (tenantId === this.tenantId) this.availableRoles = roles;
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'Failed to load roles';
    }
  }

  // ── editors ────────────────────────────────────────────────────────────

  private get editGroup(): UserEditGroup | null {
    const group = EDIT_GROUPS.find(candidate => candidate === this.edit);
    if (!group || !this.canManage) return null;
    if (group === 'roles' && !this.tenantId) return null;
    return group;
  }

  private syncEditor() {
    const group = this.editGroup;
    if (!group || !this.user) {
      this.editorFor = null;
      return;
    }
    if (this.editorFor === group) return;
    this.editorFor = group;
    this.saveError = '';
    this.saving = false;
    this.phoneError = '';
    if (group === 'identifiers') {
      this.form = { email: this.user.email ?? '', phone: this.user.phone ?? '', login: this.user.login ?? '' };
    }
    if (group === 'roles') {
      this.rolesDraft = [...this.roles.data];
      void this.ensureAvailableRoles();
    }
  }

  private openEditor(group: UserEditGroup) {
    dispatchRecordRoute(this, { edit: group });
  }

  private closeEditor() {
    dispatchRecordRoute(this, { edit: null });
  }

  private validatePhone(value: string): boolean {
    if (!value.trim()) {
      this.phoneError = '';
      return true;
    }
    if (parsePhoneNumberFromString(value)?.isValid()) {
      this.phoneError = '';
      return true;
    }
    this.phoneError = 'Invalid phone number (use E.164 format, e.g. +15551234567)';
    return false;
  }

  private async saveIdentifiers(e: Event) {
    e.preventDefault();
    const user = this.user;
    if (!user || this.saving) return;
    if (!this.validatePhone(this.form.phone)) return;
    const next: User = {
      ...user,
      email: this.form.email.trim() || undefined,
      phone: this.form.phone.trim() || undefined,
      login: this.form.login.trim() || undefined,
    };
    if (!next.email && !next.phone && !next.login) {
      this.saveError = 'Keep at least one of email, phone or login.';
      return;
    }
    this.saving = true;
    this.saveError = '';
    try {
      await updateUser(user, next);
      this.user = next;
      this.closeEditor();
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'Failed to save identifiers';
    } finally {
      this.saving = false;
    }
  }

  private async saveRoles() {
    const tenantId = this.tenantId;
    if (!tenantId || this.saving) return;
    const before = new Set(this.roles.data);
    const after = new Set(this.rolesDraft);
    const add = this.rolesDraft.filter(roleId => !before.has(roleId));
    const remove = this.roles.data.filter(roleId => !after.has(roleId));
    this.saving = true;
    this.saveError = '';
    try {
      await updateUserRoles(this.userId, tenantId, add, remove);
      this.roles = ready([...this.rolesDraft]);
      this.closeEditor();
    } catch (error) {
      this.saveError = error instanceof Error ? error.message : 'Failed to save roles';
    } finally {
      this.saving = false;
    }
  }

  private async saveClaims(e: CustomEvent<{ userId: string; patch: Record<string, unknown> }>) {
    const { userId, patch } = e.detail;
    try {
      await patchUserClaims(userId, patch);
      const claims = { ...(this.user?.claims ?? {}) };
      for (const [key, value] of Object.entries(patch)) {
        if (value === null) delete claims[key];
        else claims[key] = value;
      }
      if (this.user) this.user = { ...this.user, claims };
      this.actionError = '';
      this.closeEditor();
    } catch (error) {
      this.actionError = error instanceof Error ? error.message : 'Failed to update claims';
    }
  }

  // ── actions ────────────────────────────────────────────────────────────

  private async run(key: string, failure: string, action: () => Promise<void>) {
    if (this.busy.has(key)) return;
    this.busy = new Set([...this.busy, key]);
    this.actionError = '';
    try {
      await action();
    } catch (error) {
      this.actionError = error instanceof Error ? error.message : failure;
    } finally {
      this.busy = new Set([...this.busy].filter(entry => entry !== key));
    }
  }

  private async signOutDevice(device: Device) {
    const sessionId = device.session?.publicId;
    if (!sessionId) return;
    await this.run(`device:${sessionId}`, 'Failed to sign the device out', async () => {
      await endUserSession(this.userId, sessionId);
      await Promise.all([this.loadDevices(), this.loadTokens()]);
    });
  }

  private async forgetDevice(device: Device) {
    const user = this.user;
    const agentId = device.userAgent?.id;
    if (!user || !agentId) return;
    const name = deviceLabel(device);
    const confirmed = await confirmDestructiveAction({
      title: 'Forget device',
      messagePrefix: 'Forget ',
      messageSubject: name,
      messageSuffix: ` for ${userLabel(user)}? Auth no longer remembers it, its session ends and the offline access issued on it is revoked. The next sign-in on it starts from scratch.`,
      confirmLabel: 'Forget',
    });
    if (!confirmed) return;
    await this.run(`forget:${agentId}`, 'Failed to forget the device', async () => {
      await forgetUserAgent(this.userId, agentId);
      await Promise.all([this.loadDevices(), this.loadTokens()]);
    });
  }

  private async signOutEverywhere() {
    const user = this.user;
    if (!user) return;
    const confirmed = await confirmDestructiveAction({
      title: 'Sign out everywhere',
      messagePrefix: 'Sign ',
      messageSubject: userLabel(user),
      messageSuffix: ' out on every device? Every session ends, clients are told through back-channel logout, and every app loses its offline access.',
      confirmLabel: 'Sign out everywhere',
    });
    if (!confirmed) return;
    await this.run('everywhere', 'Failed to sign the user out', async () => {
      await invalidateUserSession(this.userId);
      await Promise.all([this.loadDevices(), this.loadTokens()]);
    });
  }

  private async refreshDevices() {
    await Promise.all([this.loadDevices(), this.loadTokens()]);
  }

  private async revokeAccess(token: UserRefreshToken) {
    const user = this.user;
    if (!user) return;
    const client = this.clientNames.get(token.clientId) ?? token.clientId;
    const confirmed = await confirmDestructiveAction({
      title: 'Revoke offline access',
      messagePrefix: 'Revoke the offline access of ',
      messageSubject: client,
      messageSuffix: ` for ${userLabel(user)}? The app can no longer get new access tokens without sending the user through sign-in again. The user stays signed in.`,
      confirmLabel: 'Revoke',
    });
    if (!confirmed) return;
    await this.run(`revoke:${token.clientId}`, 'Failed to revoke offline access', async () => {
      await revokeUserRefreshTokens(this.userId, token.clientId);
      await this.loadTokens();
    });
  }

  private async deleteThisUser() {
    const user = this.user;
    if (!user) return;
    const label = userLabel(user);
    const confirmed = await confirmDestructiveAction({
      title: 'Delete user',
      messagePrefix: 'Delete ',
      messageSubject: label,
      messageSuffix: '? Their sessions end, offline access is revoked, passkeys are gone, and clients are told through back-channel logout. This cannot be undone.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    await this.run('delete', 'Failed to delete the user', async () => {
      await deleteUser(this.userId);
      this.dispatchEvent(new CustomEvent('user-deleted', { detail: { id: this.userId, label }, bubbles: true, composed: true }));
    });
  }

  private async resetLimits() {
    const user = this.user;
    const tenantId = this.tenantId;
    if (!user || !tenantId) return;
    this.limitsDone = false;
    await this.run('limits', 'Failed to reset limits', async () => {
      await resetUserLimits(user.id, tenantId, user.email, user.phone);
      this.limitsDone = true;
    });
  }

  private openResetPassword() {
    const user = this.user;
    if (!user) return;
    this.resetChannel = user.email ? 'email' : user.phone ? 'sms' : this.canRevealPassword ? 'show' : null;
    this.resetTtlHours = DEFAULT_TEMPORARY_PASSWORD_TTL_HOURS;
    this.revealed = null;
    this.copyFeedback = '';
    this.passwordDone = false;
    this.pendingReset = true;
  }

  private async confirmResetPassword() {
    const user = this.user;
    const channel = this.resetChannel;
    const ttlHours = this.resetTtlHours;
    if (!user || !channel || !Number.isInteger(ttlHours) || ttlHours <= 0) return;
    this.pendingReset = false;
    await this.run('password', 'Failed to reset password', async () => {
      const password = await resetPassword(user.id, channel, ttlHours * 60 * 60);
      this.passwordDone = true;
      this.revealed = password === null ? null : { password, ttlHours };
    });
  }

  private async copyPassword() {
    if (!this.revealed) return;
    const copied = await copyToClipboard(this.revealed.password);
    this.copyFeedback = copied ? 'Password copied to clipboard.' : 'Could not copy password.';
  }

  private async renamePasskeyOf(passkey: PasskeyInfo) {
    const next = window.prompt('Passkey name', passkey.name ?? '');
    if (next === null) return;
    const name = next.trim() || null;
    await this.run(`passkey:${passkey.id}`, 'Failed to rename passkey', async () => {
      await renamePasskey(this.userId, passkey.id, name);
      this.passkeys = ready(this.passkeys.data.map(entry => (entry.id === passkey.id ? { ...entry, name } : entry)));
    });
  }

  private async deletePasskeyOf(passkey: PasskeyInfo) {
    const confirmed = await confirmDestructiveAction({
      title: 'Delete passkey',
      messagePrefix: 'Delete passkey ',
      messageSubject: passkey.name ?? passkey.id,
      messageSuffix: '? The user can no longer sign in with it.',
      confirmLabel: 'Delete',
    });
    if (!confirmed) return;
    await this.run(`passkey:${passkey.id}`, 'Failed to delete passkey', async () => {
      await deletePasskey(this.userId, passkey.id);
      this.passkeys = ready(this.passkeys.data.filter(entry => entry.id !== passkey.id));
    });
  }

  private scrollToOfflineAccess() {
    const target = this.renderRoot.querySelector<HTMLElement>('#offline-access');
    target?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    target?.focus({ preventScroll: true });
  }

  // ── rendering ──────────────────────────────────────────────────────────

  private card(id: string, heading: string, subheading: string, actions: unknown, body: unknown, extraClass = '') {
    return html`
      <section class="card record-card ${extraClass}" id=${id} aria-label=${heading} tabindex="-1">
        <div class="record-card-head">
          <div>
            <h2>${heading}</h2>
            ${subheading ? html`<div class="record-card-sub">${subheading}</div>` : nothing}
          </div>
          ${actions ? html`<div class="head-actions">${actions}</div>` : nothing}
        </div>
        ${body}
      </section>
    `;
  }

  private editButton(group: UserEditGroup, disabled = false) {
    if (!this.canManage) return nothing;
    return html`<button type="button" class="btn btn-secondary btn-sm" aria-label=${`Edit ${group}`}
      ?disabled=${disabled} @click=${() => this.openEditor(group)}>Edit</button>`;
  }

  private partNote<T>(part: Part<T>, what: string, retry: () => void) {
    if (part.status === 'loading') return html`<div class="part-note" role="status">Loading ${what}…</div>`;
    return html`<div class="part-note error" role="alert">
      <span>Could not load ${what}: ${part.error}</span>
      <button type="button" class="btn btn-secondary btn-sm" @click=${retry}>Retry</button>
    </div>`;
  }

  private row(label: string, value: unknown) {
    return html`<versola-record-row .label=${label} .value=${value}></versola-record-row>`;
  }

  private clientCell(clientId: string, stacked: boolean) {
    const name = this.clientNames.get(clientId);
    return html`<span class="name-id ${stacked ? 'stacked' : ''}">
      ${name ? html`<span class="client-name">${name}</span>` : nothing}
      <span class="row-id">${clientId}</span>
    </span>`;
  }

  private deviceIcon(platform: UserSession['platform']) {
    const path = platform === 'ios' || platform === 'android'
      ? svg`<rect x="7" y="2" width="10" height="20" rx="2"></rect><path d="M11 18h2"></path>`
      : svg`<rect x="2" y="4" width="20" height="13" rx="2"></rect><path d="M8 21h8M12 17v4"></path>`;
    return html`<span class="device-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor"
      stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${path}</svg></span>`;
  }

  private renderDevice(device: Device) {
    const session = device.session;
    const agent = device.userAgent;
    const name = deviceLabel(device);
    const platform = agent?.platform ?? session?.platform;
    const held = new Set(device.tokens.map(token => token.clientId));
    const marker = (clientId: string) => held.has(clientId) ? html`
      <span class="dc-marker"><span class="chip">Offline access</span>
        <button type="button" class="down" aria-label="Go to Apps with access" title="Apps with access"
          @click=${this.scrollToOfflineAccess}>↓</button></span>` : html`<span></span>`;
    const offlineNote = held.size > 0 ? ' Offline access issued on it is still valid.' : '';
    const sub = session
      ? `${platformLabel(platform)} · signed in ${when(session.createdAt)}`
      : agent
        ? `${platformLabel(platform)} · its session has ended.${offlineNote}`
        : `Its session has ended.${offlineNote}`;

    return html`
      <div class="device" data-device=${device.key}>
        ${this.deviceIcon(platform)}
        <div class="device-main">
          <div class="device-name">${name}
            ${session ? html`<span class="chip ok">Signed in</span>` : html`<span class="chip">Signed out</span>`}</div>
          ${session ? nothing : html`<div class="device-sub">${sub}</div>`}
          ${session || agent ? html`<dl class="device-facts">
            ${session ? html`
              <div><dt>Platform</dt> <dd>${platformLabel(platform)}</dd></div>
              <div data-life="signed-in"><dt>Signed in</dt> <dd>${when(session.createdAt)}</dd></div>
              <div data-life="session"><dt>Session expires</dt> <dd>${when(session.expiresAt)}</dd></div>` : nothing}
            ${session?.idleExpiresAt ? html`<div data-life="idle"><dt>Idle timeout</dt> <dd>${when(session.idleExpiresAt)}</dd></div>` : nothing}
            ${agent ? html`<div data-life="remembered"><dt>Device remembered until</dt> <dd>${when(agent.expiresAt)}</dd></div>` : nothing}
          </dl>` : nothing}
        </div>
        ${session ? html`
          <div class="device-clients" role="table" aria-label=${`Clients entered on ${name}`}>
            <div class="dc h" role="row">
              <span role="columnheader">Client</span><span role="columnheader">Entered</span>
              <span role="columnheader">Access token until</span><span role="columnheader" aria-hidden="true"></span>
            </div>
            ${session.clients.length === 0 ? html`<div class="dc" role="row"><span class="muted" role="cell">No client entered yet.</span></div>` : session.clients.map(client => html`
              <div class="dc" role="row">
                <span role="cell">${this.clientCell(client.clientId, false)}</span>
                <span role="cell" data-label="Entered">${when(client.enteredAt)}</span>
                <span role="cell" data-label="Access token until">${when(client.expiresAt)}</span>
                <span role="cell">${marker(client.clientId)}</span>
              </div>`)}
          </div>` : held.size > 0 ? html`
          <div class="device-clients" role="table" aria-label="Apps with offline access issued on this device">
            <div class="dc h" role="row">
              <span role="columnheader">Client</span><span role="columnheader">Issued</span>
              <span role="columnheader">Access token until</span><span role="columnheader" aria-hidden="true"></span>
            </div>
            ${[...held].map(clientId => {
              const token = device.tokens.find(entry => entry.clientId === clientId)!;
              return html`<div class="dc" role="row">
                <span role="cell">${this.clientCell(clientId, false)}</span>
                <span role="cell" data-label="Issued">${when(token.issuedAt)}</span>
                <span role="cell" data-label="Access token until"><span class="muted">—</span></span>
                <span role="cell">${marker(clientId)}</span>
              </div>`;
            })}
          </div>` : nothing}
        ${this.canManage && (session?.publicId || agent) ? html`<div class="device-actions">
          ${session?.publicId ? html`
            <button type="button" class="btn btn-secondary btn-sm" aria-label=${`Sign out device ${name}`}
              ?disabled=${this.busy.has(`device:${session.publicId}`)}
              @click=${() => this.signOutDevice(device)}>Sign out device</button>` : nothing}
          ${agent ? html`
            <button type="button" class="btn btn-secondary btn-danger-outline btn-sm" aria-label=${`Forget device ${name}`}
              ?disabled=${this.busy.has(`forget:${agent.id}`)}
              @click=${() => this.forgetDevice(device)}>Forget device</button>` : nothing}
        </div>` : nothing}
      </div>
    `;
  }

  private renderDevicesCard() {
    const devicesOk = this.devices.status === 'ready';
    const devices = devicesOk ? buildDevices(this.devices.data, this.tokens.status === 'ready' ? this.tokens.data : []) : [];
    const refreshing = this.devices.status === 'loading' || this.tokens.status === 'loading';
    const actions = html`
      <button type="button" class="btn btn-secondary btn-sm" ?disabled=${refreshing} @click=${this.refreshDevices}>Refresh</button>
      ${this.canManage ? html`<button type="button" class="btn btn-secondary btn-danger-outline btn-sm"
        ?disabled=${this.busy.has('everywhere') || !devicesOk || !this.devices.data.some(device => device.session)}
        @click=${this.signOutEverywhere}>Sign out everywhere…</button>` : nothing}`;
    const body = this.devices.status === 'ready'
      ? (devices.length === 0
        ? html`<div class="part-note">Not signed in on any device.</div>`
        : devices.map(device => this.renderDevice(device)))
      : this.partNote(this.devices, 'devices', () => void this.loadDevices());
    return this.card('devices', 'Signed-in devices',
      'One row per browser or app install auth remembers. Each holds at most one session, with when it expires; the clients entered during it are listed under it. A device stays listed, signed out, until it is forgotten or its remembered time runs out.',
      actions, body);
  }

  private renderAppsCard() {
    const tokens = this.tokens.data;
    const devices = buildDevices(this.devices.data, tokens);
    const deviceOf = (token: UserRefreshToken) => {
      const device = devices.find(entry => entry.tokens.includes(token));
      return device ? { name: deviceLabel(device), signedIn: device.session !== null } : { name: 'Signed-out device', signedIn: false };
    };
    let body: unknown;
    if (this.tokens.status !== 'ready') {
      body = this.partNote(this.tokens, 'offline access', () => void this.loadTokens());
    } else if (tokens.length === 0) {
      body = html`<div class="part-note">No app holds offline access for this user.</div>`;
    } else {
      body = html`
        <div role="table" aria-label="Apps with offline access">
        <div class="app h" role="row"><span role="columnheader">Client</span><span role="columnheader">Scopes</span><span role="columnheader">Issued</span><span role="columnheader">Expires</span><span role="columnheader">Device</span><span role="columnheader" aria-hidden="true"></span></div>
        ${tokens.map(token => {
          const device = deviceOf(token);
          return html`
            <div class="app" role="row" data-client=${token.clientId}>
              <span role="cell">${this.clientCell(token.clientId, true)}</span>
              <span class="tag-list" role="cell" data-label="Scopes">${token.scope.map(scope => html`<span class="tag quiet">${scope}</span>`)}
                ${token.dpopBound ? html`<span class="chip" title="Bound to the app's DPoP key (RFC 9449)">DPoP-bound</span>` : nothing}</span>
              <span role="cell" data-label="Issued">${when(token.issuedAt, false)}</span>
              <span role="cell" data-label="Expires">${when(token.expiresAt, false)}</span>
              <span class="app-device" role="cell" data-label="Device">${device.name} <span class="chip ${device.signedIn ? 'ok' : ''}">${device.signedIn ? 'Signed in' : 'Signed out'}</span></span>
              ${this.canManage ? html`<span role="cell" class="revoke"><button type="button" class="btn btn-secondary btn-sm"
                aria-label=${`Revoke offline access of ${this.clientNames.get(token.clientId) ?? token.clientId}`}
                ?disabled=${this.busy.has(`revoke:${token.clientId}`)}
                @click=${() => this.revokeAccess(token)}>Revoke</button></span>` : html`<span role="cell"></span>`}
            </div>`;
        })}
        </div>`;
    }
    return this.card('offline-access', 'Apps with access',
      'Apps that hold offline access can get new access tokens without a signed-in device until it expires or is revoked.',
      html`<button type="button" class="btn btn-secondary btn-sm" ?disabled=${this.tokens.status === 'loading'}
        @click=${() => void this.loadTokens()}>Refresh</button>`, body);
  }

  private claimValue(value: unknown) {
    if (typeof value === 'boolean') return html`<span class="chip ${value ? 'ok' : ''}">${String(value)}</span>`;
    if (typeof value === 'string') return value;
    if (value === null || value === undefined) return '—';
    if (typeof value === 'object') return html`<span class="claim-json">${JSON.stringify(value)}</span>`;
    return String(value);
  }

  private renderClaimsCard(user: User) {
    const entries = Object.entries(user.claims ?? {});
    const body = entries.length === 0
      ? html`<div class="part-note">No claims.</div>`
      : html`<div>${entries.map(([key, value]) => html`
        <versola-record-row .label=${key} .value=${this.claimValue(value)}></versola-record-row>`)}</div>`;
    return this.card('claims', 'Claims', 'Facts about the user that scopes release to clients.', this.editButton('claims'), body);
  }

  private renderRolesCard() {
    const tenantId = this.tenantId;
    if (!tenantId) return nothing;
    const body = this.roles.status !== 'ready'
      ? this.partNote(this.roles, 'roles', () => void this.loadRoles())
      : this.row('Roles', this.roles.data.length === 0
        ? html`<span class="muted">No roles in this tenant.</span>`
        : html`<div class="tag-list">${this.roles.data.map(roleId => html`<span class="tag">${roleId}</span>`)}</div>`);
    return this.card('roles', `Roles in ${tenantId}`, '', this.editButton('roles', this.roles.status !== 'ready'), body);
  }

  private renderPasskeysCard() {
    const body = this.passkeys.status !== 'ready'
      ? this.partNote(this.passkeys, 'passkeys', () => void this.loadPasskeys())
      : this.passkeys.data.length === 0
        ? html`<div class="part-note">No passkeys registered.</div>`
        : this.passkeys.data.map(passkey => {
          const name = passkey.name ?? 'Unnamed passkey';
          const kind = passkey.deviceType === 'multiDevice' ? 'Multi-device' : passkey.deviceType === 'singleDevice' ? 'Single device' : passkey.deviceType;
          return html`
            <div class="passkey" data-passkey=${passkey.id}>
              <div>
                <div class="passkey-name">${name}</div>
                <div class="passkey-chips">
                  <span class="chip">${kind}</span>
                  <span class="chip ${passkey.backedUp ? 'ok' : ''}">${passkey.backedUp ? 'Synced' : 'Not synced'}</span>
                  ${passkey.transports.map(transport => html`<span class="chip">${transport}</span>`)}
                </div>
                <div class="passkey-when">Last used ${passkey.lastUsedAt ? when(passkey.lastUsedAt) : 'never'} · enrolled ${when(passkey.createdAt)}</div>
              </div>
              ${this.canManage ? html`<div class="row-actions">
                <button type="button" class="btn btn-secondary btn-sm" aria-label=${`Rename passkey ${name}`}
                  ?disabled=${this.busy.has(`passkey:${passkey.id}`)} @click=${() => this.renamePasskeyOf(passkey)}>Rename</button>
                <button type="button" class="btn btn-secondary btn-sm" aria-label=${`Delete passkey ${name}`}
                  ?disabled=${this.busy.has(`passkey:${passkey.id}`)} @click=${() => this.deletePasskeyOf(passkey)}>Delete…</button>
              </div>` : nothing}
            </div>`;
        });
    return this.card('passkeys', 'Passkeys', 'Credentials the user signs in with on their devices.', null, body);
  }

  private renderSecurityCard() {
    const noTenant = !this.tenantId;
    return this.card('security', 'Security actions', '', null, html`
      <div class="record-row">
        <div class="record-label">Password</div>
        <div class="record-value">Managed by the user; a temporary one can be issued.
          ${this.passwordDone ? html`<div class="status-line" role="status">${this.revealed ? 'Temporary password issued.' : 'Temporary password sent.'}</div>` : nothing}</div>
        ${this.canManage ? html`<div><button type="button" class="btn btn-secondary btn-sm"
          ?disabled=${noTenant || this.busy.has('password')}
          title=${noTenant ? 'Select a tenant to reset the password' : ''}
          @click=${this.openResetPassword}>Reset password…</button></div>` : html`<div></div>`}
      </div>
      <div class="record-row">
        <div class="record-label">Submission limits</div>
        <div class="record-value">Clears the OTP, password and passkey attempt counters and any active ban.
          ${this.limitsDone ? html`<div class="status-line" role="status">Limits reset.</div>` : nothing}</div>
        ${this.canManage ? html`<div><button type="button" class="btn btn-secondary btn-sm"
          ?disabled=${noTenant || this.busy.has('limits')}
          title=${noTenant ? 'Select a tenant to reset limits' : ''}
          @click=${this.resetLimits}>Reset limits</button></div>` : html`<div></div>`}
      </div>`);
  }

  private renderResetDialog() {
    const user = this.user;
    if (!this.pendingReset || !user) return nothing;
    const channels = user.email || user.phone || this.canRevealPassword;
    const valid = this.resetChannel !== null && Number.isInteger(this.resetTtlHours) && this.resetTtlHours > 0;
    return html`
      <dialog class="modal" aria-labelledby="reset-title"
        @close=${() => { this.pendingReset = false; }}>
        <h2 id="reset-title">Reset password</h2>
        <div class="form-group" style="margin-bottom:var(--spacing-lg)">
          <label for="temporary-password-ttl">Temporary password lifetime (hours)</label>
          <input id="temporary-password-ttl" class="ttl-input" type="number" min="1" step="1"
            .value=${String(this.resetTtlHours)}
            @input=${(e: Event) => {
              const value = Number((e.target as HTMLInputElement).value);
              this.resetTtlHours = Number.isFinite(value) ? value : 0;
            }} />
        </div>
        ${channels ? html`
          <fieldset>
            <legend>Deliver via</legend>
            ${user.email ? html`<label class="radio"><input type="radio" name="channel" value="email"
              ?checked=${this.resetChannel === 'email'} @change=${() => { this.resetChannel = 'email'; }}>Email — ${user.email}</label>` : nothing}
            ${user.phone ? html`<label class="radio"><input type="radio" name="channel" value="sms"
              ?checked=${this.resetChannel === 'sms'} @change=${() => { this.resetChannel = 'sms'; }}>SMS — ${user.phone}</label>` : nothing}
            ${this.canRevealPassword ? html`<label class="radio"><input type="radio" name="channel" value="show"
              ?checked=${this.resetChannel === 'show'} @change=${() => { this.resetChannel = 'show'; }}>Show — display it here</label>` : nothing}
          </fieldset>` : html`<p class="lead">No phone or email configured for this user.</p>`}
        <div class="modal-actions">
          <button type="button" class="btn btn-secondary" @click=${() => { this.pendingReset = false; }}>Cancel</button>
          <button type="button" class="btn btn-primary" ?disabled=${!valid} @click=${this.confirmResetPassword}>Confirm</button>
        </div>
      </dialog>`;
  }

  private renderRevealedDialog() {
    const revealed = this.revealed;
    if (!revealed) return nothing;
    return html`
      <dialog class="modal" aria-labelledby="password-title"
        @close=${() => { this.revealed = null; this.copyFeedback = ''; }}>
        <h2 id="password-title">Temporary password</h2>
        <p class="lead">Valid for ${revealed.ttlHours} hour${revealed.ttlHours === 1 ? '' : 's'}. Copy it now; it is not shown again.</p>
        <pre class="password-value">${revealed.password}</pre>
        <div style="display:flex;align-items:center;gap:0.75rem;margin-top:var(--spacing-md)">
          <button type="button" class="btn btn-primary btn-sm" @click=${this.copyPassword}>Copy password</button>
          ${this.copyFeedback ? html`<span class="copy-feedback" role="status">${this.copyFeedback}</span>` : nothing}
        </div>
        <div class="modal-actions">
          <button type="button" class="btn btn-secondary" @click=${() => { this.revealed = null; this.copyFeedback = ''; }}>Close</button>
        </div>
      </dialog>`;
  }

  private renderEditorHeader(title: string, user: User) {
    return html`
      <div class="form-header">
        <div class="form-header-lead">
          <versola-nav-toggle></versola-nav-toggle>
          <div class="title-stack">
            <h1 class="form-title">${title}</h1>
            <div class="entity-id-meta">${userLabel(user)}${userDisplayName(user) ? html` · ${user.id}` : nothing}</div>
          </div>
        </div>
      </div>`;
  }

  private renderEditor(group: UserEditGroup, user: User) {
    if (group === 'claims') {
      return html`
        ${this.actionError ? html`<div class="banner warn" role="alert"><div class="banner-text">${this.actionError}</div></div>` : nothing}
        <versola-claim-edit .user=${user} .tenantId=${this.tenantId}
          @close=${() => this.closeEditor()} @save=${this.saveClaims}></versola-claim-edit>`;
    }

    const error = this.saveError ? html`<div class="banner warn" role="alert"><div class="banner-text">${this.saveError}</div></div>` : nothing;
    if (group === 'roles') {
      return html`
        ${this.renderEditorHeader(EDIT_TITLES.roles, user)}
        <div class="card">
          ${error}
          ${this.roles.status !== 'ready' ? this.partNote(this.roles, 'roles', () => void this.loadRoles()) : html`
          <versola-role-picker label=${`Roles in ${this.tenantId}`} .roles=${this.availableRoles} .value=${this.rolesDraft}
            @change=${(e: CustomEvent<{ value: string[] }>) => { this.rolesDraft = e.detail.value; }}></versola-role-picker>`}
          <div class="form-actions">
            <button type="button" class="btn btn-secondary" @click=${() => this.closeEditor()}>Cancel</button>
            <button type="button" class="btn btn-primary" ?disabled=${this.saving || this.roles.status !== 'ready'} @click=${this.saveRoles}>Save roles</button>
          </div>
        </div>`;
    }

    return html`
      ${this.renderEditorHeader(EDIT_TITLES.identifiers, user)}
      <div class="card">
        ${error}
        <form @submit=${this.saveIdentifiers} novalidate>
          <div class="form-grid">
            <div class="form-group">
              <label for="user-email">Email</label>
              <input id="user-email" class="compact-input" type="email" autocomplete="off" placeholder="user@example.com"
                .value=${this.form.email} @input=${(e: Event) => { this.form = { ...this.form, email: (e.target as HTMLInputElement).value }; }} />
            </div>
            <div class="form-group">
              <label for="user-phone">Phone</label>
              <input id="user-phone" class="compact-input ${this.phoneError ? 'input-error' : ''}" type="text" autocomplete="off" placeholder="+15551234567"
                aria-invalid=${this.phoneError ? 'true' : 'false'} aria-describedby=${this.phoneError ? 'user-phone-error' : nothing}
                .value=${this.form.phone}
                @input=${(e: Event) => {
                  const phone = (e.target as HTMLInputElement).value;
                  this.form = { ...this.form, phone };
                  this.validatePhone(phone);
                }} />
              ${this.phoneError ? html`<div id="user-phone-error" class="error-message">${this.phoneError}</div>` : nothing}
            </div>
            <div class="form-group">
              <label for="user-login">Login</label>
              <input id="user-login" class="compact-input" type="text" autocomplete="off" placeholder="alice"
                .value=${this.form.login} @input=${(e: Event) => { this.form = { ...this.form, login: (e.target as HTMLInputElement).value }; }} />
            </div>
          </div>
          <div class="form-actions">
            <button type="button" class="btn btn-secondary" @click=${() => this.closeEditor()}>Cancel</button>
            <button type="submit" class="btn btn-primary" ?disabled=${this.saving}>Save identifiers</button>
          </div>
        </form>
      </div>`;
  }

  private renderBody(user: User): TemplateResult {
    const name = userDisplayName(user);
    const signedIn = this.devices.status === 'ready' && this.devices.data.some(device => device.session);
    return html`
      ${this.actionError ? html`<div class="banner warn" role="alert">
        <div class="banner-text"><div class="banner-title">${statusIcon('alert')}Something went wrong</div><div>${this.actionError}</div></div>
        <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.actionError = ''; }}>Dismiss</button></div>` : nothing}

      <versola-record-header
        .crumbs=${[{ label: 'Users', onSelect: () => dispatchRecordRoute(this, { id: null, edit: null }) }, { label: name || 'No name' }]}
        .heading=${name || 'No name'}
        .recordId=${user.id}
        idLabel="user ID"
        .meta=${html`<span class="chip">User</span>${signedIn ? html`<span class="chip ok">Signed in</span>` : nothing}`}
      ></versola-record-header>

      ${this.tenantId ? nothing : html`<div class="banner info" role="status"><div class="banner-text">Select a tenant to see roles and reset limits.</div></div>`}

      ${this.card('identifiers', 'Identifiers', 'Any one of them signs the user in.', this.editButton('identifiers'), html`
        ${this.row('Email', user.email ?? html`<span class="muted">—</span>`)}
        ${this.row('Phone', user.phone ?? html`<span class="muted">—</span>`)}
        ${this.row('Login', user.login ? html`<span class="mono">${user.login}</span>` : html`<span class="muted">—</span>`)}`)}
      ${this.renderRolesCard()}
      ${this.renderClaimsCard(user)}
      ${this.renderDevicesCard()}
      ${this.renderAppsCard()}
      ${this.renderPasskeysCard()}
      ${this.renderSecurityCard()}

      <versola-record-card heading="Fixed" fixedLabel="Fixed" .body=${html`
        <versola-record-row label="User ID" fixed="Assigned by central" .value=${html`<span class="mono">${user.id}</span>`}></versola-record-row>`}>
      </versola-record-card>

      ${this.canManage ? html`
        <versola-danger-card heading="Delete user"
          text="Signs out every device, revokes every app's offline access, removes passkeys, and tells clients through back-channel logout. This cannot be undone."
          actionLabel="Delete user" .actionAriaLabel=${`Delete user ${userLabel(user)}`}
          @action=${this.deleteThisUser}></versola-danger-card>` : nothing}

      ${this.renderResetDialog()}
      ${this.renderRevealedDialog()}
    `;
  }

  render() {
    if (this.userState === 'loading') {
      return html`<versola-loading-cards .count=${3}></versola-loading-cards>`;
    }
    if (this.userState === 'error') {
      return html`<versola-error-card heading="Could not load user" .message=${this.userError}
        @retry=${() => { this.userState = 'loading'; void this.loadAll(); }}></versola-error-card>`;
    }
    if (this.userState === 'missing' || !this.user) {
      return html`<versola-empty-state heading="User not found" text="No user has this ID. It may have been deleted.">
        <button type="button" class="btn btn-secondary" @click=${() => dispatchRecordRoute(this, { id: null, edit: null })}>Back to users</button>
      </versola-empty-state>`;
    }
    const group = this.editGroup;
    return group ? this.renderEditor(group, this.user) : this.renderBody(this.user);
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-user-record': VersolaUserRecord;
  }
}
