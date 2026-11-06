import { describe, expect, it } from 'vitest';
import { applyScheme, readScheme, SCHEME_STORAGE_KEY } from './scheme';

const memory = () => {
  const data = new Map<string, string>();
  return {
    data,
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
    removeItem: (k: string) => void data.delete(k),
  };
};
const blocked = {
  getItem: () => { throw new Error('blocked'); },
  setItem: () => { throw new Error('blocked'); },
  removeItem: () => { throw new Error('blocked'); },
};

describe('colour scheme choice', () => {
  it('reads light and dark, and anything else as auto', () => {
    const store = memory();
    expect(readScheme(store)).toBe('auto');
    store.data.set(SCHEME_STORAGE_KEY, 'dark');
    expect(readScheme(store)).toBe('dark');
    store.data.set(SCHEME_STORAGE_KEY, 'light');
    expect(readScheme(store)).toBe('light');
    store.data.set(SCHEME_STORAGE_KEY, 'sepia');
    expect(readScheme(store)).toBe('auto');
  });

  it('applies the choice to the page and stores it; auto clears both', () => {
    const store = memory();
    const root = { style: { colorScheme: '' } };

    applyScheme('dark', root, store);
    expect(root.style.colorScheme).toBe('dark');
    expect(store.data.get(SCHEME_STORAGE_KEY)).toBe('dark');

    applyScheme('auto', root, store);
    expect(root.style.colorScheme).toBe('');
    expect(store.data.has(SCHEME_STORAGE_KEY)).toBe(false);
  });

  it('still applies the choice to the page when storage is blocked or missing', () => {
    const root = { style: { colorScheme: '' } };
    expect(readScheme(blocked)).toBe('auto');
    applyScheme('light', root, blocked);
    expect(root.style.colorScheme).toBe('light');
    applyScheme('dark', root, undefined);
    expect(root.style.colorScheme).toBe('dark');
  });
});
