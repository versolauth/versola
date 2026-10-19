import { describe, expect, it } from 'vitest';
import type { UserRefreshToken, UserSession } from '../types';
import { buildDevices, deviceName, userDisplayName, userLabel } from './user-display';

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

describe('buildDevices', () => {
  const session = (publicId: string): UserSession => ({ publicId, clients: [], createdAt: '2026-10-14T09:12:00Z' });
  const token = (sessionId: string, clientId: string): UserRefreshToken => ({
    sessionId, clientId, scope: ['openid'], issuedAt: '2026-10-14T09:12:00Z', expiresAt: '2026-11-13T09:12:00Z', dpopBound: false,
  });

  it('attaches tokens to the live session they were issued under', () => {
    const devices = buildDevices([session('s1'), session('s2')], [token('s1', 'web'), token('s2', 'app')]);
    expect(devices.map(device => [device.key, device.session !== null, device.tokens.map(t => t.clientId)])).toEqual([
      ['s1', true, ['web']],
      ['s2', true, ['app']],
    ]);
  });

  it('turns tokens whose session is gone into one signed-out device per session', () => {
    const devices = buildDevices([session('s1')], [token('gone', 'web'), token('gone', 'app'), token('s1', 'web')]);
    expect(devices).toHaveLength(2);
    expect(devices[1]).toMatchObject({ key: 'gone', session: null });
    expect(devices[1].tokens.map(t => t.clientId)).toEqual(['web', 'app']);
  });
});
