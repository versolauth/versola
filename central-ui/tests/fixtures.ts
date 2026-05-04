import { expect, type Page } from '@playwright/test';
import { setupConfigApiMocks, type MockConfigHarness, type MockConfigState, type RequestLog } from './mocks';

export async function loadAdminApp(
  page: Page,
  options: { path?: string; state?: Partial<MockConfigState> } = {},
): Promise<MockConfigHarness> {
  const harness = await setupConfigApiMocks(page, options.state);
  await page.goto(options.path ?? '/');
  await expect(page.locator('versola-admin')).toBeVisible();
  return harness;
}

export function tenantSelectorButton(page: Page) {
  return page.getByRole('button', { name: 'Select tenant' });
}

export async function openTenantDropdown(page: Page) {
  await tenantSelectorButton(page).click();
  await expect(page.getByLabel('Search tenants')).toBeVisible();
}

export function findRequest(requests: RequestLog[], method: string, pathname: string): RequestLog {
  const match = [...requests].reverse().find(request => request.method === method && request.pathname === pathname);
  expect(match, `Expected request ${method} ${pathname}`).toBeTruthy();
  return match!;
}