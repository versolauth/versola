import { describe, expect, it } from 'vitest';
import { getStoredZoneChoice, resolveZone, setStoredZoneChoice, ZONE_STORAGE_KEY } from './zone-preference';
import { localZone } from './date-format';

function memoryStore(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => { data.set(key, value); },
    data,
  };
}

describe('the remembered time zone choice', () => {
  it('is Local until something is stored', () => {
    expect(getStoredZoneChoice(memoryStore())).toBe('local');
  });

  it('remembers UTC and Local', () => {
    const store = memoryStore();
    setStoredZoneChoice('utc', store);
    expect(store.data.get(ZONE_STORAGE_KEY)).toBe('utc');
    expect(getStoredZoneChoice(store)).toBe('utc');
    setStoredZoneChoice('local', store);
    expect(getStoredZoneChoice(store)).toBe('local');
  });

  it('ignores a stored value it does not know', () => {
    expect(getStoredZoneChoice(memoryStore({ [ZONE_STORAGE_KEY]: 'Mars/Olympus' }))).toBe('local');
  });

  it('survives a storage that throws', () => {
    const broken = {
      getItem: () => { throw new Error('denied'); },
      setItem: () => { throw new Error('denied'); },
    };
    expect(getStoredZoneChoice(broken)).toBe('local');
    expect(() => setStoredZoneChoice('utc', broken)).not.toThrow();
    expect(getStoredZoneChoice(null)).toBe('local');
  });

  it('resolves Local to the browser zone and UTC to UTC', () => {
    expect(resolveZone('local')).toBe(localZone());
    expect(resolveZone('utc')).toBe('UTC');
  });
});
