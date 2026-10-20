import { readFileSync } from 'node:fs';
import type { MockConfigState } from './mocks';

/**
 * The shop's forms and themes, shared by the Forms and Themes specs, the axe scan and the docs
 * screenshots (example.com data). Pure data and node's own modules: the docs manifest imports this
 * file directly (type stripping) and passes in the compiled form bundles it builds itself.
 *
 * Forms: five built-in ones (credential has a v8 stored but not active and gaps in ru and kk;
 * set-password lacks kk) and two custom ones. Themes: the default, a stylesheet one and three
 * visual ones; the clients name them, so the list can say who uses what.
 */
export const ftTenant = 'tenant-alpha';
export const ftPath = (view: 'forms' | 'themes', rest = '') => `/?view=${view}&tenant=${ftTenant}${rest}`;

export const ftLocales = [
  { code: 'en', name: 'English', isDefault: true, active: true },
  { code: 'ru', name: 'Русский', isDefault: false, active: true },
  { code: 'kk', name: 'Қазақша', isDefault: false, active: true },
];

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const i18n = (id: string): Record<string, Record<string, string>> => JSON.parse(read(`../forms/${id}/${id}.i18n.json`));

export const goldenPreviews = JSON.parse(read('../../central/src/test/resources/form-previews.golden.json')) as {
  previews: Array<{ formId: string; scenarios: Array<{ id: string; title: Record<string, string>; step: Record<string, unknown>; error?: string }> }>;
};

/** Kazakh stands in as a copy of the Russian text: the specs care that it exists, not what it says. */
function withKazakh(texts: Record<string, Record<string, string>>, leave: string[] = []): Record<string, Record<string, string>> {
  const kk = Object.fromEntries(Object.entries(texts.ru ?? {}).filter(([key]) => !leave.includes(key)));
  return { ...texts, kk };
}

function without(texts: Record<string, string>, keys: string[]): Record<string, string> {
  return Object.fromEntries(Object.entries(texts).filter(([key]) => !keys.includes(key)));
}

const builtIn = (id: string, bundles: Record<string, string>, version: number, active: boolean, localizations: Record<string, Record<string, string>>) => ({
  id,
  version,
  active,
  style: read(`../forms/${id}/${id}.css`),
  jsSource: null,
  jsCompiled: bundles[id] ?? `document.getElementById("versola-form-root").textContent = "${id}";`,
  localizations,
  properties: [] as never[],
});

/** A custom form's script: draws its texts, so the preview and the key marks have something to show. */
export const customScript = `(function(){var c=window.__VERSOLA_FORM__;var t=c.t||{};var s=c.step||{};
var h='<div class="container"><h1>'+(t.title||'')+'</h1><p>'+(t.intro||'')+'</p>';
if(s.showPhone){h+='<label>'+(t.phone||'')+'</label><input placeholder="'+(t.phone_placeholder||'')+'">';}
h+='<button>'+(t.submit||'')+'</button></div>';document.getElementById('versola-form-root').innerHTML=h;})();`;

export function ftForms(bundles: Record<string, string> = {}) {
  const credential = i18n('credential');
  const credentialRu = without(credential.ru, ['passkey_button', 'phone_hint']);
  const credentialTexts = { ...credential, ru: credentialRu, kk: without(withKazakh({ ...credential, ru: credentialRu }).kk, []) };
  const customProperties = [
    { type: 'BooleanProperty' as const, name: 'showPhone' },
    { type: 'StringArrayProperty' as const, name: 'channel', allowedValues: ['sms', 'email'] },
  ];
  return [
    builtIn('credential', bundles, 6, false, { ...credentialTexts, ru: { ...credentialRu, title: 'Вход (старый)' } }),
    builtIn('credential', bundles, 7, true, credentialTexts),
    builtIn('credential', bundles, 8, false, { ...credentialTexts, en: { ...credential.en, title: 'Sign in to Example Shop' } }),
    builtIn('password', bundles, 4, true, withKazakh(i18n('password'))),
    builtIn('otp', bundles, 5, true, withKazakh(i18n('otp'))),
    builtIn('consent', bundles, 6, true, withKazakh(i18n('consent'))),
    builtIn('set-password', bundles, 2, true, withKazakh(i18n('set-password'), ['title'])),
    {
      id: 'support-contact', version: 3, active: true, style: '.container { padding: 1.5rem; }', jsSource: 'export const Form = () => null;', jsCompiled: customScript,
      localizations: {
        en: { title: 'Contact support', intro: 'Tell us what went wrong before you sign in.', phone: 'Phone', phone_placeholder: '+7 700 000 00 00', submit: 'Send' },
        ru: { title: 'Связаться с поддержкой', intro: 'Расскажите, что пошло не так.', phone: 'Телефон', phone_placeholder: '+7 700 000 00 00', submit: 'Отправить' },
        kk: { title: 'Қолдау', intro: 'Не болғанын айтыңыз.', phone: 'Телефон', phone_placeholder: '+7 700 000 00 00', submit: 'Жіберу' },
      },
      properties: customProperties,
    },
    {
      id: 'kyc-upload', version: 1, active: true, style: '', jsSource: null, jsCompiled: customScript,
      localizations: { en: { title: 'Verify your identity', intro: 'Upload a photo of your document.', submit: 'Upload' } },
      properties: [{ type: 'BooleanProperty' as const, name: 'showPhone' }],
    },
  ];
}

export const ftPresets = [
  { id: 'versola', title: { en: 'Versola' }, tokens: { brand: '#155e75', shape: 'soft', surface: 'bold', mode: 'auto', background: 'glow' } },
  { id: 'studio', title: { en: 'Studio' }, tokens: { brand: '#ff6b4a', shape: 'round', surface: 'bold', mode: 'auto', background: 'glow' } },
  { id: 'fintech', title: { en: 'Fintech' }, tokens: { brand: '#5b5bd6', shape: 'soft', surface: 'neutral', mode: 'auto', background: 'linear' } },
];

export const ftThemes = [
  { id: 'default', css: '', tenantId: null, tokens: { base: 'versola' } },
  { id: 'shop-brand', css: '', tenantId: ftTenant, tokens: { base: 'versola', overrides: { brand: '#e5484d', surface: 'soft', background: 'horizon' } } },
  { id: 'midnight', css: '', tenantId: null, tokens: { base: 'fintech', overrides: { mode: 'dark', surface: 'bold' } } },
  { id: 'partner-green', css: '', tenantId: ftTenant, tokens: { base: 'versola', overrides: { brand: '#2f9e63' } } },
  { id: 'legacy-2024', css: 'body { font-family: Georgia, serif; }', tenantId: ftTenant },
];

const client = (id: string, name: string, theme: string) => ({
  id,
  clientName: { en: name },
  redirectUris: [`https://${id}.example.com/callback`],
  scope: ['openid'],
  permissions: [],
  secretRotation: false,
  theme,
});

export const ftClients = [
  client('storefront-web', 'Storefront Web', 'shop-brand'),
  client('shop-mobile', 'Shop mobile', 'shop-brand'),
  client('admin-console', 'Admin console', 'default'),
  client('partner-portal', 'Partner portal', 'partner-green'),
];

/** Everything both screens load. `bundles` are the compiled built-in forms, when the preview should draw the real ones. */
export function ftState(bundles: Record<string, string> = {}): Partial<MockConfigState> {
  return {
    forms: ftForms(bundles),
    formPreviews: goldenPreviews.previews,
    locales: ftLocales,
    formLocales: ftLocales.map(({ code, name }) => ({ code, name })),
    themePresets: ftPresets,
    themes: ftThemes,
    clients: { [ftTenant]: ftClients as MockConfigState['clients'][string] },
  };
}
