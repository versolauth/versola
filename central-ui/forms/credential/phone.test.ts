import { describe, expect, it } from 'vitest';
import { detect, fits, format, groupByDialCode, matches, nationalDigits, type PhoneRegion } from './phone';

const kz: PhoneRegion = { region: 'KZ', dialCode: '+7', length: 10, regex: '7\\d{9}' };
const ru: PhoneRegion = { region: 'RU', dialCode: '+7', length: 10, regex: '9\\d{9}' };
const uz: PhoneRegion = { region: 'UZ', dialCode: '+998', length: 9, regex: '\\d{9}' };
const us: PhoneRegion = { region: 'US', dialCode: '+1', length: 10, regex: '\\d{10}' };
const bs: PhoneRegion = { region: 'BS', dialCode: '+1242', length: 7, regex: '\\d{7}' };

describe('groupByDialCode', () => {
  it('merges regions sharing a code and keeps the order of first appearance', () => {
    const groups = groupByDialCode([kz, uz, ru]);
    expect(groups.map((g) => g.dialCode)).toEqual(['+7', '+998']);
    expect(groups[0].regions.map((r) => r.region)).toEqual(['KZ', 'RU']);
  });
});

describe('fits', () => {
  it('takes numbers of either region sharing a code, and neither otherwise', () => {
    const [group] = groupByDialCode([kz, ru]);
    expect(fits(group, '7012345678')).toBe(true);
    expect(fits(group, '9012345678')).toBe(true);
    expect(fits(group, '1012345678')).toBe(false);
  });
  it('with a single region takes only its numbers', () => {
    expect(fits(groupByDialCode([ru])[0], '7012345678')).toBe(false);
    expect(fits(groupByDialCode([kz])[0], '7012345678')).toBe(true);
    expect(fits(groupByDialCode([kz])[0], '9012345678')).toBe(false);
  });
  it('checks the length of the region whose regex matched', () => {
    expect(fits(groupByDialCode([kz])[0], '701234567')).toBe(false);
  });
  it('leaves a regex the browser cannot compile to the server', () => {
    const uncompilable: PhoneRegion = { ...kz, regex: '[7' };
    expect(fits(groupByDialCode([uncompilable])[0], '7012345678')).toBe(true);
    expect(fits(groupByDialCode([uncompilable])[0], '701234567')).toBe(false);
  });
});

describe('detect', () => {
  const groups = groupByDialCode([us, bs, kz, uz]);
  it('finds the dial code of a pasted +number', () => {
    const hit = detect(groups, '+998 90 123 45 67');
    expect(hit?.group.dialCode).toBe('+998');
    expect(hit?.national).toBe('901234567');
  });
  it('prefers the longer code when it fits as a whole number', () => {
    expect(detect(groups, '+12425551234')?.group.dialCode).toBe('+1242');
    expect(detect(groups, '+12025551234')?.group.dialCode).toBe('+1');
  });
  it('ignores input without a leading plus and codes the tenant does not take', () => {
    expect(detect(groups, '998901234567')).toBeNull();
    expect(detect(groups, '+4915112345678')).toBeNull();
  });
});

describe('nationalDigits', () => {
  const [group] = groupByDialCode([kz]);
  it('drops a dial code typed again and the length overflow', () => {
    expect(nationalDigits(group, '77012345678')).toBe('7012345678');
    expect(nationalDigits(group, '901 234 56 78 55')).toBe('9012345678');
  });
  it('keeps a number that merely starts with the dial-code digit', () => {
    expect(nationalDigits(group, '701234')).toBe('701234');
  });
});

describe('format', () => {
  it('groups digits by the length of the number', () => {
    expect(format('7012345678', 10)).toBe('701 234 56 78');
    expect(format('901234567', 9)).toBe('901 234 567');
    expect(format('70123', 10)).toBe('701 23');
    expect(format('', 10)).toBe('');
  });
});

describe('matches', () => {
  const [group] = groupByDialCode([kz, ru]);
  it('finds a group by dial code with or without the plus, ISO code or country name', () => {
    expect(matches(group, '+7', 'en')).toBe(true);
    expect(matches(group, '7', 'en')).toBe(true);
    expect(matches(group, 'ru', 'en')).toBe(true);
    expect(matches(group, 'kazakh', 'en')).toBe(true);
    expect(matches(group, 'каз', 'ru')).toBe(true);
    expect(matches(group, '', 'en')).toBe(true);
  });
  it('does not match another code or country', () => {
    expect(matches(group, '+99', 'en')).toBe(false);
    expect(matches(group, 'uzbek', 'en')).toBe(false);
  });
});
