/**
 * The delivery settings form (`PUT /configuration/delivery-settings`): the drafts the two editors hold and the
 * checks they run before the request. The rules mirror `SmtpDeliverySettings.validate` and
 * `OtpProviderDeliverySettings.validate` in `central/.../DeliverySettingsRecord.scala`: central refuses a block that
 * fails them and stores nothing, so the form says the same thing next to the field instead of after the round trip.
 * Central stays the authority; this is a courtesy, and a message it sends back is shown as it is.
 */
import type { OtpProviderDeliverySettings, SmtpDeliverySettings } from '../types';

export const OTP_METHODS = ['POST', 'GET', 'PUT', 'PATCH'] as const;

export interface SmtpDraft {
  host: string;
  port: string;
  username: string;
  from: string;
  subject: string;
  startTls: boolean;
  enabled: boolean;
  password: string;
  clearPassword: boolean;
}

export interface BodyEntry {
  key: string;
  value: string;
}

export interface OtpProviderDraft {
  method: string;
  url: string;
  username: string;
  body: BodyEntry[];
  enabled: boolean;
  password: string;
  clearPassword: boolean;
}

export type FieldErrors = Record<string, string>;

// eslint-disable-next-line no-control-regex
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f-\u009f]/;

export function emptySmtpDraft(): SmtpDraft {
  return { host: '', port: '587', username: '', from: '', subject: 'Your verification code', startTls: true, enabled: true, password: '', clearPassword: false };
}

export function smtpDraftFrom(stored: SmtpDeliverySettings | undefined): SmtpDraft {
  if (!stored) return emptySmtpDraft();
  return {
    host: stored.host,
    port: String(stored.port),
    username: stored.username,
    from: stored.from,
    subject: stored.subject,
    startTls: stored.startTls,
    enabled: stored.enabled,
    password: '',
    clearPassword: false,
  };
}

export function emptyOtpProviderDraft(): OtpProviderDraft {
  return {
    method: 'POST',
    url: '',
    username: '',
    body: [{ key: 'phone', value: '{{phone}}' }, { key: 'message', value: '{{message}}' }],
    enabled: true,
    password: '',
    clearPassword: false,
  };
}

export function otpProviderDraftFrom(stored: OtpProviderDeliverySettings | undefined): OtpProviderDraft {
  if (!stored) return emptyOtpProviderDraft();
  return {
    method: stored.method,
    url: stored.url,
    username: stored.username ?? '',
    body: Object.entries(stored.body).map(([key, value]) => ({ key, value })),
    enabled: stored.enabled,
    password: '',
    clearPassword: false,
  };
}

/** What the form knows of a block that is already saved, to tell whether its password may follow the settings. */
export interface SavedBlock<T> {
  settings: T;
  hasPassword: boolean;
}

export const PASSWORD_AGAIN = 'Enter the password again: it stays only with the server it was saved for.';

/** Whether the draft reaches another server, port, account or STARTTLS setting than the saved block does. Central keeps a
 * saved password only for the server it was saved for, so a change that leaves the password empty is refused there. */
export function smtpServerChanged(saved: SmtpDeliverySettings, draft: SmtpDraft): boolean {
  return saved.host.trim().toLowerCase() !== draft.host.trim().toLowerCase()
    || saved.port !== Number(draft.port.trim())
    || saved.username !== draft.username.trim()
    || saved.startTls !== draft.startTls;
}

/** Likewise for the SMS provider: the origin (scheme, host and port, default ports as they are written) and the account. */
export function otpEndpointChanged(saved: OtpProviderDeliverySettings, draft: OtpProviderDraft): boolean {
  let before: string;
  let after: string;
  try {
    before = new URL(saved.url).origin;
    after = new URL(draft.url.trim()).origin;
  } catch {
    return true;
  }
  return before !== after || (saved.username ?? '') !== draft.username.trim();
}

export function validateSmtpDraft(draft: SmtpDraft, saved?: SavedBlock<SmtpDeliverySettings>): FieldErrors {
  const errors: FieldErrors = {};
  const host = draft.host.trim();
  if (!host) errors.host = 'The server address is required.';
  else if (CONTROL_CHARACTER.test(host)) errors.host = 'The server address must not contain control characters.';
  const port = draft.port.trim();
  if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) errors.port = 'A whole number from 1 to 65535.';
  if (!draft.username.trim()) errors.username = 'The username is required.';
  else if (CONTROL_CHARACTER.test(draft.username.trim())) errors.username = 'The username must not contain control characters.';
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(draft.from.trim())) errors.from = 'Enter an email address, such as noreply@example.com.';
  if (!draft.subject.trim()) errors.subject = 'The subject is required.';
  else if (CONTROL_CHARACTER.test(draft.subject.trim())) errors.subject = 'The subject must be one line.';
  if (saved?.hasPassword && smtpServerChanged(saved.settings, draft) && draft.password === '' && !draft.clearPassword) errors.password = PASSWORD_AGAIN;
  return errors;
}

// Central parses the address with java.net.URI, which refuses these; the form says so before the round trip.
const URL_CHARACTER_CENTRAL_REFUSES = /[\s{}|\\^`<>"]/;

export function validateOtpProviderDraft(draft: OtpProviderDraft, saved?: SavedBlock<OtpProviderDeliverySettings>): FieldErrors {
  const errors: FieldErrors = {};
  if (!(OTP_METHODS as readonly string[]).includes(draft.method)) errors.method = 'Pick GET, POST, PUT or PATCH.';
  const url = draft.url.trim();
  let parsed: URL | null = null;
  try {
    parsed = new URL(url);
  } catch {
    parsed = null;
  }
  if (!parsed || (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || !parsed.hostname) {
    errors.url = 'An absolute http:// or https:// address.';
  } else if (parsed.username || parsed.password) {
    errors.url = 'The address must not carry a username or password. Use the fields below.';
  } else if (URL_CHARACTER_CENTRAL_REFUSES.test(url)) {
    errors.url = 'The address must not contain spaces or any of { } | \\ ^ ` < > ". Placeholders such as {{phone}} belong in the body.';
  }
  if (draft.username !== '' && !draft.username.trim()) errors.username = 'Leave the username empty or give it a value.';
  else if (CONTROL_CHARACTER.test(draft.username.trim())) errors.username = 'The username must not contain control characters.';
  const keys = draft.body.map(entry => entry.key.trim());
  if (keys.some(key => !key)) errors.body = 'Every body entry needs a name.';
  else if (new Set(keys).size !== keys.length) errors.body = 'Two body entries have the same name.';
  if (saved?.hasPassword && otpEndpointChanged(saved.settings, draft) && draft.password === '' && !draft.clearPassword) errors.password = PASSWORD_AGAIN;
  return errors;
}

export function smtpFromDraft(draft: SmtpDraft): SmtpDeliverySettings {
  return {
    host: draft.host.trim(),
    port: Number(draft.port.trim()),
    username: draft.username.trim(),
    from: draft.from.trim(),
    subject: draft.subject.trim(),
    startTls: draft.startTls,
    enabled: draft.enabled,
  };
}

export function otpProviderFromDraft(draft: OtpProviderDraft): OtpProviderDeliverySettings {
  const username = draft.username.trim();
  return {
    method: draft.method,
    url: draft.url.trim(),
    ...(username ? { username } : {}),
    body: Object.fromEntries(draft.body.map(entry => [entry.key.trim(), entry.value])),
    enabled: draft.enabled,
  };
}

/** What the password field sends: a typed value replaces the stored one, the clear box removes it, an empty field
 * leaves it out and so keeps it. */
export function passwordChange(draft: { password: string; clearPassword: boolean }): string | undefined {
  if (draft.password !== '') return draft.password;
  return draft.clearPassword ? '' : undefined;
}
