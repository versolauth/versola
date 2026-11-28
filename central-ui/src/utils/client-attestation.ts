// App attestation of a native client (#423): whether edge asks the app to prove it is the genuine build on a genuine
// device when it starts a login, and which builds that is. The rules here are central's (`ClientAttestation.validated`):
// the console says what central would refuse before it is asked, and central stays the one that decides.

export type AttestationMode = 'off' | 'report' | 'enforce';
export type IosEnvironment = 'production' | 'development';

export interface AndroidApp {
  packageName: string;
  certificateDigests: string[];
  /** Also require a Play Integrity verdict for this build. Absent means no. */
  playIntegrity?: boolean;
}

/** The policy as central stores and serves it. `ios` is absent when no iOS build is accepted. */
export interface ClientAttestationPolicy {
  mode: AttestationMode;
  ios?: { appIds: string[]; environment: IosEnvironment };
  android: AndroidApp[];
}

export const MODES: AttestationMode[] = ['off', 'report', 'enforce'];

export const MODE_LABEL: Record<AttestationMode, string> = {
  off: 'Off',
  report: 'Report',
  enforce: 'Enforce',
};

/** One line under the chosen mode, so the consequence is read where the choice is made. */
export const MODE_TEXT: Record<AttestationMode, string> = {
  off: 'Edge does not ask the app for anything and checks nothing.',
  report: 'Edge verifies what the app sends and counts the outcome, but never refuses. Use it to see what Enforce would turn away: the outcomes are in `attestation_requests_total`, by client, platform and result.',
  enforce: 'Edge refuses a sign-in that does not carry a verifiable attestation of one of the builds below. Apps that do not send one yet are turned away.',
};

// The numbers central holds a policy to: it is copied into every edge's sync, so a request cannot store an unbounded one.
export const MAX_APPS = 20;
export const MAX_DIGESTS_PER_APP = 10;
const MAX_LENGTH = 255;

const APP_ID = /^[A-Z0-9]{10}\.[A-Za-z0-9][A-Za-z0-9.\-]{0,200}$/;
const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const SHA256_HEX = /^[0-9a-f]{64}$/;

/** What the form holds: the lists as the text an operator types or pastes, one value per line (commas and spaces also separate). A
 * platform that is switched off keeps what was typed for it until the policy is saved, so that switching it off by mistake loses
 * nothing; it is not part of the policy. */
export interface AttestationDraft {
  mode: AttestationMode;
  ios: boolean;
  androidOn: boolean;
  iosAppIds: string;
  iosEnvironment: IosEnvironment;
  android: { packageName: string; digests: string; playIntegrity?: boolean }[];
}

/** What is wrong with one Android row, said at the field it is about. */
export interface AndroidRowErrors {
  packageName?: string;
  digests?: string;
}

export interface AttestationErrors {
  /** The mode needs a build and no platform is switched on. */
  builds?: string;
  ios?: string;
  /** Android is switched on and has no app. */
  androidApps?: string;
  /** One entry per Android row, in order; undefined when the row is fine. */
  android: (AndroidRowErrors | undefined)[];
}

export function emptyDraft(): AttestationDraft {
  return { mode: 'report', ios: false, androidOn: false, iosAppIds: '', iosEnvironment: 'production', android: [] };
}

export function draftFromPolicy(policy: ClientAttestationPolicy | null): AttestationDraft {
  if (!policy) return { ...emptyDraft(), mode: 'report' };
  return {
    mode: policy.mode,
    ios: !!policy.ios,
    androidOn: policy.android.length > 0,
    iosAppIds: (policy.ios?.appIds ?? []).join('\n'),
    iosEnvironment: policy.ios?.environment ?? 'production',
    android: policy.android.map(app => ({ packageName: app.packageName, digests: app.certificateDigests.join('\n'), ...(app.playIntegrity ? { playIntegrity: true } : {}) })),
  };
}

function words(text: string): string[] {
  return text.split(/[\s,]+/).map(word => word.trim()).filter(Boolean);
}

function unique(values: string[]): string[] {
  return [...new Set(values)];
}

/** `keytool` prints a digest with colons and upper case, `apksigner` without: both are the same certificate. */
export function normalizeDigest(digest: string): string {
  return digest.replace(/[:\s]/g, '').toLowerCase();
}

const rowIsBlank = (row: { packageName: string; digests: string }) => !row.packageName.trim() && words(row.digests).length === 0;

/** The policy a draft says. A platform that is off is not sent, whatever was typed for it; a blank Android row is no row. */
export function policyFromDraft(draft: AttestationDraft): ClientAttestationPolicy {
  const appIds = draft.ios ? unique(words(draft.iosAppIds)) : [];
  const rows = draft.androidOn ? draft.android.filter(row => !rowIsBlank(row)) : [];
  return {
    mode: draft.mode,
    ...(appIds.length > 0 ? { ios: { appIds, environment: draft.iosEnvironment } } : {}),
    android: rows.map(row => ({
      packageName: row.packageName.trim(),
      certificateDigests: unique(words(row.digests).map(normalizeDigest)),
      ...(row.playIntegrity ? { playIntegrity: true } : {}),
    })),
  };
}

export function validateDraft(draft: AttestationDraft): AttestationErrors {
  const errors: AttestationErrors = { android: [] };
  const policy = policyFromDraft(draft);

  const appIds = policy.ios?.appIds ?? [];
  const badId = appIds.find(id => !APP_ID.test(id));
  if (draft.ios && appIds.length === 0) {
    errors.ios = 'Enter at least one App ID, or switch iOS off.';
  } else if (appIds.length > MAX_APPS) {
    errors.ios = `At most ${MAX_APPS} App IDs.`;
  } else if (badId) {
    errors.ios = `'${badId}' is not <Team ID>.<bundle id>, for example ABCDE12345.com.example.app.`;
  }

  const rows = draft.androidOn ? draft.android.filter(row => !rowIsBlank(row)) : [];
  if (draft.androidOn && rows.length === 0) errors.androidApps = 'Add an Android app, or switch Android off.';
  const seen = new Set<string>();
  if (draft.androidOn) draft.android.forEach((row, index) => {
    if (rowIsBlank(row)) {
      errors.android[index] = undefined;
      return;
    }
    const name = row.packageName.trim();
    const digests = unique(words(row.digests).map(normalizeDigest));
    const badDigest = digests.find(digest => !SHA256_HEX.test(digest));
    const found: AndroidRowErrors = {};
    if (name.length > MAX_LENGTH || !PACKAGE_NAME.test(name)) {
      found.packageName = name ? `'${name}' is not a package name, for example com.example.app.` : 'Enter the package name.';
    } else if (seen.has(name)) {
      found.packageName = 'This package is listed more than once.';
    }
    if (digests.length === 0) {
      found.digests = 'Add the SHA-256 of the certificate the build is signed with.';
    } else if (digests.length > MAX_DIGESTS_PER_APP) {
      found.digests = `At most ${MAX_DIGESTS_PER_APP} certificates per app.`;
    } else if (badDigest) {
      found.digests = `'${badDigest}' is not a SHA-256 digest: 64 hexadecimal digits.`;
    }
    seen.add(name);
    errors.android[index] = found.packageName || found.digests ? found : undefined;
  });
  if (rows.length > MAX_APPS) errors.builds = `At most ${MAX_APPS} Android apps.`;

  if (draft.mode !== 'off' && !draft.ios && !draft.androidOn) {
    errors.builds = 'Report and Enforce need at least one platform to accept: switch on iOS or Android.';
  }
  return errors;
}

export function hasErrors(errors: AttestationErrors): boolean {
  return !!errors.builds || !!errors.ios || !!errors.androidApps || errors.android.some(Boolean);
}

/** An accepted signing certificate as the card shows it: the first and last eight hex digits, the whole value for the tooltip. */
export interface DigestSummary {
  short: string;
  full: string;
}

export interface AndroidSummary {
  packageName: string;
  digests: DigestSummary[];
  playIntegrity: boolean;
}

export const shortDigest = (digest: string): string => digest.length > 20 ? `${digest.slice(0, 8)}…${digest.slice(-8)}` : digest;

/** What the record card says about the policy: only the platforms that are set. */
export function describePolicy(policy: ClientAttestationPolicy): { ios?: string; android: AndroidSummary[] } {
  return {
    ...(policy.ios && policy.ios.appIds.length > 0 ? { ios: `${policy.ios.appIds.join(', ')} · ${policy.ios.environment}` } : {}),
    android: policy.android.map(app => ({
      packageName: app.packageName,
      digests: app.certificateDigests.map(full => ({ short: shortDigest(full), full })),
      playIntegrity: !!app.playIntegrity,
    })),
  };
}

/** Whether `next` accepts fewer builds than `previous`: an App ID, a package or a signing certificate that was accepted is gone, the
 * iOS environment changed, or a build now needs a Play Integrity verdict. Under
 * Enforce that turns away apps that pass today, which is what the confirmation for Enforce is also about. */
export function narrows(previous: ClientAttestationPolicy, next: ClientAttestationPolicy): boolean {
  const ids = new Set(next.ios?.appIds ?? []);
  if ((previous.ios?.appIds ?? []).some(id => !ids.has(id))) return true;
  // Development to production: the builds signed for development are turned away.
  if (previous.ios && next.ios && previous.ios.environment !== next.ios.environment) return true;
  return previous.android.some(was => {
    const now = next.android.find(app => app.packageName === was.packageName);
    // A build that now needs a Play Integrity verdict refuses the apps that send none.
    return !now || was.certificateDigests.some(digest => !now.certificateDigests.includes(digest)) || (!was.playIntegrity && !!now.playIntegrity);
  });
}
