import { expect, type Page } from '@playwright/test';
import { setupPartnerApiMocks, type MockPartnerHarness, type MockPartnerState } from '../../src/partner/mock-backend';
import { partnerSeedState } from './partner-fixtures';

/** Loads the partner console (`/partner.html`) against the mocked partner API, seeded with Pilot Pay unless `state` says otherwise. */
export async function loadPartnerApp(
  page: Page,
  options: { path?: string; state?: Partial<MockPartnerState> } = {},
): Promise<MockPartnerHarness> {
  const harness = await setupPartnerApiMocks(page, options.state ?? {}, partnerSeedState);
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
