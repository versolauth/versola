import { describe, expect, it } from 'vitest';
import { durationSeconds, formatDurationShort, parseDurationShort, splitDuration } from './duration';

describe('splitDuration', () => {
  it('picks the largest unit that divides evenly', () => {
    expect(splitDuration(86400)).toEqual({ value: 1, unit: 'days' });
    expect(splitDuration(15552000)).toEqual({ value: 180, unit: 'days' });
    expect(splitDuration(900)).toEqual({ value: 15, unit: 'minutes' });
    expect(splitDuration(7200)).toEqual({ value: 2, unit: 'hours' });
    expect(splitDuration(45)).toEqual({ value: 45, unit: 'seconds' });
    expect(splitDuration(0)).toEqual({ value: 0, unit: 'seconds' });
  });

  it('round-trips through durationSeconds', () => {
    for (const seconds of [1, 59, 60, 3600, 5400, 86400, 15552000]) {
      expect(durationSeconds(splitDuration(seconds))).toBe(seconds);
    }
    expect(durationSeconds({ value: 1.5, unit: 'hours' })).toBe(5400);
  });

  it('formats the short value', () => {
    expect(formatDurationShort(900)).toBe('15m');
    expect(formatDurationShort(86400)).toBe('1d');
    expect(formatDurationShort(300)).toBe('5m');
    expect(formatDurationShort(90)).toBe('90s');
    expect(formatDurationShort(0)).toBe('0s');
  });
});

describe('parseDurationShort', () => {
  it('reads a number with a unit as seconds, and a bare number as seconds', () => {
    expect(parseDurationShort('5m')).toBe(300);
    expect(parseDurationShort('1h')).toBe(3600);
    expect(parseDurationShort('2D')).toBe(172800);
    expect(parseDurationShort('45s')).toBe(45);
    expect(parseDurationShort(' 90 ')).toBe(90);
    expect(parseDurationShort('10 m')).toBe(600);
  });

  it('refuses anything else', () => {
    for (const text of ['', 'm', '-5m', '1.5h', '5x', '5 minutes']) expect(parseDurationShort(text)).toBeNull();
  });

  it('round-trips what formatDurationShort writes', () => {
    for (const seconds of [1, 59, 60, 300, 3600, 5400, 86400]) expect(parseDurationShort(formatDurationShort(seconds))).toBe(seconds);
  });
});
