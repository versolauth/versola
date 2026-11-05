import { localZone } from './date-format';

/** Which zone a screen shows times in: the browser's (`local`, the default) or UTC. */
export type ZoneChoice = 'local' | 'utc';

export const ZONE_STORAGE_KEY = 'versola-time-zone';

type Store = Pick<Storage, 'getItem' | 'setItem'>;

function defaultStore(): Store | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

/** The remembered choice, `local` when nothing valid is stored or the storage is unavailable. */
export function getStoredZoneChoice(store: Store | null = defaultStore()): ZoneChoice {
  try {
    return store?.getItem(ZONE_STORAGE_KEY) === 'utc' ? 'utc' : 'local';
  } catch {
    return 'local';
  }
}

export function setStoredZoneChoice(choice: ZoneChoice, store: Store | null = defaultStore()): void {
  try {
    store?.setItem(ZONE_STORAGE_KEY, choice);
  } catch {
    // A browser that refuses storage keeps the choice for the page only.
  }
}

/** The IANA zone a choice stands for. */
export function resolveZone(choice: ZoneChoice): string {
  return choice === 'utc' ? 'UTC' : localZone();
}
