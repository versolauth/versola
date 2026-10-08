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
  blurb: string;
  /** Plain-language statement of the resulting client type, shown under the card. */
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
    blurb: 'A browser front end and its backend. Edge is the OAuth client and keeps the tokens.',
    clientTypeLabel: 'web (confidential) · fronted by edge',
  },
  {
    id: 'device',
    name: 'Mobile or desktop app',
    blurb: 'iOS, Android, Electron. Edge fronts the app and holds the credential; the device holds a key of its own.',
    clientTypeLabel: 'native · fronted by edge',
  },
  {
    id: 'service',
    name: 'Service app',
    blurb: 'client_credentials. Calls your APIs with no user present.',
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
  registrationFlow: null,
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
