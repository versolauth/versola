import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Every form ships its strings in <id>/<id>.i18n.json, one object per locale. A key present in one
 * locale and missing in another shows up to a user as a raw key (or the English fallback), and a
 * `{placeholder}` that is renamed in one language is never substituted.
 */
const formsDir = __dirname;
const forms = readdirSync(formsDir).filter(name => statSync(join(formsDir, name)).isDirectory()
  && statSync(join(formsDir, name, `${name}.i18n.json`), { throwIfNoEntry: false })?.isFile());

const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map(m => m[1]).sort();

describe('form translations', () => {
  it('finds the forms', () => {
    expect(forms.length).toBeGreaterThan(5);
  });

  for (const form of forms) {
    const locales: Record<string, Record<string, string>> = JSON.parse(readFileSync(join(formsDir, form, `${form}.i18n.json`), 'utf8'));
    const { en, ...others } = locales;

    it(`${form}: has English strings`, () => {
      expect(en).toBeTruthy();
    });

    for (const [locale, strings] of Object.entries(others)) {
      it(`${form}: ${locale} has exactly the keys English has`, () => {
        expect(Object.keys(strings).sort()).toEqual(Object.keys(en).sort());
      });

      it(`${form}: ${locale} keeps the {placeholders} of English`, () => {
        const mismatched = Object.keys(en)
          .filter(key => key in strings && JSON.stringify(placeholders(en[key])) !== JSON.stringify(placeholders(strings[key])));
        expect(mismatched).toEqual([]);
      });
    }
  }
});
