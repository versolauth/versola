import { offsetLabel } from './date-format';

export type ZoneOption = { id: string; label: string };

/** The IANA names this browser knows, `UTC` among them (some engines leave it out of the list), each with the offset in force at `at`. */
export function zoneOptions(at: Date = new Date(), names: string[] = supportedZones()): ZoneOption[] {
  const unique = [...new Set(['UTC', ...names])].sort((a, b) => a.localeCompare(b, 'en'));
  return unique.map(id => ({ id, label: id === 'UTC' ? id : `${id} (${offsetLabel(at, id)})` }));
}

function supportedZones(): string[] {
  try {
    return (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf?.('timeZone') ?? [];
  } catch {
    return [];
  }
}
