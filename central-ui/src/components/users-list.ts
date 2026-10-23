import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { parsePhoneNumberFromString } from 'libphonenumber-js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, formStyles } from '../styles/components';
import type { User, UserSearchField } from '../types';
import { copyToClipboard } from '../utils/helpers';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { userDisplayName } from '../utils/user-display';
import { createUser, searchUsers } from '../utils/users-api';
import './content-header';
import './data-table';
import './empty-state';
import './error-card';
import './loading-cards';
import './nav-toggle';
import './user-record';
import type { DataTableColumn, RowMenuItem } from './data-table';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Users. There is no list-all endpoint, so the list is a search: a field (login, email, phone,
 * ID; detected from what is typed), a query and Search. A result opens the user's page
 * (`?view=users&id=<id>`, see `versola-user-record`); `+ Create User` is a single-page form.
 */
@customElement('versola-users-list')
export class VersolaUsersList extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  /** Whether the generated password may be shown instead of delivered — non-prod only. */
  @property({ type: Boolean }) canRevealPassword = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private users: User[] = [];
  @state() private searchField: UserSearchField = 'login';
  @state() private searchQuery = '';
  @state() private isLoading = false;
  @state() private errorMessage = '';
  @state() private hasSearched = false;
  @state() private deletedLabel = '';
  @state() private createForm = { email: '', phone: '', login: '' };
  @state() private createError = '';
  @state() private phoneError = '';
  @state() private isCreating = false;
  private loadRequestId = 0;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    formStyles,
    bannerStyles,
    css`
      :host {
        display: block;
        --compact-field-max-width: 22.8rem;
      }

      .search-bar {
        display: flex;
        flex-wrap: wrap;
        gap: var(--spacing-sm);
        margin-bottom: var(--spacing-lg);
        max-width: 36rem;
      }

      .search-bar select {
        flex: 0 0 8rem;
        width: 8rem;
      }

      .search-bar input {
        flex: 1 1 14rem;
        min-width: 0;
      }

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
    `,
  ];

  willUpdate(changed: Map<PropertyKey, unknown>) {
    if (changed.has('route')) {
      const previous = changed.get('route') as RecordRoute | undefined;
      if (this.route.create && !previous?.create) this.resetCreateForm();
      if (this.route.id) this.deletedLabel = '';
    }
  }

  private async loadUsers() {
    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.errorMessage = '';
    this.hasSearched = true;
    this.deletedLabel = '';
    try {
      const result = await searchUsers(this.searchField, this.searchQuery);
      if (requestId !== this.loadRequestId) return;
      this.users = result;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.users = [];
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load users';
    } finally {
      if (requestId === this.loadRequestId) this.isLoading = false;
    }
  }

  private detectSearchField(query: string): UserSearchField {
    const trimmed = query.trim();
    if (UUID_RE.test(trimmed)) return 'id';
    if (trimmed.startsWith('+')) return 'phone';
    if (trimmed.includes('@')) return 'email';
    return 'login';
  }

  private handleSearchSubmit(e: Event) {
    e.preventDefault();
    if (!this.searchQuery.trim()) return;
    void this.loadUsers();
  }

  private open(user: User) {
    dispatchRecordRoute(this, { id: user.id, edit: null, create: false });
  }

  private handleUserDeleted(e: CustomEvent<{ id: string; label: string }>) {
    this.users = this.users.filter(user => user.id !== e.detail.id);
    this.deletedLabel = e.detail.label;
    dispatchRecordRoute(this, { id: null, edit: null, create: false });
  }

  // ── create ─────────────────────────────────────────────────────────────

  private resetCreateForm() {
    this.createForm = { email: '', phone: '', login: '' };
    this.createError = '';
    this.phoneError = '';
    this.isCreating = false;
  }

  private validatePhone(value: string): boolean {
    if (!value.trim() || parsePhoneNumberFromString(value)?.isValid()) {
      this.phoneError = '';
      return true;
    }
    this.phoneError = 'Invalid phone number (use E.164 format, e.g. +15551234567)';
    return false;
  }

  private async handleCreateSubmit(e: Event) {
    e.preventDefault();
    if (this.isCreating) return;
    if (!this.validatePhone(this.createForm.phone)) return;
    const email = this.createForm.email.trim() || undefined;
    const phone = this.createForm.phone.trim() || undefined;
    const login = this.createForm.login.trim() || undefined;
    if (!email && !phone && !login) {
      this.createError = 'Enter at least one of email, phone or login.';
      return;
    }
    this.isCreating = true;
    this.createError = '';
    try {
      const created = await createUser({ email, phone, login, claims: {} });
      this.users = [created, ...this.users.filter(user => user.id !== created.id)];
      this.hasSearched = true;
      this.errorMessage = '';
      dispatchRecordRoute(this, { id: created.id, edit: null, create: false });
    } catch (error) {
      this.createError = error instanceof Error ? error.message : 'Failed to create user';
    } finally {
      this.isCreating = false;
    }
  }

  private renderCreateForm() {
    const field = (key: 'email' | 'phone' | 'login') => (e: Event) => {
      const value = (e.target as HTMLInputElement).value;
      this.createForm = { ...this.createForm, [key]: value };
      if (key === 'phone') this.validatePhone(value);
    };
    return html`
      <div class="form-header">
        <div class="form-header-lead">
          <versola-nav-toggle></versola-nav-toggle>
          <div class="title-stack">
            <h1 class="form-title">Create New User</h1>
            <div class="form-subtitle">Any one identifier is enough; the user can sign in with whichever you set.</div>
          </div>
        </div>
      </div>
      <div class="card">
        <form @submit=${this.handleCreateSubmit} novalidate>
          ${this.createError ? html`<div class="banner warn" role="alert"><div class="banner-text">${this.createError}</div></div>` : nothing}
          <div class="form-grid">
            <div class="form-group">
              <label for="user-email">Email</label>
              <input id="user-email" class="compact-input" type="email" autocomplete="off" placeholder="user@example.com"
                .value=${this.createForm.email} @input=${field('email')} />
            </div>
            <div class="form-group">
              <label for="user-phone">Phone</label>
              <input id="user-phone" class="compact-input ${this.phoneError ? 'input-error' : ''}" type="text" autocomplete="off" placeholder="+15551234567"
                aria-invalid=${this.phoneError ? 'true' : 'false'} aria-describedby=${this.phoneError ? 'user-phone-error' : nothing}
                .value=${this.createForm.phone} @input=${field('phone')} />
              ${this.phoneError ? html`<div id="user-phone-error" class="error-message">${this.phoneError}</div>` : nothing}
            </div>
            <div class="form-group">
              <label for="user-login">Login</label>
              <input id="user-login" class="compact-input" type="text" autocomplete="off" placeholder="alice"
                .value=${this.createForm.login} @input=${field('login')} />
            </div>
          </div>
          <div class="form-actions">
            <button type="button" class="btn btn-secondary" @click=${() => dispatchRecordRoute(this, { create: false })}>Cancel</button>
            <button type="submit" class="btn btn-primary" ?disabled=${this.isCreating}>Create User</button>
          </div>
        </form>
      </div>
    `;
  }

  // ── search ─────────────────────────────────────────────────────────────

  private renderResults() {
    const muted = (value: string | undefined) => value ?? html`<span class="muted">—</span>`;
    const columns: DataTableColumn<User>[] = [
      { id: 'user', label: 'User', class: 'full-id', cell: user => {
        const name = userDisplayName(user);
        return html`
          <button type="button" class="row-link" @click=${() => this.open(user)}
            aria-label=${`Open user ${name || 'No name'} ${user.id}`}>
            <span class="row-title">${name || 'No name'}</span>
            <span class="row-id">${user.id}</span>
          </button>`;
      } },
      { id: 'email', label: 'Email', cell: user => muted(user.email) },
      { id: 'phone', label: 'Phone', cell: user => muted(user.phone) },
      { id: 'login', label: 'Login', cell: user => muted(user.login) },
    ];
    const menuItems = (user: User): RowMenuItem[] => [
      { label: 'Open', onSelect: () => this.open(user) },
      { label: 'Copy ID', ariaLabel: `Copy user ID ${user.id}`, onSelect: () => void copyToClipboard(user.id) },
    ];
    return html`
      <versola-data-table
        .columns=${columns}
        .rows=${this.users}
        .rowKey=${(user: User) => user.id}
        .menuItems=${menuItems}
        .menuLabel=${(user: User) => `Actions for ${user.id}`}
      ></versola-data-table>
    `;
  }

  render() {
    if (this.route.create && this.canManage) return this.renderCreateForm();

    if (this.route.id) {
      return html`
        <versola-user-record
          .userId=${this.route.id}
          .tenantId=${this.tenantId}
          .canManage=${this.canManage}
          .canRevealPassword=${this.canRevealPassword}
          .edit=${this.route.edit}
          @user-deleted=${this.handleUserDeleted}
        ></versola-user-record>`;
    }

    return html`
      <content-header title="Users" description="Search for a user to see their devices, roles and claims">
        ${this.canManage ? html`<button slot="actions" class="btn btn-primary"
          @click=${() => dispatchRecordRoute(this, { id: null, edit: null, create: true })}>+ Create User</button>` : nothing}
      </content-header>

      ${this.deletedLabel ? html`<div class="banner ok" role="status"><div class="banner-text">Deleted ${this.deletedLabel}.</div>
        <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.deletedLabel = ''; }}>Dismiss</button></div>` : nothing}

      <form class="search-bar" role="search" @submit=${this.handleSearchSubmit}>
        <select aria-label="Search by" .value=${this.searchField}
          @change=${(e: Event) => { this.searchField = (e.target as HTMLSelectElement).value as UserSearchField; }}>
          <option value="login">Login</option>
          <option value="email">Email</option>
          <option value="phone">Phone</option>
          <option value="id">ID</option>
        </select>
        <input type="search" aria-label="Search users" placeholder="Search users…" .value=${this.searchQuery}
          @input=${(e: Event) => {
            this.searchQuery = (e.target as HTMLInputElement).value;
            this.searchField = this.detectSearchField(this.searchQuery);
          }} />
        <button type="submit" class="btn btn-secondary">Search</button>
      </form>

      ${!this.hasSearched ? html`
        <versola-empty-state heading="Search for a user" text="Enter a query above to find a user by login, email, phone or ID."></versola-empty-state>
      ` : this.isLoading ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
      : this.errorMessage ? html`
        <versola-error-card heading="Could not load users" .message=${this.errorMessage} @retry=${() => this.loadUsers()}></versola-error-card>
      ` : this.users.length === 0 ? html`
        <versola-empty-state heading="No users found" text="Adjust your search or create a new user."></versola-empty-state>
      ` : this.renderResults()}
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-users-list': VersolaUsersList;
  }
}
