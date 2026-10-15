import { describe, it, expect } from 'vitest';
import { createDefaultAuthFlow, createDefaultConsentFlow, isPartnerClient, otpTemplateForConsent } from './helpers';

const WITH_PARTNER = ['default', 'partner'];

describe('isPartnerClient', () => {
  it('is a partner exactly when it asks for consent', () => {
    expect(isPartnerClient({ consentFlow: createDefaultConsentFlow() })).toBe(true);
    expect(isPartnerClient({ consentFlow: null })).toBe(false);
    expect(isPartnerClient({})).toBe(false);
  });
});

describe('otpTemplateForConsent', () => {
  it('moves a partner from the default SMS template to the partner one', () => {
    expect(otpTemplateForConsent('default', true, 'sms', WITH_PARTNER)).toBe('partner');
    expect(otpTemplateForConsent(null, true, 'sms', WITH_PARTNER)).toBe('partner');
  });

  it('moves back to default when consent is switched off', () => {
    expect(otpTemplateForConsent('partner', false, 'sms', WITH_PARTNER)).toBe('default');
  });

  it('leaves a template chosen by hand alone', () => {
    expect(otpTemplateForConsent('custom', true, 'sms', [...WITH_PARTNER, 'custom'])).toBe('custom');
    expect(otpTemplateForConsent('custom', false, 'sms', [...WITH_PARTNER, 'custom'])).toBe('custom');
  });

  it('does not pick a partner template for email or when none exists', () => {
    expect(otpTemplateForConsent('default', true, 'email', WITH_PARTNER)).toBe('default');
    expect(otpTemplateForConsent('default', true, 'sms', ['default'])).toBe('default');
  });
});

describe('default sign-in flows', () => {
  it('offers passkey sign-in and a skippable enrollment after the OTP', () => {
    const flow = createDefaultAuthFlow();
    expect(flow.primaryCredentials).toEqual(['phone']);
    expect(flow.passkey).toBe(true);
    expect(flow.factors).toEqual([
      { type: 'otp', required: true },
      { type: 'passkeyEnroll', required: false },
    ]);
  });
});
