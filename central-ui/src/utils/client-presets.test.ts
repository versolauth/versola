import { describe, it, expect } from 'vitest';
import {
  AssuranceTier,
  CLIENT_KINDS,
  ClientKind,
  certificateBoundFor,
  clientPreset,
  clientTypeFor,
  defaultCredentialMode,
  signsUsersIn,
} from './client-presets';

const KINDS: ClientKind[] = ['web', 'device', 'service'];
const TIERS: AssuranceTier[] = ['high', 'compat'];

describe('clientPreset', () => {
  it('offers a card for every kind it presets', () => {
    expect(CLIENT_KINDS.map(k => k.id).sort()).toEqual([...KINDS].sort());
  });

  it('requests PAR and signed request objects only for a high-assurance web client', () => {
    expect(clientPreset('web', 'high').patch.requirePushedAuthorizationRequests).toBe(true);
    expect(clientPreset('web', 'high').patch.requireSignedRequestObject).toBe(true);
    expect(clientPreset('web', 'compat').patch.requirePushedAuthorizationRequests).toBe(false);
    expect(clientPreset('web', 'compat').patch.requireSignedRequestObject).toBe(false);
  });

  it('binds a device token to a device key only at high assurance', () => {
    expect(clientPreset('device', 'high').patch.dpopBoundAccessTokens).toBe(true);
    expect(clientPreset('device', 'compat').patch.dpopBoundAccessTokens).toBe(false);
  });

  it('never asks a shipped binary for a signed request object', () => {
    for (const tier of TIERS) {
      expect(clientPreset('device', tier).patch.requireSignedRequestObject).toBe(false);
    }
  });

  it('leaves a service client with no user-facing configuration at all', () => {
    for (const tier of TIERS) {
      const { patch } = clientPreset('service', tier);
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
      for (const tier of TIERS) {
        expect(clientPreset(kind, tier).patch.accessTokenTtl).toBe(3600);
      }
    }
  });

  it('offers a choice of credential exactly where a plan line asks for one', () => {
    for (const kind of KINDS) {
      for (const tier of TIERS) {
        const preset = clientPreset(kind, tier);
        const asksToPick = preset.plan.some(line => line.state === 'pick');
        expect(asksToPick).toBe(preset.credentialModes.length > 1);
      }
    }
  });

  it('explains every line that is not a plain default', () => {
    for (const kind of KINDS) {
      for (const tier of TIERS) {
        for (const line of clientPreset(kind, tier).plan) {
          if (line.state === 'fixed' || line.state === 'na' || line.state === 'pick') {
            expect(line.why).not.toBe('');
          }
        }
      }
    }
  });
});

describe('defaultCredentialMode', () => {
  it('keeps a secret out of a high-assurance deployment', () => {
    expect(defaultCredentialMode('web', 'high')).toBe('private-key-jwt');
    expect(defaultCredentialMode('service', 'high')).toBe('private-key-jwt');
  });

  it('falls back to a secret where nothing else can be presented', () => {
    expect(defaultCredentialMode('web', 'compat')).toBe('secret');
    expect(defaultCredentialMode('service', 'compat')).toBe('secret');
    expect(defaultCredentialMode('device', 'high')).toBe('secret');
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
      for (const tier of TIERS) {
        expect(clientPreset(kind, tier).patch.clientType).toBe(clientTypeFor(kind));
      }
    }
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
