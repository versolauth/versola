import { AuthMethod, ClientType, OAuthClient } from '../types';

/** What the client is, in the terms the person creating it already thinks in. */
export type ClientKind = 'web' | 'device' | 'service';

/** How much the deployment can carry: a key and a bound token, or a shared secret. */
export type AssuranceTier = 'high' | 'compat';

export type ClientCredentialMode = 'secret' | 'mtls' | 'mtls-self-signed' | 'private-key-jwt';

/**
 * One consequence of a kind x tier choice.
 *
 * `fixed` is a server rule and `na` is a rule that rules the setting out, so neither is a
 * choice; `on` and `off` are the defaults the combination applies, and `pick` is a choice the
 * next step asks for.
 */
export type PlanLineState = 'on' | 'off' | 'fixed' | 'na' | 'pick';

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
  /** Credential methods this combination allows, best first - the first is applied. */
  credentialModes: ClientCredentialMode[];
  /** Client fields the combination settles, applied over the form's defaults. */
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
    blurb: 'iOS, Android, Electron. Talks to auth itself - edge cannot sit in front of it.',
    clientTypeLabel: 'native (public)',
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

const NO_KEY_SET_ON_A_BINARY: PlanLine = {
  state: 'na',
  text: 'Signed request objects',
  why: 'Needs a registered key set; a shipped binary has no private key of its own.',
};

const PUBLIC_CLIENT: PlanLine = {
  state: 'fixed',
  text: 'Public client, no secret',
  why: 'A shipped binary cannot keep one.',
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

const HOUR = 3600;

const PRESETS: Record<ClientKind, Record<AssuranceTier, ClientPreset>> = {
  web: {
    high: {
      credentialModes: ['private-key-jwt', 'mtls', 'mtls-self-signed'],
      patch: {
        clientType: 'web',
        accessTokenTtl: HOUR,
        requirePushedAuthorizationRequests: true,
        requireSignedRequestObject: true,
        certificateBoundAccessTokens: false,
        dpopBoundAccessTokens: false,
      },
      plan: [
        PKCE,
        {
          state: 'pick',
          text: 'private_key_jwt or mTLS',
          why: 'No shared secret in a config file. Registering a key set is also what makes JAR possible.',
        },
        {
          state: 'on',
          text: 'Pushed authorization requests',
          why: 'RFC 9126. Request parameters never travel through the browser.',
        },
        {
          state: 'on',
          text: 'Signed request objects',
          why: 'RFC 9101. Verified against the key set above.',
        },
        {
          state: 'on',
          text: 'Certificate-bound access tokens',
          why: 'RFC 8705 §3.4, when the credential is mTLS.',
        },
        ONE_HOUR,
      ],
    },
    compat: {
      credentialModes: ['secret'],
      patch: {
        clientType: 'web',
        accessTokenTtl: HOUR,
        requirePushedAuthorizationRequests: false,
        requireSignedRequestObject: false,
        certificateBoundAccessTokens: false,
        dpopBoundAccessTokens: false,
      },
      plan: [
        PKCE,
        { state: 'on', text: 'Client secret', why: 'The only method edge can present today.' },
        {
          state: 'on',
          text: 'Edge fronts the client',
          why: 'Tokens stay inside edge behind a session cookie and never reach the browser.',
        },
        {
          state: 'off',
          text: 'Pushed authorization requests',
          why: 'Edge builds a plain /authorize URL.',
        },
        { state: 'off', text: 'Signed request objects', why: 'No key set, and edge signs nothing.' },
        ONE_HOUR,
      ],
    },
  },
  device: {
    high: {
      credentialModes: ['secret'],
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
        PUBLIC_CLIENT,
        {
          state: 'on',
          text: 'DPoP-bound access tokens',
          why: 'RFC 9449. Binds the token to a key in the device keystore.',
        },
        {
          state: 'on',
          text: 'Pushed authorization requests',
          why: 'RFC 9126. A public client pushes with a bare client_id - the value is request integrity, not client auth.',
        },
        {
          state: 'on',
          text: '1 hour access token, refresh rotation on',
          why: 'DPoP requires at least 1 h; the binding limits exposure, not the clock.',
        },
        NO_KEY_SET_ON_A_BINARY,
      ],
    },
    compat: {
      credentialModes: ['secret'],
      patch: {
        clientType: 'native',
        accessTokenTtl: HOUR,
        dpopBoundAccessTokens: false,
        requirePushedAuthorizationRequests: false,
        requireSignedRequestObject: false,
        certificateBoundAccessTokens: false,
      },
      plan: [
        PKCE,
        PUBLIC_CLIENT,
        {
          state: 'off',
          text: 'DPoP-bound access tokens',
          why: 'The access token carries no certificate or key binding; a rooted device leaks one that is usable until it expires.',
        },
        {
          state: 'off',
          text: 'Pushed authorization requests',
          why: 'Request parameters pass through the system browser.',
        },
        {
          state: 'on',
          text: '1 hour access token, refresh rotation on',
          why: 'Same 1 hour floor as every client on this server; unlike high assurance, nothing else here limits what a copied token can do before then.',
        },
        NO_KEY_SET_ON_A_BINARY,
      ],
    },
  },
  service: {
    high: {
      credentialModes: ['private-key-jwt', 'mtls', 'mtls-self-signed'],
      patch: {
        clientType: 'web',
        accessTokenTtl: HOUR,
        redirectUris: [],
        scope: [],
        authFlow: null,
        registrationFlow: null,
        consentFlow: null,
        requirePushedAuthorizationRequests: false,
        requireSignedRequestObject: false,
        certificateBoundAccessTokens: false,
        dpopBoundAccessTokens: false,
      },
      plan: [
        {
          state: 'pick',
          text: 'private_key_jwt or mTLS',
          why: 'No shared secret in a config file. A service has a deployment to keep a key in.',
        },
        {
          state: 'on',
          text: 'Certificate-bound access tokens',
          why: 'RFC 8705 §3.4, when the credential is mTLS.',
        },
        NO_USER,
        PERMISSIONS_ONLY,
        ONE_HOUR,
        NO_AUTHORIZATION_REQUEST,
      ],
    },
    compat: {
      credentialModes: ['secret'],
      patch: {
        clientType: 'web',
        accessTokenTtl: HOUR,
        redirectUris: [],
        scope: [],
        authFlow: null,
        registrationFlow: null,
        consentFlow: null,
        requirePushedAuthorizationRequests: false,
        requireSignedRequestObject: false,
        certificateBoundAccessTokens: false,
        dpopBoundAccessTokens: false,
      },
      plan: [
        {
          state: 'on',
          text: 'Client secret',
          why: 'Shared with the server. Lives in your deployment config.',
        },
        {
          state: 'off',
          text: 'Certificate-bound access tokens',
          why: 'The access token carries no certificate or key binding.',
        },
        NO_USER,
        PERMISSIONS_ONLY,
        ONE_HOUR,
        NO_AUTHORIZATION_REQUEST,
      ],
    },
  },
};

export function clientPreset(kind: ClientKind, tier: AssuranceTier): ClientPreset {
  return PRESETS[kind][tier];
}

/** The label the tier carries wherever a stored template is named back to an operator. */
export function assuranceTierName(tier: AssuranceTier): string {
  return tier === 'high' ? 'High assurance' : 'Compatibility';
}

/** `"Mobile or desktop app · High assurance"` - the two choices as they were made. */
export function templateLabel(template: { kind: ClientKind; tier: AssuranceTier }): string {
  const kind = CLIENT_KINDS.find(descriptor => descriptor.id === template.kind);
  return `${kind?.name ?? template.kind} · ${assuranceTierName(template.tier)}`;
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
  template: { kind: ClientKind; tier: AssuranceTier },
  current: Partial<OAuthClient>,
): TemplateDifference[] {
  const patch = clientPreset(template.kind, template.tier).patch as Record<string, unknown>;
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
  template: { kind: ClientKind; tier: AssuranceTier },
  section: TemplateSettingSection,
): boolean {
  const patch = clientPreset(template.kind, template.tier).patch as Record<string, unknown>;
  return TEMPLATE_SETTINGS.some(setting => setting.section === section && setting.field in patch);
}

/** The credential the combination applies until the next step is told otherwise. */
export function defaultCredentialMode(kind: ClientKind, tier: AssuranceTier): ClientCredentialMode {
  return clientPreset(kind, tier).credentialModes[0];
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

/** The method a (clientType, credential mode) pair registers. A native client has nothing to
 * authenticate with regardless of which mode a leftover selection names. */
export function authMethodFor(clientType: ClientType, mode: ClientCredentialMode): AuthMethod {
  if (clientType === 'native') {
    return 'none';
  }
  switch (mode) {
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
