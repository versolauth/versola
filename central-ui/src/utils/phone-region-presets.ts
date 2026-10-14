import type { PhoneRegion } from '../types';

/**
 * Ready-made countries for the Allowed Phones editor. The patterns are deliberately loose
 * (national numbering plans change, and the console cannot track them): auth validates the real
 * number with libphonenumber as well, so a region's regex only has to rule out obvious garbage.
 * `sample` is a national number (the digits after the dial code) the regex must match; it is data
 * for the tests, not sent anywhere.
 */
export interface PhoneRegionPreset {
  region: string;
  /** English country name. */
  name: string;
  dialCode: string;
  length: number;
  regex: string;
  sample: string;
}

export const PHONE_REGION_PRESETS: PhoneRegionPreset[] = [
  { region: 'KZ', name: 'Kazakhstan', dialCode: '+7', length: 10, regex: '7\\d{9}', sample: '7011234567' },
  { region: 'RU', name: 'Russia', dialCode: '+7', length: 10, regex: '9\\d{9}', sample: '9123456789' },
  { region: 'BY', name: 'Belarus', dialCode: '+375', length: 9, regex: '(25|29|33|44)\\d{7}', sample: '291234567' },
  { region: 'UA', name: 'Ukraine', dialCode: '+380', length: 9, regex: '\\d{9}', sample: '501234567' },
  { region: 'UZ', name: 'Uzbekistan', dialCode: '+998', length: 9, regex: '\\d{9}', sample: '901234567' },
  { region: 'KG', name: 'Kyrgyzstan', dialCode: '+996', length: 9, regex: '\\d{9}', sample: '700123456' },
  { region: 'TJ', name: 'Tajikistan', dialCode: '+992', length: 9, regex: '\\d{9}', sample: '917123456' },
  { region: 'AM', name: 'Armenia', dialCode: '+374', length: 8, regex: '\\d{8}', sample: '77123456' },
  { region: 'AZ', name: 'Azerbaijan', dialCode: '+994', length: 9, regex: '\\d{9}', sample: '501234567' },
  { region: 'GE', name: 'Georgia', dialCode: '+995', length: 9, regex: '5\\d{8}', sample: '555123456' },
  { region: 'MD', name: 'Moldova', dialCode: '+373', length: 8, regex: '\\d{8}', sample: '67123456' },
  { region: 'TR', name: 'Turkey', dialCode: '+90', length: 10, regex: '5\\d{9}', sample: '5321234567' },
  { region: 'US', name: 'United States', dialCode: '+1', length: 10, regex: '[2-9]\\d{9}', sample: '2025550123' },
  { region: 'CA', name: 'Canada', dialCode: '+1', length: 10, regex: '[2-9]\\d{9}', sample: '4165550123' },
  { region: 'GB', name: 'United Kingdom', dialCode: '+44', length: 10, regex: '7\\d{9}', sample: '7400123456' },
  { region: 'FR', name: 'France', dialCode: '+33', length: 9, regex: '[67]\\d{8}', sample: '612345678' },
  { region: 'IN', name: 'India', dialCode: '+91', length: 10, regex: '[6-9]\\d{9}', sample: '9876543210' },
  { region: 'AE', name: 'United Arab Emirates', dialCode: '+971', length: 9, regex: '5\\d{8}', sample: '501234567' },
  { region: 'PL', name: 'Poland', dialCode: '+48', length: 9, regex: '[4-8]\\d{8}', sample: '512345678' },
];

/** A preset as a list row; every preset allows registration, the admin untick it per row. */
export function presetToRegion(p: PhoneRegionPreset): PhoneRegion {
  return { region: p.region, dialCode: p.dialCode, length: p.length, regex: p.regex, registration: true };
}

/** Appends the chosen presets (in preset order), skipping ISO codes already in the list. */
export function appendPresets(existing: PhoneRegion[], chosen: Iterable<string>): PhoneRegion[] {
  const have = new Set(existing.map(r => r.region.trim().toUpperCase()));
  const wanted = new Set(chosen);
  const added = PHONE_REGION_PRESETS
    .filter(p => wanted.has(p.region) && !have.has(p.region))
    .map(presetToRegion);
  return [...existing, ...added];
}
