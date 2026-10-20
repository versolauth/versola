import type { FormPreview, Locale } from '../types';

export type Localizations = Record<string, Record<string, string>>;

/** The groups the Texts tab sorts keys into, in the order they are shown. */
export const KEY_GROUPS = ['Page', 'Fields', 'Buttons', 'Errors'] as const;
export type KeyGroup = (typeof KEY_GROUPS)[number];

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

/** A key's place in the Texts tab, read from its name: the forms' keys are flat (`email_placeholder`, `login_failed`). */
export function groupOfKey(key: string): KeyGroup {
  const name = key.toLowerCase();
  if (/(error|failed|invalid|required|unavailable|expired|denied|not_allowed|not-allowed|unknown|no_results|wrong|rate)/.test(name)) return 'Errors';
  if (/(button|^continue$|^close$|submit|allow|deny|cancel|resend|logout|sign_out|_link$|verify)/.test(name)) return 'Buttons';
  if (/(placeholder|label|_tab$|hint|country|search|show_|hide_|^email|^phone|^login|^password)/.test(name)) return 'Fields';
  return 'Page';
}

export function groupKeys(keys: string[]): Array<{ group: KeyGroup; keys: string[] }> {
  return KEY_GROUPS.map(group => ({ group, keys: keys.filter(key => groupOfKey(key) === group) })).filter(entry => entry.keys.length > 0);
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
