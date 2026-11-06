import { describe, it, expect } from 'vitest';
import { PHONE_REGION_PRESETS, appendPresets, presetToRegion } from './phone-region-presets';
import { validatePhoneRegions } from './validators';

describe('PHONE_REGION_PRESETS', () => {
  it('passes the region validator', () => {
    const errors = validatePhoneRegions(PHONE_REGION_PRESETS.map(presetToRegion));
    expect(errors.filter(e => e !== undefined)).toEqual([]);
  });

  it('has unique ISO codes and a name for each', () => {
    const codes = PHONE_REGION_PRESETS.map(p => p.region);
    expect(new Set(codes).size).toBe(codes.length);
    expect(PHONE_REGION_PRESETS.every(p => p.name.length > 0)).toBe(true);
  });

  it('keeps dial code digits + length within 15', () => {
    for (const p of PHONE_REGION_PRESETS) {
      expect(p.dialCode.length - 1 + p.length, p.region).toBeLessThanOrEqual(15);
    }
  });

  it('has a regex that compiles and fully matches its sample of the right length', () => {
    for (const p of PHONE_REGION_PRESETS) {
      expect(p.sample.length, p.region).toBe(p.length);
      expect(new RegExp(`^(?:${p.regex})$`).test(p.sample), p.region).toBe(true);
    }
  });

  it('presets every region with registration on', () => {
    expect(PHONE_REGION_PRESETS.every(p => presetToRegion(p).registration)).toBe(true);
  });
});

describe('appendPresets', () => {
  it('appends chosen presets and skips ISO codes already present', () => {
    const existing = [{ region: 'KZ', dialCode: '+7', length: 10, regex: 'x', registration: false }];
    const next = appendPresets(existing, ['KZ', 'US', 'UZ']);
    expect(next.map(r => r.region)).toEqual(['KZ', 'UZ', 'US']);
    expect(next[0].registration).toBe(false);
    expect(next[1].registration).toBe(true);
  });
});
