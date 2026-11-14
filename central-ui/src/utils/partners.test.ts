import { describe, expect, it } from 'vitest';
import { partnerKindPlan } from './partners';

const limits = {
  scopes: ['openid', 'profile'], redirectDomains: ['pilotpay.example'], maxClients: 10, maxEdges: 3, accessTokenTtlMax: 86_400, refreshTokenTtlMax: 30 * 86_400,
  clientAuthMethods: ['tls_client_auth'],
} as const;
const partner = (clientAuthMethods: readonly string[] = ['tls_client_auth']) => ({ displayName: 'Pilot Pay', groupId: 'pilot-pay', limits: { ...limits, clientAuthMethods: [...clientAuthMethods] } }) as never;

describe('partnerKindPlan', () => {
  it('always names the group certificate, even for a partner whose limits still allow private_key_jwt: this console does not offer it', () => {
    const plan = partnerKindPlan('web', partner(['tls_client_auth', 'private_key_jwt']));
    expect(plan[1].text).toContain('tls_client_auth');
    expect(plan.map(line => line.text + line.why).join(' ')).not.toContain('private_key_jwt');
  });

  it('says the tokens are certificate-bound and another edge re-binds them', () => {
    const line = partnerKindPlan('web', partner()).find(l => l.text === 'Certificate-bound access tokens')!;
    expect(line.state).toBe('on');
    expect(line.why).toContain('Another edge of the group re-binds');
  });

  it('does not say the tokens never reach the browser: the HttpOnly session cookie carries the access token', () => {
    const served = partnerKindPlan('web', partner()).find(l => l.text.startsWith('Served by the edges'))!;
    expect(served.why).toContain('HttpOnly');
    expect(served.why).toContain('carries the access token');
    expect(served.why).not.toContain('never reach the browser');
  });

  it('keeps the protocol facts: PKCE, credential, edges, PAR, binding, lifetime cap', () => {
    const texts = partnerKindPlan('web', partner()).map(line => line.text);
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
