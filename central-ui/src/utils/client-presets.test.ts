import { describe, it, expect } from 'vitest';
import {
  CLIENT_KINDS,
  ClientKind,
  authMethodFor,
  certificateBoundFor,
  clientPreset,
  clientTypeFor,
  credentialBinding,
  defaultCredentialMode,
  signsUsersIn,
  templateDecides,
  templateDifferences,
  templateLabel,
} from './client-presets';
import type { SecurityProfile } from '../types';

const KINDS: ClientKind[] = ['web', 'device', 'service'];
const PROFILES: SecurityProfile[] = ['standard', 'fapi2'];

describe('clientPreset', () => {
  it('offers a card for every kind it presets', () => {
    expect(CLIENT_KINDS.map(k => k.id).sort()).toEqual([...KINDS].sort());
  });

  it('fronts web and mobile clients by edge in both profiles, with no credential to pick', () => {
    for (const kind of ['web', 'device'] as ClientKind[]) {
      for (const profile of PROFILES) {
        const preset = clientPreset(kind, profile);
        expect(preset.credentialModes).toEqual(['edge']);
        expect(preset.patch.requirePushedAuthorizationRequests).toBe(true);
        expect(preset.patch.requireSignedRequestObject).toBe(false);
      }
    }
  });

  it('sets the same patch for an edge-fronted kind whichever the profile', () => {
    for (const kind of ['web', 'device'] as ClientKind[]) {
      expect(clientPreset(kind, 'standard').patch).toEqual(clientPreset(kind, 'fapi2').patch);
    }
  });

  it('binds a web token to edge\'s certificate and a device token to the device key', () => {
    expect(clientPreset('web', 'fapi2').patch).toMatchObject({
      clientType: 'web', certificateBoundAccessTokens: true, dpopBoundAccessTokens: false,
    });
    expect(clientPreset('device', 'fapi2').patch).toMatchObject({
      clientType: 'native', certificateBoundAccessTokens: false, dpopBoundAccessTokens: true,
    });
  });

  it('lets a service pick a key or a certificate, and a secret only outside FAPI 2.0', () => {
    expect(clientPreset('service', 'fapi2').credentialModes).toEqual(['private-key-jwt', 'mtls', 'mtls-self-signed']);
    expect(clientPreset('service', 'standard').credentialModes).toEqual(
      ['secret', 'private-key-jwt', 'mtls', 'mtls-self-signed'],
    );
  });

  it('never offers a shared secret to a FAPI 2.0 tenant', () => {
    for (const kind of KINDS) {
      expect(clientPreset(kind, 'fapi2').credentialModes).not.toContain('secret');
    }
  });

  it('never asks a shipped binary for a signed request object', () => {
    for (const profile of PROFILES) {
      expect(clientPreset('device', profile).patch.requireSignedRequestObject).toBe(false);
    }
  });

  it('leaves a service client with no user-facing configuration at all', () => {
    for (const profile of PROFILES) {
      const { patch } = clientPreset('service', profile);
      expect(patch.redirectUris).toEqual([]);
      expect(patch.scope).toEqual([]);
      expect(patch.authFlow).toBeNull();
      expect(patch.registrationFlow).toBeNull();
      expect(patch.consentFlow).toBeNull();
      expect(patch.requirePushedAuthorizationRequests).toBe(false);
      expect(patch.requireSignedRequestObject).toBe(false);
    }
  });

  it('issues a one hour access token everywhere', () => {
    for (const kind of KINDS) {
      for (const profile of PROFILES) {
        expect(clientPreset(kind, profile).patch.accessTokenTtl).toBe(3600);
      }
    }
  });

  it('offers a choice of credential exactly where a plan line asks for one', () => {
    for (const kind of KINDS) {
      for (const profile of PROFILES) {
        const preset = clientPreset(kind, profile);
        const asksToPick = preset.plan.some(line => line.state === 'pick');
        expect(asksToPick).toBe(preset.credentialModes.length > 1);
      }
    }
  });

  it('explains every line that is not a plain default', () => {
    for (const kind of KINDS) {
      for (const profile of PROFILES) {
        for (const line of clientPreset(kind, profile).plan) {
          if (line.state !== 'on' && line.state !== 'off') {
            expect(line.why).not.toBe('');
          }
        }
      }
    }
  });

  it('notes that the tenant is not FAPI 2.0 on a standard tenant only, once', () => {
    for (const kind of KINDS) {
      const notes = (profile: SecurityProfile) =>
        clientPreset(kind, profile).plan.filter(line => line.state === 'note');
      expect(notes('fapi2')).toEqual([]);
      expect(notes('standard')).toHaveLength(1);
    }
  });
});

describe('defaultCredentialMode', () => {
  it('has edge authenticate the kinds it fronts', () => {
    for (const profile of PROFILES) {
      expect(defaultCredentialMode('web', profile)).toBe('edge');
      expect(defaultCredentialMode('device', profile)).toBe('edge');
    }
  });

  it('keeps a secret out of a FAPI 2.0 service', () => {
    expect(defaultCredentialMode('service', 'fapi2')).toBe('private-key-jwt');
  });

  it('defaults a standard service to a secret', () => {
    expect(defaultCredentialMode('service', 'standard')).toBe('secret');
  });
});

describe('credentialBinding', () => {
  it('has a key carry DPoP, a certificate bind itself, and a secret bind nothing', () => {
    expect(credentialBinding('private-key-jwt')).toEqual({ dpopBoundAccessTokens: true, certificateBoundAccessTokens: false });
    expect(credentialBinding('mtls')).toEqual({ dpopBoundAccessTokens: false, certificateBoundAccessTokens: true });
    expect(credentialBinding('secret')).toEqual({ dpopBoundAccessTokens: false, certificateBoundAccessTokens: false });
  });

  it('leaves an edge-fronted kind to its preset', () => {
    expect(credentialBinding('edge')).toBeNull();
  });
});

describe('clientTypeFor', () => {
  it('makes only a shipped binary public', () => {
    expect(clientTypeFor('device')).toBe('native');
    expect(clientTypeFor('web')).toBe('web');
    expect(clientTypeFor('service')).toBe('web');
  });

  it('agrees with the client type each preset applies', () => {
    for (const kind of KINDS) {
      for (const profile of PROFILES) {
        expect(clientPreset(kind, profile).patch.clientType).toBe(clientTypeFor(kind));
      }
    }
  });
});

describe('authMethodFor', () => {
  it('is none for a native client regardless of which mode a leftover selection names', () => {
    expect(authMethodFor('native', 'secret')).toBe('none');
    expect(authMethodFor('native', 'private-key-jwt')).toBe('none');
  });

  it('is mTLS for every client edge authenticates as, native or not', () => {
    expect(authMethodFor('native', 'edge')).toBe('tls_client_auth');
    expect(authMethodFor('web', 'edge')).toBe('tls_client_auth');
  });

  it('names the RFC method behind each confidential credential mode', () => {
    expect(authMethodFor('web', 'secret')).toBe('client_secret');
    expect(authMethodFor('web', 'mtls')).toBe('tls_client_auth');
    expect(authMethodFor('web', 'mtls-self-signed')).toBe('self_signed_tls_client_auth');
    expect(authMethodFor('web', 'private-key-jwt')).toBe('private_key_jwt');
  });
});

describe('certificateBoundFor', () => {
  it('binds the token to the certificate only when one is presented', () => {
    expect(certificateBoundFor('mtls')).toBe(true);
    expect(certificateBoundFor('mtls-self-signed')).toBe(true);
    expect(certificateBoundFor('private-key-jwt')).toBe(false);
    expect(certificateBoundFor('secret')).toBe(false);
  });
});

describe('signsUsersIn', () => {
  it('is false for the one kind with no user', () => {
    expect(signsUsersIn('service')).toBe(false);
    expect(signsUsersIn('web')).toBe(true);
    expect(signsUsersIn('device')).toBe(true);
  });
});

describe('templateDifferences', () => {
  const device = { kind: 'device' as ClientKind };

  it('finds nothing while the client still holds what the template asked for', () => {
    expect(templateDifferences(device, 'fapi2', {
      accessTokenTtl: 3600,
      dpopBoundAccessTokens: true,
      certificateBoundAccessTokens: false,
      requirePushedAuthorizationRequests: true,
      requireSignedRequestObject: false,
    })).toEqual([]);
  });

  it('states the template value a setting moved away from, and the one it holds now', () => {
    const differences = templateDifferences(device, 'fapi2', {
      accessTokenTtl: 4 * 3600,
      dpopBoundAccessTokens: true,
      certificateBoundAccessTokens: false,
      requirePushedAuthorizationRequests: false,
      requireSignedRequestObject: false,
    });

    expect(differences.map(d => [d.label, d.templateText, d.currentText])).toEqual([
      ['Access token TTL', '1 h', '4 h'],
      ['Pushed authorization requests', 'required', 'off'],
    ]);
    expect(differences.map(d => d.templateValue)).toEqual([3600, true]);
    expect(differences.map(d => d.section)).toEqual(['tokens', 'integrity']);
  });

  it('compares nothing a template does not decide', () => {
    // Every preset decides all five settings, so drift is only ever reported for a value
    // the registration actually asked for - a scope added later is configuration, not drift.
    expect(templateDifferences(device, 'fapi2', { scope: ['openid'], accessTokenTtl: 3600 })
      .map(d => d.field)).not.toContain('scope');
  });
});

describe('templateDecides', () => {
  it('knows which part of the form a template has an opinion about', () => {
    const web = { kind: 'web' as ClientKind };
    expect(templateDecides(web, 'fapi2', 'tokens')).toBe(true);
    expect(templateDecides(web, 'fapi2', 'integrity')).toBe(true);
    expect(templateDecides(web, 'fapi2', 'credential')).toBe(true);
  });
});

describe('templateLabel', () => {
  it('names what the client was registered as', () => {
    expect(templateLabel({ kind: 'device' })).toBe('Mobile or desktop app');
    expect(templateLabel({ kind: 'web' })).toBe('Web app');
  });
});
