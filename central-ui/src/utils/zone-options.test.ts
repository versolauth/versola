import { describe, expect, it } from 'vitest';
import { zoneOptions } from './zone-options';

describe('the zone options', () => {
  const winter = new Date('2026-01-15T12:00:00Z');

  it('label each name with its offset at the given time', () => {
    const options = zoneOptions(winter, ['Asia/Almaty', 'Europe/Berlin', 'America/St_Johns']);
    expect(options.find(option => option.id === 'Asia/Almaty')?.label).toBe('Asia/Almaty (UTC+5)');
    expect(options.find(option => option.id === 'Europe/Berlin')?.label).toBe('Europe/Berlin (UTC+1)');
    expect(options.find(option => option.id === 'America/St_Johns')?.label).toBe('America/St_Johns (UTC-3:30)');
  });

  it('read the offset in force then, not today', () => {
    const summer = new Date('2026-07-15T12:00:00Z');
    expect(zoneOptions(summer, ['Europe/Berlin']).find(option => option.id === 'Europe/Berlin')?.label).toBe('Europe/Berlin (UTC+2)');
  });

  it('always offer UTC once, first among equals, and sort by name', () => {
    const options = zoneOptions(winter, ['Europe/Berlin', 'UTC', 'Asia/Almaty']);
    expect(options.map(option => option.id)).toEqual(['Asia/Almaty', 'Europe/Berlin', 'UTC']);
    expect(zoneOptions(winter, []).map(option => option.id)).toEqual(['UTC']);
    expect(options.find(option => option.id === 'UTC')?.label).toBe('UTC');
  });

  it('list the browser zones by default, every one a zone the formatter accepts', () => {
    const options = zoneOptions(winter);
    expect(options.length).toBeGreaterThan(100);
    expect(options.some(option => option.id === 'Asia/Almaty')).toBe(true);
  });
});
