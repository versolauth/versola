import { describe, expect, it } from 'vitest';
import {
  MAX_JWKS_KEYS,
  isHttpUrl,
  clientCredentialKind,
  jwksVerifiesRequestObjects,
  validateClientCredential,
  validateEdgeFrontedRedirectUri,
  validateJwksJson,
  describeJwks,
  validateMtlsTermination,
  validatePermission,
  validateRoleId,
  validateScopeId,
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
    expect(clientCredentialKind({ authMethod: 'self_signed_tls_client_auth' })).toBe('mtls');
    expect(clientCredentialKind({ authMethod: 'tls_client_auth' })).toBe('mtls');
    expect(clientCredentialKind({ authMethod: 'private_key_jwt' })).toBe('private_key_jwt');
  });

  it('leaves the secret in force for a plain confidential client, and none for a native one', () => {
    expect(clientCredentialKind({ authMethod: 'client_secret' })).toBe('secret');
    expect(clientCredentialKind({ authMethod: 'none' })).toBe('public');
  });
});

describe('validateEdgeFrontedRedirectUri', () => {
  it('admits an https App Link', () => {
    expect(validateEdgeFrontedRedirectUri('https://app.example.com/callback').valid).toBe(true);
  });

  it('refuses a custom scheme and plain http, which central refuses for an app fronted by edge', () => {
    expect(validateEdgeFrontedRedirectUri('com.example.app://callback').valid).toBe(false);
    expect(validateEdgeFrontedRedirectUri('http://localhost:3000/cb').valid).toBe(false);
  });

  it('keeps the general rules, such as no fragment', () => {
    expect(validateEdgeFrontedRedirectUri('https://app.example.com/cb#frag').valid).toBe(false);
  });
});

describe('describeJwks', () => {
  it('names the key type, curve and size', () => {
    const summary = describeJwks({
      keys: [
        { kty: 'EC', crv: 'P-256', kid: 'a', x: 'x', y: 'y' },
        { kty: 'RSA', n: 'A'.repeat(342), e: 'AQAB', alg: 'PS256' },
      ],
    });
    expect(summary.map(key => key.kind)).toEqual(['EC P-256', 'RSA 2048']);
    expect(summary[0].kid).toBe('a');
    expect(summary[1].alg).toBe('PS256');
  });

  it('flags private key material', () => {
    expect(describeJwks({ keys: [{ kty: 'EC', crv: 'P-256', d: 'secret' }] })[0].private).toBe(true);
    expect(describeJwks({ keys: [{ kty: 'EC', crv: 'P-256' }] })[0].private).toBe(false);
  });

  it('is empty for anything that is not a key set', () => {
    expect(describeJwks(undefined)).toEqual([]);
  });
});

describe('identifier validators match the server patterns', () => {
  it('role IDs allow hyphens and reject underscores', () => {
    expect(validateRoleId('oauth-admin')).toBe(true);
    expect(validateRoleId('role_admin')).toBe(false);
    expect(validateRoleId('1admin')).toBe(false);
  });

  it('scope IDs allow underscores and reject hyphens, dots and colons', () => {
    expect(validateScopeId('user_data')).toBe(true);
    expect(validateScopeId('user-data')).toBe(false);
    expect(validateScopeId('users:read')).toBe(false);
    expect(validateScopeId('users.read')).toBe(false);
  });

  it('permissions use only the colon as a separator', () => {
    expect(validatePermission('orders:read')).toBe(true);
    expect(validatePermission('billing:invoice:read')).toBe(true);
    expect(validatePermission('orders')).toBe(true);
    expect(validatePermission('orders.read')).toBe(false);
    expect(validatePermission('billing:invoice.read')).toBe(false);
    expect(validatePermission('billing:')).toBe(false);
    expect(validatePermission('billing-invoice')).toBe(true);
    expect(validatePermission('auth-settings:manage')).toBe(true);
    expect(validatePermission('-billing')).toBe(false);
    expect(validatePermission('Billing')).toBe(false);
  });
});

describe('isHttpUrl', () => {
  it('accepts absolute http(s) URLs', () => {
    expect(isHttpUrl('https://cdn.example/logo.svg')).toBe(true);
    expect(isHttpUrl('http://localhost:8080/logo.png')).toBe(true);
  });

  it('refuses relative, scheme-less and non-http URLs', () => {
    for (const value of ['/logo.svg', 'logo.svg', 'javascript:alert(1)', 'data:image/svg+xml,<svg/>', 'ftp://cdn.example/a.svg', '']) {
      expect(isHttpUrl(value)).toBe(false);
    }
  });
});
