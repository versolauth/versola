import { afterEach, describe, expect, it } from 'vitest';
import {
  clearStoredZoneChoice,
  effectiveZoneChoice,
  getProfileZone,
  getStoredZoneChoice,
  onProfileZoneChange,
  resolveZone,
  setProfileZone,
  setStoredZoneChoice,
  usableZone,
  ZONE_STORAGE_KEY,
} from './zone-preference';
import { localZone } from './date-format';

function memoryStore(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => { data.set(key, value); },
    removeItem: (key: string) => { data.delete(key); },
    data,
  };
}

describe('the remembered time zone choice', () => {
  it('is nothing until something is stored', () => {
    expect(getStoredZoneChoice(memoryStore())).toBeNull();
  });

  it('remembers each choice and forgets them', () => {
    const store = memoryStore();
    for (const choice of ['utc', 'local', 'zone'] as const) {
      setStoredZoneChoice(choice, store);
      expect(store.data.get(ZONE_STORAGE_KEY)).toBe(choice);
      expect(getStoredZoneChoice(store)).toBe(choice);
    }
    clearStoredZoneChoice(store);
    expect(getStoredZoneChoice(store)).toBeNull();
  });

  it('ignores a stored value it does not know', () => {
    expect(getStoredZoneChoice(memoryStore({ [ZONE_STORAGE_KEY]: 'Mars/Olympus' }))).toBeNull();
  });

  it('survives a storage that throws', () => {
    const broken = {
      getItem: () => { throw new Error('denied'); },
      setItem: () => { throw new Error('denied'); },
      removeItem: () => { throw new Error('denied'); },
    };
    expect(getStoredZoneChoice(broken)).toBeNull();
    expect(() => setStoredZoneChoice('utc', broken)).not.toThrow();
    expect(() => clearStoredZoneChoice(broken)).not.toThrow();
    expect(getStoredZoneChoice(null)).toBeNull();
  });
});

describe('the choice in force', () => {
  it('is the profile zone when nothing is remembered and the profile has one that is not the browser zone', () => {
    expect(effectiveZoneChoice(null, 'Asia/Almaty', 'Europe/Berlin')).toBe('zone');
    expect(effectiveZoneChoice('zone', 'Asia/Almaty', 'Europe/Berlin')).toBe('zone');
  });

  it('is the browser zone without a profile zone, or when the profile zone is the browser zone', () => {
    expect(effectiveZoneChoice(null, null, 'Europe/Berlin')).toBe('local');
    expect(effectiveZoneChoice('zone', null, 'Europe/Berlin')).toBe('local');
    expect(effectiveZoneChoice(null, 'Europe/Berlin', 'Europe/Berlin')).toBe('local');
  });

  it('is what the administrator switched to, Local and UTC alike, over the profile zone', () => {
    expect(effectiveZoneChoice('utc', 'Asia/Almaty', 'Europe/Berlin')).toBe('utc');
    expect(effectiveZoneChoice('local', 'Asia/Almaty', 'Europe/Berlin')).toBe('local');
  });
});

describe('the zone a choice stands for', () => {
  it('resolves Local to the browser zone, UTC to UTC and the zone choice to the profile zone', () => {
    expect(resolveZone('local', 'Asia/Almaty')).toBe(localZone());
    expect(resolveZone('utc', 'Asia/Almaty')).toBe('UTC');
    expect(resolveZone('zone', 'Asia/Almaty')).toBe('Asia/Almaty');
  });

  it('falls back to the browser zone when the zone choice has no profile zone', () => {
    expect(resolveZone('zone', null)).toBe(localZone());
  });
});

describe('the profile zone', () => {
  afterEach(() => setProfileZone(null));

  it('accepts a zone this browser knows and refuses anything else', () => {
    expect(usableZone('Asia/Almaty')).toBe('Asia/Almaty');
    expect(usableZone('UTC')).toBe('UTC');
    expect(usableZone('Mars/Olympus')).toBeNull();
    expect(usableZone('+05:00')).toBeNull();
    expect(usableZone('')).toBeNull();
    expect(usableZone(null)).toBeNull();
    setProfileZone('Mars/Olympus');
    expect(getProfileZone()).toBeNull();
  });

  it('tells a listener once per change, and not after it stops', () => {
    let calls = 0;
    const stop = onProfileZoneChange(() => { calls += 1; });
    setProfileZone('Asia/Almaty');
    setProfileZone('Asia/Almaty');
    expect(calls).toBe(1);
    setProfileZone(null);
    expect(calls).toBe(2);
    stop();
    setProfileZone('UTC');
    expect(calls).toBe(2);
  });
});
