import { describe, expect, it } from 'vitest';
import { PARTNER_LOGIN_PATH, signInPath } from './partner-api';

const origin = 'https://console.pilotpay.example';

describe('signInPath', () => {
  it('follows the edge when it names a path under /partner/', () => {
    expect(signInPath('/partner/login', origin)).toBe('/partner/login');
    expect(signInPath('/partner/login?next=%2Fpartner%2F', origin)).toBe('/partner/login?next=%2Fpartner%2F');
  });

  it.each([undefined, null, 42, '', 'login', '/login', '/partnerx/login', '//evil.example/partner/x', 'https://evil.example/partner/login', '/partner/../admin', '/partner/%2e%2e/admin', '\\\\evil.example', 'javascript:alert(1)'])(
    'falls back to the fixed login path for %j',
    named => expect(signInPath(named, origin)).toBe(PARTNER_LOGIN_PATH),
  );
});
