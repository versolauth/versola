import type { FormPreviewScenario } from '../types';

/** The one scenario of the preview catalog this preview shows; the first consent scenario stands in if a central lacks it. */
export const CONSENT_PREVIEW_SCENARIO = 'typical';

/** The consent scenario to show: the typical one, else the first the catalog lists. */
export function pickConsentScenario(scenarios: FormPreviewScenario[]): FormPreviewScenario | null {
  return scenarios.find(s => s.id === CONSENT_PREVIEW_SCENARIO) ?? scenarios[0] ?? null;
}

/** The step the consent form gets for this client: the catalog's scenario, with what auth would send for the client
 *  itself (`clientName`, `clientNames`, `logoUri`; see ConversationRenderService) in place of the catalog's sample app. */
export function consentStepFor(
  scenario: FormPreviewScenario,
  clientName: Record<string, string> | undefined,
  logoUri: string | undefined,
  defaultLocale: string,
): Record<string, unknown> {
  const names = Object.fromEntries(Object.entries(clientName ?? {}).map(([code, name]) => [code, name.trim()]).filter(([, name]) => name));
  const step: Record<string, unknown> = { ...scenario.step };
  delete step.logoUri;
  const picked = names[defaultLocale] ?? names.en ?? Object.values(names)[0];
  if (picked !== undefined) {
    step.clientName = picked;
    step.clientNames = names;
  } else {
    // A name not typed yet: the form words it as "An application", not as the catalog's sample app.
    delete step.clientName;
    delete step.clientNames;
  }
  // Only a web address reaches the frame as an image source; half-typed text does not.
  if (logoUri && /^https?:\/\/\S+$/i.test(logoUri.trim())) step.logoUri = logoUri.trim();
  return step;
}
