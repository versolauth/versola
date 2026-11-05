import { describe, expect, it } from 'vitest';
import {
  dayKey,
  formatDate,
  formatDateField,
  formatDateTime,
  formatShortDateTime,
  formatTime,
  formatTimeField,
  formatWithOffset,
  isKnownZone,
  offsetLabel,
  parseDateField,
  parseFieldsInZone,
  parseTimeField,
  wallClock,
  zonedInstant,
} from './date-format';

const instant = '2026-11-03T21:14:07Z';

describe('formatting in a zone', () => {
  it('rolls the day over in a zone ahead of UTC and keeps it in UTC', () => {
    expect(formatDateTime(instant, 'Asia/Almaty')).toBe('Nov 4, 2026, 02:14:07');
    expect(formatDateTime(instant, 'UTC')).toBe('Nov 3, 2026, 21:14:07');
    expect(dayKey(instant, 'Asia/Almaty')).toBe('2026-11-04');
    expect(dayKey(instant, 'UTC')).toBe('2026-11-03');
  });

  it('rolls the day back in a zone behind UTC', () => {
    expect(formatDateTime('2026-11-04T02:30:00Z', 'America/Los_Angeles')).toBe('Nov 3, 2026, 18:30:00');
    expect(dayKey('2026-11-04T02:30:00Z', 'America/Los_Angeles')).toBe('2026-11-03');
  });

  it('writes midnight as 00, never 24, and a 24-hour clock', () => {
    expect(formatTime('2026-11-04T00:00:05Z', 'UTC')).toBe('00:00:05');
    expect(formatTime('2026-11-04T15:04:09Z', 'UTC', { seconds: false })).toBe('15:04');
  });

  it('writes short and long forms', () => {
    expect(formatDate(instant, 'UTC')).toBe('Nov 3, 2026');
    expect(formatShortDateTime(instant, 'UTC', { seconds: false })).toBe('Nov 3, 21:14');
    expect(formatShortDateTime(instant, 'UTC')).toBe('Nov 3, 21:14:07');
  });

  it('names the offset, whole hours, half hours, and UTC itself', () => {
    expect(offsetLabel(instant, 'Asia/Almaty')).toBe('UTC+5');
    expect(offsetLabel(instant, 'Asia/Kolkata')).toBe('UTC+5:30');
    expect(offsetLabel(instant, 'America/St_Johns')).toBe('UTC-3:30');
    expect(offsetLabel(instant, 'UTC')).toBe('UTC');
    expect(formatWithOffset(instant, 'Asia/Almaty')).toBe('Nov 4, 2026, 02:14:07 (UTC+5)');
    expect(formatWithOffset(instant, 'UTC')).toBe('Nov 3, 2026, 21:14:07 UTC');
  });

  it('follows daylight saving time: the offset is the one in force at that instant', () => {
    expect(offsetLabel('2026-01-15T12:00:00Z', 'America/New_York')).toBe('UTC-5');
    expect(offsetLabel('2026-07-15T12:00:00Z', 'America/New_York')).toBe('UTC-4');
    // The clocks went forward at 07:00Z on 8 March 2026 and back at 06:00Z on 1 November 2026.
    expect(formatTime('2026-03-08T06:59:59Z', 'America/New_York')).toBe('01:59:59');
    expect(formatTime('2026-03-08T07:00:00Z', 'America/New_York')).toBe('03:00:00');
    expect(formatTime('2026-11-01T05:59:59Z', 'America/New_York')).toBe('01:59:59');
    expect(formatTime('2026-11-01T06:00:00Z', 'America/New_York')).toBe('01:00:00');
  });

  it('gives a day of 23 and of 25 hours its own boundaries', () => {
    // 1 November 2026 in New York is 25 hours long: midnight to midnight is 04:00Z to 05:00Z the next day.
    const start = zonedInstant({ year: 2026, month: 11, day: 1, hour: 0, minute: 0, second: 0 }, 'America/New_York');
    const end = zonedInstant({ year: 2026, month: 11, day: 2, hour: 0, minute: 0, second: 0 }, 'America/New_York');
    expect(start.toISOString()).toBe('2026-11-01T04:00:00.000Z');
    expect(end.toISOString()).toBe('2026-11-02T05:00:00.000Z');
    expect((end.getTime() - start.getTime()) / 3_600_000).toBe(25);
  });

  it('accepts a Date and epoch milliseconds as well as a string', () => {
    expect(formatTime(new Date(instant), 'UTC')).toBe('21:14:07');
    expect(formatTime(Date.parse(instant), 'UTC')).toBe('21:14:07');
    expect(wallClock(instant, 'UTC')).toEqual({ year: 2026, month: 11, day: 3, hour: 21, minute: 14, second: 7 });
  });

  it('knows a zone name from a made-up one', () => {
    expect(isKnownZone('Asia/Almaty')).toBe(true);
    expect(isKnownZone('Mars/Olympus')).toBe(false);
  });
});

describe('reading a wall clock in a zone', () => {
  it('turns what an operator typed into the UTC instant the API takes', () => {
    expect(parseFieldsInZone('1 Nov 2026', '00:00', 'Asia/Almaty')?.toISOString()).toBe('2026-10-31T19:00:00.000Z');
    expect(parseFieldsInZone('1 Nov 2026', '00:00', 'UTC')?.toISOString()).toBe('2026-11-01T00:00:00.000Z');
    expect(parseFieldsInZone('4 Nov 2026', '12:00', 'Asia/Almaty')?.toISOString()).toBe('2026-11-04T07:00:00.000Z');
  });

  it('takes the first of two identical times when the clocks go back, and the time after the gap when they go forward', () => {
    // 01:30 on 1 Nov 2026 in New York is 05:30Z (EDT) and again 06:30Z (EST).
    expect(zonedInstant({ year: 2026, month: 11, day: 1, hour: 1, minute: 30, second: 0 }, 'America/New_York').toISOString()).toBe('2026-11-01T05:30:00.000Z');
    // 02:30 on 8 Mar 2026 does not exist in New York.
    expect(zonedInstant({ year: 2026, month: 3, day: 8, hour: 2, minute: 30, second: 0 }, 'America/New_York').toISOString()).toBe('2026-03-08T07:30:00.000Z');
  });

  it('round-trips an instant through its own wall clock', () => {
    for (const zone of ['Asia/Almaty', 'America/New_York', 'Europe/London', 'Pacific/Auckland', 'UTC']) {
      const at = new Date('2026-06-15T10:20:30Z');
      expect(zonedInstant(wallClock(at, zone), zone).toISOString()).toBe(at.toISOString());
    }
  });

  it('is null when a field is not a real date or time', () => {
    expect(parseFieldsInZone('31 Feb 2026', '10:00', 'UTC')).toBeNull();
    expect(parseFieldsInZone('1 Nov 2026', '24:00', 'UTC')).toBeNull();
    expect(parseFieldsInZone('yesterday', '10:00', 'UTC')).toBeNull();
  });
});

describe('the range fields', () => {
  it('write a date as 1 Nov 2026 and a time on a 24-hour clock', () => {
    expect(formatDateField('2026-11-01T00:00:00Z', 'UTC')).toBe('1 Nov 2026');
    expect(formatTimeField('2026-11-01T15:05:00Z', 'UTC')).toBe('15:05');
  });

  it('read the written form, a lower-case month and ISO', () => {
    expect(parseDateField('1 Nov 2026')).toEqual({ year: 2026, month: 11, day: 1 });
    expect(parseDateField('  04 nov 2026 ')).toEqual({ year: 2026, month: 11, day: 4 });
    expect(parseDateField('2026-11-01')).toEqual({ year: 2026, month: 11, day: 1 });
    expect(parseDateField('1 Sept 2026')).toEqual({ year: 2026, month: 9, day: 1 });
  });

  it('refuse what is not a date', () => {
    expect(parseDateField('')).toBeNull();
    expect(parseDateField('1 Foo 2026')).toBeNull();
    expect(parseDateField('29 Feb 2026')).toBeNull();
    expect(parseDateField('11/04/2026')).toBeNull();
  });

  it('read 9:05 and refuse 12 PM, 24:00 and 10:75', () => {
    expect(parseTimeField('9:05')).toEqual({ hour: 9, minute: 5 });
    expect(parseTimeField('00:00')).toEqual({ hour: 0, minute: 0 });
    expect(parseTimeField('12 PM')).toBeNull();
    expect(parseTimeField('24:00')).toBeNull();
    expect(parseTimeField('10:75')).toBeNull();
  });
});
