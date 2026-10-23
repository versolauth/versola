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
export const INVALID_FIELD_BORDER = 'rgb(192, 52, 29)';

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

/** A client registration or update: one endpoint per kind of client, so the path says which. */
export const CLIENT_WRITE_PATH = /^\/configuration\/clients\/(web|native|service)$/;

export async function findClientWrite(requests: RequestLog[], method: 'POST' | 'PUT'): Promise<RequestLog> {
  const find = () => [...requests].reverse().find(request => request.method === method && CLIENT_WRITE_PATH.test(request.pathname));
  await expect.poll(() => find() !== undefined, { message: `Expected a client ${method} to one of the kind endpoints` }).toBe(true);
  return find()!;
}

export function clientWrites(requests: RequestLog[], method: 'POST' | 'PUT'): RequestLog[] {
  return requests.filter(request => request.method === method && CLIENT_WRITE_PATH.test(request.pathname));
}

/** The side panel whose title is `heading`, as its host element: its slotted content (the record
  * cards, the editor) are DOM children of the host, not of the `role="dialog"` inside its shadow
  * root, so a locator chained from the host reaches them while one chained from the dialog
  * does not. `sidePanelDialog` is the dialog itself, for its ARIA state and its box. */
export function sidePanel(page: Page, heading: string) {
  const pattern = new RegExp(`^${heading.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`);
  return page.locator('versola-side-panel').filter({ has: page.locator('#panel-title', { hasText: pattern }) });
}

export function sidePanelDialog(page: Page, heading: string) {
  return sidePanel(page, heading).getByRole('dialog');
}

/** A sheet fills the viewport. The page's own scrollbar (15px where scrollbars are classic, as on
 *  the Linux CI browser) stays visible beside it, so the width is the viewport minus at most that. */
export async function expectSheetWidth(page: Page, width: number | undefined) {
  const viewport = await page.evaluate(() => window.innerWidth);
  expect(width).toBeGreaterThanOrEqual(viewport - 15);
  expect(width).toBeLessThanOrEqual(viewport);
}
