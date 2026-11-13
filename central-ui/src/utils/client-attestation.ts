// App attestation of a native client (#423): whether edge asks the app to prove it is the genuine build on a genuine
// device when it starts a login, and which builds that is. The rules here are central's (`ClientAttestation.validated`):
// the console says what central would refuse before it is asked, and central stays the one that decides.

export type AttestationMode = 'off' | 'report' | 'enforce';
export type IosEnvironment = 'production' | 'development';

export interface AndroidApp {
  packageName: string;
  certificateDigests: string[];
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
  report: 'Edge verifies what the app sends and counts the outcome, but never refuses. Use it to see what Enforce would turn away.',
  enforce: 'Edge refuses a sign-in that does not carry a verifiable attestation of one of the builds below. Apps that do not send one yet are turned away.',
};

// The numbers central holds a policy to: it is copied into every edge's sync, so a request cannot store an unbounded one.
export const MAX_APPS = 20;
export const MAX_DIGESTS_PER_APP = 10;
const MAX_LENGTH = 255;

const APP_ID = /^[A-Z0-9]{10}\.[A-Za-z0-9][A-Za-z0-9.\-]{0,200}$/;
const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const SHA256_HEX = /^[0-9a-f]{64}$/;

/** What the form holds: the lists as the text an operator types or pastes, one value per line (commas and spaces also separate). */
export interface AttestationDraft {
  mode: AttestationMode;
  iosAppIds: string;
  iosEnvironment: IosEnvironment;
  android: { packageName: string; digests: string }[];
}

export interface AttestationErrors {
  /** The mode needs a build and none is named. */
  builds?: string;
  ios?: string;
  /** One entry per Android row, in order; undefined when the row is fine. */
  android: (string | undefined)[];
}

export function emptyDraft(): AttestationDraft {
  return { mode: 'report', iosAppIds: '', iosEnvironment: 'production', android: [] };
}

export function draftFromPolicy(policy: ClientAttestationPolicy | null): AttestationDraft {
  if (!policy) return { ...emptyDraft(), mode: 'report' };
  return {
    mode: policy.mode,
    iosAppIds: (policy.ios?.appIds ?? []).join('\n'),
    iosEnvironment: policy.ios?.environment ?? 'production',
    android: policy.android.map(app => ({ packageName: app.packageName, digests: app.certificateDigests.join('\n') })),
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

/** The policy a draft says. A blank Android row is no row; a platform with nothing listed is not sent. */
export function policyFromDraft(draft: AttestationDraft): ClientAttestationPolicy {
  const appIds = unique(words(draft.iosAppIds));
  return {
    mode: draft.mode,
    ...(appIds.length > 0 ? { ios: { appIds, environment: draft.iosEnvironment } } : {}),
    android: draft.android.filter(row => !rowIsBlank(row)).map(row => ({
      packageName: row.packageName.trim(),
      certificateDigests: unique(words(row.digests).map(normalizeDigest)),
    })),
  };
}

export function validateDraft(draft: AttestationDraft): AttestationErrors {
  const errors: AttestationErrors = { android: [] };
  const policy = policyFromDraft(draft);

  const appIds = policy.ios?.appIds ?? [];
  const badId = appIds.find(id => !APP_ID.test(id));
  if (appIds.length > MAX_APPS) {
    errors.ios = `At most ${MAX_APPS} App IDs.`;
  } else if (badId) {
    errors.ios = `'${badId}' is not <Team ID>.<bundle id>, for example ABCDE12345.com.example.app.`;
  }

  const rows = draft.android.filter(row => !rowIsBlank(row));
  const seen = new Set<string>();
  draft.android.forEach((row, index) => {
    if (rowIsBlank(row)) {
      errors.android[index] = undefined;
      return;
    }
    const name = row.packageName.trim();
    const digests = unique(words(row.digests).map(normalizeDigest));
    const badDigest = digests.find(digest => !SHA256_HEX.test(digest));
    let message: string | undefined;
    if (name.length > MAX_LENGTH || !PACKAGE_NAME.test(name)) {
      message = name ? `'${name}' is not a package name, for example com.example.app.` : 'Enter the package name.';
    } else if (seen.has(name)) {
      message = 'This package is listed more than once.';
    } else if (digests.length === 0) {
      message = 'Add the SHA-256 of the certificate the build is signed with.';
    } else if (digests.length > MAX_DIGESTS_PER_APP) {
      message = `At most ${MAX_DIGESTS_PER_APP} certificates per app.`;
    } else if (badDigest) {
      message = `'${badDigest}' is not a SHA-256 digest: 64 hexadecimal digits.`;
    }
    seen.add(name);
    errors.android[index] = message;
  });
  if (rows.length > MAX_APPS) errors.builds = `At most ${MAX_APPS} Android apps.`;

  if (draft.mode !== 'off' && appIds.length === 0 && rows.length === 0) {
    errors.builds = 'Report and Enforce need at least one iOS App ID or Android app to accept.';
  }
  return errors;
}

export function hasErrors(errors: AttestationErrors): boolean {
  return !!errors.builds || !!errors.ios || errors.android.some(Boolean);
}

/** What the record card says about the policy, one short line per platform. */
export function describePolicy(policy: ClientAttestationPolicy): { ios: string; android: string[] } {
  const ios = policy.ios && policy.ios.appIds.length > 0
    ? `${policy.ios.appIds.join(', ')} · ${policy.ios.environment}`
    : 'None accepted';
  return {
    ios,
    android: policy.android.map(app => `${app.packageName} · ${app.certificateDigests.length} certificate${app.certificateDigests.length === 1 ? '' : 's'}`),
  };
}
