import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { rememberLocale } from './locale';

describe('rememberLocale', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('stores the locale in a long-lived, root-scoped, SameSite=Lax cookie', () => {
    const doc = { cookie: '' };
    vi.stubGlobal('document', doc);
    rememberLocale('ru');
    expect(doc.cookie).toBe('ui_locale=ru; Path=/; Max-Age=31536000; SameSite=Lax; Secure');
  });

  it('encodes the value so it cannot add cookie attributes', () => {
    const doc = { cookie: '' };
    vi.stubGlobal('document', doc);
    rememberLocale('ru; Domain=evil.test');
    expect(doc.cookie.startsWith('ui_locale=ru%3B%20Domain%3Devil.test; Path=/;')).toBe(true);
  });

  it('does not throw when cookies are unavailable', () => {
    vi.stubGlobal('document', { get cookie() { return ''; }, set cookie(_: string) { throw new Error('blocked'); } });
    expect(() => rememberLocale('ru')).not.toThrow();
  });
});

// A form that lets the user switch language but does not remember the choice loses it on the
// next page that has no conversation to read it from.
describe('forms with a language switcher', () => {
  const formsDir = __dirname;
  const forms = readdirSync(formsDir).filter(name => statSync(join(formsDir, name)).isDirectory()
    && statSync(join(formsDir, name, `${name}.tsx`), { throwIfNoEntry: false })?.isFile());

  for (const form of forms) {
    const source = readFileSync(join(formsDir, form, `${form}.tsx`), 'utf8');
    if (!/LocaleDropdown|aria-label="Language"/.test(source)) continue;
    it(`${form}: remembers the chosen locale`, () => {
      expect(source).toMatch(/import \{ rememberLocale \} from '\.\.\/locale'/);
      expect(source).toMatch(/rememberLocale\(/);
    });
  }
});
