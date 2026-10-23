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

const quote = (name: string) => `"${name}"`;

/** A language in English, from its code (`ru` is Russian), or the code itself when it is not one the browser knows. */
export function languageName(code: string): string {
  try {
    return new Intl.DisplayNames(['en'], { type: 'language' }).of(code) ?? code;
  } catch {
    return code;
  }
}

/** Sample values for the `{placeholders}` a preview fills in; any other `{x}` shows as `x`. */
const SAMPLE_VALUES: Record<string, string> = {
  name: 'Dana',
  minutes: '5',
  seconds: '30',
  destination: 'dana@example.com',
  email: 'dana@example.com',
  phone: '+1 555 0100',
  code: '123456',
};

/** The text with every `{placeholder}` replaced by a sample value, for the live preview. */
export function withSampleValues(text: string): string {
  return text.replace(/\{([A-Za-z0-9_]+)\}/g, (_, name: string) => SAMPLE_VALUES[name] ?? name);
}

/** Every language's texts with the placeholders filled in. */
export function sampleTexts(texts: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(texts).map(([key, text]) => [key, withSampleValues(text)]));
}

export interface LocalizationRules {
  /** The language codes a form may carry: the active locales. */
  locales: string[];
  /** The language the others are held to. */
  reference: string;
  /** A built-in form's own keys, which every language (the reference too) must have exactly; `null` for a custom
   *  form, whose keys are whatever its reference language has. */
  builtInKeys: string[] | null;
  /** Language names by code, for the messages; the English name of the code (`languageName`) without one. */
  names?: Record<string, string>;
}

export interface LocalizationProblem {
  /** One sentence in plain words. */
  message: string;
  /** Where in the typed text it points (the start of the key's line); absent when it points nowhere in particular. */
  offset?: number;
}

export interface LocalizationCheck {
  /** The texts when the text has the shape of language, key, text (even if the rules below fault it); else `null`. */
  parsed: Localizations | null;
  /** What is wrong; empty when the texts may be saved. */
  problems: LocalizationProblem[];
  /** The messages of `problems`. */
  errors: string[];
}

/**
 * Where each language (`code`) and each key of it (`code` + NUL + `key`) starts in the typed JSON, as the offset of the
 * start of its line. A scan, not a parse: it works on text that is not valid JSON up to the fault.
 */
export function keyOffsets(text: string): Map<string, number> {
  const offsets = new Map<string, number>();
  let depth = 0;
  let locale = '';
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === '{' || ch === '[') depth++;
    else if (ch === '}' || ch === ']') depth--;
    else if (ch === '"') {
      let end = i + 1;
      let value = '';
      while (end < text.length && text[end] !== '"') {
        if (text[end] === '\\') { value += text[end + 1] ?? ''; end += 2; } else value += text[end++];
      }
      let next = end + 1;
      while (next < text.length && /\s/.test(text[next])) next++;
      if (text[next] === ':' && (depth === 1 || depth === 2)) {
        const lineStart = text.lastIndexOf('\n', i) + 1;
        if (depth === 1) { locale = value; if (!offsets.has(value)) offsets.set(value, lineStart); }
        else if (!offsets.has(`${locale}\0${value}`)) offsets.set(`${locale}\0${value}`, lineStart);
      }
      i = end;
    }
  }
  return offsets;
}

/**
 * Checks the one JSON object a form's texts are edited as, `{ "en": { "<key>": "..." }, "ru": { ... } }` (the shape of
 * `forms/<name>/<name>.i18n.json`): valid JSON; its top-level keys are active languages; every language has the
 * reference language's keys (a built-in form's own keys), no more and no fewer; and a key keeps its `{placeholders}`
 * in every language. Central repeats the last two on save (FormLocalizations), without the language and built-in rules.
 */
export function checkLocalizations(text: string, rules: LocalizationRules): LocalizationCheck {
  const result = (parsed: Localizations | null, problems: LocalizationProblem[]): LocalizationCheck =>
    ({ parsed, problems, errors: problems.map(problem => problem.message) });
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch (error) {
    const reason = error instanceof Error ? error.message : 'the text cannot be read';
    const position = /position (\d+)/.exec(reason);
    const offset = position ? text.lastIndexOf('\n', Number(position[1]) - 1) + 1 : undefined;
    return result(null, [{ message: `Not valid JSON: ${reason}.`, offset }]);
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return result(null, [{ message: 'The top level must be an object of language codes, like { "en": { "title": "Sign in" } }.', offset: 0 }]);
  }
  const offsets = keyOffsets(text);
  const nameOf = (code: string) => rules.names?.[code] ?? languageName(code);
  const shape: LocalizationProblem[] = [];
  const parsed: Localizations = {};
  for (const [code, texts] of Object.entries(value)) {
    if (typeof texts !== 'object' || texts === null || Array.isArray(texts)) {
      shape.push({ message: `${quote(code)} must be an object of key and text pairs.`, offset: offsets.get(code) });
      continue;
    }
    const own: Record<string, string> = {};
    for (const [key, line] of Object.entries(texts)) {
      if (typeof line === 'string') own[key] = line;
      else shape.push({ message: `The ${nameOf(code)} text of ${quote(key)} must be a text.`, offset: offsets.get(`${code}\0${key}`) });
    }
    parsed[code] = own;
  }
  if (shape.length > 0) return result(null, shape);

  const problems: LocalizationProblem[] = [];
  const unknown = Object.keys(parsed).filter(code => !rules.locales.includes(code));
  for (const code of unknown) problems.push({ message: `${quote(code)} is not an active language (${rules.locales.join(', ')}). Activate it under Locales first, or remove it.`, offset: offsets.get(code) });
  if (!(rules.reference in parsed)) {
    problems.push({ message: `The reference language ${nameOf(rules.reference)} is missing: every other language is held to its keys.`, offset: 0 });
    return result(parsed, problems);
  }

  const expected = rules.builtInKeys ?? Object.keys(parsed[rules.reference]);
  const wanted = new Set(expected);
  const holder = rules.builtInKeys ? 'the form does' : `${nameOf(rules.reference)} does`;
  for (const code of Object.keys(parsed).filter(code => !unknown.includes(code))) {
    if (code === rules.reference && !rules.builtInKeys) continue;
    const have = new Set(Object.keys(parsed[code]));
    for (const key of expected.filter(key => !have.has(key))) {
      problems.push({ message: `${nameOf(code)} is missing ${quote(key)}`, offset: offsets.get(code) });
    }
    for (const key of [...have].filter(key => !wanted.has(key))) {
      problems.push({ message: `${nameOf(code)} has ${quote(key)}, which ${holder} not`, offset: offsets.get(`${code}\0${key}`) });
    }
  }
  const reference = parsed[rules.reference];
  const list = (names: string[]) => names.map(name => `{${name}}`).join(', ');
  for (const code of Object.keys(parsed).filter(code => code !== rules.reference && !unknown.includes(code))) {
    for (const key of Object.keys(parsed[code]).filter(key => key in reference)) {
      const want = placeholdersOf(reference[key]);
      const have = placeholdersOf(parsed[code][key]);
      const lacks = want.filter(name => !have.includes(name));
      const extra = have.filter(name => !want.includes(name));
      const offset = offsets.get(`${code}\0${key}`);
      if (lacks.length > 0) problems.push({ message: `The ${nameOf(code)} text of ${quote(key)} has no ${list(lacks)}, the ${nameOf(rules.reference)} one does`, offset });
      if (extra.length > 0) problems.push({ message: `The ${nameOf(code)} text of ${quote(key)} has ${list(extra)}, the ${nameOf(rules.reference)} one does not`, offset });
    }
  }
  return result(parsed, problems);
}

/** The texts as the editor shows them. */
export function formatLocalizations(localizations: Localizations): string {
  return JSON.stringify(localizations, null, 2);
}
