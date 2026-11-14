import { describe, expect, it } from 'vitest';
import { partnerKindPlan } from './partners';

const limits = {
  scopes: ['openid', 'profile'], redirectDomains: ['pilotpay.example'], maxClients: 10, maxEdges: 3, accessTokenTtlMax: 86_400, refreshTokenTtlMax: 30 * 86_400,
  clientAuthMethods: ['tls_client_auth', 'private_key_jwt'], rateLimitPolicy: 'partner-default',
} as const;
const partner = (clientAuthMethods: readonly string[]) => ({ displayName: 'Pilot Pay', groupId: 'pilot-pay', limits: { ...limits, clientAuthMethods: [...clientAuthMethods] } }) as never;

describe('partnerKindPlan', () => {
  const credentialLine = (methods: string[], credential: 'mtls' | 'private-key-jwt') => partnerKindPlan('web', partner(methods), credential)[1];

  it('says once, on the credential line, that the partner may use the other credential', () => {
    expect(credentialLine(['tls_client_auth', 'private_key_jwt'], 'mtls').why).toContain('may also use private_key_jwt; choose on the next step');
    expect(credentialLine(['tls_client_auth', 'private_key_jwt'], 'private-key-jwt').why).toContain('may also use the group certificate; choose on the next step');
    expect(credentialLine(['tls_client_auth'], 'mtls').why).not.toContain('choose on the next step');
    expect(partnerKindPlan('web', partner(['tls_client_auth', 'private_key_jwt']), 'mtls').filter(line => line.why.includes('choose on the next step'))).toHaveLength(1);
  });

  it('says the tokens are certificate-bound with the group certificate, and bearer with a key', () => {
    const line = (credential: 'mtls' | 'private-key-jwt') =>
      partnerKindPlan('web', partner(['tls_client_auth', 'private_key_jwt']), credential).find(l => l.text === 'Certificate-bound access tokens')!;
    expect(line('mtls').state).toBe('on');
    expect(line('mtls').why).toContain('Another edge of the group re-binds');
    expect(line('private-key-jwt').state).toBe('off');
    expect(line('private-key-jwt').why).toContain('bearer tokens');
  });

  it('does not say the tokens never reach the browser: the HttpOnly session cookie carries the access token', () => {
    const served = partnerKindPlan('web', partner(['tls_client_auth']), 'mtls').find(l => l.text.startsWith('Served by the edges'))!;
    expect(served.why).toContain('HttpOnly');
    expect(served.why).toContain('carries the access token');
    expect(served.why).not.toContain('never reach the browser');
  });

  it('keeps the protocol facts: PKCE, credential, edges, PAR, binding, lifetime cap', () => {
    const texts = partnerKindPlan('web', partner(['tls_client_auth']), 'mtls').map(line => line.text);
    expect(texts).toEqual([
      'PKCE, S256 only',
      'Group certificate: tls_client_auth, san_uri = urn:versola:edge-group:pilot-pay',
      'Served by the edges of group pilot-pay',
      'Pushed authorization requests',
      'Certificate-bound access tokens',
      '1 day access token at most',
    ]);
  });
});
