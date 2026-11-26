import { describe, expect, it } from 'vitest';
import {
  MAX_TEMPORARY_PASSWORD_TTL_HOURS,
  MIN_TEMPORARY_PASSWORD_TTL_HOURS,
  parseTemporaryPasswordTtlHours,
} from './temporary-password-ttl';
import { DEFAULT_TEMPORARY_PASSWORD_TTL_HOURS } from './users-api';

describe('temporary password lifetime', () => {
  it('matches the range central accepts, 3600 to 604800 seconds, and holds the default', () => {
    expect(MIN_TEMPORARY_PASSWORD_TTL_HOURS * 3600).toBe(3600);
    expect(MAX_TEMPORARY_PASSWORD_TTL_HOURS * 3600).toBe(604800);
    expect(DEFAULT_TEMPORARY_PASSWORD_TTL_HOURS).toBeGreaterThanOrEqual(MIN_TEMPORARY_PASSWORD_TTL_HOURS);
    expect(DEFAULT_TEMPORARY_PASSWORD_TTL_HOURS).toBeLessThanOrEqual(MAX_TEMPORARY_PASSWORD_TTL_HOURS);
  });

  it('accepts the bounds and what is between them', () => {
    for (const raw of ['1', '12', '168', ' 24 ']) {
      expect(parseTemporaryPasswordTtlHours(raw)).toEqual({ ok: true, hours: Number(raw.trim()) });
    }
  });

  it('says why a value is refused, and each reason is its own', () => {
    const reasons = ['', '   ', 'abc', '1.5', '0', '-3', '169', '1000'].map(raw => {
      const result = parseTemporaryPasswordTtlHours(raw);
      expect(result.ok).toBe(false);
      return result.ok ? '' : result.message;
    });
    expect(reasons[0]).toBe(reasons[1]);
    expect(reasons[4]).toBe(reasons[5]);
    expect(reasons[6]).toBe(reasons[7]);
    expect(new Set(reasons).size).toBe(5);
    expect(reasons[6]).toContain('168');
  });
});
