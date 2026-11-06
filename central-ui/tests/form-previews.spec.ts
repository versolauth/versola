import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import type { FormPreview } from '../src/types';
import { renderForm } from './form-harness';

/**
 * The console previews the built-in forms from the states central lists (FormPreviews.scala). central
 * and the forms live apart, so a step the forms cannot show would only surface in the console. This
 * renders every listed state through the real form, the way the console's preview does, from the
 * golden file central's own test keeps equal to its list.
 */
const golden = JSON.parse(
  readFileSync(new URL('../../central/src/test/resources/form-previews.golden.json', import.meta.url), 'utf8'),
) as { previews: FormPreview[] };

// `auth-settings`, the account page, has no step to preview and is not listed.
const noInlineMessage = new Set(['passkey_failed']);

for (const form of golden.previews) {
  for (const scenario of form.scenarios) {
    test(`${form.formId}: ${scenario.id} renders`, async ({ page }) => {
      const problems: string[] = [];
      page.on('pageerror', error => problems.push(error.message));
      page.on('console', message => message.type() === 'error' && problems.push(message.text()));

      const { t } = await renderForm(page, form.formId, { step: scenario.step, ...(scenario.error ? { error: scenario.error } : {}) });

      if (scenario.error && !noInlineMessage.has(scenario.error)) {
        expect(t[scenario.error], `the form has no text for the error key ${scenario.error}`).toBeTruthy();
        await expect(page.getByText(t[scenario.error]).first()).toBeVisible();
      }
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(results.violations.map(v => `${v.id}: ${v.help}`)).toEqual([]);
      expect(problems).toEqual([]);
    });
  }
}

test('lists a state for every built-in form the console can preview', () => {
  expect(golden.previews.map(p => p.formId).sort()).toEqual(
    ['access-denied', 'confirm-logout', 'consent', 'conversation-expired', 'credential', 'otp', 'passkey-enroll', 'password', 'service-unavailable', 'set-password', 'signed-out'],
  );
});
