import { describe, expect, it } from 'vitest';
import {
  PASSWORD_AGAIN,
  emptyOtpProviderDraft,
  emptySmtpDraft,
  otpEndpointChanged,
  otpProviderDraftFrom,
  otpProviderFromDraft,
  passwordChange,
  smtpDraftFrom,
  smtpFromDraft,
  smtpServerChanged,
  validateOtpProviderDraft,
  validateSmtpDraft,
  type OtpProviderDraft,
  type SmtpDraft,
} from './delivery-settings';

const smtp = (patch: Partial<SmtpDraft> = {}): SmtpDraft => ({
  ...emptySmtpDraft(), host: 'smtp.example.com', username: 'mailer', from: 'noreply@example.com', ...patch,
});

const provider = (patch: Partial<OtpProviderDraft> = {}): OtpProviderDraft => ({
  ...emptyOtpProviderDraft(), url: 'https://sms.example.com/send', ...patch,
});

describe('validateSmtpDraft', () => {
  it('accepts a complete block', () => {
    expect(validateSmtpDraft(smtp())).toEqual({});
  });

  it.each([
    ['an empty host', { host: '  ' }, 'host'],
    ['a line break inside the host', { host: 'smtp.example\n.com' }, 'host'],
    ['port 0', { port: '0' }, 'port'],
    ['a port above 65535', { port: '65536' }, 'port'],
    ['a port that is not a whole number', { port: '58.7' }, 'port'],
    ['a port with letters', { port: '58a' }, 'port'],
    ['an empty username', { username: '' }, 'username'],
    ['a control character in the username', { username: 'mail\u0000er' }, 'username'],
    ['a sender that is not an address', { from: 'not-an-address' }, 'from'],
    ['an empty subject', { subject: ' ' }, 'subject'],
    ['a line break in the subject', { subject: 'Code\r\nBcc: x@example.com' }, 'subject'],
  ])('refuses %s, on the %s field', (_what, patch, field) => {
    expect(Object.keys(validateSmtpDraft(smtp(patch)))).toEqual([field]);
  });
});

describe('validateOtpProviderDraft', () => {
  it.each([
    ['a placeholder in the query', 'https://sms.example/send?to={{phone}}'],
    ['a space', 'https://sms.example/send me'],
    ['a pipe', 'https://sms.example/a|b'],
    ['a backslash', 'https://sms.example/a\\b'],
    ['an angle bracket', 'https://sms.example/<x>'],
  ])('refuses %s, which central refuses too', (_name, url) => {
    expect(validateOtpProviderDraft(provider({ url })).url).toMatch(/must not contain spaces/);
  });

  it('accepts a complete block, with or without a username, over http or https', () => {
    expect(validateOtpProviderDraft(provider())).toEqual({});
    expect(validateOtpProviderDraft(provider({ username: 'sms' }))).toEqual({});
    expect(validateOtpProviderDraft(provider({ url: 'http://sms-gateway.internal:8080/send' }))).toEqual({});
  });

  it.each([
    ['a method outside the four', { method: 'DELETE' }, 'method'],
    ['a URL without a scheme', { url: 'sms.example.com/send' }, 'url'],
    ['a URL with another scheme', { url: 'ftp://sms.example.com/send' }, 'url'],
    ['credentials in the URL', { url: 'https://sms:topsecret@sms.example.com/send' }, 'url'],
    ['a blank username', { username: ' ' }, 'username'],
    ['a control character inside the username', { username: 'sms\r-account' }, 'username'],
    ['a body entry without a name', { body: [{ key: '', value: '{{phone}}' }] }, 'body'],
    ['two body entries with one name', { body: [{ key: 'to', value: '{{phone}}' }, { key: ' to', value: 'x' }] }, 'body'],
  ])('refuses %s, on the %s field', (_what, patch, field) => {
    expect(Object.keys(validateOtpProviderDraft(provider(patch)))).toEqual([field]);
  });

  it('never repeats a credential in its message', () => {
    const message = validateOtpProviderDraft(provider({ url: 'https://sms:topsecret@sms.example.com/send' })).url;
    expect(message).not.toContain('topsecret');
  });
});

describe('what is trimmed before the check', () => {
  it('judges the value that will be sent: a trailing line break is trimmed away, not refused', () => {
    expect(validateSmtpDraft(smtp({ host: 'smtp.example.com\n', subject: 'Code\t' }))).toEqual({});
    expect(smtpFromDraft(smtp({ host: 'smtp.example.com\n' })).host).toBe('smtp.example.com');
    expect(validateOtpProviderDraft(provider({ username: 'sms\r' }))).toEqual({});
  });

  it('refuses a C1 control character, as central does', () => {
    expect(Object.keys(validateSmtpDraft(smtp({ subject: 'Co\u0085de' })))).toEqual(['subject']);
  });
});

describe('the wire form', () => {
  it('trims what the operator typed and sends the port as a number', () => {
    expect(smtpFromDraft(smtp({ host: ' smtp.example.com ', port: ' 2525 ', subject: ' Code ' }))).toEqual({
      host: 'smtp.example.com', port: 2525, username: 'mailer', from: 'noreply@example.com', subject: 'Code', startTls: true, enabled: true,
    });
  });

  it('leaves the username out when it is empty and builds the body as an object', () => {
    expect(otpProviderFromDraft(provider({ body: [{ key: 'to', value: '{{phone}}' }, { key: 'text', value: '{{message}}' }] }))).toEqual({
      method: 'POST', url: 'https://sms.example.com/send', body: { to: '{{phone}}', text: '{{message}}' }, enabled: true,
    });
    expect(otpProviderFromDraft(provider({ username: ' sms ' })).username).toBe('sms');
  });

  it('opens a stored block as a draft without any password', () => {
    const stored = { host: 'smtp.example.com', port: 587, username: 'mailer', from: 'noreply@example.com', subject: 'Code', startTls: false, enabled: false };
    expect(smtpDraftFrom(stored)).toMatchObject({ host: 'smtp.example.com', port: '587', startTls: false, enabled: false, password: '', clearPassword: false });
    expect(otpProviderDraftFrom({ method: 'GET', url: 'https://a.example.com', username: null, body: { k: 'v' }, enabled: true }))
      .toMatchObject({ method: 'GET', username: '', body: [{ key: 'k', value: 'v' }], password: '', clearPassword: false });
  });

  it('starts a new block from sensible defaults', () => {
    expect(smtpDraftFrom(undefined)).toMatchObject({ port: '587', startTls: true, enabled: true });
    expect(otpProviderDraftFrom(undefined)).toMatchObject({ method: 'POST', enabled: true });
  });
});

describe('passwordChange', () => {
  it('keeps the saved password when the field is empty', () => {
    expect(passwordChange({ password: '', clearPassword: false })).toBeUndefined();
  });

  it('replaces it with what was typed, even when the clear box is ticked', () => {
    expect(passwordChange({ password: 'new-secret', clearPassword: false })).toBe('new-secret');
    expect(passwordChange({ password: 'new-secret', clearPassword: true })).toBe('new-secret');
  });

  it('clears it only through the box, with an empty string', () => {
    expect(passwordChange({ password: '', clearPassword: true })).toBe('');
  });

  it('does not trim a typed password', () => {
    expect(passwordChange({ password: ' spaced ', clearPassword: false })).toBe(' spaced ');
  });
});

const savedSmtp = { host: 'smtp.example.com', port: 587, username: 'mailer', from: 'noreply@example.com', subject: 'Code', startTls: true, enabled: true };
const savedProvider = { method: 'POST', url: 'https://sms.example.com/send', username: 'sms', body: { phones: '{{phone}}' }, enabled: true };

describe('smtpServerChanged', () => {
  const draft = (patch: Partial<SmtpDraft> = {}): SmtpDraft => ({ ...smtpDraftFrom(savedSmtp), ...patch });

  it('is false for the same server, however it is written', () => {
    expect(smtpServerChanged(savedSmtp, draft())).toBe(false);
    expect(smtpServerChanged(savedSmtp, draft({ host: ' SMTP.Example.com ', port: ' 587 ' }))).toBe(false);
    expect(smtpServerChanged(savedSmtp, draft({ subject: 'Another subject', from: 'other@example.com', enabled: false }))).toBe(false);
  });

  it('is false for a port written another way', () => {
    expect(smtpServerChanged(savedSmtp, draft({ port: '0587' }))).toBe(false);
  });

  it.each([
    ['another host', { host: 'smtp.attacker.example' }],
    ['another port', { port: '2525' }],
    ['a port that is not a number', { port: 'abc' }],
    ['another account', { username: 'someone-else' }],
    ['STARTTLS switched off', { startTls: false }],
  ])('is true for %s', (_what, patch) => {
    expect(smtpServerChanged(savedSmtp, draft(patch))).toBe(true);
  });
});

describe('otpEndpointChanged', () => {
  const draft = (patch: Partial<OtpProviderDraft> = {}): OtpProviderDraft => ({ ...otpProviderDraftFrom(savedProvider), ...patch });

  it('is false for the same origin and account, a path or a default port apart', () => {
    expect(otpEndpointChanged(savedProvider, draft())).toBe(false);
    expect(otpEndpointChanged(savedProvider, draft({ url: 'https://SMS.example.com:443/v2/send?x=1' }))).toBe(false);
  });

  it.each([
    ['another host', { url: 'https://sms.attacker.example/send' }],
    ['another scheme', { url: 'http://sms.example.com/send' }],
    ['another port', { url: 'https://sms.example.com:8443/send' }],
    ['another account', { username: 'someone-else' }],
    ['no account', { username: '' }],
    ['an address that is not a URL', { url: 'not a url' }],
  ])('is true for %s', (_what, patch) => {
    expect(otpEndpointChanged(savedProvider, draft(patch))).toBe(true);
  });
});

describe('the saved password follows only the server it was saved for', () => {
  const smtpSaved = { settings: savedSmtp, hasPassword: true };
  const providerSaved = { settings: savedProvider, hasPassword: true };

  it('asks for the password again when the server changes and the field is empty', () => {
    expect(validateSmtpDraft({ ...smtpDraftFrom(savedSmtp), host: 'smtp.other.example' }, smtpSaved).password).toBe(PASSWORD_AGAIN);
    expect(validateOtpProviderDraft({ ...otpProviderDraftFrom(savedProvider), url: 'https://sms.other.example/send' }, providerSaved).password).toBe(PASSWORD_AGAIN);
  });

  it('does not ask when a password is typed, when the box removes it, or when the server is the same', () => {
    const moved = { ...smtpDraftFrom(savedSmtp), host: 'smtp.other.example' };
    expect(validateSmtpDraft({ ...moved, password: 'new' }, smtpSaved).password).toBeUndefined();
    expect(validateSmtpDraft({ ...moved, clearPassword: true }, smtpSaved).password).toBeUndefined();
    expect(validateSmtpDraft({ ...smtpDraftFrom(savedSmtp), subject: 'Another' }, smtpSaved).password).toBeUndefined();
  });

  it('has nothing to protect when no password is saved or nothing is saved at all', () => {
    const moved = { ...smtpDraftFrom(savedSmtp), host: 'smtp.other.example' };
    expect(validateSmtpDraft(moved, { settings: savedSmtp, hasPassword: false }).password).toBeUndefined();
    expect(validateSmtpDraft(moved).password).toBeUndefined();
  });
});
