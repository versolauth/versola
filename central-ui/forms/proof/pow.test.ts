import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { isSolution, leadingZeroBits, sha256, solve } from './pow';

const hex = (bytes: Uint8Array) => [...bytes].map(b => b.toString(16).padStart(2, '0')).join('');

describe('sha256', () => {
  it('matches the platform on messages of every padding length', () => {
    for (const length of [0, 1, 3, 55, 56, 57, 63, 64, 65, 119, 120, 200]) {
      const message = Buffer.alloc(length, 'a');
      expect(hex(sha256(message))).toBe(createHash('sha256').update(message).digest('hex'));
    }
  });

  it('knows the digest of abc', () => {
    expect(hex(sha256(new TextEncoder().encode('abc')))).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  });
});

describe('leadingZeroBits', () => {
  it('counts whole zero bytes and the zero bits of the first other', () => {
    expect(leadingZeroBits(Uint8Array.from([0, 0, 1]))).toBe(23);
    expect(leadingZeroBits(Uint8Array.from([0x0f]))).toBe(4);
    expect(leadingZeroBits(Uint8Array.from([0x80]))).toBe(0);
  });
});

describe('solve', () => {
  it('finds a nonce that the server-side rule accepts', async () => {
    const nonce = await solve('seed-1', 10);
    expect(nonce).toMatch(/^\d+$/);
    expect(isSolution('seed-1', nonce!, 10)).toBe(true);
    const digest = createHash('sha256').update(`seed-1:${nonce}`).digest();
    expect(digest[0]).toBe(0);
    expect(digest[1] >> 6).toBe(0);
  });

  it('stops when it is cancelled', async () => {
    expect(await solve('seed-2', 60, () => true, 1)).toBeNull();
  });
});
