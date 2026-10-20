import { describe, expect, it } from 'vitest';
import { checkLocalizations, effectiveTexts, formatLocalizations, isBuiltInForm, localeGaps, localeSummary, missingKeys, namesWithOverflow, placeholdersOf, textKeys } from './form-texts';

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

  it('reads the placeholders of a text, sorted and without repeats', () => {
    expect(placeholdersOf('Code for {destination} in {seconds}s, {destination}')).toEqual(['destination', 'seconds']);
    expect(placeholdersOf('No placeholders, { spaced } or {a-b}')).toEqual([]);
  });
});

describe('checking the texts as one JSON object', () => {
  const rules = { locales: ['en', 'ru', 'kk'], reference: 'en', builtInKeys: null };
  const en = { title: 'Sign in', hint: 'Code for {destination} in {seconds}s' };
  const ru = { title: 'Вход', hint: 'Код для {destination} через {seconds}с' };
  const check = (value: unknown, over = {}) => checkLocalizations(typeof value === 'string' ? value : JSON.stringify(value), { ...rules, ...over });

  it('accepts languages with the same keys and placeholders, and formats them as the i18n files are', () => {
    const result = check({ en, ru });
    expect(result.errors).toEqual([]);
    expect(result.parsed).toEqual({ en, ru });
    expect(JSON.parse(formatLocalizations({ en, ru }))).toEqual({ en, ru });
    expect(formatLocalizations({ en })).toContain('\n  "en": {');
  });

  it('accepts placeholders in another order, and a reference that has no other language yet', () => {
    expect(check({ en, ru: { ...ru, hint: '{seconds}с, {destination}' } }).errors).toEqual([]);
    expect(check({ en }).errors).toEqual([]);
  });

  it('refuses text that is not JSON, with the reason, and gives no texts', () => {
    const result = check('{ "en": { "title": ');
    expect(result.parsed).toBeNull();
    expect(result.errors).toHaveLength(1);
    expect(result.errors[0]).toMatch(/^Not valid JSON: /);
  });

  it('refuses a top level that is not an object, and a language that is not an object of texts', () => {
    expect(check('[]').errors[0]).toMatch(/top level must be an object/);
    expect(check('"en"').parsed).toBeNull();
    expect(check({ en: ['a'] }).errors).toEqual(['"en" must be an object of key and text pairs.']);
    expect(check({ en: { title: 3 } }).errors).toEqual(['"en" "title" must be a text.']);
  });

  it('refuses a language that is not active, naming the active ones', () => {
    const result = check({ en, fr: en });
    expect(result.errors).toEqual(['"fr" is not an active language (en, ru, kk). Activate it under Locales first, or remove it.']);
    expect(result.parsed).toEqual({ en, fr: en });
  });

  it('refuses a missing reference language, since the others have nothing to be held to', () => {
    expect(check({ ru }).errors[0]).toMatch(/reference language "en" is missing/);
  });

  it('names the keys a language lacks and the keys it adds', () => {
    expect(check({ en, ru: { title: 'Вход' } }).errors).toEqual(['"ru" is missing the key hint, which "en" has.']);
    expect(check({ en, ru: { ...ru, extra: 'x', more: 'y' } }).errors).toEqual(['"ru" has keys extra, more, which "en" lacks.']);
    const many = Object.fromEntries(['a', 'b', 'c', 'd', 'e', 'f'].map(key => [key, 'x']));
    expect(check({ en: many, ru: {} }).errors).toEqual(['"ru" is missing keys a, b, c, d +2, which "en" has.']);
  });

  it('names a key whose placeholders differ between languages', () => {
    expect(check({ en, ru: { ...ru, hint: 'Код для {address} через {seconds}с' } }).errors)
      .toEqual(['Key "hint": "en" uses {destination}, {seconds} but "ru" uses {address}, {seconds}.']);
    expect(check({ en, ru: { ...ru, title: 'Вход, {name}' } }).errors)
      .toEqual(['Key "title": "en" uses none but "ru" uses {name}.']);
  });

  it('holds a built-in form to its own keys in every language, the reference too', () => {
    const builtIn = { ...rules, builtInKeys: ['title', 'hint'] };
    expect(check({ en, ru }, builtIn).errors).toEqual([]);
    expect(check({ en: { title: 'x' }, ru }, builtIn).errors).toContain('"en" is missing the key hint, which the form has.');
    expect(check({ en: { ...en, invented: 'x' }, ru }, builtIn).errors).toContain('"en" has the key invented, which the form does not use.');
  });

  it('keeps counting blank texts as missing in the summary, though they are not an error', () => {
    expect(check({ en, ru: { ...ru, title: ' ' } }).errors).toEqual([]);
    expect(missingKeys({ en, ru: { ...ru, title: ' ' } }, 'ru')).toEqual(['title']);
  });
});
