import { describe, expect, it } from 'vitest';
import {
  describePolicy, draftFromPolicy, emptyDraft, hasErrors, normalizeDigest, policyFromDraft, validateDraft,
  type AttestationDraft, type ClientAttestationPolicy,
} from './client-attestation';

const DIGEST = 'ab'.repeat(32);

const draft = (over: Partial<AttestationDraft> = {}): AttestationDraft => ({ ...emptyDraft(), ...over });

describe('the policy a draft says', () => {
  it('reads one value per line, with commas and spaces also separating, and drops repeats', () => {
    const policy = policyFromDraft(draft({ iosAppIds: 'ABCDE12345.com.example.app,\nABCDE12345.com.example.beta  ABCDE12345.com.example.app' }));

    expect(policy.ios?.appIds).toEqual(['ABCDE12345.com.example.app', 'ABCDE12345.com.example.beta']);
  });

  it('sends no iOS member when no App ID is listed, and ignores a blank Android row', () => {
    const policy = policyFromDraft(draft({ mode: 'off', android: [{ packageName: '', digests: '' }] }));

    expect(policy).toEqual({ mode: 'off', android: [] });
  });

  it('puts a digest in one form, as central stores it, however it was copied', () => {
    const colons = DIGEST.toUpperCase().match(/../g)!.join(':');

    expect(normalizeDigest(colons)).toBe(DIGEST);
    const policy = policyFromDraft(draft({ android: [{ packageName: ' com.example.app ', digests: `${colons}\n${DIGEST}` }] }));
    expect(policy.android).toEqual([{ packageName: 'com.example.app', certificateDigests: [DIGEST] }]);
  });

  it('round-trips through a draft', () => {
    const policy: ClientAttestationPolicy = {
      mode: 'enforce',
      ios: { appIds: ['ABCDE12345.com.example.app'], environment: 'development' },
      android: [{ packageName: 'com.example.app', certificateDigests: [DIGEST, 'cd'.repeat(32)] }],
    };

    expect(policyFromDraft(draftFromPolicy(policy))).toEqual(policy);
  });

  it('opens a client with no policy on Report, the mode that refuses nothing', () => {
    expect(draftFromPolicy(null).mode).toBe('report');
  });
});

describe('what central would refuse, said before it is asked', () => {
  const good = draft({
    mode: 'enforce',
    iosAppIds: 'ABCDE12345.com.example.app',
    android: [{ packageName: 'com.example.app', digests: DIGEST }],
  });

  it('accepts a well-formed policy', () => {
    expect(hasErrors(validateDraft(good))).toBe(false);
  });

  it('accepts Off with nothing listed, so a policy can be created first', () => {
    expect(hasErrors(validateDraft(draft({ mode: 'off' })))).toBe(false);
  });

  it('needs a build for Report and Enforce', () => {
    expect(validateDraft(draft({ mode: 'report' })).builds).toMatch(/at least one/);
    expect(validateDraft(draft({ mode: 'enforce' })).builds).toMatch(/at least one/);
  });

  it('refuses an App ID that is not a Team ID and a bundle id, including a lower-case Team ID', () => {
    expect(validateDraft({ ...good, iosAppIds: 'com.example.app' }).ios).toMatch(/Team ID/);
    expect(validateDraft({ ...good, iosAppIds: 'abcde12345.com.example.app' }).ios).toMatch(/not <Team ID>/);
  });

  it('refuses an Android package that has one segment, or a row with no certificate', () => {
    expect(validateDraft({ ...good, android: [{ packageName: 'example', digests: DIGEST }] }).android[0]).toMatch(/package name/);
    expect(validateDraft({ ...good, android: [{ packageName: 'com.example.app', digests: '' }] }).android[0]).toMatch(/SHA-256/);
  });

  it('refuses a digest that is not 64 hexadecimal digits', () => {
    expect(validateDraft({ ...good, android: [{ packageName: 'com.example.app', digests: 'abcd' }] }).android[0]).toMatch(/64 hexadecimal/);
  });

  it('refuses a package listed twice, saying so on the second row only', () => {
    const errors = validateDraft({ ...good, android: [
      { packageName: 'com.example.app', digests: DIGEST },
      { packageName: 'com.example.app', digests: DIGEST },
    ] });

    expect(errors.android[0]).toBeUndefined();
    expect(errors.android[1]).toMatch(/more than once/);
  });

  it('holds the numbers central holds: 20 apps, 10 digests', () => {
    const manyIds = Array.from({ length: 21 }, (_, index) => `ABCDE12345.com.example.app${index}`).join('\n');
    const manyDigests = Array.from({ length: 11 }, (_, index) => index.toString(16).padStart(64, '0')).join('\n');

    expect(validateDraft({ ...good, iosAppIds: manyIds }).ios).toMatch(/At most 20/);
    expect(validateDraft({ ...good, android: [{ packageName: 'com.example.app', digests: manyDigests }] }).android[0]).toMatch(/At most 10/);
  });
});

describe('what the record card says', () => {
  it('names each platform, and what is not accepted', () => {
    const summary = describePolicy({
      mode: 'report',
      ios: { appIds: ['ABCDE12345.com.example.app'], environment: 'production' },
      android: [{ packageName: 'com.example.app', certificateDigests: [DIGEST, 'cd'.repeat(32)] }],
    });

    expect(summary.ios).toBe('ABCDE12345.com.example.app · production');
    expect(summary.android).toEqual(['com.example.app · 2 certificates']);
    expect(describePolicy({ mode: 'off', android: [] }).ios).toBe('None accepted');
  });
});
