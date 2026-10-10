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

/** The border color an invalid field takes, i.e. --danger in the default
  * (light) theme. Shared so a palette change is a one-line edit rather than a
  * hunt through every form's validation test. */
export const INVALID_FIELD_BORDER = 'rgb(220, 38, 38)';

/** --accent in the default (light) theme — see INVALID_FIELD_BORDER. */
export const ACCENT_COLOR = 'rgb(21, 94, 117)';

export function tenantSelectorButton(page: Page) {
  return page.getByRole('button', { name: 'Select tenant' });
}

export async function openTenantDropdown(page: Page) {
  await tenantSelectorButton(page).click();
  await expect(page.getByLabel('Search tenants')).toBeVisible();
}

/** The latest request with this method and path. A request is logged when the mocked API receives
  * it, which is after the click that causes it has returned, so this waits (up to the expect
  * timeout) instead of checking once; a one-shot check passes on a fast machine and fails on a
  * slow one. */
export async function findRequest(requests: RequestLog[], method: string, pathname: string): Promise<RequestLog> {
  const find = () => [...requests].reverse().find(request => request.method === method && request.pathname === pathname);
  await expect.poll(() => find() !== undefined, { message: `Expected request ${method} ${pathname}` }).toBe(true);
  return find()!;
}
