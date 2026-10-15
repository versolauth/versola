import { AuthMethod, ClientType, OAuthClient, SecurityProfile } from '../types';

/** What the client is, in the terms the person creating it already thinks in. */
export type ClientKind = 'web' | 'device' | 'service';

/** `edge` is the credential nobody picks: edge authenticates with a certificate central has it
 * enrol, so there is nothing for the operator to register or keep. */
export type ClientCredentialMode = 'secret' | 'mtls' | 'mtls-self-signed' | 'private-key-jwt' | 'edge';

/**
 * One consequence of a kind, under a tenant profile.
 *
 * `fixed` is a server rule and `na` is a rule that rules the setting out, so neither is a
 * choice; `on` and `off` are the defaults the kind applies, `pick` is a choice the next step
 * asks for, and `note` is a remark about the tenant rather than a setting.
 */
export type PlanLineState = 'on' | 'off' | 'fixed' | 'na' | 'pick' | 'note';

export interface PlanLine {
  state: PlanLineState;
  text: string;
  why: string;
}

export interface ClientKindDescriptor {
  id: ClientKind;
  name: string;
  /** What the kind is, in a sentence a person choosing it recognises. */
  blurb: string;
  /** What the console sets up for it, shown as a short list under the card. */
  gets: string[];
  /** Where the kind turns up, so a card can be matched to what is being built. */
  examples: string;
  /** Plain-language statement of the resulting client type. */
  clientTypeLabel: string;
}

export interface ClientPreset {
  /** Credential methods this kind allows under the profile, best first - the first is applied. */
  credentialModes: ClientCredentialMode[];
  /** Client fields the kind settles, applied over the form's defaults. */
  patch: Partial<OAuthClient>;
  plan: PlanLine[];
}

export const CLIENT_KINDS: ClientKindDescriptor[] = [
  {
    id: 'web',
    name: 'Web app',
    blurb: 'A site with a backend. Users sign in; tokens stay on the server.',
    gets: ['Tokens never reach the browser', 'No secret to store'],
    examples: 'Shop, dashboard, internal portal',
    clientTypeLabel: 'web (confidential) · fronted by edge',
  },
  {
    id: 'device',
    name: 'Mobile or desktop app',
    blurb: 'An app installed on a device. It holds a key of its own.',
    gets: ['Tokens bound to the device', 'Opens only by verified link'],
    examples: 'iOS, Android, Electron',
    clientTypeLabel: 'native · fronted by edge',
  },
  {
    id: 'service',
    name: 'Service app',
    blurb: 'Code that calls your APIs with no user present.',
    gets: ['Signs in with a key or certificate', 'Gets only the permissions you grant'],
    examples: 'Batch jobs, backends, CLIs',
    clientTypeLabel: 'web (confidential)',
  },
];

const PKCE: PlanLine = {
  state: 'fixed',
  text: 'PKCE, S256 only',
  why: 'Every client on this server. Not a setting.',
};

const EDGE_CERTIFICATE: PlanLine = {
  state: 'fixed',
  text: 'mTLS by edge',
  why: 'Central provisions the certificate edge authenticates with. Nothing to register or store.',
};

const PAR: PlanLine = {
  state: 'on',
  text: 'Pushed authorization requests',
  why: 'RFC 9126. Request parameters never travel through the browser.',
};

const NO_KEY_SET_ON_A_BINARY: PlanLine = {
  state: 'na',
  text: 'Signed request objects',
  why: 'Needs a registered key set; a shipped binary has no private key of its own.',
};

const NO_USER: PlanLine = {
  state: 'fixed',
  text: 'No redirect URIs, no sign-in flow',
  why: 'No user is present in client_credentials.',
};

const PERMISSIONS_ONLY: PlanLine = {
  state: 'on',
  text: 'Permissions only, no OIDC scopes',
  why: 'There is no subject to describe.',
};

const NO_AUTHORIZATION_REQUEST: PlanLine = {
  state: 'na',
  text: 'PAR and signed request objects',
  why: 'Both describe an authorization request. client_credentials makes none.',
};

const ONE_HOUR: PlanLine = { state: 'on', text: '1 hour access token', why: '' };

const NOT_FAPI2: PlanLine = {
  state: 'note',
  text: 'This tenant is not FAPI 2.0',
  why: 'A client secret, RS256 keys and public native clients are admitted here. Edge-fronted apps stay FAPI-shaped regardless.',
};

const HOUR = 3600;

const SERVICE_PATCH: Partial<OAuthClient> = {
  clientType: 'web',
  accessTokenTtl: HOUR,
  redirectUris: [],
  scope: [],
  authFlow: null,
  consentFlow: null,
  requirePushedAuthorizationRequests: false,
  requireSignedRequestObject: false,
};

/** What a kind sets that does not depend on the profile. The profile only decides which
 * credentials a service may pick from and whether the plan carries a remark. */
const EDGE_FRONTED: Record<'web' | 'device', Omit<ClientPreset, 'credentialModes'>> = {
  web: {
    patch: {
      clientType: 'web',
      accessTokenTtl: HOUR,
      requirePushedAuthorizationRequests: true,
      requireSignedRequestObject: false,
      certificateBoundAccessTokens: true,
      dpopBoundAccessTokens: false,
    },
    plan: [
      PKCE,
      EDGE_CERTIFICATE,
      {
        state: 'on',
        text: 'Edge fronts the client',
        why: 'Tokens stay inside edge behind a session cookie and never reach the browser.',
      },
      PAR,
      {
        state: 'on',
        text: 'Certificate-bound access tokens',
        why: 'RFC 8705 §3.4. Bound to the certificate edge authenticates with.',
      },
      ONE_HOUR,
    ],
  },
  device: {
    patch: {
      clientType: 'native',
      accessTokenTtl: HOUR,
      dpopBoundAccessTokens: true,
      requirePushedAuthorizationRequests: true,
      requireSignedRequestObject: false,
      certificateBoundAccessTokens: false,
    },
    plan: [
      PKCE,
      EDGE_CERTIFICATE,
      {
        state: 'on',
        text: 'DPoP-bound access tokens',
        why: 'RFC 9449. Binds the token to a key in the device keystore; edge\'s certificate is shared by every installation, so it cannot bind them.',
      },
      PAR,
      {
        state: 'fixed',
        text: 'App Link redirect only',
        why: 'A verified domain. A custom scheme can be claimed by any app on the device.',
      },
      {
        state: 'on',
        text: '1 hour access token, refresh rotation on',
        why: 'DPoP requires at least 1 h; the binding limits exposure, not the clock.',
      },
      NO_KEY_SET_ON_A_BINARY,
    ],
  },
};

function servicePlan(profile: SecurityProfile): PlanLine[] {
  const fapi2 = profile === 'fapi2';
  return [
    {
      state: 'pick',
      text: fapi2 ? 'Key or certificate' : 'Client secret, key or certificate',
      why: fapi2
        ? 'private_key_jwt with DPoP, or tls_client_auth with certificate-bound tokens. No shared secret.'
        : 'A secret is the default. private_key_jwt and mTLS keep a secret out of your deployment config.',
    },
    {
      state: 'on',
      text: 'Sender-constrained access tokens',
      why: fapi2
        ? 'DPoP with a key, certificate-bound with a certificate.'
        : 'DPoP with a key, certificate-bound with a certificate. A secret gives bearer tokens.',
    },
    NO_USER,
    PERMISSIONS_ONLY,
    ONE_HOUR,
    NO_AUTHORIZATION_REQUEST,
    ...(fapi2 ? [] : [NOT_FAPI2]),
  ];
}

/** What a kind sets under the tenant's profile. */
export function clientPreset(kind: ClientKind, profile: SecurityProfile): ClientPreset {
  if (kind === 'service') {
    return {
      credentialModes: profile === 'fapi2'
        ? ['private-key-jwt', 'mtls', 'mtls-self-signed']
        : ['secret', 'private-key-jwt', 'mtls', 'mtls-self-signed'],
      patch: SERVICE_PATCH,
      plan: servicePlan(profile),
    };
  }
  const fronted = EDGE_FRONTED[kind];
  return {
    credentialModes: ['edge'],
    patch: fronted.patch,
    plan: profile === 'fapi2' ? fronted.plan : [...fronted.plan, NOT_FAPI2],
  };
}

/** The label a stored template carries wherever it is named back to an operator. */
export function templateLabel(template: { kind: ClientKind }): string {
  return CLIENT_KINDS.find(descriptor => descriptor.id === template.kind)?.name ?? template.kind;
}

/** Which part of the edit form a template-decided setting is edited in, so a section can
 * say that one of its own settings has moved. */
export type TemplateSettingSection = 'tokens' | 'credential' | 'integrity';

type TemplateSettingField =
  | 'accessTokenTtl'
  | 'dpopBoundAccessTokens'
  | 'certificateBoundAccessTokens'
  | 'requirePushedAuthorizationRequests'
  | 'requireSignedRequestObject';

interface TemplateSetting {
  field: TemplateSettingField;
  section: TemplateSettingSection;
  label: string;
  format: (value: unknown) => string;
}

const onOff = (value: unknown) => (value ? 'required' : 'off');

const hours = (value: unknown) => {
  const seconds = Number(value);
  return seconds % 3600 === 0 ? `${seconds / 3600} h` : `${Math.round(seconds / 60)} min`;
};

/** The settings a template decides and an operator can move afterwards, which is what a
 * difference can be stated about. The rest of a preset's patch is either fixed at creation
 * (`clientType`) or a starting point rather than a rule - a service template clears `scope`,
 * and the first scope an operator grants is configuration, not drift. */
const TEMPLATE_SETTINGS: TemplateSetting[] = [
  { field: 'accessTokenTtl', section: 'tokens', label: 'Access token TTL', format: hours },
  { field: 'dpopBoundAccessTokens', section: 'credential', label: 'DPoP-bound access tokens', format: onOff },
  { field: 'certificateBoundAccessTokens', section: 'credential', label: 'Certificate-bound access tokens', format: onOff },
  { field: 'requirePushedAuthorizationRequests', section: 'integrity', label: 'Pushed authorization requests', format: onOff },
  { field: 'requireSignedRequestObject', section: 'integrity', label: 'Signed request objects', format: onOff },
];

export interface TemplateDifference {
  field: TemplateSettingField;
  section: TemplateSettingSection;
  label: string;
  /** The template's value, and the current one, both already formatted for display. */
  templateText: string;
  currentText: string;
  /** The value resetting this difference writes back. */
  templateValue: unknown;
}

/** Every template-decided setting whose current value is no longer the one the template
 * asked for. Settings the template does not decide are not compared: a template that says
 * nothing about a field has nothing to differ from. */
export function templateDifferences(
  template: { kind: ClientKind },
  profile: SecurityProfile,
  current: Partial<OAuthClient>,
): TemplateDifference[] {
  const patch = clientPreset(template.kind, profile).patch as Record<string, unknown>;
  return TEMPLATE_SETTINGS.flatMap(setting => {
    if (!(setting.field in patch)) {
      return [];
    }
    const templateValue = patch[setting.field];
    const currentValue = (current as Record<string, unknown>)[setting.field];
    const same = typeof templateValue === 'boolean'
      ? !!currentValue === templateValue
      : currentValue === templateValue;
    return same ? [] : [{
      field: setting.field,
      section: setting.section,
      label: setting.label,
      templateText: setting.format(templateValue),
      currentText: setting.format(currentValue),
      templateValue,
    }];
  });
}

/** Whether the template decides anything the given section shows, so a section with no
 * template-decided setting is left untagged rather than labelled "from template". */
export function templateDecides(
  template: { kind: ClientKind },
  profile: SecurityProfile,
  section: TemplateSettingSection,
): boolean {
  const patch = clientPreset(template.kind, profile).patch as Record<string, unknown>;
  return TEMPLATE_SETTINGS.some(setting => setting.section === section && setting.field in patch);
}

/** The credential the combination applies until the next step is told otherwise. */
export function defaultCredentialMode(kind: ClientKind, profile: SecurityProfile): ClientCredentialMode {
  return clientPreset(kind, profile).credentialModes[0];
}

/** A service client has no user, so nothing about signing one in applies to it. */
export function signsUsersIn(kind: ClientKind): boolean {
  return kind !== 'service';
}

/** RFC 8705 §3.4 is implied by an mTLS credential and pointless without one. */
export function certificateBoundFor(mode: ClientCredentialMode): boolean {
  return mode === 'mtls' || mode === 'mtls-self-signed';
}

export function clientTypeFor(kind: ClientKind): ClientType {
  return kind === 'device' ? 'native' : 'web';
}

/** The method a (clientType, credential mode) pair registers. Edge authenticates as the
 * client by mTLS, whether the app behind it is a web page's backend or a native binary; any
 * other native client is a public one, whichever mode a leftover selection names. */
export function authMethodFor(clientType: ClientType, mode: ClientCredentialMode): AuthMethod {
  if (clientType === 'native' && mode !== 'edge') {
    return 'none';
  }
  switch (mode) {
    case 'edge':
      return 'tls_client_auth';
    case 'mtls':
      return 'tls_client_auth';
    case 'mtls-self-signed':
      return 'self_signed_tls_client_auth';
    case 'private-key-jwt':
      return 'private_key_jwt';
    default:
      return 'client_secret';
  }
}

/** What the credential decides about how the access token is bound: a key carries DPoP, a
 * certificate binds the token to itself, a secret binds nothing. Edge-fronted kinds decide
 * their own binding in the preset, so they are left out. */
export function credentialBinding(mode: ClientCredentialMode): Pick<OAuthClient, 'dpopBoundAccessTokens' | 'certificateBoundAccessTokens'> | null {
  switch (mode) {
    case 'private-key-jwt':
      return { dpopBoundAccessTokens: true, certificateBoundAccessTokens: false };
    case 'mtls':
    case 'mtls-self-signed':
      return { dpopBoundAccessTokens: false, certificateBoundAccessTokens: true };
    case 'secret':
      return { dpopBoundAccessTokens: false, certificateBoundAccessTokens: false };
    default:
      return null;
  }
}

/** What the client is, read back from what was stored: the template the wizard recorded, or -
 * for a client registered through the API - the shape of the registration. A native client is
 * a device; a web one with no sign-in flow and no redirect URIs makes no authorization
 * request, which is a service. */
export function clientKindOf(
  client: Pick<OAuthClient, 'clientType' | 'redirectUris'> & { kind?: ClientKind; template?: { kind: ClientKind } | null; authFlow?: unknown },
): ClientKind {
  // What central says the client is, where it does: the same rule it holds an update to.
  if (client.kind) {
    return client.kind;
  }
  if (client.template?.kind) {
    return client.template.kind;
  }
  if (client.clientType === 'native') {
    return 'device';
  }
  return client.authFlow || (client.redirectUris ?? []).length > 0 ? 'web' : 'service';
}

/** A setting the kind, the profile or the credential settles. Locked means the console shows the
 * value and its reason, and offers no way to move it. */
export type SettingLock = { locked: false } | { locked: true; value: boolean; reason: string };

const UNLOCKED: SettingLock = { locked: false };
const lockedAs = (value: boolean, reason: string): SettingLock => ({ locked: true, value, reason });

export interface ClientLocks {
  /** The client authenticates with a certificate, which binds every token it gets to that
   *  certificate by itself: nothing about binding is stored or chosen, so the console states it once
   *  instead of offering a DPoP key or a flag beside it. */
  boundByCertificate: boolean;
  dpop: SettingLock;
  certificateBound: SettingLock;
  par: SettingLock;
  jar: SettingLock;
}

const EDGE_HOLDS_NO_SIGNING_KEY =
  'Edge authenticates with a certificate and holds no key it could sign a request object with.';
const NO_AUTHORIZATION_REQUEST_REASON = 'A service client makes no authorization request, so there is nothing to push or sign.';

/** What is settled for a client of this kind under this profile and credential - everything
 * the edit form must not offer as a choice. Edge-fronted kinds are settled in both profiles,
 * because what makes the split safe (see `validateEdgeFrontedNative`) does not depend on
 * which profile the tenant is on. */
export function clientLocks(kind: ClientKind, profile: SecurityProfile, mode: ClientCredentialMode): ClientLocks {
  if (kind === 'device') {
    return {
      boundByCertificate: false,
      dpop: lockedAs(true, "Binds each token to a key in the device's keystore. Edge's certificate is shared by every installation, so it cannot do that."),
      certificateBound: lockedAs(false, "Edge's certificate is shared by every installation, so a token cannot be bound to it."),
      par: lockedAs(true, 'Edge pushes the request over its authenticated back channel; the device key binding depends on it.'),
      jar: lockedAs(false, EDGE_HOLDS_NO_SIGNING_KEY),
    };
  }
  if (kind === 'web' && mode === 'edge') {
    return {
      boundByCertificate: true,
      dpop: lockedAs(false, "Tokens are bound to edge's certificate instead (RFC 8705 §3.1)."),
      certificateBound: lockedAs(true, 'Edge authenticates with a certificate, so every token is bound to it.'),
      par: lockedAs(true, 'Edge pushes the request over its authenticated back channel, so the browser never carries it.'),
      jar: lockedAs(false, EDGE_HOLDS_NO_SIGNING_KEY),
    };
  }
  const certificate = mode === 'mtls' || mode === 'mtls-self-signed';
  return {
    boundByCertificate: certificate,
    dpop: mode === 'private-key-jwt' && profile === 'fapi2'
      ? lockedAs(true, 'FAPI 2.0 requires sender-constrained tokens, and a key-authenticated client gets them through DPoP.')
      : UNLOCKED,
    certificateBound: certificate
      ? lockedAs(true, 'A certificate credential binds every token it gets to that certificate (RFC 8705 §3.1).')
      : mode === 'private-key-jwt' && profile === 'fapi2'
        ? lockedAs(false, 'DPoP is what binds this client\'s tokens.')
        : UNLOCKED,
    // A web client that authenticates for itself (not through edge) makes authorization
    // requests, and how they arrive is its own choice.
    par: kind === 'service' ? lockedAs(false, NO_AUTHORIZATION_REQUEST_REASON) : UNLOCKED,
    jar: kind === 'service' ? lockedAs(false, NO_AUTHORIZATION_REQUEST_REASON) : UNLOCKED,
  };
}

export interface PostureCheck {
  ok: boolean;
  text: string;
  /** What is wrong, when `ok` is false. */
  problem?: string;
}

/** Kept in sync with `InvalidRegistrationConfiguration.profileViolations`: what FAPI 2.0 asks of
 * a client, one line each, so the page can say whether this one meets it. Empty under the
 * standard profile, which asks nothing. */
export function profilePosture(
  client: Pick<
    OAuthClient,
    'authMethod' | 'clientType' | 'dpopBoundAccessTokens' | 'certificateBoundAccessTokens' | 'redirectUris' | 'requirePushedAuthorizationRequests'
  >,
  profile: SecurityProfile,
): PostureCheck[] {
  if (profile !== 'fapi2') {
    return [];
  }
  const authMethods: AuthMethod[] = ['private_key_jwt', 'tls_client_auth', 'self_signed_tls_client_auth'];
  const certificateAuth = client.authMethod === 'tls_client_auth' || client.authMethod === 'self_signed_tls_client_auth';
  const senderConstrained = !!client.dpopBoundAccessTokens || !!client.certificateBoundAccessTokens || certificateAuth;
  const redirectUris = client.redirectUris ?? [];
  const checks: PostureCheck[] = [
    authMethods.includes(client.authMethod)
      ? { ok: true, text: `Authenticates with ${client.authMethod === 'private_key_jwt' ? 'a signing key' : 'a certificate'}, no shared secret` }
      : { ok: false, text: 'Authenticates with a key or a certificate', problem: `Uses ${client.authMethod}, which FAPI 2.0 does not admit` },
    senderConstrained
      ? { ok: true, text: `Tokens bound to ${client.dpopBoundAccessTokens ? 'a DPoP key' : 'a certificate'}` }
      : { ok: false, text: 'Tokens are sender-constrained', problem: 'Tokens are bearer tokens: nothing binds them to the client' },
  ];
  if (redirectUris.length > 0) {
    checks.push(
      client.requirePushedAuthorizationRequests
        ? { ok: true, text: 'Authorization requests pushed (PAR)' }
        : { ok: false, text: 'Authorization requests pushed (PAR)', problem: 'PAR is off for a client with redirect URIs' },
    );
    // Loopback http is not a gap: central admits it only outside production, so a client that has it
    // is one a local stack registered, and in production it could not have been.
    // Loopback http is not a gap: central admits it only outside production, so a client that has it
    // is one a local stack registered, and in production it could not have been.
    const insecure = redirectUris.filter(uri => !uri.startsWith('https://') && !/^http:\/\/(localhost|127\.0\.0\.1|\[::1\])/.test(uri));
    checks.push(
      insecure.length === 0
        ? { ok: true, text: 'Redirect URIs use https' }
        : { ok: false, text: 'Redirect URIs use https', problem: `Not https: ${insecure.join(', ')}` },
    );
  }
  return checks;
}

/** What the client authenticates with, in a few words for a list or a summary. */
export function credentialLabel(client: Pick<OAuthClient, 'authMethod' | 'clientType' | 'mtlsAuth'>): string {
  switch (client.authMethod) {
    case 'tls_client_auth':
      return client.clientType === 'native' || !client.mtlsAuth ? 'Certificate, issued to edge' : 'Certificate';
    case 'self_signed_tls_client_auth':
      return 'Self-signed certificate';
    case 'private_key_jwt':
      return 'Signing key';
    case 'client_secret':
      return 'Client secret';
    default:
      return 'None (public client)';
  }
}

/** Whether the client would pass the FAPI 2.0 profile, whatever profile its tenant is on: a
 * standard tenant holds clients of both kinds, and which one this is is worth saying. */
export function fapiConformance(client: Parameters<typeof profilePosture>[0]): { conformant: boolean; gaps: PostureCheck[] } {
  const checks = profilePosture(client, 'fapi2');
  const gaps = checks.filter(check => !check.ok);
  return { conformant: gaps.length === 0, gaps };
}

/** What the page calls the group of access settings: a client that signs users in has scopes, and its
 *  users bring their permissions through their roles; permissions are what a token issued to the
 *  client itself may call, which only a service client has. */
export function accessSectionTitle(kind: ClientKind): string {
  return kind === 'service' ? 'Permissions' : 'Scopes';
}
