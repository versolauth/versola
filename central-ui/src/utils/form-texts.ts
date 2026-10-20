import type { FormPreview, Locale } from '../types';

export type Localizations = Record<string, Record<string, string>>;

const isBlank = (value: string | undefined) => (value ?? '').trim() === '';

/** Every key any language has a text for, sorted. */
export function textKeys(localizations: Localizations): string[] {
  const keys = new Set<string>();
  for (const texts of Object.values(localizations)) for (const key of Object.keys(texts ?? {})) keys.add(key);
  return [...keys].sort();
}

/** The keys a language has no text for (or only a blank one), among every key the form has. */
export function missingKeys(localizations: Localizations, locale: string): string[] {
  const texts = localizations[locale] ?? {};
  return textKeys(localizations).filter(key => isBlank(texts[key]));
}

/** The languages that have gaps, each with the keys it lacks. A language with none is left out. */
export function localeGaps(localizations: Localizations, locales: string[]): Record<string, string[]> {
  const gaps: Record<string, string[]> = {};
  for (const locale of locales) {
    const missing = missingKeys(localizations, locale);
    if (missing.length > 0) gaps[locale] = missing;
  }
  return gaps;
}

/** What a page shows in `locale`: its own texts, and the reference language's for every key it lacks. */
export function effectiveTexts(localizations: Localizations, locale: string, reference: string): Record<string, string> {
  const own = localizations[locale] ?? {};
  const out: Record<string, string> = { ...(localizations[reference] ?? {}) };
  for (const [key, value] of Object.entries(own)) if (!isBlank(value)) out[key] = value;
  return out;
}

/** The languages a form is complete in, and those missing texts: the list's Locales column. */
export function localeSummary(localizations: Localizations, locales: Locale[]): { complete: string[]; missing: string[] } {
  const active = locales.filter(locale => locale.active);
  const codes = (active.length > 0 ? active : locales).map(locale => locale.code);
  const gaps = localeGaps(localizations, codes);
  return { complete: codes.filter(code => !(code in gaps)), missing: codes.filter(code => code in gaps) };
}

/** Built in when central lists preview states for the form; a form it lists nothing for is the author's own. */
export function isBuiltInForm(formId: string, previews: FormPreview[]): boolean {
  return (previews.find(preview => preview.formId === formId)?.scenarios.length ?? 0) > 0;
}

/** The first `max` names and `+N` for the rest: a gap is a value, never a bare count. */
export function namesWithOverflow(names: string[], max = 2): string {
  const shown = names.slice(0, max).join(', ');
  return names.length > max ? `${shown} +${names.length - max}` : shown;
}

/** The `{name}` placeholders of a text, as a sorted list without repeats. */
export function placeholdersOf(text: string): string[] {
  return [...new Set([...text.matchAll(/\{([A-Za-z0-9_]+)\}/g)].map(match => match[1]))].sort();
}

const showNames = (names: string[]) => (names.length === 0 ? 'none' : names.map(name => `{${name}}`).join(', '));
const quote = (name: string) => `"${name}"`;

export interface LocalizationRules {
  /** The language codes a form may carry: the active locales. */
  locales: string[];
  /** The language the others are held to. */
  reference: string;
  /** A built-in form's own keys, which every language (the reference too) must have exactly; `null` for a custom
   *  form, whose keys are whatever its reference language has. */
  builtInKeys: string[] | null;
}

export interface LocalizationCheck {
  /** The texts when the text has the shape of language, key, text (even if the rules below fault it); else `null`. */
  parsed: Localizations | null;
  /** What is wrong, one sentence each; empty when the texts may be saved. */
  errors: string[];
}

/**
 * Checks the one JSON object a form's texts are edited as, `{ "en": { "<key>": "..." }, "ru": { ... } }` (the shape of
 * `forms/<name>/<name>.i18n.json`): valid JSON; its top-level keys are active languages; every language has the
 * reference language's keys (a built-in form's own keys), no more and no fewer; and a key keeps its `{placeholders}`
 * in every language. Central repeats the last two on save (FormLocalizations), without the language and built-in rules.
 */
export function checkLocalizations(text: string, rules: LocalizationRules): LocalizationCheck {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch (error) {
    return { parsed: null, errors: [`Not valid JSON: ${error instanceof Error ? error.message : 'the text cannot be read'}.`] };
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return { parsed: null, errors: ['The top level must be an object of language codes, like { "en": { "title": "Sign in" } }.'] };
  }
  const shape: string[] = [];
  const parsed: Localizations = {};
  for (const [code, texts] of Object.entries(value)) {
    if (typeof texts !== 'object' || texts === null || Array.isArray(texts)) {
      shape.push(`${quote(code)} must be an object of key and text pairs.`);
      continue;
    }
    const own: Record<string, string> = {};
    for (const [key, line] of Object.entries(texts)) {
      if (typeof line === 'string') own[key] = line;
      else shape.push(`${quote(code)} ${quote(key)} must be a text.`);
    }
    parsed[code] = own;
  }
  if (shape.length > 0) return { parsed: null, errors: shape };

  const errors: string[] = [];
  const unknown = Object.keys(parsed).filter(code => !rules.locales.includes(code));
  for (const code of unknown) errors.push(`${quote(code)} is not an active language (${rules.locales.join(', ')}). Activate it under Locales first, or remove it.`);
  if (!(rules.reference in parsed)) {
    errors.push(`The reference language ${quote(rules.reference)} is missing: every other language is held to its keys.`);
    return { parsed, errors };
  }

  const expected = rules.builtInKeys ?? Object.keys(parsed[rules.reference]);
  const wanted = new Set(expected);
  const subject = rules.builtInKeys ? 'the form' : quote(rules.reference);
  for (const code of Object.keys(parsed).filter(code => !unknown.includes(code))) {
    if (code === rules.reference && !rules.builtInKeys) continue;
    const have = new Set(Object.keys(parsed[code]));
    const missing = expected.filter(key => !have.has(key));
    const extra = [...have].filter(key => !wanted.has(key));
    if (missing.length > 0) errors.push(`${quote(code)} is missing ${missing.length === 1 ? 'the key' : 'keys'} ${namesWithOverflow(missing, 4)}, which ${subject} has.`);
    if (extra.length > 0) errors.push(`${quote(code)} has ${extra.length === 1 ? 'the key' : 'keys'} ${namesWithOverflow(extra, 4)}, which ${subject} ${rules.builtInKeys ? 'does not use' : 'lacks'}.`);
  }
  const reference = parsed[rules.reference];
  for (const code of Object.keys(parsed).filter(code => code !== rules.reference && !unknown.includes(code))) {
    for (const key of Object.keys(parsed[code]).filter(key => key in reference)) {
      const want = placeholdersOf(reference[key]);
      const have = placeholdersOf(parsed[code][key]);
      if (want.join() !== have.join()) {
        errors.push(`Key ${quote(key)}: ${quote(rules.reference)} uses ${showNames(want)} but ${quote(code)} uses ${showNames(have)}.`);
      }
    }
  }
  return { parsed, errors };
}

/** The texts as the editor shows them. */
export function formatLocalizations(localizations: Localizations): string {
  return JSON.stringify(localizations, null, 2);
}
