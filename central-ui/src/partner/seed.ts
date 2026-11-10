import type { MockPartnerState } from './mock-backend';

/**
 * The partner console's demo and test data: Pilot Pay, the partner of the operator demo
 * (`src/demo/seed.ts`), seen from its own side, with three staff members and one invite. Dates
 * are relative to `now` so a spec can pin them.
 */
export function partnerSeed(now = Date.now()): MockPartnerState {
  const at = (days: number, hour = 10) => {
    const date = new Date(now);
    date.setUTCDate(date.getUTCDate() + days);
    date.setUTCHours(hour, 0, 0, 0);
    return date.toISOString();
  };
  const limits = {
    scopes: ['openid', 'profile', 'email', 'phone'],
    redirectDomains: ['pilotpay.example', 'pilotpay.app'],
    maxClients: 10,
    maxEdges: 3,
    // Central's own maximum: the operator may narrow it, but never widen what central allows every client.
    accessTokenTtlMax: 86_400,
    refreshTokenTtlMax: 30 * 86_400,
    clientAuthMethods: ['tls_client_auth' as const, 'private_key_jwt' as const],
    rateLimitPolicy: 'partner-default',
  };
  const fixed = { consent: true as const, flow: 'code+pkce+par' as const, authMethod: 'tls_client_auth' as const, group: 'pilot-pay' };
  const policies = {
    overload: { inbound: { mode: 'priority' }, outbound: { retryBudgetPercent: 10 } },
    accessLog: { mode: 'failures-and-auth' },
    rateLimits: { policies: [{ name: 'partner-default', by: ['client'], scope: 'edge-wide', limits: [{ limit: 30000, windowSeconds: 60 }], mode: 'enforce' }, { name: 'per-user', by: ['subject'], scope: 'edge-wide', limits: [{ limit: 3000, windowSeconds: 60 }], mode: 'enforce' }] },
  };
  return {
    me: {
      partner: {
        id: 'pilot-pay', displayName: 'Pilot Pay', logoUri: 'https://pay.pilotpay.example/brand/pilot-pay.svg', status: 'active', limits, reviewAt: at(20),
        operator: { name: 'Acme Retail', issuer: 'https://id.acme.example', mtlsUrl: 'https://id-mtls.acme.example', centralMtlsUrl: 'https://central-mtls.acme.example' },
      },
      staff: { sub: 'u_7f3a9c2e', display: 'Dana Kim', level: 'admin' },
      edge: { id: 'edge-pilot-eu-1', publicUrl: 'https://login.pilotpay.example', central: 'ok' },
    },
    clients: [
      {
        id: 'pilot-pay-web', kind: 'web', clientName: 'Pilot Pay Shop', logoUri: 'https://pay.pilotpay.example/brand/pilot-pay.svg',
        policyUri: 'https://pilotpay.example/privacy', tosUri: 'https://pilotpay.example/terms',
        redirectUris: ['https://pay.pilotpay.example/complete', 'https://staging.pilotpay.example/complete'],
        postLogoutRedirectUris: ['https://pay.pilotpay.example/'],
        scope: ['openid', 'profile', 'email'], accessTokenTtl: 300, refreshTokenTtl: 14 * 86_400,
        backChannelLogoutUri: 'https://login.pilotpay.example/logout/backchannel', createdAt: at(-60),
        loginUrl: 'https://login.pilotpay.example/login/pilot-pay-web',
        approval: { kind: 'create', status: 'approved', waitingFields: [], requestedAt: at(-61), note: 'Name matches the partner.' }, fixed,
      },
      {
        id: 'pilot-pay-ios', kind: 'native', clientName: 'Pilot Pay iOS', logoUri: 'https://pay.pilotpay.example/brand/pilot-pay.svg',
        redirectUris: ['https://app.pilotpay.example/callback'], scope: ['openid', 'profile', 'phone'], accessTokenTtl: 3600, refreshTokenTtl: 30 * 86_400,
        backChannelLogoutUri: 'https://login.pilotpay.example/logout/backchannel', createdAt: at(-33),
        loginUrl: 'https://login.pilotpay.example/login/pilot-pay-ios',
        approval: { kind: 'create', status: 'approved', waitingFields: [], requestedAt: at(-34) }, fixed,
      },
      {
        id: 'pilot-pay-checkout', kind: 'web', clientName: 'Pilot Pay Checkout', logoUri: 'https://pay.pilotpay.example/brand/pilot-pay.svg',
        redirectUris: ['https://checkout.pilotpay.example/complete', 'https://checkout.pilot-partner.net/complete'],
        scope: ['openid', 'profile', 'email'], accessTokenTtl: 300, refreshTokenTtl: 14 * 86_400,
        backChannelLogoutUri: 'https://login.pilotpay.example/logout/backchannel', createdAt: at(-2, 14),
        loginUrl: 'https://login.pilotpay.example/login/pilot-pay-checkout',
        approval: { kind: 'create', status: 'pending', waitingFields: ['clientName', 'logoUri', 'redirectUris'], requestedAt: at(-2, 14) }, fixed,
      },
    ],
    resources: [
      {
        resourceId: 'pilot-orders', resource: 'http://orders.pilotpay.internal:8080', audience: ['pilot-pay-web', 'pilot-pay-ios'], secretRotation: false, secretRotatedAt: at(-5),
        endpoints: [
          { id: 401, method: 'GET', path: '/orders', fetchUserInfo: false, allow: "'profile' in token.scope", inject: [{ target: 'header', name: 'X-User', expression: 'token.sub' }] },
          { id: 402, method: 'POST', path: '/orders', fetchUserInfo: true, allow: "'profile' in token.scope && user.email_verified == true", inject: [{ target: 'header', name: 'X-User', expression: 'token.sub' }, { target: 'header', name: 'X-User-Phone', expression: 'user.phone_number' }], stepUpCondition: 'request.body.amount > 50000', stepUpAcr: 'sensitive', maxAge: 300, rateLimit: { limits: [{ limit: 60, windowSeconds: 60 }], by: 'subject', mode: 'enforce' } },
          { id: 403, method: 'GET', path: '/orders/{id}', fetchUserInfo: false, allow: "'profile' in token.scope", inject: [] },
          { id: 404, method: 'DELETE', path: '/orders/{id}', fetchUserInfo: false, allow: "'pop' in token.amr", inject: [], rateLimit: { limits: [{ limit: 10, windowSeconds: 60 }], by: 'subject', mode: 'enforce' } },
        ],
      },
      {
        resourceId: 'pilot-wallet', resource: 'http://wallet.pilotpay.internal:8080', audience: ['pilot-pay-ios'], secretRotation: true, secretRotatedAt: at(-1),
        endpoints: [
          { id: 411, method: 'GET', path: '/wallet', fetchUserInfo: false, allow: "'phone' in token.scope", inject: [{ target: 'header', name: 'X-User', expression: 'token.sub' }] },
          { id: 412, method: 'POST', path: '/wallet/topup', fetchUserInfo: false, allow: "'phone' in token.scope", inject: [], stepUpCondition: 'true', stepUpAcr: 'sensitive', maxAge: 120 },
        ],
      },
    ],
    edges: [
      { id: 'edge-pilot-eu-1', publicUrl: 'https://login.pilotpay.example', status: 'enrolled', certificateNotAfter: at(10), lastRenewedAt: at(-4, 2), replicas: 2, policies },
      { id: 'edge-pilot-eu-2', publicUrl: 'https://login.pilotpay.example', status: 'enrolled', certificateNotAfter: at(8), lastRenewedAt: at(-6, 2), replicas: 1, policies },
      { id: 'edge-pilot-kz-1', publicUrl: 'https://login-kz.pilotpay.example', status: 'waiting', replicas: 0, policies, pendingToken: { expiresAt: at(0, 11) } },
    ],
    revocations: [
      { id: 'rv-1', at: at(-1, 10), by_staff: 'Marat Lee', by: 'user', value: 'u_2b91d4e0', clients: ['pilot-pay-web'], edges: ['edge-pilot-eu-1', 'edge-pilot-eu-2'], ended: 3 },
      { id: 'rv-2', at: at(-9, 16), by_staff: 'Dana Kim', by: 'client', value: 'pilot-pay-ios', clients: ['pilot-pay-ios'], edges: ['edge-pilot-eu-1', 'edge-pilot-eu-2'], ended: 1284 },
    ],
    audit: [
      { id: 'ev-12', at: at(-1, 10), actor: { sub: 'u_2b91d4e0', display: 'Marat Lee' }, action: 'ended the sessions of a user', target: 'pilot-pay-web · u_2b91d4e0', outcome: 'ok', from: 'edge-pilot-eu-1' },
      { id: 'ev-11', at: at(-1, 9), actor: { sub: 'u_7f3a9c2e', display: 'Dana Kim' }, action: 'invited a member (admin)', target: 'ops@…', outcome: 'ok', from: 'edge-pilot-eu-1' },
      { id: 'ev-10', at: at(-2, 14), actor: { sub: 'u_7f3a9c2e', display: 'Dana Kim' }, action: 'submitted a client for approval', target: 'pilot-pay-checkout', outcome: 'ok', from: 'edge-pilot-eu-1' },
      { id: 'ev-9', at: at(-4, 2), actor: { sub: 'edge-pilot-eu-1', display: 'edge-pilot-eu-1' }, action: 'renewed its certificate', target: 'serial 4f3a…', outcome: 'ok' },
      { id: 'ev-8', at: at(-5, 9), actor: { sub: 'u_2b91d4e0', display: 'Marat Lee' }, action: 'rotated the secret of a resource', target: 'pilot-orders', outcome: 'ok', from: 'edge-pilot-eu-2' },
      { id: 'ev-7', at: at(-6, 18), actor: { sub: 'anonymous' }, action: 'sign-in refused: not a member', target: '+7 7•• ••• 11', outcome: 'refused', from: 'edge-pilot-eu-1' },
      { id: 'ev-6', at: at(-10, 11), actor: { sub: 'operator', display: 'the operator', byOperator: true }, action: 'set the review date', target: at(20).slice(0, 10), outcome: 'ok' },
      { id: 'ev-5', at: at(-17, 16), actor: { sub: 'operator', display: 'the operator', byOperator: true }, action: 'changed the limits: refresh token cap 14 → 30 days', target: 'limits', outcome: 'ok' },
      { id: 'ev-4', at: at(-33, 12), actor: { sub: 'operator', display: 'the operator', byOperator: true }, action: 'approved a client', target: 'pilot-pay-ios', outcome: 'ok' },
      { id: 'ev-3', at: at(-60, 12), actor: { sub: 'operator', display: 'the operator', byOperator: true }, action: 'approved a client', target: 'pilot-pay-web', outcome: 'ok' },
      { id: 'ev-2', at: at(-61, 12), actor: { sub: 'u_7f3a9c2e', display: 'Dana Kim' }, action: 'submitted a client for approval', target: 'pilot-pay-web', outcome: 'ok', from: 'edge-pilot-eu-1' },
      { id: 'ev-1', at: at(-66, 12), actor: { sub: 'edge-pilot-eu-1', display: 'edge-pilot-eu-1' }, action: 'enrolled', target: 'group pilot-pay', outcome: 'ok' },
    ],
    staff: [
      { id: 'u_7f3a9c2e', subject: 'u_7f3a9c2e', identifier: { kind: 'phone', masked: '+7 701 •••• 42' }, display: 'Dana Kim', level: 'admin', state: 'member', addedBy: 'config', addedAt: at(-66), lastSignInAt: at(0, 9) },
      { id: 'u_2b91d4e0', subject: 'u_2b91d4e0', identifier: { kind: 'phone', masked: '+7 777 •••• 18' }, display: 'Marat Lee', level: 'developer', state: 'member', addedBy: 'Dana Kim', addedAt: at(-60), lastSignInAt: at(-1, 10) },
      { id: 'u_9c0e1a77', subject: 'u_9c0e1a77', identifier: { kind: 'email', masked: 'a.s•••@pilotpay.example' }, display: 'Aya Sato', level: 'viewer', state: 'member', addedBy: 'Dana Kim', addedAt: at(-28), lastSignInAt: at(-26) },
      { id: 'inv-1', identifier: { kind: 'email', masked: 'ops@pilotpay.example' }, level: 'admin', state: 'invited', addedBy: 'Dana Kim', addedAt: at(-1, 9), expiresAt: at(6, 9) },
    ],
    settings: {
      contact: { email: 'security@pilotpay.example', phone: '+7 727 300 1100' },
      defaults: { backChannelLogoutUri: 'https://login.pilotpay.example/logout/backchannel', accessTokenTtl: 300, refreshTokenTtl: 14 * 86_400 },
    },
    profileApproval: null,
  };
}
