import type { MockConfigState } from './mocks';

/** A stand-in for the consent form's compiled bundle: it writes what the frame was given, so a test can read it back. */
const stubBundle = `
  var step = window.__VERSOLA_FORM__.step;
  var root = document.getElementById('versola-form-root');
  var title = document.createElement('h1');
  title.id = 'consent-client';
  title.textContent = step.clientName || '(no name)';
  root.appendChild(title);
  var scenario = document.createElement('p');
  scenario.id = 'consent-scenario';
  scenario.textContent = step.marker || '';
  root.appendChild(scenario);
  if (step.logoUri) {
    var logo = document.createElement('img');
    logo.id = 'consent-logo';
    logo.alt = '';
    logo.src = step.logoUri;
    root.appendChild(logo);
  }
`;

export const consentForm = {
  id: 'consent',
  version: 3,
  active: true,
  style: '.container { color: red; }',
  jsSource: 'export {};',
  jsCompiled: stubBundle,
  localizations: { en: { title: 'Allow access' } },
  properties: [],
};

/** The typical scenario is not the first one listed: the preview has to pick it by id. */
export const consentPreviews = {
  formId: 'consent',
  scenarios: [
    { id: 'everything', title: { en: 'Everything requested' }, step: { type: 'consent', clientName: 'Danceliker', marker: 'everything', scopes: [] } },
    { id: 'typical', title: { en: 'Typical request' }, step: { type: 'consent', clientName: 'Danceliker', marker: 'typical', scopes: [] } },
  ],
};

export const previewPresets = [
  { id: 'versola', title: { en: 'Versola' }, tokens: { brand: '#155e75', shape: 'soft', surface: 'bold', mode: 'auto', background: 'glow' } },
  { id: 'fintech', title: { en: 'Fintech' }, tokens: { brand: '#5b5bd6', shape: 'soft', surface: 'neutral', mode: 'auto', background: 'linear' } },
];

export const previewThemes = [
  { id: 'default', css: '', tenantId: null, tokens: { base: 'versola' } },
  { id: 'money', css: 'MONEY-CSS', tenantId: null, tokens: { base: 'fintech', overrides: { shape: 'sharp' } } },
  { id: 'legacy', css: 'LEGACY-CSS', tenantId: null },
];

/** A central that can draw the consent screen: its form, the catalog's scenarios, and the themes. */
export const consentPreviewState: Partial<MockConfigState> = {
  forms: [consentForm],
  formPreviews: [consentPreviews],
  locales: [{ code: 'en', name: 'English', active: true, isDefault: true }],
  themes: previewThemes,
  themePresets: previewPresets,
};
