import { describe, expect, it } from 'vitest';
import { effectiveTexts, groupKeys, groupOfKey, isBuiltInForm, localeGaps, localeSummary, missingKeys, namesWithOverflow, textKeys } from './form-texts';

const localizations = {
  en: { title: 'Sign in', continue: 'Continue', login_failed: 'Wrong login' },
  ru: { title: 'Вход', continue: '   ' },
};
const locale = (code: string, active = true) => ({ code, name: code, isDefault: code === 'en', active });

describe('form texts', () => {
  it('collects the keys of every language', () => {
    expect(textKeys({ en: { b: '1' }, ru: { a: '2' } })).toEqual(['a', 'b']);
  });

  it('counts an absent or blank text as missing', () => {
    expect(missingKeys(localizations, 'ru')).toEqual(['continue', 'login_failed']);
    expect(missingKeys(localizations, 'en')).toEqual([]);
    expect(missingKeys(localizations, 'kk')).toEqual(['continue', 'login_failed', 'title']);
  });

  it('lists only the languages that have gaps', () => {
    expect(Object.keys(localeGaps(localizations, ['en', 'ru', 'kk']))).toEqual(['ru', 'kk']);
  });

  it('shows the reference text for a key the language lacks', () => {
    expect(effectiveTexts(localizations, 'ru', 'en')).toEqual({ title: 'Вход', continue: 'Continue', login_failed: 'Wrong login' });
  });

  it('summarises the languages of a form over the active locales', () => {
    expect(localeSummary(localizations, [locale('en'), locale('ru'), locale('kk', false)])).toEqual({ complete: ['en'], missing: ['ru'] });
    expect(localeSummary(localizations, [locale('en', false), locale('ru', false)])).toEqual({ complete: ['en'], missing: ['ru'] });
  });

  it('puts a key in the group its name says', () => {
    expect(groupOfKey('login_failed')).toBe('Errors');
    expect(groupOfKey('passkey_button')).toBe('Buttons');
    expect(groupOfKey('email_placeholder')).toBe('Fields');
    expect(groupOfKey('title')).toBe('Page');
    expect(groupKeys(['title', 'login_failed', 'continue']).map(entry => entry.group)).toEqual(['Page', 'Buttons', 'Errors']);
  });

  it('treats a form with listed preview states as built in', () => {
    const previews = [{ formId: 'credential', scenarios: [{ id: 's', title: {}, step: {} }] }, { formId: 'custom', scenarios: [] }];
    expect(isBuiltInForm('credential', previews)).toBe(true);
    expect(isBuiltInForm('custom', previews)).toBe(false);
    expect(isBuiltInForm('other', previews)).toBe(false);
  });

  it('names the first two and marks the rest with +N', () => {
    expect(namesWithOverflow(['a', 'b'])).toBe('a, b');
    expect(namesWithOverflow(['a', 'b', 'c', 'd'])).toBe('a, b +2');
  });
});
