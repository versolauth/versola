import { describe, expect, it } from 'vitest';
import { MAX_JWKS_KEYS, validateClientCredential, validateJwksJson } from './validators';

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
