import { LitElement, html, css, nothing, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { theme } from '../styles/theme';
import { bannerStyles, buttonStyles, cardStyles, chipStyles, editorStyles, formStyles, iconButtonStyles, recordStyles, tagStyles } from '../styles/components';
import type {
  ChallengeSettingsRecord, EmailRegistration, EmailRegistrationMode, JwksKeySummary, MtlsCertificateEncoding, PasskeySettings, PhoneRegion, RateLimit, Role, SecurityProfile, SubmissionLimits,
} from '../types';
import { fetchChallengeSettings, fetchJwksKeys, getRoles, upsertChallengeSettings } from '../utils/central-api';
import { confirmDiscardDraft } from '../utils/draft-guard';
import { DURATION_UNITS, durationSeconds, formatDurationShort, splitDuration, type Duration } from '../utils/duration';
import { PHONE_REGION_PRESETS, appendPresets } from '../utils/phone-region-presets';
import { EMPTY_RECORD_ROUTE, dispatchRecordRoute, type RecordRoute } from '../utils/record-route';
import { isHttpUrl, validatePhoneRegions, validateRedirectUri, validateRegistrationDomains } from '../utils/validators';
import type { NavItem } from './navigation';
import { statusIcon } from './status-icon';
import './content-header';
import './empty-state';
import './error-card';
import './loading-cards';
import './record-card';
import './record-header';
import './role-picker';

export type SecurityGroup = 'methods' | 'codes' | 'passkeys' | 'registration' | 'stepup' | 'sessions' | 'tokens' | 'logout' | 'limits' | 'branding';

/** The cards of the page in reading order, under the three headings of the rail. */
const SECTIONS: Array<{ heading: string; groups: Array<{ id: SecurityGroup; label: string; title: string }> }> = [
  { heading: 'Sign-in', groups: [
    { id: 'methods', label: 'Sign-in methods', title: 'Sign-in methods' },
    { id: 'codes', label: 'One-time codes', title: 'One-time codes' },
    { id: 'passkeys', label: 'Passkeys', title: 'Passkeys (WebAuthn)' },
    { id: 'registration', label: 'Registration', title: 'Registration' },
    { id: 'stepup', label: 'Step-up (ACR)', title: 'Step-up (ACR vocabulary)' },
    { id: 'branding', label: 'Branding', title: 'Branding' },
  ] },
  { heading: 'Sessions & tokens', groups: [
    { id: 'sessions', label: 'Sessions', title: 'Sessions' },
    { id: 'tokens', label: 'Tokens & keys', title: 'Tokens & keys' },
    { id: 'logout', label: 'Logout', title: 'Logout' },
  ] },
  { heading: 'Protection', groups: [
    { id: 'limits', label: 'Submission limits', title: 'Submission limits' },
  ] },
];
const GROUPS = SECTIONS.flatMap(section => section.groups);
const isGroup = (value: string | null): value is SecurityGroup => GROUPS.some(group => group.id === value);

const EMAIL_MODE_LABELS: Record<EmailRegistrationMode, string> = { closed: 'Closed', open: 'Open', allowlist: 'Allowlist' };
const KNOWN_IP_HEADERS = ['X-Real-IP', 'X-Forwarded-For'];
const MTLS_PRESETS: Array<{ value: string; header: string; encoding: MtlsCertificateEncoding; label: string }> = [
  { value: 'nginx', header: 'ssl-client-cert', encoding: 'urlEncodedPem', label: 'ssl-client-cert (nginx / ingress-nginx)' },
  { value: 'traefik', header: 'X-Forwarded-Tls-Client-Cert', encoding: 'base64Der', label: 'X-Forwarded-Tls-Client-Cert (Traefik)' },
];
const LIMIT_ACTIONS: Array<{ id: keyof Omit<SubmissionLimits, 'banDurationSeconds'>; label: string }> = [
  { id: 'otpRequest', label: 'OTP request' },
  { id: 'otpSubmit', label: 'OTP submit' },
  { id: 'passwordSubmit', label: 'Password submit' },
  { id: 'passkeyAssertion', label: 'Passkey assertion' },
];
const ACR_FACTORS = ['otp', 'password', 'passkey'] as const;

/** What a tenant that has never saved settings gets: the values central applies on its own. */
function defaultSettings(tenantId: string): ChallengeSettingsRecord {
  return {
    tenantId,
    phoneRegions: [],
    emailRegistration: { mode: 'closed', domains: [], blockSubaddress: false },
    defaultRoles: ['user'],
    submissionLimits: { otpRequest: [], otpSubmit: [], passwordSubmit: [], passkeyAssertion: [], banDurationSeconds: 0 },
    otpLength: 6,
    otpResendAfter: 60,
    passkeySettings: null,
    authConversationTtlSeconds: 900,
    sessionTtlSeconds: 86400,
    sessionIdleTtlSeconds: null,
    userAgentTtlSeconds: 15552000,
    ipHeader: 'X-Real-IP',
    acrVocabulary: {},
    postLogoutRedirectUris: [],
    requireDpopNonce: false,
    mtlsCertificateHeader: null,
    mtlsCertificateEncoding: null,
    signingKeyId: null,
    clientAssertionMaxLifetimeSeconds: 300,
    logoLight: null,
    logoDark: null,
  };
}

/** Central may leave an optional member out of the record; the console reads the defaults in. */
function normalize(record: ChallengeSettingsRecord): ChallengeSettingsRecord {
  const defaults = defaultSettings(record.tenantId);
  return {
    ...defaults,
    ...record,
    emailRegistration: record.emailRegistration ?? defaults.emailRegistration,
    defaultRoles: record.defaultRoles?.length ? record.defaultRoles : defaults.defaultRoles,
    submissionLimits: { ...defaults.submissionLimits, ...record.submissionLimits },
    acrVocabulary: record.acrVocabulary ?? {},
    postLogoutRedirectUris: record.postLogoutRedirectUris ?? [],
    ipHeader: record.ipHeader || defaults.ipHeader,
    clientAssertionMaxLifetimeSeconds: record.clientAssertionMaxLifetimeSeconds ?? defaults.clientAssertionMaxLifetimeSeconds,
  };
}

type Entry = { key: number; value: string };
type AcrRow = { key: number; acr: string; factors: string[] };
type RegionRow = PhoneRegion & { key: number };

/**
 * The tenant's sign-in and security settings: one record, read as anchored cards (one per group,
 * each with its own Edit) under a section rail, edited one group at a time on its own page. The
 * record is a whole-record PUT, so a group's Save sends the other groups' current values back.
 */
@customElement('versola-security-settings')
export class VersolaSecuritySettings extends LitElement {
  @property({ type: String }) tenantId: string | null = null;
  @property({ type: Boolean }) canManage = false;
  @property({ attribute: false }) route: RecordRoute = EMPTY_RECORD_ROUTE;

  @state() private settings: ChallengeSettingsRecord | null = null;
  @state() private profile: SecurityProfile = 'fapi2';
  @state() private roles: Role[] = [];
  @state() private keys: JwksKeySummary[] = [];
  @state() private isLoading = false;
  @state() private loaded = false;
  @state() private errorMessage = '';
  @state() private activeAnchor: SecurityGroup = 'methods';

  // The draft of the open group editor.
  @state() private draft: ChallengeSettingsRecord | null = null;
  @state() private regions: RegionRow[] = [];
  @state() private regionErrors: Array<string | undefined> = [];
  @state() private presetsOpen = false;
  @state() private presetSearch = '';
  @state() private presetChosen = new Set<string>();
  @state() private domainsText = '';
  @state() private origins: Entry[] = [];
  @state() private uris: Entry[] = [];
  @state() private acrRows: AcrRow[] = [];
  @state() private mtlsEnabled = false;
  @state() private idleEnabled = false;
  @state() private durations: Record<string, Duration> = {};
  @state() private fieldErrors: Record<string, string> = {};
  @state() private formError = '';
  @state() private isSaving = false;
  private entryKey = 0;
  private draftKey = '';
  private draftBaseline = '';
  private loadRequestId = 0;

  static styles = [
    theme,
    buttonStyles,
    cardStyles,
    chipStyles,
    tagStyles,
    bannerStyles,
    formStyles,
    editorStyles,
    iconButtonStyles,
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

      .settings {
        display: grid;
        grid-template-columns: minmax(0, 1fr);
        gap: var(--spacing-lg);
        align-items: start;
      }

      .rail {
        display: none;
      }

      .rail-select {
        max-width: 22.8rem;
        margin-bottom: var(--spacing-lg);
      }

      .rail-select label {
        margin-bottom: 0.25rem;
      }

      @media (min-width: 1200px) {
        .settings {
          grid-template-columns: 12rem minmax(0, 1fr);
        }

        .rail {
          display: block;
          position: sticky;
          top: var(--spacing-lg);
        }

        .rail-select {
          display: none;
        }
      }

      .rail-heading {
        margin: var(--spacing-md) 0 0.25rem;
        font-size: var(--text-xs);
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--text-secondary);
      }

      .rail-heading:first-child {
        margin-top: 0;
      }

      /* The rail's items are the sidebar's nav items: a neutral fill on hover, the accent fill with
         a 3px bar on the left edge for the section in view; the weight never changes. */
      .rail a {
        display: flex;
        align-items: center;
        padding: 0.375rem 0.75rem;
        margin-bottom: 0.125rem;
        border-radius: var(--radius-md);
        color: var(--text-secondary);
        font-size: var(--text-base);
        font-weight: 500;
        line-height: 1.5;
        text-decoration: none;
        min-height: 1.5rem;
        transition: background var(--transition-fast), color var(--transition-fast);
      }

      .rail a:hover {
        background: var(--sidebar-hover-bg);
        color: var(--text-primary);
      }

      .rail a[aria-current='true'],
      .rail a[aria-current='true']:hover {
        background: var(--accent-subtle);
        color: var(--accent-text);
        box-shadow: inset 3px 0 0 var(--accent);
        /* Square the left corners so the bar is a bar, not a curved sliver. */
        border-radius: 0 var(--radius-md) var(--radius-md) 0;
      }

      .rail a:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: -2px;
      }

      .cards versola-record-card {
        scroll-margin-top: var(--spacing-lg);
      }

      /* The editor page: a title, one card at a readable width, the action row. */
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

      .locked {
        display: flex;
        gap: 0.375rem;
        align-items: center;
        color: var(--text-secondary);
        font-size: var(--text-sm);
      }

      .duration {
        display: grid;
        grid-template-columns: minmax(0, 1fr) 8rem;
        gap: 0.5rem;
        max-width: 22.8rem;
      }

      .check-row {
        display: flex;
        gap: 0.5rem;
        align-items: center;
        margin: 0;
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
        margin: 0;
        accent-color: var(--accent);
      }

      .check-row input:focus-visible {
        outline: 2px solid var(--focus-ring);
        outline-offset: 2px;
      }

      .entry-rows {
        display: grid;
        gap: 0.5rem;
        margin-bottom: 0.75rem;
      }

      .entry-row {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto;
        gap: 0.5rem;
        align-items: start;
        max-width: 28rem;
      }

      .entry-row .icon-button {
        margin-top: 0.5rem;
      }

      .entry-row .icon-button:hover {
        color: var(--danger);
      }

      .region-rows {
        display: grid;
        gap: 0.5rem;
        margin-bottom: 0.75rem;
      }

      .region-row {
        display: grid;
        grid-template-columns: 4.5rem 5.5rem 5rem minmax(7rem, 1fr) auto auto;
        gap: 0.5rem;
        align-items: center;
      }

      .region-head {
        font-size: var(--text-xs);
        color: var(--text-secondary);
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .region-row .error-message {
        grid-column: 1 / -1;
        margin: 0;
      }

      .region-actions {
        display: flex;
        gap: 0.25rem;
      }

      .region-actions .icon-button:hover {
        color: var(--danger);
      }

      @media (max-width: 720px) {
        .region-row {
          grid-template-columns: 1fr 1fr;
        }

        .region-head {
          display: none;
        }
      }

      .preset-picker {
        border: 1px solid var(--border-input);
        border-radius: var(--radius-md);
        background: var(--surface-raised);
        box-shadow: var(--shadow-lg);
        padding: var(--spacing-md);
        margin-bottom: var(--spacing-md);
      }

      .preset-list {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(17rem, 1fr));
        gap: 0.25rem var(--spacing-md);
        max-height: 16rem;
        overflow-y: auto;
        margin: 0.5rem 0 var(--spacing-md);
      }

      .preset-item {
        display: flex;
        gap: 0.5rem;
        align-items: center;
        padding: 0.25rem 0;
        min-height: 1.5rem;
        font-size: var(--text-sm);
        color: var(--text-primary);
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
        margin: 0;
        cursor: pointer;
      }

      .preset-item input {
        width: 1rem;
        height: 1rem;
        margin: 0;
        accent-color: var(--accent);
        flex: none;
      }

      .preset-item .iso {
        font-family: var(--font-mono);
        font-weight: 600;
        color: var(--accent);
      }

      .preset-item .meta {
        white-space: nowrap;
        color: var(--text-secondary);
        font-size: var(--text-xs);
      }

      .preset-item.present {
        color: var(--text-muted);
        cursor: default;
      }

      .preset-actions,
      .button-row {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
      }

      .preset-actions {
        justify-content: flex-end;
      }

      .limit-row {
        display: grid;
        grid-template-columns: 6rem 6rem auto;
        gap: 0.5rem;
        align-items: center;
        margin-bottom: 0.5rem;
      }

      .limit-row .icon-button:hover {
        color: var(--danger);
      }

      .acr-row {
        display: grid;
        grid-template-columns: minmax(0, 1fr) auto auto;
        gap: 0.5rem var(--spacing-md);
        align-items: center;
        padding: 0.5rem 0;
        border-bottom: 1px solid var(--border-subtle);
      }

      .acr-row .error-message {
        grid-column: 1 / -1;
        margin: 0;
      }

      .acr-factors {
        display: flex;
        gap: var(--spacing-md);
        flex-wrap: wrap;
      }

      .acr-row .icon-button:hover {
        color: var(--danger);
      }

      @media (max-width: 720px) {
        .acr-row {
          grid-template-columns: minmax(0, 1fr) auto;
        }

        .acr-factors {
          grid-column: 1 / -1;
        }
      }

      .logo-previews {
        display: flex;
        gap: var(--spacing-md);
        flex-wrap: wrap;
        margin-top: var(--spacing-md);
      }

      /* The logo is shown bare on the login page, so each file is previewed on the login forms'
         sheet of the scheme it is made for (the forms' page colours, not the console's). */
      .logo-swatch {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 11rem;
        height: 4.5rem;
        padding: 0.75rem;
        border: 1px solid var(--border-default);
        border-radius: var(--radius-md);
        box-sizing: border-box;
      }

      .logo-swatch.light {
        background: var(--logo-swatch-light);
      }

      .logo-swatch.dark {
        background: var(--logo-swatch-dark);
      }

      .logo-swatch img {
        max-height: 100%;
        max-width: 100%;
        object-fit: contain;
      }
    `,
  ];

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('scroll', this.handleScroll, { passive: true });
  }

  disconnectedCallback() {
    window.removeEventListener('scroll', this.handleScroll);
    super.disconnectedCallback();
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('tenantId')) void this.loadData();
    if (changed.has('route') || changed.has('settings') || changed.has('loaded')) {
      const group = this.editGroup;
      const draftKey = group ? `${this.tenantId}:${group}:${this.loaded}` : '';
      if (draftKey !== this.draftKey) {
        this.draftKey = draftKey;
        if (group) this.resetDraft();
      }
    }
    if (changed.has('loaded') && this.loaded && window.location.hash) {
      const anchor = window.location.hash.slice(1);
      if (isGroup(anchor)) void this.updateComplete.then(() => this.scrollToGroup(anchor, false));
    }
  }

  private async loadData() {
    if (!this.tenantId) {
      this.settings = null;
      this.loaded = false;
      return;
    }
    const requestId = ++this.loadRequestId;
    this.isLoading = true;
    this.loaded = false;
    this.errorMessage = '';
    try {
      const [settings, roles, keys] = await Promise.all([
        fetchChallengeSettings(this.tenantId),
        getRoles(this.tenantId).catch(() => [] as Role[]),
        fetchJwksKeys().catch(() => [] as JwksKeySummary[]),
      ]);
      if (requestId !== this.loadRequestId) return;
      // A tenant with no settings yet is created on fapi2, so it is treated as one.
      this.profile = settings?.securityProfile ?? 'fapi2';
      this.settings = settings ? normalize(settings) : null;
      this.roles = roles;
      this.keys = keys;
      this.loaded = true;
    } catch (error) {
      if (requestId !== this.loadRequestId) return;
      this.errorMessage = error instanceof Error ? error.message : 'Failed to load security settings';
    } finally {
      if (requestId === this.loadRequestId) this.isLoading = false;
    }
  }

  // ── routing ──

  private get editGroup(): SecurityGroup | null {
    return this.canManage && isGroup(this.route.edit) ? this.route.edit : null;
  }

  private openEditor(group: SecurityGroup) {
    dispatchRecordRoute(this, { id: null, edit: group, create: false, layout: null });
  }

  private async cancelEditor() {
    if (this.draftSnapshot !== this.draftBaseline && !await confirmDiscardDraft()) return;
    dispatchRecordRoute(this, { edit: null });
  }

  private readonly handleScroll = () => {
    if (this.editGroup || !this.settings) return;
    const cards = Array.from(this.renderRoot.querySelectorAll<HTMLElement>('.cards versola-record-card[id]'));
    const line = 120;
    let current: SecurityGroup = 'methods';
    for (const card of cards) {
      if (card.getBoundingClientRect().top <= line) current = card.id as SecurityGroup;
    }
    if (current !== this.activeAnchor) this.activeAnchor = current;
  };

  /** Anchors live inside this shadow root, so the browser's own `#id` jump cannot reach them. */
  private scrollToGroup(group: SecurityGroup, pushHash = true) {
    const card = this.renderRoot.querySelector<HTMLElement>(`.cards versola-record-card[id="${group}"]`);
    if (!card) return;
    card.scrollIntoView({ block: 'start' });
    this.activeAnchor = group;
    if (pushHash) window.history.replaceState({}, '', `${window.location.pathname}${window.location.search}#${group}`);
  }

  private navigateTo(view: NavItem, id?: string) {
    this.dispatchEvent(new CustomEvent('navigate-view', { detail: { view, id }, bubbles: true, composed: true }));
  }

  // ── draft ──

  private entry(value = ''): Entry {
    return { key: ++this.entryKey, value };
  }

  private get draftSnapshot(): string {
    return JSON.stringify([this.draft, this.regions, this.domainsText, this.origins, this.uris, this.acrRows, this.mtlsEnabled, this.idleEnabled, this.durations]);
  }

  private resetDraft() {
    const base = this.settings ?? defaultSettings(this.tenantId ?? '');
    const draft: ChallengeSettingsRecord = JSON.parse(JSON.stringify(base)) as ChallengeSettingsRecord;
    this.draft = draft;
    this.regions = draft.phoneRegions.map(region => ({ ...region, key: ++this.entryKey }));
    this.regionErrors = [];
    this.presetsOpen = false;
    this.domainsText = (draft.emailRegistration?.domains ?? []).join('\n');
    this.origins = (draft.passkeySettings?.origins ?? []).map(value => this.entry(value));
    this.uris = draft.postLogoutRedirectUris.map(value => this.entry(value));
    this.acrRows = Object.entries(draft.acrVocabulary ?? {}).map(([acr, factors]) => ({ key: ++this.entryKey, acr, factors: [...factors] }));
    this.mtlsEnabled = draft.mtlsCertificateHeader != null;
    this.idleEnabled = draft.sessionIdleTtlSeconds != null;
    this.durations = {
      otpResendAfter: splitDuration(draft.otpResendAfter),
      authConversationTtlSeconds: splitDuration(draft.authConversationTtlSeconds),
      sessionTtlSeconds: splitDuration(draft.sessionTtlSeconds),
      sessionIdleTtlSeconds: splitDuration(draft.sessionIdleTtlSeconds ?? 7200),
      userAgentTtlSeconds: splitDuration(draft.userAgentTtlSeconds),
      banDurationSeconds: splitDuration(draft.submissionLimits.banDurationSeconds),
      clientAssertionMaxLifetimeSeconds: splitDuration(draft.clientAssertionMaxLifetimeSeconds ?? 300),
    };
    this.fieldErrors = {};
    this.formError = '';
    this.isSaving = false;
    this.draftBaseline = this.draftSnapshot;
  }

  private patch(changes: Partial<ChallengeSettingsRecord>) {
    if (this.draft) this.draft = { ...this.draft, ...changes };
  }

  private setDuration(id: string, changes: Partial<Duration>) {
    this.durations = { ...this.durations, [id]: { ...this.durations[id], ...changes } };
  }

  private seconds(id: string): number {
    return durationSeconds(this.durations[id] ?? { value: 0, unit: 'seconds' });
  }

  // ── validation and save ──

  /** The record a Save sends: the current record with the open group's draft merged in. */
  private buildNext(group: SecurityGroup): { next: ChallengeSettingsRecord | null; errors: Record<string, string> } {
    const draft = this.draft!;
    const base = this.settings ?? defaultSettings(this.tenantId ?? '');
    const next: ChallengeSettingsRecord = { ...base, tenantId: this.tenantId ?? base.tenantId };
    const errors: Record<string, string> = {};

    switch (group) {
      case 'methods': {
        const regions = this.regions.map(row => ({
          region: row.region.trim().toUpperCase(), dialCode: row.dialCode.trim(), length: Number(row.length), regex: row.regex.trim(), registration: row.registration === true,
        }));
        const regionErrors = validatePhoneRegions(regions);
        this.regionErrors = regionErrors;
        if (regionErrors.some(error => error !== undefined)) errors.regions = 'Fix the highlighted phone regions.';
        const ipHeader = draft.ipHeader.trim();
        if (!ipHeader) errors.ipHeader = 'The client IP header is required.';
        next.phoneRegions = regions;
        next.ipHeader = ipHeader;
        break;
      }
      case 'codes': {
        if (!Number.isInteger(draft.otpLength) || draft.otpLength < 4 || draft.otpLength > 10) errors.otpLength = 'Between 4 and 10 digits.';
        const resend = this.seconds('otpResendAfter');
        if (resend < 1) errors.otpResendAfter = 'At least one second.';
        next.otpLength = draft.otpLength;
        next.otpResendAfter = resend;
        break;
      }
      case 'passkeys': {
        const rpId = (draft.passkeySettings?.rpId ?? '').trim();
        const rpName = (draft.passkeySettings?.rpName ?? '').trim();
        const origins = this.origins.map(entry => entry.value.trim()).filter(Boolean);
        if (!rpId) errors.rpId = 'The relying party ID is required.';
        if (!rpName) errors.rpName = 'The relying party name is required.';
        if (origins.length === 0) errors.origins = 'At least one origin is required.';
        else if (origins.some(origin => !isHttpUrl(origin))) errors.origins = 'Every origin is an absolute http(s) URL.';
        next.passkeySettings = { rpId, rpName, origins, userVerification: draft.passkeySettings?.userVerification ?? 'preferred' };
        break;
      }
      case 'registration': {
        const mode = draft.emailRegistration?.mode ?? 'closed';
        const domains = this.domainsText.split('\n').map(domain => domain.trim()).filter(Boolean);
        const domainsError = validateRegistrationDomains(domains);
        // In closed mode the list is ignored by central, so an invalid draft must not block the
        // save: the stored list goes back unchanged instead.
        if (mode !== 'closed' && domainsError) errors.domains = domainsError;
        const defaultRoles = [...new Set(draft.defaultRoles ?? [])];
        if (defaultRoles.length === 0) errors.defaultRoles = 'Select at least one default role.';
        next.emailRegistration = {
          mode,
          domains: mode === 'closed' && domainsError ? [...(base.emailRegistration?.domains ?? [])] : domains,
          blockSubaddress: draft.emailRegistration?.blockSubaddress ?? false,
        };
        next.defaultRoles = defaultRoles;
        break;
      }
      case 'stepup': {
        const vocabulary: Record<string, string[]> = {};
        for (const row of this.acrRows) {
          const acr = row.acr.trim();
          if (!acr) errors[`acr-${row.key}`] = 'An acr value is required.';
          else if (vocabulary[acr]) errors[`acr-${row.key}`] = 'This acr value is listed twice.';
          else if (row.factors.length === 0) errors[`acr-${row.key}`] = 'Pick at least one factor.';
          else vocabulary[acr] = [...row.factors];
        }
        next.acrVocabulary = vocabulary;
        break;
      }
      case 'sessions': {
        const conversation = this.seconds('authConversationTtlSeconds');
        const sso = this.seconds('sessionTtlSeconds');
        const idle = this.idleEnabled ? this.seconds('sessionIdleTtlSeconds') : null;
        const userAgent = this.seconds('userAgentTtlSeconds');
        if (conversation < 1) errors.authConversationTtlSeconds = 'At least one second.';
        if (sso < 1) errors.sessionTtlSeconds = 'At least one second.';
        if (idle !== null && idle < 1) errors.sessionIdleTtlSeconds = 'At least one second.';
        else if (idle !== null && idle >= sso) errors.sessionIdleTtlSeconds = 'Must be shorter than the SSO session.';
        if (userAgent < 1) errors.userAgentTtlSeconds = 'At least one second.';
        next.authConversationTtlSeconds = conversation;
        next.sessionTtlSeconds = sso;
        next.sessionIdleTtlSeconds = idle;
        next.userAgentTtlSeconds = userAgent;
        break;
      }
      case 'tokens': {
        const lifetime = this.seconds('clientAssertionMaxLifetimeSeconds');
        if (lifetime < 30 || lifetime > 900) errors.clientAssertionMaxLifetimeSeconds = 'Between 30 seconds and 15 minutes.';
        const header = this.mtlsEnabled ? (draft.mtlsCertificateHeader ?? '').trim() : null;
        if (this.mtlsEnabled && !header) errors.mtlsCertificateHeader = 'The certificate header is required when mutual TLS is terminated.';
        next.signingKeyId = draft.signingKeyId ?? null;
        next.requireDpopNonce = draft.requireDpopNonce;
        next.mtlsCertificateHeader = header;
        next.mtlsCertificateEncoding = this.mtlsEnabled ? draft.mtlsCertificateEncoding ?? 'urlEncodedPem' : null;
        next.clientAssertionMaxLifetimeSeconds = lifetime;
        break;
      }
      case 'logout': {
        const uris = this.uris.map(entry => entry.value.trim()).filter(Boolean);
        for (const entry of this.uris) {
          const uri = entry.value.trim();
          if (!uri) continue;
          const validation = validateRedirectUri(uri);
          if (!validation.valid) errors[`uri-${entry.key}`] = validation.error ?? 'Invalid URI.';
        }
        next.postLogoutRedirectUris = uris;
        break;
      }
      case 'limits': {
        const limits = draft.submissionLimits;
        for (const action of LIMIT_ACTIONS) {
          if (limits[action.id].some(limit => limit.maxAttempts < 1 || limit.windowSeconds < 1)) errors[action.id] = 'Attempts and the window are at least 1.';
        }
        next.submissionLimits = { ...limits, banDurationSeconds: this.seconds('banDurationSeconds') };
        break;
      }
      case 'branding': {
        const light = (draft.logoLight ?? '').trim();
        const dark = (draft.logoDark ?? '').trim();
        if (!light !== !dark) errors.logo = 'The logo needs both a light and a dark file, or neither.';
        else if ((light && !isHttpUrl(light)) || (dark && !isHttpUrl(dark))) errors.logo = 'Each file is an absolute http(s) URL.';
        next.logoLight = light || null;
        next.logoDark = dark || null;
        break;
      }
    }
    return { next: Object.keys(errors).length ? null : next, errors };
  }

  private get passkeysMissing(): boolean {
    return !this.settings?.passkeySettings && this.editGroup !== 'passkeys';
  }

  private async handleSubmit(event: Event) {
    event.preventDefault();
    const group = this.editGroup;
    if (!group || !this.draft || !this.tenantId || this.isSaving || this.passkeysMissing) return;
    const { next, errors } = this.buildNext(group);
    this.fieldErrors = errors;
    if (!next) {
      this.formError = 'Fix the highlighted fields.';
      return;
    }
    this.isSaving = true;
    this.formError = '';
    try {
      await upsertChallengeSettings(next);
      this.settings = next;
      this.draftBaseline = this.draftSnapshot;
      dispatchRecordRoute(this, { edit: null });
      void this.updateComplete.then(() => this.scrollToGroup(group));
    } catch (error) {
      this.formError = error instanceof Error ? error.message : 'Failed to save security settings';
    } finally {
      this.isSaving = false;
    }
  }

  // ── view ──

  private row(label: unknown, value: unknown, fixed = '') {
    return html`<versola-record-row .label=${label} .fixed=${fixed} .value=${value}></versola-record-row>`;
  }

  private duration(seconds: number) {
    return html`<span title=${`${seconds} seconds`}>${formatDurationShort(seconds)}</span>`;
  }

  private tags(values: string[], empty: string) {
    return values.length ? html`<div class="tag-list">${values.map(value => html`<span class="tag">${value}</span>`)}</div>` : html`<span class="muted">${empty}</span>`;
  }

  private card(group: SecurityGroup, subheading: string, body: unknown, options: { editDisabled?: boolean } = {}) {
    const meta = GROUPS.find(candidate => candidate.id === group)!;
    return html`
      <versola-record-card id=${group} heading=${meta.title} subheading=${subheading} ?editable=${this.canManage} ?editDisabled=${!!options.editDisabled}
        .body=${body} @edit=${() => this.openEditor(group)}></versola-record-card>
    `;
  }

  private renderCards(settings: ChallengeSettingsRecord) {
    const registration = settings.emailRegistration!;
    const signupRegions = settings.phoneRegions.filter(region => region.registration).map(region => region.region);
    const signingKey = settings.signingKeyId ? this.keys.find(key => key.kid === settings.signingKeyId) : null;
    const limits = settings.submissionLimits;
    return html`
      ${this.card('methods', 'Which identifiers a user may sign in with on this tenant.', html`
        ${this.row('Phone numbers', settings.phoneRegions.length ? html`
          <div class="tag-list">
            ${settings.phoneRegions.map(region => html`<span class="tag" title=${`Regex ${region.regex}`}>${region.region} ${region.dialCode} · ${region.length} digits</span>`)}
            ${signupRegions.length ? html`<span class="tag quiet">sign-up: ${signupRegions.join(', ')}</span>` : ''}
          </div>` : html`<span class="muted">Any E.164 number is accepted; nobody can register by phone.</span>`)}
        ${this.row('Email', `Accepted; registration is ${EMAIL_MODE_LABELS[registration.mode].toLowerCase()} (see Registration)`)}
        ${this.row('Client IP header', html`<span class="mono">${settings.ipHeader}</span>`)}
      `)}

      ${this.card('codes', 'SMS and email codes; the message text lives under OTP templates.', html`
        ${this.row('Code length', `${settings.otpLength} digits`)}
        ${this.row('Resend after', this.duration(settings.otpResendAfter))}
      `)}

      ${this.card('passkeys', '', settings.passkeySettings ? html`
        ${this.row('Relying party', html`<span class="mono">${settings.passkeySettings.rpId}</span> · ${settings.passkeySettings.rpName}`)}
        ${this.row('User verification', settings.passkeySettings.userVerification.charAt(0).toUpperCase() + settings.passkeySettings.userVerification.slice(1))}
        ${this.row('Allowed origins', this.tags(settings.passkeySettings.origins, 'None'))}
      ` : html`<div class="record-row"><div class="record-label"></div><div class="record-value muted">Passkeys are not configured for this tenant. Every save needs them: set them up first.</div></div>`)}

      ${this.card('registration', 'Who may create an account and what they get.', html`
        ${this.row('Email registration', EMAIL_MODE_LABELS[registration.mode])}
        ${registration.mode === 'closed' ? '' : this.row(registration.mode === 'open' ? 'Blocked domains' : 'Allowed domains',
          this.tags(registration.domains, registration.mode === 'open' ? 'No domains blocked.' : 'No domains listed, so no address can register.'))}
        ${this.row('Sub-addresses', registration.blockSubaddress
          ? html`<span class="mono">a+tag@example.com</span> is refused (+ = # are blocked)`
          : html`<span class="mono">a+tag@example.com</span> is the same account as <span class="mono">a@example.com</span>`)}
        ${this.row('Default roles', html`<div class="tag-list">${(settings.defaultRoles ?? []).map(role => html`
          <button type="button" class="tag" @click=${() => this.navigateTo('roles', role)}>${role}</button>`)}</div>`)}
      `)}

      ${this.card('stepup', 'What each acr value a client may request means.', Object.keys(settings.acrVocabulary ?? {}).length
        ? Object.entries(settings.acrVocabulary ?? {}).map(([acr, factors]) => this.row(html`<span class="mono">${acr}</span>`, this.tags(factors, '—')))
        : html`<div class="record-row"><div class="record-label"></div><div class="record-value muted">No ACR vocabulary configured.</div></div>`)}

      ${this.card('branding', 'The tenant\'s logo above the sign-in card and as the favicon of its login pages.', settings.logoLight && settings.logoDark ? html`
        ${this.row('Light scheme', html`<a href=${settings.logoLight} target="_blank" rel="noreferrer">${settings.logoLight}</a>`)}
        ${this.row('Dark scheme', html`<a href=${settings.logoDark} target="_blank" rel="noreferrer">${settings.logoDark}</a>`)}
        ${this.row('', html`<div class="logo-previews">${this.renderLogoPreview('light', settings.logoLight)}${this.renderLogoPreview('dark', settings.logoDark)}</div>`)}
      ` : html`<div class="record-row"><div class="record-label"></div><div class="record-value muted">Not set. The login pages of this tenant show the Versola wordmark.</div></div>`)}

      ${this.card('sessions', 'How long a device stays signed in.', html`
        ${this.row('Sign-in conversation', this.duration(settings.authConversationTtlSeconds))}
        ${this.row('SSO session', this.duration(settings.sessionTtlSeconds))}
        ${this.row('Idle timeout', settings.sessionIdleTtlSeconds != null ? this.duration(settings.sessionIdleTtlSeconds) : 'Disabled')}
        ${this.row('Remembered device', this.duration(settings.userAgentTtlSeconds))}
      `)}

      ${this.card('tokens', 'How tokens are signed and bound.', html`
        ${this.row('Signing key', settings.signingKeyId
          ? html`<button type="button" class="tag" @click=${() => this.navigateTo('keys', settings.signingKeyId!)}>${settings.signingKeyId}</button>
              ${signingKey?.algorithm ? html` · ${signingKey.algorithm}` : signingKey === undefined ? html` <span class="chip warn">Missing</span>` : ''}`
          : html`<span class="muted">No key selected: auth signs with its own configured private key.</span>`)}
        ${this.row('DPoP nonce', settings.requireDpopNonce ? 'Required on every proof' : 'Not required')}
        ${this.row('Mutual TLS', settings.mtlsCertificateHeader
          ? html`Certificate in <span class="mono">${settings.mtlsCertificateHeader}</span> · ${settings.mtlsCertificateEncoding === 'base64Der' ? 'Base64-encoded DER' : 'URL-encoded PEM'}`
          : 'Not terminated by the reverse proxy')}
        ${this.row('Client assertion lifetime', this.duration(settings.clientAssertionMaxLifetimeSeconds ?? 300))}
      `)}

      ${this.card('logout', '', html`
        ${this.row('Post-logout redirect URIs', this.tags(settings.postLogoutRedirectUris, 'None: RPs cannot request a post_logout_redirect_uri.'))}
      `)}

      ${this.card('limits', 'Attempts allowed per user before a ban.', html`
        ${LIMIT_ACTIONS.map(action => this.row(action.label, limits[action.id].length
          ? limits[action.id].map((limit, index) => html`${index ? ', ' : ''}${limit.maxAttempts} per ${formatDurationShort(limit.windowSeconds)}`)
          : html`<span class="muted">No limit</span>`))}
        ${this.row('Ban duration', limits.banDurationSeconds > 0 ? this.duration(limits.banDurationSeconds) : html`<span class="muted">No ban</span>`)}
      `)}

      <versola-record-card id="profile" heading="Security profile" fixedLabel="Fixed" .body=${html`
        ${this.row('Profile', this.profile === 'fapi2'
          ? 'FAPI 2.0 · PAR, PKCE S256 and sender-constrained tokens are enforced for every client'
          : 'Standard · clients choose their own protections', 'Chosen when the tenant was created')}
      `}></versola-record-card>
    `;
  }

  private renderLogoPreview(scheme: 'light' | 'dark', url: string) {
    return html`
      <div class="logo-swatch ${scheme}" data-testid=${`logo-preview-${scheme}`}>
        ${url && isHttpUrl(url) ? html`<img src=${url} alt="" referrerpolicy="no-referrer" />` : nothing}
      </div>
    `;
  }

  private renderRail() {
    return html`
      <nav class="rail" aria-label="On this page">
        ${SECTIONS.map(section => html`
          <div class="rail-heading">${section.heading}</div>
          ${section.groups.map(group => html`
            <a href=${`#${group.id}`} aria-current=${this.activeAnchor === group.id ? 'true' : nothing}
              @click=${(e: Event) => { e.preventDefault(); this.scrollToGroup(group.id); }}>${group.label}</a>`)}
        `)}
        <a href="#profile" @click=${(e: Event) => { e.preventDefault(); this.renderRoot.querySelector('#profile')?.scrollIntoView({ block: 'start' }); }}>Security profile</a>
      </nav>
      <div class="rail-select">
        <label for="security-jump">On this page</label>
        <select id="security-jump" @change=${(e: Event) => this.scrollToGroup((e.target as HTMLSelectElement).value as SecurityGroup)}>
          ${GROUPS.map(group => html`<option value=${group.id}>${group.label}</option>`)}
        </select>
      </div>
    `;
  }

  private renderMeta() {
    return html`
      <span class="chip">${this.tenantId}</span>
      ${this.profile === 'fapi2'
        ? html`<span class="chip ok" title="Clients are held to the FAPI 2.0 security profile">${statusIcon('shield', 'icon', 14)}FAPI 2.0</span>`
        : html`<span class="chip">Standard</span>`}
    `;
  }

  private renderPage() {
    return html`
      <versola-record-header .heading=${'Sign-in and security'} .meta=${this.renderMeta()}></versola-record-header>
      ${this.isLoading || (!this.loaded && !this.errorMessage) ? html`<versola-loading-cards .count=${3}></versola-loading-cards>`
      : this.errorMessage ? html`<versola-error-card heading="Could not load the security settings" .message=${this.errorMessage} @retry=${() => this.loadData()}></versola-error-card>`
      : !this.settings ? html`
        <versola-empty-state heading="No security settings yet" text="Configure how users of this tenant sign in, how long they stay signed in and how tokens are issued.">
          ${this.canManage ? html`<button class="btn btn-primary" @click=${() => this.openEditor('passkeys')}>Set up settings</button>` : ''}
        </versola-empty-state>`
      : html`
        <div class="settings">
          ${this.renderRail()}
          <div class="cards">${this.renderCards(this.settings)}</div>
        </div>`}
    `;
  }

  // ── editor ──

  private error(id: string) {
    const message = this.fieldErrors[id];
    return message ? html`<div class="error-message" id=${`${id}-error`} role="alert">${message}</div>` : '';
  }

  private field(id: string, label: string, control: TemplateResult, hint = '') {
    return html`
      <div class="form-group">
        <label for=${id}>${label}</label>
        ${control}
        ${this.error(id) || (hint ? html`<div class="hint" id=${`${id}-hint`}>${hint}</div>` : '')}
      </div>
    `;
  }

  private textInput(id: string, value: string, onInput: (value: string) => void, options: { placeholder?: string; type?: string; mono?: boolean; max?: string } = {}) {
    const invalid = !!this.fieldErrors[id];
    return html`
      <input type=${options.type ?? 'text'} id=${id} class="compact-input ${options.mono ? 'mono' : ''} ${invalid ? 'input-error' : ''}" .value=${value}
        placeholder=${options.placeholder ?? nothing} aria-invalid=${invalid ? 'true' : nothing} aria-describedby=${invalid ? `${id}-error` : nothing}
        style=${options.max ? `max-width:${options.max}` : nothing}
        @input=${(e: Event) => onInput((e.target as HTMLInputElement).value)} />
    `;
  }

  private durationInput(id: string, label: string, hint = '') {
    const duration = this.durations[id] ?? { value: 0, unit: 'seconds' };
    const invalid = !!this.fieldErrors[id];
    return this.field(id, label, html`
      <div class="duration">
        <input type="number" id=${id} class="compact-input ${invalid ? 'input-error' : ''}" min="0" step="any" .value=${String(duration.value)}
          aria-invalid=${invalid ? 'true' : nothing} aria-describedby=${invalid ? `${id}-error` : `${id}-hint`}
          @input=${(e: Event) => this.setDuration(id, { value: Number((e.target as HTMLInputElement).value) })} />
        <select aria-label=${`${label} unit`} .value=${duration.unit} @change=${(e: Event) => this.setDuration(id, { unit: (e.target as HTMLSelectElement).value as Duration['unit'] })}>
          ${DURATION_UNITS.map(unit => html`<option value=${unit.id} ?selected=${unit.id === duration.unit}>${unit.label}</option>`)}
        </select>
      </div>
    `, hint || `Stored as ${this.seconds(id)} seconds.`);
  }

  private entryList(entries: Entry[], label: string, placeholder: string, onChange: (entries: Entry[]) => void, errorPrefix: string, addLabel: string) {
    return html`
      <div class="entry-rows">
        ${entries.map(entry => {
          const error = this.fieldErrors[`${errorPrefix}-${entry.key}`];
          return html`
            <div class="entry-row">
              <div>
                <input type="url" class="compact-input mono ${error ? 'input-error' : ''}" aria-label=${label} placeholder=${placeholder} .value=${entry.value}
                  aria-invalid=${error ? 'true' : nothing} aria-describedby=${error ? `${errorPrefix}-${entry.key}-error` : nothing}
                  @input=${(e: Event) => onChange(entries.map(other => other.key === entry.key ? { ...other, value: (e.target as HTMLInputElement).value } : other))} />
                ${error ? html`<div class="error-message" id=${`${errorPrefix}-${entry.key}-error`} role="alert">${error}</div>` : ''}
              </div>
              <button type="button" class="icon-button" aria-label=${`Remove ${label.toLowerCase()} ${entry.value || 'new'}`} title="Remove"
                @click=${() => onChange(entries.filter(other => other.key !== entry.key))}>${statusIcon('x', 'icon')}</button>
            </div>`;
        })}
      </div>
      <button type="button" class="btn btn-secondary btn-sm" @click=${() => onChange([...entries, this.entry()])}>${addLabel}</button>
    `;
  }

  private renderMethodsEditor() {
    const draft = this.draft!;
    const isCustom = !KNOWN_IP_HEADERS.includes(draft.ipHeader);
    return html`
      <div class="editor-box">
        <h3>Phone numbers</h3>
        <p class="editor-sub">Regions in the order the login form lists them: an ISO 3166-1 alpha-2 code, the dial code, the digits after it and a regex matched in full against them. Several regions may share a dial code; none listed accepts any E.164 number. Registration decides whether a number from that region may create an account.</p>
        ${this.regions.length ? html`
          <div class="region-rows">
            <div class="region-row region-head"><span>Region</span><span>Dial code</span><span>Length</span><span>Regex</span><span>Sign-up</span><span></span></div>
            ${this.regions.map((row, index) => html`
              <div class="region-row" data-testid="region-row">
                <input type="text" class="compact-input mono" aria-label="Region" .value=${row.region} placeholder="KZ" maxlength="2" @input=${(e: Event) => this.updateRegion(row.key, { region: (e.target as HTMLInputElement).value })} />
                <input type="text" class="compact-input mono" aria-label="Dial code" .value=${row.dialCode} placeholder="+7" @input=${(e: Event) => this.updateRegion(row.key, { dialCode: (e.target as HTMLInputElement).value })} />
                <input type="number" class="compact-input" aria-label="Length" .value=${String(row.length)} min="4" max="14" @input=${(e: Event) => this.updateRegion(row.key, { length: parseInt((e.target as HTMLInputElement).value) })} />
                <input type="text" class="compact-input mono" aria-label="Regex" .value=${row.regex} placeholder="7\\d{9}" @input=${(e: Event) => this.updateRegion(row.key, { regex: (e.target as HTMLInputElement).value })} />
                <label class="check-row"><input type="checkbox" aria-label="Registration" .checked=${row.registration === true} @change=${(e: Event) => this.updateRegion(row.key, { registration: (e.target as HTMLInputElement).checked })} /></label>
                <span class="region-actions">
                  <button type="button" class="icon-button" ?disabled=${index === 0} aria-label="Move up" title="Move up" @click=${() => this.moveRegion(index, -1)}>↑</button>
                  <button type="button" class="icon-button" ?disabled=${index === this.regions.length - 1} aria-label="Move down" title="Move down" @click=${() => this.moveRegion(index, 1)}>↓</button>
                  <button type="button" class="icon-button" aria-label="Remove row" title="Remove" @click=${() => { this.regions = this.regions.filter(other => other.key !== row.key); this.regionErrors = []; }}>${statusIcon('x', 'icon')}</button>
                </span>
                ${this.regionErrors[index] ? html`<div class="error-message region-error" role="alert">${this.regionErrors[index]}</div>` : ''}
              </div>`)}
          </div>` : html`<p class="hint">No regions: any E.164 number is accepted and nobody can register by phone.</p>`}
        ${this.presetsOpen ? this.renderPresetPicker() : ''}
        <div class="button-row">
          <button type="button" class="btn btn-secondary btn-sm" @click=${() => { this.regions = [...this.regions, { key: ++this.entryKey, region: '', dialCode: '', length: 10, regex: '', registration: false }]; this.regionErrors = []; }}>+ Add region</button>
          <button type="button" class="btn btn-secondary btn-sm" aria-haspopup="dialog" aria-expanded=${this.presetsOpen ? 'true' : 'false'}
            @click=${() => this.presetsOpen ? this.closePresets() : this.openPresets()}>Add from presets</button>
        </div>
      </div>
      <div class="editor-box">
        <h3>Client IP header</h3>
        <p class="editor-sub">The header the reverse proxy sets with the real client IP, used for IP-based throttling.</p>
        ${this.field('ip-header', 'Header', html`
          <select id="ip-header" class="compact-input" .value=${isCustom ? 'custom' : draft.ipHeader}
            @change=${(e: Event) => { const value = (e.target as HTMLSelectElement).value; this.patch({ ipHeader: value === 'custom' ? '' : value }); }}>
            <option value="X-Real-IP" ?selected=${draft.ipHeader === 'X-Real-IP'}>X-Real-IP (nginx)</option>
            <option value="X-Forwarded-For" ?selected=${draft.ipHeader === 'X-Forwarded-For'}>X-Forwarded-For (other proxies)</option>
            <option value="custom" ?selected=${isCustom}>Custom…</option>
          </select>
          ${isCustom ? html`<div style="margin-top: 0.5rem">${this.textInput('ipHeader', draft.ipHeader, value => this.patch({ ipHeader: value }), { placeholder: 'X-Client-IP', mono: true })}</div>` : ''}
        `)}
        ${this.error('ipHeader')}
      </div>
    `;
  }

  private updateRegion(key: number, changes: Partial<PhoneRegion>) {
    this.regions = this.regions.map(row => row.key === key ? { ...row, ...changes } : row);
  }

  private moveRegion(index: number, delta: -1 | 1) {
    const target = index + delta;
    if (target < 0 || target >= this.regions.length) return;
    const next = [...this.regions];
    [next[index], next[target]] = [next[target], next[index]];
    this.regions = next;
    this.regionErrors = [];
  }

  private openPresets() {
    this.presetChosen = new Set();
    this.presetSearch = '';
    this.presetsOpen = true;
  }

  private closePresets() {
    this.presetsOpen = false;
    this.presetSearch = '';
    this.presetChosen = new Set();
  }

  private confirmPresets() {
    const plain: PhoneRegion[] = this.regions.map(({ key: _key, ...region }) => region);
    this.regions = appendPresets(plain, this.presetChosen).map(region => ({ ...region, key: ++this.entryKey }));
    this.regionErrors = [];
    this.closePresets();
  }

  private renderPresetPicker() {
    const query = this.presetSearch.trim().toLowerCase();
    const present = new Set(this.regions.map(row => row.region.trim().toUpperCase()));
    const shown = PHONE_REGION_PRESETS.filter(preset => !query || preset.region.toLowerCase().includes(query) || preset.name.toLowerCase().includes(query) || preset.dialCode.includes(query));
    const count = this.presetChosen.size;
    return html`
      <div class="preset-picker" role="dialog" aria-label="Add from presets" data-testid="preset-picker"
        @keydown=${(e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); this.closePresets(); } }}>
        <h3 style="margin: 0 0 0.25rem; font-size: var(--text-base)">Add countries</h3>
        <p class="hint" style="margin: 0 0 0.5rem">Each is added with registration on; untick it per row afterwards. Patterns are loose on purpose: the real number is also checked against libphonenumber.</p>
        <input type="search" class="compact-input" aria-label="Search presets" placeholder="Search country, code or dial code"
          .value=${this.presetSearch} @input=${(e: Event) => this.presetSearch = (e.target as HTMLInputElement).value} />
        <div class="preset-list">
          ${shown.length === 0 ? html`<div class="hint">No country matches.</div>` : shown.map(preset => html`
            <label class="preset-item ${present.has(preset.region) ? 'present' : ''}">
              <input type="checkbox" aria-label=${preset.name} ?disabled=${present.has(preset.region)}
                .checked=${present.has(preset.region) || this.presetChosen.has(preset.region)}
                @change=${(e: Event) => { const next = new Set(this.presetChosen); if ((e.target as HTMLInputElement).checked) next.add(preset.region); else next.delete(preset.region); this.presetChosen = next; }} />
              <span class="iso">${preset.region}</span>
              <span>${preset.name}</span>
              <span class="meta">${preset.dialCode} · ${preset.length} digits${present.has(preset.region) ? ' · already listed' : ''}</span>
            </label>`)}
        </div>
        <div class="preset-actions">
          <button type="button" class="btn btn-secondary btn-sm" @click=${() => this.closePresets()}>Cancel</button>
          <button type="button" class="btn btn-secondary btn-sm" ?disabled=${count === 0} @click=${() => this.confirmPresets()}>${count === 0 ? 'Add selected' : `Add ${count} selected`}</button>
        </div>
      </div>
    `;
  }

  private renderCodesEditor() {
    const draft = this.draft!;
    return html`
      <div class="editor-box">
        <h3>One-time codes</h3>
        <p class="editor-sub">The code sent by SMS or email; its text is the OTP template of the client.</p>
        ${this.field('otpLength', 'Code length', this.textInput('otpLength', String(draft.otpLength), value => this.patch({ otpLength: parseInt(value) || 0 }), { type: 'number', max: '8rem' }), 'Digits in a generated code, 4 to 10.')}
        ${this.durationInput('otpResendAfter', 'Resend after', 'How long the user waits before asking for a new code.')}
      </div>
    `;
  }

  private renderPasskeysEditor() {
    const passkeys: PasskeySettings = this.draft!.passkeySettings ?? { rpId: '', rpName: '', origins: [], userVerification: 'preferred' };
    const patchPasskeys = (changes: Partial<PasskeySettings>) => this.patch({ passkeySettings: { ...passkeys, ...changes } });
    return html`
      <div class="editor-box">
        <h3>Relying party</h3>
        <p class="editor-sub">What a passkey is bound to: the domain, and the name the authenticator shows.</p>
        ${this.field('rpId', 'Relying party ID', this.textInput('rpId', passkeys.rpId, value => patchPasskeys({ rpId: value }), { placeholder: 'example.com', mono: true }), 'The domain the passkeys are scoped to.')}
        ${this.field('rpName', 'Relying party name', this.textInput('rpName', passkeys.rpName, value => patchPasskeys({ rpName: value }), { placeholder: 'Example Inc.' }), 'Shown to users during passkey prompts.')}
        ${this.field('userVerification', 'User verification', html`
          <select id="userVerification" class="compact-input" .value=${passkeys.userVerification} @change=${(e: Event) => patchPasskeys({ userVerification: (e.target as HTMLSelectElement).value })}>
            ${['required', 'preferred', 'discouraged'].map(option => html`<option value=${option} ?selected=${passkeys.userVerification === option}>${option.charAt(0).toUpperCase() + option.slice(1)}</option>`)}
          </select>`, 'Whether the authenticator must verify the user (PIN, biometrics).')}
      </div>
      <div class="editor-box">
        <h3>Allowed origins</h3>
        <p class="editor-sub">The full origins permitted to use these passkeys.</p>
        ${this.entryList(this.origins, 'Origin', 'https://example.com', entries => this.origins = entries, 'origin', '+ Add origin')}
        ${this.error('origins')}
      </div>
    `;
  }

  private renderRegistrationEditor() {
    const registration: EmailRegistration = this.draft!.emailRegistration!;
    const mode = registration.mode;
    const patchRegistration = (changes: Partial<EmailRegistration>) => this.patch({ emailRegistration: { ...registration, ...changes } });
    return html`
      <div class="editor-box">
        <h3>Email registration</h3>
        <p class="editor-sub">Who may create an account with an email address; also applied when an existing user changes their address. Closed: nobody. Open: anyone except the domains listed. Allowlist: only the domains listed.</p>
        ${this.field('email-mode', 'Email registration', html`
          <select id="email-mode" class="compact-input" .value=${mode} @change=${(e: Event) => patchRegistration({ mode: (e.target as HTMLSelectElement).value as EmailRegistrationMode })}>
            ${(['closed', 'open', 'allowlist'] as const).map(option => html`<option value=${option} ?selected=${mode === option}>${EMAIL_MODE_LABELS[option]}</option>`)}
          </select>`)}
        ${mode === 'closed' ? '' : html`
          <div class="form-group">
            <label for="domains">${mode === 'open' ? 'Blocked domains' : 'Allowed domains'}</label>
            <textarea id="domains" class="compact-input mono ${this.fieldErrors.domains ? 'input-error' : ''}" rows="5" spellcheck="false" .value=${this.domainsText}
              aria-invalid=${this.fieldErrors.domains ? 'true' : nothing} aria-describedby=${this.fieldErrors.domains ? 'domains-error' : 'domains-hint'}
              placeholder=${'example.com\n*.corp.example'} @input=${(e: Event) => this.domainsText = (e.target as HTMLTextAreaElement).value}></textarea>
            ${this.error('domains') || html`<div class="hint" id="domains-hint">One per line, lower-case: <code>example.com</code> matches that domain only, <code>*.example.com</code> its subdomains. ${mode === 'open' ? 'Anyone may register except from these domains.' : 'Only addresses from these domains may register; with none listed, nobody can.'}</div>`}
          </div>`}
        <label class="check-row"><input type="checkbox" .checked=${registration.blockSubaddress} @change=${(e: Event) => patchRegistration({ blockSubaddress: (e.target as HTMLInputElement).checked })} /> Block addresses with + = # (sub-addresses)</label>
      </div>
      <div class="editor-box">
        <h3>Default roles</h3>
        <p class="editor-sub">Granted to every self-registered account. At least one.</p>
        <versola-role-picker label="Default roles" .roles=${this.roles} .value=${this.draft!.defaultRoles ?? []}
          @change=${(e: CustomEvent<{ value: string[] }>) => this.patch({ defaultRoles: e.detail.value })}></versola-role-picker>
        ${this.error('defaultRoles')}
      </div>
    `;
  }

  private renderStepupEditor() {
    return html`
      <div class="editor-box">
        <h3>ACR vocabulary</h3>
        <p class="editor-sub">What each <code>acr</code> value a client may request means: the factors a sign-in must pass. Clients request one through <code>acr_values</code>.</p>
        ${this.acrRows.map(row => {
          const error = this.fieldErrors[`acr-${row.key}`];
          return html`
            <div class="acr-row">
              <input type="text" class="compact-input mono ${error ? 'input-error' : ''}" aria-label="ACR value" placeholder="urn:example:mfa" .value=${row.acr}
                aria-invalid=${error ? 'true' : nothing} aria-describedby=${error ? `acr-${row.key}-error` : nothing}
                @input=${(e: Event) => this.acrRows = this.acrRows.map(other => other.key === row.key ? { ...other, acr: (e.target as HTMLInputElement).value } : other)} />
              <div class="acr-factors">
                ${ACR_FACTORS.map(factor => html`
                  <label class="check-row"><input type="checkbox" .checked=${row.factors.includes(factor)}
                    @change=${(e: Event) => this.acrRows = this.acrRows.map(other => other.key === row.key
                      ? { ...other, factors: (e.target as HTMLInputElement).checked ? [...other.factors, factor] : other.factors.filter(f => f !== factor) } : other)} /> ${factor}</label>`)}
              </div>
              <button type="button" class="icon-button" aria-label=${`Remove ACR ${row.acr || 'new'}`} title="Remove" @click=${() => this.acrRows = this.acrRows.filter(other => other.key !== row.key)}>${statusIcon('x', 'icon')}</button>
              ${error ? html`<div class="error-message" id=${`acr-${row.key}-error`} role="alert">${error}</div>` : ''}
            </div>`;
        })}
        <button type="button" class="btn btn-secondary btn-sm" style="margin-top: 0.75rem" @click=${() => this.acrRows = [...this.acrRows, { key: ++this.entryKey, acr: '', factors: [] }]}>+ Add ACR</button>
      </div>
    `;
  }

  private renderSessionsEditor() {
    return html`
      <div class="editor-box">
        <h3>Signing in</h3>
        <p class="editor-sub">The time a user has to finish the sign-in forms.</p>
        ${this.durationInput('authConversationTtlSeconds', 'Sign-in conversation')}
      </div>
      <div class="editor-box">
        <h3>Staying signed in</h3>
        <p class="editor-sub">An SSO session belongs to one device; signing in on a second device starts a second session.</p>
        ${this.durationInput('sessionTtlSeconds', 'SSO session', 'After this the user signs in again, even if active.')}
        <div class="form-group">
          <label id="idle-label">Idle timeout</label>
          <label class="check-row"><input type="checkbox" aria-labelledby="idle-label" .checked=${this.idleEnabled} @change=${(e: Event) => this.idleEnabled = (e.target as HTMLInputElement).checked} /> End the session when it is not used for a while</label>
        </div>
        ${this.idleEnabled ? this.durationInput('sessionIdleTtlSeconds', 'Idle timeout', 'Must be shorter than the SSO session; online sessions slide forward on each silent re-authentication.') : ''}
        ${this.durationInput('userAgentTtlSeconds', 'Remembered device', 'How long a device (user agent) is recognised across sessions; shown on the user\'s record as Devices.')}
      </div>
      ${this.profile === 'fapi2' ? html`
        <div class="editor-box">
          <h3>Rule set by the profile</h3>
          <div class="locked">${statusIcon('lock', 'icon')}Access tokens are sender-constrained (DPoP or mTLS) · By FAPI 2.0</div>
        </div>` : ''}
    `;
  }

  private renderTokensEditor() {
    const draft = this.draft!;
    const allowsRs256 = this.profile !== 'fapi2';
    // RS256 is refused for a fapi2 tenant (PKCS#1 v1.5 padding), so it is not offered to one.
    const keys = this.keys.filter(key => key.canSign && (allowsRs256 || key.algorithm !== 'RS256'));
    const isKnown = keys.some(key => key.kid === draft.signingKeyId);
    const matchedPreset = MTLS_PRESETS.find(preset => preset.header === draft.mtlsCertificateHeader && preset.encoding === draft.mtlsCertificateEncoding);
    return html`
      <div class="editor-box">
        <h3>Token signing</h3>
        <p class="editor-sub">The JWKS key this tenant's access, ID and logout tokens are signed with. Only keys central holds a private half for are listed; generate one under Keys &amp; metadata. ${allowsRs256 ? '' : 'RS256 keys are not listed: FAPI 2.0 disallows its padding.'} Unset signs with the private key configured on the auth service itself.</p>
        ${this.field('signingKeyId', 'Signing key', html`
          <select id="signingKeyId" class="compact-input" .value=${draft.signingKeyId ?? ''} @change=${(e: Event) => { const value = (e.target as HTMLSelectElement).value; this.patch({ signingKeyId: value === '' ? null : value }); }}>
            <option value="" ?selected=${draft.signingKeyId === null}>Auth service's configured key</option>
            ${keys.map(key => html`<option value=${key.kid} ?selected=${draft.signingKeyId === key.kid}>${key.kid}${key.algorithm ? ` · ${key.algorithm}` : ''}</option>`)}
            ${draft.signingKeyId && !isKnown ? html`<option value=${draft.signingKeyId} selected>${draft.signingKeyId} · unavailable</option>` : ''}
          </select>`, keys.length === 0 ? 'No signable keys are stored. Generate one under Keys & metadata before selecting it here.' : '')}
        ${this.durationInput('clientAssertionMaxLifetimeSeconds', 'Client assertion lifetime', 'How far ahead a private_key_jwt client may set its assertion\'s expiry: 30 seconds to 15 minutes.')}
      </div>
      <div class="editor-box">
        <h3>DPoP</h3>
        <p class="editor-sub">A nonce (RFC 9449 section 8) bounds how long a captured proof stays usable. On, every proof at <code>/token</code> and <code>/userinfo</code> must carry one; a client that does not retry on <code>use_dpop_nonce</code> stops working. Proxied calls follow the edge's own setting.</p>
        <label class="check-row"><input type="checkbox" .checked=${draft.requireDpopNonce} @change=${(e: Event) => this.patch({ requireDpopNonce: (e.target as HTMLInputElement).checked })} /> Require a DPoP nonce on every proof</label>
      </div>
      <div class="editor-box">
        <h3>Mutual TLS</h3>
        <p class="editor-sub">Lets clients registered with an mTLS subject authenticate by certificate. The proxy in front of this tenant must request and forward the client certificate.</p>
        <label class="check-row"><input type="checkbox" .checked=${this.mtlsEnabled} @change=${(e: Event) => this.mtlsEnabled = (e.target as HTMLInputElement).checked} /> This tenant's reverse proxy terminates mutual TLS</label>
        ${this.mtlsEnabled ? html`
          <div style="margin-top: var(--spacing-md)">
            ${this.field('mtls-preset', 'Certificate header', html`
              <select id="mtls-preset" class="compact-input" .value=${matchedPreset?.value ?? 'custom'}
                @change=${(e: Event) => { const preset = MTLS_PRESETS.find(candidate => candidate.value === (e.target as HTMLSelectElement).value); this.patch(preset ? { mtlsCertificateHeader: preset.header, mtlsCertificateEncoding: preset.encoding } : { mtlsCertificateHeader: '' }); }}>
                ${MTLS_PRESETS.map(preset => html`<option value=${preset.value} ?selected=${matchedPreset === preset}>${preset.label}</option>`)}
                <option value="custom" ?selected=${!matchedPreset}>Custom…</option>
              </select>`, 'Which header the proxy sets and how the certificate is encoded; both must match the proxy exactly.')}
            ${matchedPreset ? '' : html`
              ${this.field('mtlsCertificateHeader', 'Header name', this.textInput('mtlsCertificateHeader', draft.mtlsCertificateHeader ?? '', value => this.patch({ mtlsCertificateHeader: value }), { placeholder: 'X-Client-Cert', mono: true }))}
              ${this.field('mtls-encoding', 'Encoding', html`
                <select id="mtls-encoding" class="compact-input" .value=${draft.mtlsCertificateEncoding ?? 'urlEncodedPem'} @change=${(e: Event) => this.patch({ mtlsCertificateEncoding: (e.target as HTMLSelectElement).value as MtlsCertificateEncoding })}>
                  <option value="urlEncodedPem" ?selected=${draft.mtlsCertificateEncoding !== 'base64Der'}>URL-encoded PEM</option>
                  <option value="base64Der" ?selected=${draft.mtlsCertificateEncoding === 'base64Der'}>Base64-encoded DER</option>
                </select>`)}`}
          </div>` : ''}
      </div>
    `;
  }

  private renderLogoutEditor() {
    return html`
      <div class="editor-box">
        <h3>Post-logout redirect URIs</h3>
        <p class="editor-sub">RPs must request an exact match of one of these as <code>post_logout_redirect_uri</code> after OIDC logout.</p>
        ${this.entryList(this.uris, 'Post-logout redirect URI', 'https://app.example.com/logged-out', entries => this.uris = entries, 'uri', '+ Add URI')}
      </div>
    `;
  }

  private renderLimitsEditor() {
    const limits = this.draft!.submissionLimits;
    const setLimits = (changes: Partial<SubmissionLimits>) => this.patch({ submissionLimits: { ...limits, ...changes } });
    return html`
      ${LIMIT_ACTIONS.map(action => html`
        <div class="editor-box">
          <h3>${action.label}</h3>
          <p class="editor-sub">Windows of attempts; only the longest one triggers a ban, the others are immediate rate limits.</p>
          ${limits[action.id].map((limit: RateLimit, index: number) => html`
            <div class="limit-row">
              <input type="number" class="compact-input" min="1" aria-label=${`${action.label} max attempts`} .value=${String(limit.maxAttempts)}
                @input=${(e: Event) => setLimits({ [action.id]: limits[action.id].map((other, i) => i === index ? { ...other, maxAttempts: parseInt((e.target as HTMLInputElement).value) || 0 } : other) })} />
              <input type="number" class="compact-input" min="1" aria-label=${`${action.label} window in seconds`} .value=${String(limit.windowSeconds)}
                @input=${(e: Event) => setLimits({ [action.id]: limits[action.id].map((other, i) => i === index ? { ...other, windowSeconds: parseInt((e.target as HTMLInputElement).value) || 0 } : other) })} />
              <span class="hint" style="margin: 0">per ${formatDurationShort(limit.windowSeconds)} <button type="button" class="icon-button" aria-label=${`Remove ${action.label} window ${index + 1}`} title="Remove"
                @click=${() => setLimits({ [action.id]: limits[action.id].filter((_, i) => i !== index) })}>${statusIcon('x', 'icon')}</button></span>
            </div>`)}
          ${this.error(action.id)}
          <button type="button" class="btn btn-secondary btn-sm" @click=${() => setLimits({ [action.id]: [...limits[action.id], { maxAttempts: 5, windowSeconds: 60 }] })}>+ Add window</button>
        </div>`)}
      <div class="editor-box">
        <h3>Ban</h3>
        ${this.durationInput('banDurationSeconds', 'Ban duration', 'How long a user is banned after exceeding the longest window; 0 bans nobody.')}
      </div>
    `;
  }

  private renderBrandingEditor() {
    const draft = this.draft!;
    return html`
      <div class="editor-box">
        <h3>Logo</h3>
        <p class="editor-sub">Shown above the sign-in card of this tenant's clients and as the favicon of its login pages. Both files or neither; SVG with text converted to outlines is recommended.</p>
        ${this.field('logoLight', 'Logo for the light scheme (dark artwork)', this.textInput('logoLight', draft.logoLight ?? '', value => this.patch({ logoLight: value }), { type: 'url', placeholder: 'https://example.com/logo-light.svg' }))}
        ${this.field('logoDark', 'Logo for the dark scheme (light artwork)', this.textInput('logoDark', draft.logoDark ?? '', value => this.patch({ logoDark: value }), { type: 'url', placeholder: 'https://example.com/logo-dark.svg' }))}
        ${this.error('logo')}
        <div class="logo-previews">${this.renderLogoPreview('light', (draft.logoLight ?? '').trim())}${this.renderLogoPreview('dark', (draft.logoDark ?? '').trim())}</div>
      </div>
    `;
  }

  private renderEditor(group: SecurityGroup) {
    const meta = GROUPS.find(candidate => candidate.id === group)!;
    const editors: Record<SecurityGroup, () => unknown> = {
      methods: () => this.renderMethodsEditor(),
      codes: () => this.renderCodesEditor(),
      passkeys: () => this.renderPasskeysEditor(),
      registration: () => this.renderRegistrationEditor(),
      stepup: () => this.renderStepupEditor(),
      sessions: () => this.renderSessionsEditor(),
      tokens: () => this.renderTokensEditor(),
      logout: () => this.renderLogoutEditor(),
      limits: () => this.renderLimitsEditor(),
      branding: () => this.renderBrandingEditor(),
    };
    const blocked = this.passkeysMissing;
    return html`
      <versola-record-header
        .crumbs=${[{ label: 'Sign-in and security', onSelect: () => void this.cancelEditor() }, { label: meta.label }]}
        .heading=${`Edit ${meta.title.toLowerCase().replace(' (webauthn)', '').replace(' (acr vocabulary)', ' (ACR)')}`}
        .meta=${html`<span class="muted">Saved as part of <span class="mono">${this.tenantId}</span>'s security settings; the other groups keep their current values.</span>`}
      ></versola-record-header>
      <form class="card editor-card" novalidate @submit=${(e: Event) => this.handleSubmit(e)}>
        ${blocked ? html`<div class="editor-error" role="alert">Passkeys are not configured for this tenant, and central needs them in every save. Set them up first.</div>` : ''}
        ${this.formError ? html`<div class="editor-error" role="alert">${this.formError}</div>` : ''}
        ${editors[group]()}
        <div class="form-actions">
          <button type="button" class="btn btn-secondary" ?disabled=${this.isSaving} @click=${() => void this.cancelEditor()}>Cancel</button>
          <button type="submit" class="btn btn-primary" ?disabled=${this.isSaving || blocked}>${this.isSaving ? 'Saving…' : `Save ${meta.label.toLowerCase().replace(' (acr)', '')}`}</button>
        </div>
      </form>
    `;
  }

  render() {
    if (!this.tenantId) {
      return html`
        <content-header title="Sign-in and security"></content-header>
        <versola-empty-state heading="Select a tenant" text="Select a tenant to manage its sign-in and security settings."></versola-empty-state>
      `;
    }
    const group = this.editGroup;
    if (group && this.draft && this.loaded) return this.renderEditor(group);
    return this.renderPage();
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'versola-security-settings': VersolaSecuritySettings;
  }
}
