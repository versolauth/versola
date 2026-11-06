import type { BackendProperty, FormPreviewScenario, Locale, ThemeRecord } from '../types';
import { renderTheme } from './central-api';

// Builds the `step` object injected into a form preview generically from the
// form's `properties`. Each property name becomes a step field, matching the
// runtime StepView field names the form component reads. The form `id` is used
// as the step `type`. Fields a form needs but does not expose as a property
// (e.g. numeric otp length) are defaulted inside the form component itself, so
// no per-form code is required here.
export function buildPreviewStep(
  type: string,
  properties: BackendProperty[],
  getValue: (prop: BackendProperty) => string | boolean | number | string[],
): Record<string, unknown> {
  const step: Record<string, unknown> = { type };
  for (const prop of properties) step[prop.name] = getValue(prop);
  return step;
}

export interface PreviewSrcdocParams {
  formId: string;
  properties: BackendProperty[];
  getValue: (prop: BackendProperty) => string | boolean | number | string[];
  /** A scenario listed by central for this form: its step and error are used as they are, and the
   *  form's backend properties are not consulted. */
  scenario?: FormPreviewScenario;
  localizations: Record<string, Record<string, string>>;
  locale: string;
  locales: string[];
  /** The language names configured under Locales, shown in the form's language switcher instead of bare codes. */
  localeNames?: Record<string, string>;
  themeCss: string;
  style: string;
  jsCompiled: string;
  previewId?: string;
}

/** The language switcher's names, the way the server sends them: each configured code with its name. */
export function localeNamesOf(locales: Locale[]): Record<string, string> {
  return Object.fromEntries(locales.filter(l => l.name).map(l => [l.code, l.name]));
}

/** A scenario's name in the given language, else English, else its id. */
export function scenarioTitle(scenario: FormPreviewScenario, locale = 'en'): string {
  return scenario.title[locale] ?? scenario.title.en ?? scenario.id;
}

/** The HTML parser ends a `<script>` block at the first `</script` and a `<style>` block at the first `</style`, whatever
 *  the JSON, JavaScript or CSS around it says: a text or a stylesheet an operator typed could otherwise end the block and
 *  continue as markup of its own. `\u003c` is `<` to JSON and nothing to HTML; `\3c ` is the CSS escape of `<`, honoured in
 *  strings and identifiers, the only places `<` can stand in a stylesheet; `<\/` is `</` to JavaScript. Mirrors
 *  `escapeJsonForScript` and `escapeCssForStyle` in util, which protect the pages auth serves. */
export const escapeJsonForScript = (json: string): string => json.replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/&/g, '\\u0026');
export const escapeCssForStyle = (css: string): string => css.replace(/</g, '\\3c ');
export const escapeScriptForScript = (js: string): string => js.replace(/<\/(script)/gi, '<\\/$1');

/** The `sandbox` of every preview iframe. `allow-scripts` runs the form; `allow-same-origin` is deliberately absent, so the
 *  document gets an opaque origin: a script in a form or a theme runs with no access to the console's cookies, storage or
 *  API, however it got there. The form talks to the console with `postMessage` only, which needs no origin. */
export const PREVIEW_SANDBOX = 'allow-scripts';

// Builds the full iframe `srcdoc` HTML for a form preview. Shared by the form
// edit screen and the forms list version cards so both render previews
// identically.
export function buildPreviewSrcdoc(params: PreviewSrcdocParams): string {
  const configJson = JSON.stringify({
    step: params.scenario?.step ?? buildPreviewStep(params.formId, params.properties, params.getValue),
    error: params.scenario?.error ?? undefined,
    t: params.localizations[params.locale] ?? {},
    locale: params.locale,
    locales: params.locales,
    localeNames: params.localeNames,
    allT: params.localizations,
    previewId: params.previewId,
    // Every preview, whichever screen shows it (previewId is set by the forms list only).
    preview: true,
  });
  const css = escapeCssForStyle(`${params.themeCss}\n${params.style}`);
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><style>${css}</style><script>window.__VERSOLA_FORM__=${escapeJsonForScript(configJson)};</script></head><body><div id="versola-form-root"></div><script>${escapeScriptForScript(params.jsCompiled)}</script></body></html>`;
}

/** The stylesheet each theme gives a page, for previews. A theme stored with CSS only is its own CSS; any other (the
 *  built-in `default` among them) is composed by central on the base stylesheet, which the console does not have, so
 *  it is asked for. A theme central cannot render keeps its own CSS rather than failing the preview. */
export async function previewStylesheets(themes: ThemeRecord[]): Promise<Record<string, string>> {
  const entries = await Promise.all(themes.map(async (record): Promise<[string, string]> => {
    if (!record.tokens) return [record.id, record.css];
    try {
      return [record.id, (await renderTheme(record.tokens, record.css)).css];
    } catch {
      return [record.id, record.css];
    }
  }));
  return Object.fromEntries(entries);
}
