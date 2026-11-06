/**
 * Date and time formatting in a named time zone, one helper for screens that show instants in the
 * operator's zone or in UTC. Everything takes the zone as an IANA name (`Asia/Almaty`, `UTC`) and an
 * instant as a `Date`, an ISO string or epoch milliseconds, and writes the same English text in every
 * browser (`Nov 4, 2026, 14:41:12`, 24-hour clock), so a screen and its tests agree.
 */

export type Instant = Date | string | number;

export type WallClock = { year: number; month: number; day: number; hour: number; minute: number; second: number };

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatterFor(zone: string): Intl.DateTimeFormat {
  let formatter = formatters.get(zone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: 'numeric',
      day: 'numeric',
      hour: 'numeric',
      minute: 'numeric',
      second: 'numeric',
    });
    formatters.set(zone, formatter);
  }
  return formatter;
}

function epoch(instant: Instant): number {
  return instant instanceof Date ? instant.getTime() : new Date(instant).getTime();
}

/** The browser's own time zone, `UTC` when it does not say. */
export function localZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

/** Whether the browser knows this IANA zone name. */
export function isKnownZone(zone: string): boolean {
  try {
    formatterFor(zone);
    return true;
  } catch {
    return false;
  }
}

/** The wall clock of an instant in a zone. */
export function wallClock(instant: Instant, zone: string): WallClock {
  // Whole seconds: the formatter drops the milliseconds, and a time before the epoch must not round up.
  const ms = Math.floor(epoch(instant) / 1000) * 1000;
  const values: Record<string, number> = {};
  for (const part of formatterFor(zone).formatToParts(ms)) {
    if (part.type !== 'literal') values[part.type] = Number(part.value);
  }
  return {
    year: values.year,
    month: values.month,
    day: values.day,
    hour: values.hour === 24 ? 0 : values.hour,
    minute: values.minute,
    second: values.second,
  };
}

/** Milliseconds the zone is ahead of UTC at this instant (negative to the west). */
export function offsetMs(instant: Instant, zone: string): number {
  const ms = Math.floor(epoch(instant) / 1000) * 1000;
  const clock = wallClock(ms, zone);
  return Date.UTC(clock.year, clock.month - 1, clock.day, clock.hour, clock.minute, clock.second) - ms;
}

/** `UTC` for UTC, else `UTC+5`, `UTC-3:30`, the offset in force at this instant. */
export function offsetLabel(instant: Instant, zone: string): string {
  const minutes = Math.round(offsetMs(instant, zone) / 60_000);
  if (minutes === 0) return 'UTC';
  const sign = minutes < 0 ? '-' : '+';
  const abs = Math.abs(minutes);
  const hours = Math.floor(abs / 60);
  const rest = abs % 60;
  return `UTC${sign}${hours}${rest ? `:${String(rest).padStart(2, '0')}` : ''}`;
}

const pad = (value: number) => String(value).padStart(2, '0');

/** `14:41:12`, or `14:41` without seconds. */
export function formatTime(instant: Instant, zone: string, options: { seconds?: boolean } = {}): string {
  const clock = wallClock(instant, zone);
  const base = `${pad(clock.hour)}:${pad(clock.minute)}`;
  return options.seconds === false ? base : `${base}:${pad(clock.second)}`;
}

/** `Nov 4, 2026`. */
export function formatDate(instant: Instant, zone: string): string {
  const clock = wallClock(instant, zone);
  return `${MONTHS[clock.month - 1]} ${clock.day}, ${clock.year}`;
}

/** `Nov 4, 14:41` (the year left out), or with seconds `Nov 4, 14:41:12`. */
export function formatShortDateTime(instant: Instant, zone: string, options: { seconds?: boolean } = {}): string {
  const clock = wallClock(instant, zone);
  return `${MONTHS[clock.month - 1]} ${clock.day}, ${formatTime(instant, zone, options)}`;
}

/** `Nov 4, 2026, 14:41:12`. */
export function formatDateTime(instant: Instant, zone: string, options: { seconds?: boolean } = {}): string {
  return `${formatDate(instant, zone)}, ${formatTime(instant, zone, options)}`;
}

/** `Nov 4, 2026, 02:14:07 (UTC+5)`; UTC reads `Nov 3, 2026, 21:14:07 UTC`. */
export function formatWithOffset(instant: Instant, zone: string): string {
  const label = offsetLabel(instant, zone);
  const text = formatDateTime(instant, zone);
  return label === 'UTC' ? `${text} UTC` : `${text} (${label})`;
}

/** The key of the calendar day an instant falls on in the zone (`2026-11-04`), for grouping rows by day. */
export function dayKey(instant: Instant, zone: string): string {
  const clock = wallClock(instant, zone);
  return `${clock.year}-${pad(clock.month)}-${pad(clock.day)}`;
}

/** The instant a wall clock reads in the zone. A time that does not exist (the hour skipped when the clocks go
 *  forward) reads as the same moment after the change; a time that happens twice (when they go back) is the first. */
export function zonedInstant(clock: WallClock, zone: string): Date {
  const guess = Date.UTC(clock.year, clock.month - 1, clock.day, clock.hour, clock.minute, clock.second);
  const first = guess - offsetMs(guess, zone);
  const firstOffset = offsetMs(first, zone);
  if (guess - firstOffset === first) return new Date(first);
  const second = guess - firstOffset;
  if (guess - offsetMs(second, zone) === second) return new Date(second);
  return new Date(Math.max(first, second));
}

/** `1 Nov 2026`, the date field of a range. */
export function formatDateField(instant: Instant, zone: string): string {
  const clock = wallClock(instant, zone);
  return `${clock.day} ${MONTHS[clock.month - 1]} ${clock.year}`;
}

/** `00:00`, the time field of a range (24-hour). */
export function formatTimeField(instant: Instant, zone: string): string {
  return formatTime(instant, zone, { seconds: false });
}

/** Reads `1 Nov 2026`, `01 nov 2026` or `2026-11-01`; null when it is not a real calendar date. */
export function parseDateField(text: string): Pick<WallClock, 'year' | 'month' | 'day'> | null {
  const value = text.trim();
  let year: number;
  let month: number;
  let day: number;
  const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(value);
  const written = /^(\d{1,2})\s+([A-Za-z]{3,9})\.?\s+(\d{4})$/.exec(value);
  if (iso) {
    year = Number(iso[1]);
    month = Number(iso[2]);
    day = Number(iso[3]);
  } else if (written) {
    day = Number(written[1]);
    const index = MONTHS.findIndex(name => written[2].toLowerCase().startsWith(name.toLowerCase()));
    if (index < 0 || written[2].length < 3) return null;
    month = index + 1;
    year = Number(written[3]);
  } else {
    return null;
  }
  const check = new Date(Date.UTC(year, month - 1, day));
  if (check.getUTCFullYear() !== year || check.getUTCMonth() !== month - 1 || check.getUTCDate() !== day) return null;
  return { year, month, day };
}

/** Reads a 24-hour `14:30` (or `9:05`); null for anything else, `24:00` included. */
export function parseTimeField(text: string): Pick<WallClock, 'hour' | 'minute'> | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(text.trim());
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) return null;
  return { hour, minute };
}

/** The instant two fields read as in the zone, or null when either is not valid. */
export function parseFieldsInZone(dateText: string, timeText: string, zone: string): Date | null {
  const date = parseDateField(dateText);
  const time = parseTimeField(timeText);
  if (!date || !time) return null;
  return zonedInstant({ ...date, ...time, second: 0 }, zone);
}
