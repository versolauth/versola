import { expect, test } from '@playwright/test';
import { INVALID_FIELD_BORDER, expectSheetWidth, findRequest, loadAdminApp, sidePanel, sidePanelDialog } from './fixtures';

/**
 * Authorization detail types (RFC 9396) on the table + wide side panel: the JSON Schema is read
 * highlighted and edited in `versola-code-editor`, a schema central rejects shows its lines.
 */
const base = '/?view=authorization-details&tenant=tenant-alpha';
const locales = [
  { code: 'en', name: 'English', isDefault: true, active: true },
  { code: 'ru', name: 'Русский', isDefault: false, active: true },
];
const payment = {
  type: 'payment_initiation',
  description: { en: 'Payment initiation', ru: 'Инициация платежа' },
  schema: { type: 'object', properties: { type: { type: 'string' }, instructedAmount: { type: 'object' } }, required: ['type', 'instructedAmount'] },
};
const accounts = { type: 'account_information', description: { en: 'Account information' }, schema: { type: 'object', properties: { type: { type: 'string' } } } };
const readOnly = { resources: { central: { permissions: ['security:read'] } }, isProd: false };
const withTypes = (...types: typeof payment[]) => ({ authorizationDetailTypes: { 'tenant-alpha': types }, locales });

test('lists types with a schema summary and languages, and searches by type or description', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: withTypes(payment, accounts) });

  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Type', 'Schema', 'Languages', 'Actions']);
  const row = table.getByRole('row').filter({ hasText: 'payment_initiation' });
  await expect(row).toContainText('2 properties · required: type, instructedAmount');
  await expect(row).toContainText('2 of 2');
  await expect(table.getByRole('row').filter({ hasText: 'account_information' })).toContainText('Missing: ru');

  await page.getByLabel('Search types').fill('Account');
  await expect(table.getByRole('button', { name: /^Open type/ })).toHaveCount(1);
  await page.getByLabel('Search types').fill('nothing');
  await expect(page.getByRole('heading', { name: 'No types match' })).toBeVisible();
});

test('opens the wide panel from the row and shows description, schema and Fixed', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: withTypes(payment) });

  await page.locator('versola-data-table').getByRole('row').filter({ hasText: 'payment_initiation' }).getByRole('cell').nth(1).click();
  const panel = sidePanel(page, 'Payment initiation');
  await expect(panel.getByRole('dialog')).toBeVisible();
  await expect(page).toHaveURL(/id=payment_initiation/);
  await expect(panel.getByRole('region', { name: 'General' })).toContainText('Payment initiation');
  const schema = panel.getByRole('region', { name: 'JSON Schema' });
  await expect(schema).toContainText('instructedAmount');
  await expect(schema).toContainText('2 properties · required: type, instructedAmount');
  await expect(panel.getByRole('region', { name: 'Fixed' })).toContainText('Set at creation; clients send it as the type member');
  await expect(panel.getByRole('region', { name: 'Delete type' })).toBeVisible();
  const box = await panel.getByRole('dialog').boundingBox();
  expect(box?.width).toBeGreaterThan(600);

  await panel.getByRole('button', { name: 'Close panel' }).click();
  await expect(panel).toHaveCount(0);
  await expect(page).not.toHaveURL(/id=/);
});

test('Enter on the row opens it and Escape closes it, returning focus', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: withTypes(payment) });

  const row = page.getByRole('button', { name: /^Open type .*payment_initiation$/ });
  await row.focus();
  await page.keyboard.press('Enter');
  await expect(sidePanelDialog(page, 'Payment initiation')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(sidePanel(page, 'Payment initiation')).toHaveCount(0);
  await expect(row).toBeFocused();
});

test('edits the JSON Schema in the code editor and sends the parsed schema', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&id=payment_initiation`, state: withTypes(payment) });

  await sidePanel(page, 'Payment initiation').getByRole('button', { name: 'Edit json schema' }).click();
  const editor = sidePanel(page, 'Edit JSON Schema');
  await expect(page).toHaveURL(/edit=schema/);
  await expect(editor.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
  await editor.locator('versola-code-editor#detail-schema textarea').fill('{ "type": "object", "required": ["type"] }');
  await editor.getByRole('button', { name: 'Save JSON Schema' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/authorization-detail-types')).body).toEqual({
    tenantId: 'tenant-alpha',
    type: 'payment_initiation',
    description: { en: 'Payment initiation', ru: 'Инициация платежа' },
    schema: { type: 'object', required: ['type'] },
  });
  await expect(sidePanel(page, 'Payment initiation').getByRole('region', { name: 'JSON Schema' })).toContainText('0 properties · required: type');
});

test('refuses a schema that is not a JSON object before sending', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&id=payment_initiation&edit=schema`, state: withTypes(payment) });

  const editor = sidePanel(page, 'Edit JSON Schema');
  await editor.locator('versola-code-editor#detail-schema textarea').fill('{ "type": ');
  await editor.getByRole('button', { name: 'Save JSON Schema' }).click();
  await expect(editor.getByRole('alert')).toContainText('Invalid JSON');
  await editor.locator('versola-code-editor#detail-schema textarea').fill('[1]');
  await editor.getByRole('button', { name: 'Save JSON Schema' }).click();
  await expect(editor.getByRole('alert')).toContainText('The schema is a JSON object.');
  expect(api.requests.some(request => request.method === 'PUT')).toBe(false);
});

test('edits the description and sends the whole record', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&id=payment_initiation`, state: withTypes(payment) });

  await sidePanel(page, 'Payment initiation').getByRole('button', { name: 'Edit general' }).click();
  const editor = sidePanel(page, 'Edit general');
  await editor.locator('#detail-description').fill('Payments');
  await editor.getByRole('button', { name: 'Save general' }).click();

  expect((await findRequest(api.requests, 'PUT', '/configuration/authorization-detail-types')).body).toMatchObject({
    type: 'payment_initiation',
    description: { en: 'Payments', ru: 'Инициация платежа' },
    schema: payment.schema,
  });
  await expect(sidePanel(page, 'Payments').getByRole('region', { name: 'General' })).toContainText('Payments');
});

test('creates a type with a description and a schema and opens it', async ({ page }) => {
  const api = await loadAdminApp(page, { path: base, state: withTypes(payment) });

  await page.getByRole('button', { name: '+ Create Type' }).click();
  const dialog = sidePanel(page, 'Create type');
  await expect(page).toHaveURL(/new=1/);
  const type = dialog.getByLabel('Type *');
  await type.fill('BadType');
  await expect(type).toHaveAttribute('aria-invalid', 'true');
  await expect(type).toHaveCSS('border-top-color', INVALID_FIELD_BORDER);
  await expect(dialog.getByText('Lowercase letters, digits and underscores, starting with a letter.').first()).toBeVisible();
  await type.fill('payment_initiation');
  await expect(dialog.getByText('A type with this value already exists.')).toBeVisible();
  await type.fill('openid_credential');
  await dialog.locator('#detail-description').fill('Credential issuance');
  await dialog.locator('versola-code-editor#detail-schema textarea').fill('{ "type": "object", "properties": { "format": { "type": "string" } } }');
  await dialog.getByRole('button', { name: 'Create type', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/authorization-detail-types')).body).toEqual({
    tenantId: 'tenant-alpha',
    type: 'openid_credential',
    description: { en: 'Credential issuance' },
    schema: { type: 'object', properties: { format: { type: 'string' } } },
  });
  await expect(sidePanelDialog(page, 'Credential issuance')).toBeVisible();
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'openid_credential' })).toHaveAttribute('aria-current', 'true');
});

test('requires an English description before creating', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${base}&new=1`, state: withTypes(payment) });

  const dialog = sidePanel(page, 'Create type');
  await dialog.getByLabel('Type *').fill('orders');
  await dialog.getByRole('button', { name: 'Create type', exact: true }).click();
  await expect(dialog.getByText('A description in English is required.')).toBeVisible();
  expect(api.requests.some(request => request.method === 'POST')).toBe(false);
});

test('shows the lines central finds wrong in a schema and stays in the editor', async ({ page }) => {
  await loadAdminApp(page, { path: `${base}&new=1`, state: withTypes(payment) });
  await page.route('**/configuration/authorization-detail-types', route => route.request().method() === 'POST'
    ? route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ InvalidSchema: { errors: ['/properties/type: unknown keyword "typo"'] } }) })
    : route.fallback());

  const dialog = sidePanel(page, 'Create type');
  await dialog.getByLabel('Type *').fill('orders');
  await dialog.locator('#detail-description').fill('Orders');
  await dialog.getByRole('button', { name: 'Create type', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('Invalid JSON Schema: /properties/type: unknown keyword "typo"');
  await expect(sidePanel(page, 'Create type')).toBeVisible();
});

test('deletes a type from the panel and from the row menu through the confirm dialog', async ({ page }) => {
  const api = await loadAdminApp(page, { path: base, state: withTypes(payment, accounts) });

  await page.getByRole('button', { name: /^Open type .*payment_initiation$/ }).click();
  await sidePanel(page, 'Payment initiation').getByRole('button', { name: 'Delete type payment_initiation' }).click();
  await page.getByRole('dialog', { name: 'Delete type' }).getByRole('button', { name: 'Delete', exact: true }).click();
  expect((await findRequest(api.requests, 'DELETE', '/configuration/authorization-detail-types')).searchParams).toMatchObject({ tenantId: 'tenant-alpha', type: 'payment_initiation' });
  await expect(sidePanel(page, 'Payment initiation')).toHaveCount(0);

  await page.getByRole('button', { name: 'Actions for account_information' }).click();
  await page.getByRole('menuitem', { name: 'Delete type account_information' }).click();
  await page.getByRole('dialog', { name: 'Delete type' }).getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'No authorization detail types yet' })).toBeVisible();
});

test('read-only console: no create, no Edit, no danger card, row menu without Delete', async ({ page }) => {
  await loadAdminApp(page, { path: base, state: { ...withTypes(payment), myPermissions: readOnly } });

  await expect(page.getByRole('button', { name: '+ Create Type' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Actions for payment_initiation' }).click();
  await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy type']);
  await page.getByRole('menuitem', { name: 'Open' }).click();
  const panel = sidePanel(page, 'Payment initiation');
  await expect(panel.getByRole('region', { name: 'JSON Schema' })).toBeVisible();
  await expect(panel.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(panel.getByRole('region', { name: 'Delete type' })).toHaveCount(0);
});

test('below 720px the panel is a full-screen sheet and nothing scrolls sideways', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: `${base}&id=payment_initiation`, state: withTypes(payment) });

  const box = await sidePanel(page, 'Payment initiation').getByRole('dialog').boundingBox();
  await expectSheetWidth(page, box?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});
