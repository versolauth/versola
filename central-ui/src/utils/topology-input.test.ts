import { describe, expect, it } from 'vitest';
import { addressError, certificatesOf, validId } from './topology-input';

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
});
