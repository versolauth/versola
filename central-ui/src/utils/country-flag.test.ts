import { describe, expect, it } from 'vitest';
import { countryName, describeCountry, flagEmoji } from './country-flag';

describe('country flags', () => {
  it('builds the emoji from the two letters of the code, in any case', () => {
    expect(flagEmoji('KZ')).toBe('🇰🇿');
    expect(flagEmoji('nl')).toBe('🇳🇱');
  });

  it('draws no flag for anything that is not two letters', () => {
    for (const bad of ['', 'K', 'KAZ', '1Z', 'K Z', 'ÄÖ']) expect(flagEmoji(bad)).toBeNull();
  });

  it('takes the name from the browser data', () => {
    expect(countryName('KZ')).toBe('Kazakhstan');
    expect(countryName('nl')).toBe('Netherlands');
  });

  it('gives the flag, the name and the upper-case code together', () => {
    expect(describeCountry('kz')).toEqual({ code: 'KZ', flag: '🇰🇿', name: 'Kazakhstan' });
  });

  it('draws nothing when the API sent no country', () => {
    expect(describeCountry(undefined)).toBeNull();
    expect(describeCountry(null)).toBeNull();
    expect(describeCountry('')).toBeNull();
    expect(describeCountry('??')).toBeNull();
  });

  it('falls back to the code as the name for a code the browser does not know', () => {
    expect(describeCountry('ZZ')?.name).toBe('ZZ');
  });
});
