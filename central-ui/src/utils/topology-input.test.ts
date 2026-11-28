import { describe, expect, it } from 'vitest';
import type { PublicJwk } from '../types';
import { addressError, certificatesOf, parsePublicKeys, publicKeysText, validId } from './topology-input';

const pem = (body: string) => `-----BEGIN CERTIFICATE-----\n${body}\n-----END CERTIFICATE-----`;

describe('topology input', () => {
  it('takes an ID the way a routing key spells a cell', () => {
    for (const good of ['kz-1', 'a', 'almaty', '0', 'a-b-c', 'x'.repeat(64)]) expect(validId(good)).toBe(true);
  });

  it('refuses an ID a routing key could not carry', () => {
    for (const bad of ['', '-kz', 'kz-', 'KZ-1', 'kz_1', 'kz.1', 'kz 1', 'x'.repeat(65)]) expect(validId(bad)).toBe(false);
  });

  it('takes http for an internal address and only https for the mutual-TLS one', () => {
    expect(addressError('http://auth.internal:9003', false)).toBe('');
    expect(addressError('https://auth.internal:9003', false)).toBe('');
    expect(addressError('http://auth.internal:9008', true)).not.toBe('');
    expect(addressError('https://auth.internal:9008', true)).toBe('');
  });

  it('refuses what is not an absolute http(s) address', () => {
    for (const bad of ['', 'auth.internal', 'ftp://auth.internal', '/relative']) {
      expect(addressError(bad, false)).not.toBe('');
      expect(addressError(bad, true)).not.toBe('');
    }
  });

  it('reads one certificate for each PEM block and ignores the text between them', () => {
    expect(certificatesOf(`${pem('AAA')}\nnotes\n${pem('BBB')}`)).toEqual([pem('AAA'), pem('BBB')]);
    expect(certificatesOf('no certificate here')).toEqual([]);
    expect(certificatesOf('-----BEGIN PRIVATE KEY-----\nAAA\n-----END PRIVATE KEY-----')).toEqual([]);
  });

  describe('public keys', () => {
    const key = (kid: string, extra: Record<string, unknown> = {}) => ({ kty: 'RSA', kid, n: `${'q'.repeat(342 - kid.length)}${kid}`, e: 'AQAB', ...extra });

    it('reads one key, an array of two, or two objects one after another', () => {
      expect(parsePublicKeys(JSON.stringify(key('a')))).toEqual({ keys: [key('a')], error: '' });
      expect(parsePublicKeys(JSON.stringify([key('a'), key('b')])).keys).toEqual([key('a'), key('b')]);
      expect(parsePublicKeys(`${JSON.stringify(key('a'))}\n\n${JSON.stringify(key('b'))}`).keys).toEqual([key('a'), key('b')]);
      expect(parsePublicKeys(`${JSON.stringify(key('a'))},${JSON.stringify(key('b'))}`).keys).toEqual([key('a'), key('b')]);
    });

    it('keeps the members a key was pasted with, so the key set is sent as it was', () => {
      expect(parsePublicKeys(JSON.stringify(key('a', { alg: 'RS256', use: 'sig' }))).keys).toEqual([key('a', { alg: 'RS256', use: 'sig' })]);
    });

    it('is not confused by braces inside a string', () => {
      expect(parsePublicKeys(JSON.stringify(key('a', { note: '} {"x":' }))).error).toBe('');
    });

    it('requires a key, and at most two', () => {
      expect(parsePublicKeys('  ').error).toContain('required');
      expect(parsePublicKeys('[]').error).toContain('required');
      expect(parsePublicKeys(JSON.stringify([key('a'), key('b'), key('c')])).error).toContain('At most 2');
    });

    it('refuses what is not JSON or not an object', () => {
      for (const bad of ['{', 'nope', '"text"', '42', '[1]', '[[{}]]']) expect(parsePublicKeys(bad).keys).toBeNull();
    });

    it('refuses a key that is not RSA or lacks kid, n or e', () => {
      expect(parsePublicKeys(JSON.stringify(key('a', { kty: 'EC' }))).error).toContain('RSA');
      for (const member of ['kid', 'n', 'e']) {
        const { [member]: _, ...without } = key('a') as Record<string, unknown>;
        expect(parsePublicKeys(JSON.stringify(without)).error).toContain(`"${member}"`);
        expect(parsePublicKeys(JSON.stringify({ ...without, [member]: '  ' })).error).toContain(`"${member}"`);
      }
    });

    it('refuses every private member, and names it', () => {
      for (const member of ['d', 'p', 'q', 'dp', 'dq', 'qi']) {
        expect(parsePublicKeys(JSON.stringify(key('a', { [member]: 'x' }))).error).toContain(`"${member}"`);
      }
    });

    it('refuses a modulus outside 2048 to 8192 bits and takes the edges of the range', () => {
      expect(parsePublicKeys(JSON.stringify(key('a', { n: 'q'.repeat(341) }))).error).toContain('2048 to 8192');
      expect(parsePublicKeys(JSON.stringify(key('a', { n: 'q'.repeat(342) }))).error).toBe('');
      expect(parsePublicKeys(JSON.stringify(key('a', { n: 'q'.repeat(1366) }))).error).toBe('');
      expect(parsePublicKeys(JSON.stringify(key('a', { n: 'q'.repeat(1368) }))).error).toContain('2048 to 8192');
    });

    it('refuses a repeated kid or a repeated key, naming which key has the problem', () => {
      expect(parsePublicKeys(JSON.stringify([key('a'), key('b', { kid: 'a' })])).error).toContain('same "kid"');
      expect(parsePublicKeys(JSON.stringify([key('a'), key('b', { n: key('a').n })])).error).toContain('same key');
      expect(parsePublicKeys(JSON.stringify([key('a'), key('b', { kty: 'EC' })])).error).toMatch(/^Key 2: /);
    });

    it('shows a key set for editing so that it reads back the same', () => {
      expect(publicKeysText([])).toBe('');
      for (const keys of [[key('a')], [key('a'), key('b')]] as PublicJwk[][]) expect(parsePublicKeys(publicKeysText(keys)).keys).toEqual(keys);
    });
  });
});
