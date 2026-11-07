import { isKnownZone, localZone } from './date-format';

/**
 * Which zone a screen shows times in: the administrator's own (`zone`, the `zoneinfo` of their profile, set under Preferences),
 * the browser's (`local`) or UTC. Nothing chosen reads as the profile zone when there is one and as the browser's otherwise,
 * so a screen is the same on every browser the administrator opens.
 */
export type ZoneChoice = 'zone' | 'local' | 'utc';

export const ZONE_STORAGE_KEY = 'versola-time-zone';

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

function defaultStore(): Store | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

/** What this browser remembers of an explicit switch, `null` when nothing valid is stored or the storage is unavailable. */
export function getStoredZoneChoice(store: Store | null = defaultStore()): ZoneChoice | null {
  try {
    const value = store?.getItem(ZONE_STORAGE_KEY);
    return value === 'zone' || value === 'local' || value === 'utc' ? value : null;
  } catch {
    return null;
  }
}

export function setStoredZoneChoice(choice: ZoneChoice, store: Store | null = defaultStore()): void {
  try {
    store?.setItem(ZONE_STORAGE_KEY, choice);
  } catch {
    // A browser that refuses storage keeps the choice for the page only.
  }
}

/** Forgets the switch, so the profile zone is the default again (saving a zone under Preferences does this). */
export function clearStoredZoneChoice(store: Store | null = defaultStore()): void {
  try {
    store?.removeItem(ZONE_STORAGE_KEY);
  } catch {
    // Nothing to forget in a browser that refuses storage.
  }
}

/** A profile zone this browser can format with, else `null`: a stale or odd value never breaks a screen. A UTC offset (`+05:00`) is
 *  accepted by `Intl` but is no `zoneinfo`, which names a zone of the IANA database, so it is refused too. */
export function usableZone(zone: string | null | undefined): string | null {
  return zone && /^[A-Za-z]/.test(zone) && isKnownZone(zone) ? zone : null;
}

/**
 * The choice in force: the remembered one, else the profile zone, else the browser's. A profile zone that is the browser's own
 * reads as `local`, so the switch never offers two buttons for one zone; a remembered `zone` with no profile zone to show falls
 * back the same way.
 */
export function effectiveZoneChoice(stored: ZoneChoice | null, profileZone: string | null, browserZone: string = localZone()): ZoneChoice {
  if (stored === 'utc' || stored === 'local') return stored;
  return profileZone && profileZone !== browserZone ? 'zone' : 'local';
}

/** The IANA zone a choice stands for. */
export function resolveZone(choice: ZoneChoice, profileZone: string | null = getProfileZone()): string {
  if (choice === 'utc') return 'UTC';
  if (choice === 'zone') return profileZone ?? localZone();
  return localZone();
}

// The administrator's own zone, loaded once by the shell and changed under Preferences. A screen reads it when it opens and
// listens for a change, so what is saved shows without a reload.
let profileZone: string | null = null;
const listeners = new Set<() => void>();

export function getProfileZone(): string | null {
  return profileZone;
}

export function setProfileZone(zone: string | null | undefined): void {
  const next = usableZone(zone);
  if (next === profileZone) return;
  profileZone = next;
  listeners.forEach(listener => listener());
}

/** Calls `listener` whenever the profile zone changes; returns the function that stops it. */
export function onProfileZoneChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** The zone a screen without a switch of its own shows times in: the same one the Activity tab is on now. */
export function currentZone(): string {
  return resolveZone(effectiveZoneChoice(getStoredZoneChoice(), getProfileZone()), getProfileZone());
}
