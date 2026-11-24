import { describe, expect, it } from 'vitest';
import {
  describePolicy, draftFromPolicy, emptyDraft, hasErrors, narrows, normalizeDigest, policyFromDraft, shortDigest, validateDraft,
  type AttestationDraft, type ClientAttestationPolicy,
} from './client-attestation';

const DIGEST = 'ab'.repeat(32);

const draft = (over: Partial<AttestationDraft> = {}): AttestationDraft => ({ ...emptyDraft(), ...over });

describe('the policy a draft says', () => {
  it('reads one value per line, with commas and spaces also separating, and drops repeats', () => {
    const policy = policyFromDraft(draft({ ios: true, iosAppIds: 'ABCDE12345.com.example.app,\nABCDE12345.com.example.beta  ABCDE12345.com.example.app' }));

    expect(policy.ios?.appIds).toEqual(['ABCDE12345.com.example.app', 'ABCDE12345.com.example.beta']);
  });

  it('sends no iOS member when no App ID is listed, and ignores a blank Android row', () => {
    const policy = policyFromDraft(draft({ mode: 'off', androidOn: true, android: [{ packageName: '', digests: '' }] }));

    expect(policy).toEqual({ mode: 'off', android: [] });
  });

  it('leaves out a platform that is switched off, whatever was typed for it, and keeps it in the draft', () => {
    const typed = draft({
      mode: 'report', ios: false, iosAppIds: 'ABCDE12345.com.example.app',
      androidOn: false, android: [{ packageName: 'com.example.app', digests: DIGEST }],
    });

    expect(policyFromDraft(typed)).toEqual({ mode: 'report', android: [] });
    expect(typed.iosAppIds).toBe('ABCDE12345.com.example.app');
  });

  it('puts a digest in one form, as central stores it, however it was copied', () => {
    const colons = DIGEST.toUpperCase().match(/../g)!.join(':');

    expect(normalizeDigest(colons)).toBe(DIGEST);
    const policy = policyFromDraft(draft({ androidOn: true, android: [{ packageName: ' com.example.app ', digests: `${colons}\n${DIGEST}` }] }));
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

  it('opens a client with no policy on Report, the mode that refuses nothing, with both platforms off', () => {
    const opened = draftFromPolicy(null);

    expect(opened.mode).toBe('report');
    expect(opened.ios).toBe(false);
    expect(opened.androidOn).toBe(false);
  });

  it('opens a policy with the platforms it names switched on', () => {
    const opened = draftFromPolicy({ mode: 'report', android: [{ packageName: 'com.example.app', certificateDigests: [DIGEST] }] });

    expect(opened.ios).toBe(false);
    expect(opened.androidOn).toBe(true);
  });
});

describe('what central would refuse, said before it is asked', () => {
  const good = draft({
    mode: 'enforce',
    ios: true,
    iosAppIds: 'ABCDE12345.com.example.app',
    androidOn: true,
    android: [{ packageName: 'com.example.app', digests: DIGEST }],
  });

  it('accepts a well-formed policy', () => {
    expect(hasErrors(validateDraft(good))).toBe(false);
  });

  it('accepts Off with no platform, so a policy can be created first', () => {
    expect(hasErrors(validateDraft(draft({ mode: 'off' })))).toBe(false);
  });

  it('needs a platform for Report and Enforce, and says it where the mode is chosen', () => {
    expect(validateDraft(draft({ mode: 'report' })).builds).toMatch(/at least one platform/);
    expect(validateDraft(draft({ mode: 'enforce' })).builds).toMatch(/at least one platform/);
  });

  it('needs a build for a platform that is switched on, and names the platform', () => {
    expect(validateDraft(draft({ mode: 'report', ios: true })).ios).toMatch(/at least one App ID/);
    expect(validateDraft(draft({ mode: 'report', androidOn: true })).androidApps).toMatch(/Add an Android app/);
    expect(validateDraft(draft({ mode: 'report', ios: true })).builds).toBeUndefined();
  });

  it('does not read what was typed for a platform that is off', () => {
    const typed = { ...good, ios: false, iosAppIds: 'not an app id', androidOn: false, android: [{ packageName: 'x', digests: 'y' }] };

    expect(validateDraft(typed).ios).toBeUndefined();
    expect(validateDraft(typed).android).toEqual([]);
    expect(validateDraft(typed).builds).toMatch(/at least one platform/);
  });

  it('refuses an App ID that is not a Team ID and a bundle id, including a lower-case Team ID', () => {
    expect(validateDraft({ ...good, iosAppIds: 'com.example.app' }).ios).toMatch(/Team ID/);
    expect(validateDraft({ ...good, iosAppIds: 'abcde12345.com.example.app' }).ios).toMatch(/not <Team ID>/);
  });

  it('says an Android problem at the field it is about: the package name or the certificates, not both at once', () => {
    const badName = validateDraft({ ...good, android: [{ packageName: 'example', digests: DIGEST }] }).android[0];
    const noDigest = validateDraft({ ...good, android: [{ packageName: 'com.example.app', digests: '' }] }).android[0];
    const both = validateDraft({ ...good, android: [{ packageName: 'example', digests: '' }] }).android[0];

    expect(badName?.packageName).toMatch(/package name/);
    expect(badName?.digests).toBeUndefined();
    expect(noDigest?.digests).toMatch(/SHA-256/);
    expect(noDigest?.packageName).toBeUndefined();
    expect(both?.packageName).toMatch(/package name/);
    expect(both?.digests).toMatch(/SHA-256/);
  });

  it('refuses a digest that is not 64 hexadecimal digits', () => {
    expect(validateDraft({ ...good, android: [{ packageName: 'com.example.app', digests: 'abcd' }] }).android[0]?.digests).toMatch(/64 hexadecimal/);
  });

  it('refuses a package listed twice, saying so on the second row only', () => {
    const errors = validateDraft({ ...good, android: [
      { packageName: 'com.example.app', digests: DIGEST },
      { packageName: 'com.example.app', digests: DIGEST },
    ] });

    expect(errors.android[0]).toBeUndefined();
    expect(errors.android[1]?.packageName).toMatch(/more than once/);
  });

  it('carries Play Integrity only for an app that asks for it', () => {
    const asks = policyFromDraft(draft({ androidOn: true, android: [{ packageName: 'com.example.app', digests: DIGEST, playIntegrity: true }] }));
    const plain = policyFromDraft(draft({ androidOn: true, android: [{ packageName: 'com.example.app', digests: DIGEST, playIntegrity: false }] }));
    expect(asks.android[0]).toEqual({ packageName: 'com.example.app', certificateDigests: [DIGEST], playIntegrity: true });
    expect('playIntegrity' in plain.android[0]).toBe(false);
    expect(draftFromPolicy(asks).android[0].playIntegrity).toBe(true);
    expect(describePolicy(asks).android[0].playIntegrity).toBe(true);
  });

  it('holds the numbers central holds: 20 apps, 10 digests', () => {
    const manyIds = Array.from({ length: 21 }, (_, index) => `ABCDE12345.com.example.app${index}`).join('\n');
    const manyDigests = Array.from({ length: 11 }, (_, index) => index.toString(16).padStart(64, '0')).join('\n');

    expect(validateDraft({ ...good, iosAppIds: manyIds }).ios).toMatch(/At most 20/);
    expect(validateDraft({ ...good, android: [{ packageName: 'com.example.app', digests: manyDigests }] }).android[0]?.digests).toMatch(/At most 10/);
  });
});

describe('what the record card says', () => {
  it('names each platform that is set, and which certificates are accepted', () => {
    const summary = describePolicy({
      mode: 'report',
      ios: { appIds: ['ABCDE12345.com.example.app'], environment: 'production' },
      android: [{ packageName: 'com.example.app', certificateDigests: [DIGEST, 'cd'.repeat(32)] }],
    });

    expect(summary.ios).toBe('ABCDE12345.com.example.app · production');
    expect(summary.android).toEqual([{
      packageName: 'com.example.app',
      digests: [
        { short: 'abababab…abababab', full: DIGEST },
        { short: 'cdcdcdcd…cdcdcdcd', full: 'cd'.repeat(32) },
      ],
      playIntegrity: false,
    }]);
  });

  it('says nothing of a platform that is not set', () => {
    const summary = describePolicy({ mode: 'off', android: [] });

    expect(summary.ios).toBeUndefined();
    expect(summary.android).toEqual([]);
  });

  it('shortens a digest to its first and last eight digits, and leaves a short value as it is', () => {
    expect(shortDigest('0123456789abcdef'.repeat(4))).toBe('01234567…89abcdef');
    expect(shortDigest('abcd')).toBe('abcd');
  });
});

describe('whether a policy accepts fewer builds than it did', () => {
  const was: ClientAttestationPolicy = {
    mode: 'enforce',
    ios: { appIds: ['ABCDE12345.com.example.app', 'ABCDE12345.com.example.beta'], environment: 'production' },
    android: [{ packageName: 'com.example.app', certificateDigests: [DIGEST, 'cd'.repeat(32)] }],
  };

  it('is not narrowed by adding builds or by leaving it as it is', () => {
    expect(narrows(was, was)).toBe(false);
    expect(narrows(was, { ...was, android: [...was.android, { packageName: 'com.example.other', certificateDigests: [DIGEST] }] })).toBe(false);
  });

  it('is narrowed by a build that now needs a Play Integrity verdict, and by the iOS environment changing', () => {
    expect(narrows(was, { ...was, android: [{ ...was.android[0], playIntegrity: true }] })).toBe(true);
    expect(narrows(was, { ...was, ios: { appIds: was.ios!.appIds, environment: 'development' } })).toBe(true);
  });

  it('is narrowed by dropping an App ID, the iOS platform, a package, or one signing certificate of a package', () => {
    expect(narrows(was, { ...was, ios: { appIds: ['ABCDE12345.com.example.app'], environment: 'production' } })).toBe(true);
    expect(narrows(was, { ...was, ios: undefined })).toBe(true);
    expect(narrows(was, { ...was, android: [] })).toBe(true);
    expect(narrows(was, { ...was, android: [{ packageName: 'com.example.app', certificateDigests: [DIGEST] }] })).toBe(true);
  });
});
