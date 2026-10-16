import { describe, expect, it } from 'vitest';
import type { FormPreviewScenario } from '../types';
import { consentStepFor, pickConsentScenario } from './consent-preview';

const scenario = (id: string, step: Record<string, unknown> = {}): FormPreviewScenario => ({ id, title: { en: id }, step: { type: 'consent', ...step } });

describe('pickConsentScenario', () => {
  it('takes the typical request, wherever the catalog lists it', () => {
    expect(pickConsentScenario([scenario('everything'), scenario('typical')])?.id).toBe('typical');
  });

  it('falls back to the first consent scenario when the catalog lacks the typical one', () => {
    expect(pickConsentScenario([scenario('everything'), scenario('all-required')])?.id).toBe('everything');
  });

  it('is nothing for a catalog with no consent scenario', () => {
    expect(pickConsentScenario([])).toBeNull();
  });
});

describe('consentStepFor', () => {
  const sample = scenario('typical', { clientName: 'Danceliker', logoUri: 'https://sample.example/logo.svg', scopes: [{ scope: 'openid' }] });

  it('puts the client in place of the catalog\'s sample app and keeps the rest of the scenario', () => {
    const step = consentStepFor(sample, { en: 'Acme App', ru: 'Акме' }, 'https://acme.example/logo.svg', 'en');
    expect(step).toMatchObject({
      type: 'consent',
      clientName: 'Acme App',
      clientNames: { en: 'Acme App', ru: 'Акме' },
      logoUri: 'https://acme.example/logo.svg',
      scopes: [{ scope: 'openid' }],
    });
  });

  it('names the app in the default locale, else English, else the first name it has', () => {
    expect(consentStepFor(sample, { en: 'Acme', ru: 'Акме' }, '', 'ru').clientName).toBe('Акме');
    expect(consentStepFor(sample, { en: 'Acme', ru: 'Акме' }, '', 'kk').clientName).toBe('Acme');
    expect(consentStepFor(sample, { ru: 'Акме' }, '', 'kk').clientName).toBe('Акме');
  });

  it('drops blank names, and the sample app\'s name and logo when the client has none yet', () => {
    const step = consentStepFor(sample, { en: '   ' }, '', 'en');
    expect(step).not.toHaveProperty('clientName');
    expect(step).not.toHaveProperty('clientNames');
    expect(step).not.toHaveProperty('logoUri');
  });

  it('passes only a web address on as a logo, so half-typed text never reaches the frame', () => {
    expect(consentStepFor(sample, { en: 'Acme' }, 'https://acme.exa', 'en').logoUri).toBe('https://acme.exa');
    expect(consentStepFor(sample, { en: 'Acme' }, 'javascript:alert(1)', 'en')).not.toHaveProperty('logoUri');
    expect(consentStepFor(sample, { en: 'Acme' }, 'acme', 'en')).not.toHaveProperty('logoUri');
  });
});
