import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { isSolution, search, solve } from './pow';

const zeros = (seed: string, nonce: number) => {
  const digest = createHash('sha256').update(`${seed}:${nonce}`).digest();
  let bits = 0;
  for (const byte of digest) { if (byte === 0) { bits += 8; continue; } return bits + Math.clz32(byte) - 24; }
  return bits;
};

describe('search', () => {
  it('agrees with the platform on every nonce it accepts, and on the ones it passes by', () => {
    const seed = 'q3Zr_8aK1x0Wm2c4VbN7eA';
    for (let nonce = 0; nonce < 3000; nonce++) {
      expect(search(seed, 8, nonce, 1) === nonce, `nonce ${nonce}`).toBe(zeros(seed, nonce) >= 8);
    }
  });

  it('handles nonces of every length, up to the ones the server accepts', () => {
    const seed = 'q3Zr_8aK1x0Wm2c4VbN7eA';
    for (const nonce of [0, 9, 10, 99, 100, 123456789, 4294967296, 9007199254740991]) {
      expect(search(seed, 0, nonce, 1)).toBe(nonce);
    }
    expect(search(seed, 8, 0, 1000, 3) % 3).toBe(0);
  });
});

describe('solve', () => {
  it('finds a nonce that the server-side rule accepts', async () => {
    const seed = 'seed-1';
    const nonce = await solve(seed, 12);
    expect(nonce).toMatch(/^\d+$/);
    expect(isSolution(seed, nonce!, 12)).toBe(true);
    expect(zeros(seed, Number(nonce))).toBeGreaterThanOrEqual(12);
  });

  it('stops when it is cancelled', async () => {
    expect(await solve('seed-2', 60, () => true, 1)).toBeNull();
  });
});
