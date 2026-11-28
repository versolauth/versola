import { expect, test as base, type Page } from '@playwright/test';
import { setupPartnerApiMocks, type MockPartnerHarness, type MockPartnerState } from '../../src/partner/mock-backend';
import { partnerSeedState } from './partner-fixtures';
import { expectWritesWithinContract } from './write-contract';

const harnesses = new WeakMap<Page, MockPartnerHarness>();

/**
 * `test` of every partner-console spec: after each one, every write its page sent is checked against the partner contract
 * (`write-contract.ts`): only contract fields, and never a group, a group kind, an owner, a binding or a party.
 */
export const test = base.extend<{ writesWithinContract: void }>({
  writesWithinContract: [async ({ page }, use) => {
    await use();
    const harness = harnesses.get(page);
    if (harness) expectWritesWithinContract(harness.requests);
  }, { auto: true }],
});
export { expect };

/** Loads the partner console (`/partner.html`) against the mocked partner API, seeded with Pilot Pay unless `state` says otherwise. */
export async function loadPartnerApp(
  page: Page,
  options: { path?: string; state?: Partial<MockPartnerState> } = {},
): Promise<MockPartnerHarness> {
  const harness = await setupPartnerApiMocks(page, options.state ?? {}, partnerSeedState);
  harnesses.set(page, harness);
  await page.goto(options.path ?? '/partner.html?view=overview');
  await expect(page.locator('versola-partner-console')).toBeVisible();
  return harness;
}

/** The side panel whose title is `heading`, as its host element (its slotted content are DOM children of the host). */
export function sidePanel(page: Page, heading: string) {
  const pattern = new RegExp(`^${heading.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`);
  return page.locator('versola-side-panel').filter({ has: page.locator('#panel-title', { hasText: pattern }) });
}

export function sidePanelDialog(page: Page, heading: string) {
  return sidePanel(page, heading).getByRole('dialog');
}
