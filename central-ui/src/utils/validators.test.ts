import { describe, expect, it } from 'vitest';
import {
  MAX_JWKS_KEYS,
  clientCredentialKind,
  jwksVerifiesRequestObjects,
  validateClientCredential,
  validateJwksJson,
  validateMtlsTermination,
} from './validators';

describe('validateJwksJson', () => {
  it('accepts a key set and returns the parsed value', () => {
    const result = validateJwksJson('{"keys": [{"kty": "EC"}]}');
    expect(result.valid).toBe(true);
    expect(result.keySet).toEqual({ keys: [{ kty: 'EC' }] });
  });

  it('rejects blank input, non-JSON, a non-object, and an empty key list', () => {
    expect(validateJwksJson('   ').valid).toBe(false);
    expect(validateJwksJson('not json').valid).toBe(false);
    expect(validateJwksJson('[{"kty": "EC"}]').valid).toBe(false);
    expect(validateJwksJson('{"keys": []}').valid).toBe(false);
  });

  it('rejects more keys than the backend accepts', () => {
    const keys = Array.from({ length: MAX_JWKS_KEYS + 1 }, () => ({ kty: 'EC' }));
    expect(validateJwksJson(JSON.stringify({ keys })).valid).toBe(false);
  });
});

describe('validateClientCredential', () => {
  it('accepts a single credential', () => {
    expect(validateClientCredential(null, false).valid).toBe(true);
    expect(validateClientCredential(null, true).valid).toBe(true);
    expect(validateClientCredential({ type: 'tls_client_auth', subjectType: 'subject_dn', subjectValue: 'CN=a' }, false).valid).toBe(true);
    expect(validateClientCredential({ type: 'self_signed_tls_client_auth' }, true).valid).toBe(true);
  });

  it('rejects tls_client_auth combined with a JWK Set', () => {
    const result = validateClientCredential({ type: 'tls_client_auth', subjectType: 'subject_dn', subjectValue: 'CN=a' }, true);
    expect(result.valid).toBe(false);
  });

  it('rejects self_signed_tls_client_auth without a JWK Set to match against', () => {
    expect(validateClientCredential({ type: 'self_signed_tls_client_auth' }, false).valid).toBe(false);
  });
});

describe('validateMtlsTermination', () => {
  it('accepts an mTLS credential under a tenant that names a certificate header', () => {
    expect(validateMtlsTermination({ type: 'self_signed_tls_client_auth' }, 'x-client-cert').valid).toBe(true);
  });

  it('accepts any other credential under a tenant that names none', () => {
    expect(validateMtlsTermination(null, null).valid).toBe(true);
  });

  it('rejects an mTLS credential under a tenant that names none, as the backend does', () => {
    const result = validateMtlsTermination(
      { type: 'tls_client_auth', subjectType: 'subject_dn', subjectValue: 'CN=a' },
      null,
    );
    expect(result.valid).toBe(false);
    expect(result.error).toContain('mTLS certificate header');
  });
});

describe('jwksVerifiesRequestObjects', () => {
  it('accepts the key types a request object signature is verified with', () => {
    expect(jwksVerifiesRequestObjects({ keys: [{ kty: 'EC', crv: 'P-256' }] })).toBe(true);
    expect(jwksVerifiesRequestObjects({ keys: [{ kty: 'RSA', n: 'n', e: 'AQAB' }] })).toBe(true);
    expect(jwksVerifiesRequestObjects({ keys: [{ kty: 'EC', crv: 'P-256', alg: 'ES256' }] })).toBe(true);
  });

  it('rejects a curve no signature algorithm here names, which certificate matching still accepts', () => {
    expect(jwksVerifiesRequestObjects({ keys: [{ kty: 'EC', crv: 'P-384' }] })).toBe(false);
    expect(jwksVerifiesRequestObjects({ keys: [{ kty: 'EC', crv: 'P-256' }, { kty: 'EC', crv: 'P-521' }] })).toBe(false);
  });

  it('rejects a key pinned to an algorithm its type cannot perform, and one marked for encryption', () => {
    expect(jwksVerifiesRequestObjects({ keys: [{ kty: 'RSA', alg: 'ES256' }] })).toBe(false);
    expect(jwksVerifiesRequestObjects({ keys: [{ kty: 'RSA', use: 'enc' }] })).toBe(false);
  });

  it('rejects an absent or empty set', () => {
    expect(jwksVerifiesRequestObjects(null)).toBe(false);
    expect(jwksVerifiesRequestObjects({ keys: [] })).toBe(false);
  });
});

describe('clientCredentialKind', () => {
  it('names the credential that replaces the secret', () => {
    expect(clientCredentialKind({ clientType: 'web', mtlsAuth: { type: 'self_signed_tls_client_auth' }, jwks: { keys: [] } }))
      .toBe('mtls');
    expect(clientCredentialKind({ clientType: 'web', mtlsAuth: null, jwks: { keys: [] } })).toBe('private_key_jwt');
  });

  it('leaves the secret in force for a plain confidential client, and none for a native one', () => {
    expect(clientCredentialKind({ clientType: 'web', mtlsAuth: null, jwks: null })).toBe('secret');
    expect(clientCredentialKind({ clientType: 'native', mtlsAuth: null, jwks: null })).toBe('public');
  });
});
