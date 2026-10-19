import { describe, expect, it } from 'vitest';
import type { UserAgent, UserDevice, UserRefreshToken, UserSession } from '../types';
import { buildDevices, deviceLabel, deviceName, userDisplayName, userLabel } from './user-display';

describe('userDisplayName', () => {
  it('prefers the name claim', () => {
    expect(userDisplayName({ claims: { name: ' Dana Reyes ', given_name: 'D' } })).toBe('Dana Reyes');
  });

  it('builds family then given without a middle name, and given middle family with one', () => {
    expect(userDisplayName({ claims: { given_name: 'Dana', family_name: 'Reyes' } })).toBe('Reyes Dana');
    expect(userDisplayName({ claims: { given_name: 'Dana', middle_name: 'M', family_name: 'Reyes' } })).toBe('Dana M Reyes');
  });

  it('is empty when no claim names the person', () => {
    expect(userDisplayName({ claims: { locale: 'ru' } })).toBe('');
    expect(userDisplayName({ claims: { name: 5 } })).toBe('');
  });
});

describe('userLabel', () => {
  it('falls back from name to login, email, phone and id', () => {
    expect(userLabel({ id: 'u1', claims: { name: 'Dana' }, login: 'dana' })).toBe('Dana');
    expect(userLabel({ id: 'u1', claims: {}, login: 'dana', email: 'd@example.com' })).toBe('dana');
    expect(userLabel({ id: 'u1', claims: {}, email: 'd@example.com' })).toBe('d@example.com');
    expect(userLabel({ id: 'u1', claims: {} })).toBe('u1');
  });
});

describe('deviceName', () => {
  it('joins browser, version and OS', () => {
    expect(deviceName({ browser: 'Chrome', version: '130', os: 'macOS 14.6' })).toBe('Chrome 130 on macOS 14.6');
    expect(deviceName({ browser: 'Chrome' })).toBe('Chrome');
    expect(deviceName({ os: 'iOS 18.1' })).toBe('iOS 18.1');
    expect(deviceName({})).toBe('Unknown device');
  });
});

describe('deviceLabel', () => {
  const agent: UserAgent = { id: 'ua', browser: 'Firefox', version: '131', os: 'Windows 11', createdAt: '2026-10-01T09:00:00Z', expiresAt: '2027-01-01T09:00:00Z' };

  it('names a device by its user agent, falling back to the session, then to a plain signed-out device', () => {
    expect(deviceLabel({ userAgent: agent, session: null })).toBe('Firefox 131 on Windows 11');
    expect(deviceLabel({ userAgent: null, session: { clients: [], browser: 'Chrome', os: 'macOS 14.6' } })).toBe('Chrome on macOS 14.6');
    expect(deviceLabel({ userAgent: null, session: null })).toBe('Signed-out device');
  });
});

describe('buildDevices', () => {
  const agent = (id: string, browser: string): UserAgent => ({ id, browser, createdAt: '2026-10-01T09:00:00Z', expiresAt: '2027-01-01T09:00:00Z' });
  const session = (publicId: string): UserSession => ({ publicId, clients: [], createdAt: '2026-10-14T09:12:00Z', expiresAt: '2026-10-21T09:12:00Z' });
  const live = (id: string, publicId: string, browser = 'Chrome'): UserDevice => ({ userAgent: agent(id, browser), session: session(publicId), endedSessionIds: [] });
  const signedOut = (id: string, ended: string[], browser = 'Firefox'): UserDevice => ({ userAgent: agent(id, browser), session: null, endedSessionIds: ended });
  const token = (sessionId: string, clientId: string): UserRefreshToken => ({
    sessionId, clientId, scope: ['openid'], issuedAt: '2026-10-14T09:12:00Z', expiresAt: '2026-11-13T09:12:00Z', dpopBound: false,
  });

  it('attaches tokens to the live session they were issued under', () => {
    const devices = buildDevices([live('ua1', 's1'), live('ua2', 's2')], [token('s1', 'web'), token('s2', 'app')]);
    expect(devices.map(device => [device.key, device.session !== null, device.tokens.map(t => t.clientId)])).toEqual([
      ['ua1', true, ['web']],
      ['ua2', true, ['app']],
    ]);
  });

  it('ties a token whose session ended to the signed-out device that session ran under, by name', () => {
    const devices = buildDevices([live('ua1', 's1'), signedOut('ua2', ['old-1', 'old-2'])], [token('old-1', 'web'), token('old-2', 'app'), token('s1', 'web')]);
    expect(devices).toHaveLength(2);
    expect(devices[1]).toMatchObject({ key: 'ua2', session: null });
    expect(devices[1].userAgent?.browser).toBe('Firefox');
    expect(devices[1].tokens.map(t => t.clientId)).toEqual(['web', 'app']);
  });

  it('keeps a signed-out device that holds no tokens', () => {
    const devices = buildDevices([signedOut('ua2', ['old-1'])], []);
    expect(devices).toHaveLength(1);
    expect(devices[0].tokens).toEqual([]);
  });

  it('turns tokens whose session no remembered device names into one unnamed signed-out device per session', () => {
    const devices = buildDevices([live('ua1', 's1')], [token('gone', 'web'), token('gone', 'app'), token('s1', 'web')]);
    expect(devices).toHaveLength(2);
    expect(devices[1]).toMatchObject({ key: 'gone', session: null, userAgent: null });
    expect(devices[1].tokens.map(t => t.clientId)).toEqual(['web', 'app']);
  });

  it('lists a live session whose user agent row is gone under its session id', () => {
    const devices = buildDevices([{ session: session('s9'), endedSessionIds: [] }], [token('s9', 'web')]);
    expect(devices[0]).toMatchObject({ key: 's9', userAgent: null });
    expect(devices[0].tokens).toHaveLength(1);
  });
});
