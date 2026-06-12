import type { BackendProperty } from '../types';

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
  localizations: Record<string, Record<string, string>>;
  locale: string;
  locales: string[];
  themeCss: string;
  style: string;
  jsCompiled: string;
  previewId?: string;
}

// Builds the full iframe `srcdoc` HTML for a form preview. Shared by the form
// edit screen and the forms list version cards so both render previews
// identically.
export function buildPreviewSrcdoc(params: PreviewSrcdocParams): string {
  const configJson = JSON.stringify({
    step: buildPreviewStep(params.formId, params.properties, params.getValue),
    t: params.localizations[params.locale] ?? {},
    locale: params.locale,
    locales: params.locales,
    allT: params.localizations,
    previewId: params.previewId,
  });
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><style>${params.themeCss}\n${params.style}</style><script>window.__VERSOLA_FORM__=${configJson};</script></head><body><div id="versola-form-root"></div><script>${params.jsCompiled}</script></body></html>`;
}
