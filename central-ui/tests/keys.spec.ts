import { expect, test } from '@playwright/test';
import { expectSheetWidth, findRequest, loadAdminApp, sidePanel, sidePanelDialog } from './fixtures';
import type { MockConfigState } from './mocks';

/**
 * Keys & metadata: the JWKS keys on the table + side panel pattern (who signs with a key comes
 * from the tenants' `signingKeyId`), Generate key as the one primary action, Add key as the
 * secondary one, and the server metadata overrides as a card below the table.
 */
const path = '/?view=keys';
const es256 = { kid: 'es256-a1', kty: 'EC', alg: 'ES256', use: 'sig', crv: 'P-256', x: 'f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU', y: 'x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0' };
const ps256 = { kid: 'ps256-c0', kty: 'RSA', alg: 'PS256', use: 'sig', n: 'xGOr-H7A', e: 'AQAB' };
const partner = { kid: 'partner-rs256', kty: 'RSA', alg: 'RS256', use: 'sig', n: 'xGOr-H7A', e: 'AQAB' };
const summaries = [
  { kid: 'es256-a1', algorithm: 'ES256', keyType: 'EC', curve: 'P-256', canSign: true },
  { kid: 'ps256-c0', algorithm: 'PS256', keyType: 'RSA', curve: null, canSign: true },
  { kid: 'partner-rs256', algorithm: 'RS256', keyType: 'RSA', curve: null, canSign: false },
];
const tenants = [
  { id: 'tenant-alpha', description: 'Alpha', edgeId: null, securityProfile: 'fapi2' as const, signingKeyId: 'es256-a1' },
  { id: 'tenant-bravo', description: 'Bravo', edgeId: null, securityProfile: 'fapi2' as const, signingKeyId: 'es256-a1' },
  { id: 'tenant-charlie', description: 'Charlie', edgeId: null, securityProfile: 'standard' as const, signingKeyId: null },
];
const state = (extra: Partial<MockConfigState> = {}): Partial<MockConfigState> => ({
  jwksKeys: [es256, ps256, partner] as MockConfigState['jwksKeys'],
  jwksKeySummaries: summaries,
  tenants,
  ...extra,
});
const readOnly = { resources: { central: { permissions: ['jwks:read'] } }, isProd: false };

test('lists keys with type, algorithm, usage and who uses them, and filters by usage', async ({ page }) => {
  await loadAdminApp(page, { path, state: state() });

  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Key ID', 'Type', 'Algorithm', 'Usage', 'Used by', 'Actions']);
  const es = table.getByRole('row').filter({ hasText: 'es256-a1' });
  await expect(es).toContainText('EC · P-256');
  await expect(es).toContainText('Can sign');
  await expect(es).toContainText('2 tenants');
  await expect(table.getByRole('row').filter({ hasText: 'partner-rs256' })).toContainText('Verify only');

  await page.getByRole('button', { name: /^Verify only\s?1$/ }).click();
  await expect(table.getByRole('button', { name: /^Open key/ })).toHaveText([/partner-rs256/]);
  await page.getByRole('button', { name: /^Can sign\s?2$/ }).click();
  await expect(table.getByRole('button', { name: /^Open key/ })).toHaveCount(2);
});

test('Generate key is the one primary action, Add key is secondary', async ({ page }) => {
  await loadAdminApp(page, { path, state: state() });

  await expect(page.getByRole('button', { name: 'Generate key' })).toHaveClass(/btn-primary/);
  await expect(page.getByRole('button', { name: 'Add key (paste JWK)' })).toHaveClass(/btn-secondary/);
  await expect(page.locator('versola-keys-metadata .btn-primary')).toHaveCount(1);
});

test('opens the panel with the public key, who uses it, Fixed and a disabled Delete that says why', async ({ page }) => {
  await loadAdminApp(page, { path, state: state() });

  await page.locator('versola-data-table').getByRole('row').filter({ hasText: 'es256-a1' }).getByRole('cell').nth(1).click();
  const panel = sidePanel(page, 'es256-a1');
  await expect(panel.getByRole('dialog')).toBeVisible();
  await expect(page).toHaveURL(/id=es256-a1/);
  await expect(panel.getByRole('region', { name: 'Public key' })).toContainText('"crv": "P-256"');
  await expect(panel.getByRole('region', { name: 'Used by tenants' })).toContainText('tenant-alpha');
  await expect(panel.getByRole('region', { name: 'Used by tenants' })).toContainText('tenant-bravo');
  await expect(panel.getByRole('region', { name: 'Fixed' })).toContainText('Part of the key; replace = delete and add');
  const danger = panel.getByRole('region', { name: 'Delete key' });
  await expect(danger.getByRole('button', { name: 'Delete key es256-a1' })).toBeDisabled();
  await expect(danger).toContainText('Still selected by 2 tenants');

  await panel.getByRole('button', { name: 'Close panel' }).click();
  await expect(panel).toHaveCount(0);
  await expect(page).not.toHaveURL(/id=/);
});

test('a key no tenant uses says so and can be deleted from the panel', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&id=ps256-c0`, state: state() });

  const panel = sidePanel(page, 'ps256-c0');
  await expect(panel.getByRole('region', { name: 'Used by tenants' })).toContainText('No tenant signs with this key.');
  await panel.getByRole('button', { name: 'Delete key ps256-c0' }).click();
  await page.getByRole('dialog', { name: 'Delete key' }).getByRole('button', { name: 'Delete', exact: true }).click();
  expect((await findRequest(api.requests, 'DELETE', '/configuration/jwks')).searchParams.kid).toBe('ps256-c0');
  await expect(sidePanel(page, 'ps256-c0')).toHaveCount(0);
  await expect(page.locator('versola-data-table').getByRole('button', { name: /^Open key/ })).toHaveCount(2);
});

test('the row menu disables Delete for a key in use and deletes one that is not', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: state() });

  await page.getByRole('button', { name: 'Actions for es256-a1' }).click();
  const used = page.getByRole('menuitem', { name: 'Delete key es256-a1' });
  await expect(used).toHaveAttribute('aria-disabled', 'true');
  await expect(used).toHaveAttribute('title', /Still selected by 2 tenants/);
  await used.click({ force: true });
  expect(api.requests.some(request => request.method === 'DELETE')).toBe(false);
  await page.keyboard.press('Escape');

  await page.getByRole('button', { name: 'Actions for partner-rs256' }).click();
  await page.getByRole('menuitem', { name: 'Delete key partner-rs256' }).click();
  await page.getByRole('dialog', { name: 'Delete key' }).getByRole('button', { name: 'Delete', exact: true }).click();
  expect((await findRequest(api.requests, 'DELETE', '/configuration/jwks')).searchParams.kid).toBe('partner-rs256');
});

test('a verify-only key says Central holds no private half', async ({ page }) => {
  await loadAdminApp(page, { path: `${path}&id=partner-rs256`, state: state() });

  await expect(sidePanel(page, 'partner-rs256').getByRole('status')).toContainText('Central holds no private half for this key');
});

test('generates a key with the chosen algorithm and opens it with the published banner', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: state() });

  await page.getByRole('button', { name: 'Generate key' }).click();
  const dialog = sidePanel(page, 'Generate key');
  await expect(page).toHaveURL(/new=1/);
  await expect(dialog.getByRole('button', { name: /^ES256/ })).toHaveAttribute('aria-pressed', 'true');
  await expect(dialog.getByRole('button', { name: /^RS256/ })).toContainText('not selectable by FAPI 2.0 tenants');
  await dialog.getByRole('button', { name: /^PS256/ }).click();
  await dialog.getByRole('button', { name: 'Generate key', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/jwks/generate')).searchParams.alg).toBe('PS256');
  const opened = sidePanel(page, 'generated-ps256');
  await expect(opened.getByRole('status')).toContainText('Key generated and published');
});

test('adds a public key by pasting a JWK, refusing a duplicate kid and a missing kid', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: state() });

  await page.getByRole('button', { name: 'Add key (paste JWK)' }).click();
  const dialog = sidePanel(page, 'Add key');
  const editor = dialog.locator('versola-code-editor#jwk-json textarea');
  await editor.fill('{ "kty": "RSA" }');
  await expect(dialog.getByRole('alert')).toContainText('kid');
  await editor.fill(JSON.stringify(es256));
  await expect(dialog.getByRole('alert')).toContainText('A key with this kid already exists.');
  await dialog.getByRole('button', { name: 'Add key', exact: true }).click();
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/jwks')).toBe(false);

  await editor.fill(JSON.stringify({ kid: 'new-rs', kty: 'RSA', alg: 'RS256', n: 'abc', e: 'AQAB' }));
  await dialog.getByRole('button', { name: 'Add key', exact: true }).click();
  expect((await findRequest(api.requests, 'POST', '/configuration/jwks')).body).toMatchObject({ kid: 'new-rs', kty: 'RSA' });
  await expect(sidePanelDialog(page, 'new-rs')).toBeVisible();
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'new-rs' })).toContainText('Verify only');
});

test('edits the public key and refuses a changed kid inline', async ({ page }) => {
  const api = await loadAdminApp(page, { path: `${path}&id=ps256-c0&edit=jwk`, state: state() });

  const editor = sidePanel(page, 'Edit public key');
  await expect(editor.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
  const field = editor.locator('versola-code-editor#jwk-json textarea');
  await field.fill(JSON.stringify({ ...ps256, kid: 'other' }));
  await expect(editor.getByRole('alert')).toContainText("The 'kid' cannot change");
  await editor.getByRole('button', { name: 'Save public key' }).click();
  expect(api.requests.some(request => request.method === 'PUT' && request.pathname === '/configuration/jwks')).toBe(false);

  await field.fill(JSON.stringify({ ...ps256, use: 'sig', key_ops: ['verify'] }));
  await editor.getByRole('button', { name: 'Save public key' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/jwks')).body).toMatchObject({ kid: 'ps256-c0', key_ops: ['verify'] });
  await expect(sidePanel(page, 'ps256-c0').getByRole('region', { name: 'Public key' })).toContainText('key_ops');
});

test('shows no overrides, then saves server metadata from its editor', async ({ page }) => {
  const api = await loadAdminApp(page, { path, state: state({ serverMetadata: null }) });

  const card = page.getByRole('region', { name: 'Server metadata overrides' });
  await expect(card).toContainText('No overrides set.');
  await card.getByRole('button', { name: 'Edit server metadata overrides' }).click();
  await expect(page).toHaveURL(/edit=metadata/);
  const field = page.locator('versola-code-editor#metadata-json textarea');
  await field.fill('{ "issuer": "https://auth.example" }');
  await expect(page.getByRole('alert')).toContainText('authorization_endpoint');
  const full = { issuer: 'https://auth.example', authorization_endpoint: 'https://auth.example/authorize', token_endpoint: 'https://auth.example/token', jwks_uri: 'https://auth.example/jwks', response_types_supported: ['code'] };
  await field.fill(JSON.stringify(full));
  await page.getByRole('button', { name: 'Save server metadata' }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/server-metadata')).body).toEqual(full);
  await expect(page).not.toHaveURL(/edit=metadata/);
  await expect(page.getByRole('region', { name: 'Server metadata overrides' })).toContainText('authorization_endpoint');
});

test('a key in the table links to the tenant that uses it', async ({ page }) => {
  await loadAdminApp(page, { path: `${path}&id=es256-a1`, state: state() });

  await sidePanel(page, 'es256-a1').getByRole('button', { name: 'tenant-bravo' }).click();
  await expect(page).toHaveURL(/view=tenants/);
  await expect(page).toHaveURL(/id=tenant-bravo/);
});

test('read-only console: no Generate, no Add, no Edit, no danger card, row menu without Delete', async ({ page }) => {
  await loadAdminApp(page, { path, state: state({ myPermissions: readOnly }) });

  await expect(page.getByRole('button', { name: 'Generate key' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Add key (paste JWK)' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Actions for es256-a1' }).click();
  await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy key ID']);
  await page.getByRole('menuitem', { name: 'Open' }).click();
  const panel = sidePanel(page, 'es256-a1');
  await expect(panel.getByRole('region', { name: 'Public key' })).toBeVisible();
  await expect(panel.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(panel.getByRole('region', { name: 'Delete key' })).toHaveCount(0);
});

test('below 720px the panel is a full-screen sheet and nothing scrolls sideways', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: `${path}&id=es256-a1`, state: state() });

  const box = await sidePanel(page, 'es256-a1').getByRole('dialog').boundingBox();
  await expectSheetWidth(page, box?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test('a long key ID is cut with an ellipsis, never broken inside, and reads in full from its title', async ({ page }) => {
  const long = 'partner-bank-rs256-with-a-very-long-identifier-2026';
  await loadAdminApp(page, {
    path: `${path}&id=es256-a1`,
    state: state({ jwksKeys: [es256, { ...partner, kid: long }] as MockConfigState['jwksKeys'], jwksKeySummaries: [summaries[0], { ...summaries[2], kid: long }] }),
  });

  const id = page.locator('versola-data-table .row-id').filter({ hasText: 'partner-bank' });
  await expect(id).toHaveAttribute('title', long);
  const box = await id.boundingBox();
  expect(box!.height).toBeLessThan(30);
  await expect(page.locator('versola-keys-metadata content-header')).toBeVisible();
  const title = page.locator('versola-keys-metadata content-header').getByRole('heading', { name: 'Keys & metadata' });
  expect((await title.boundingBox())!.height).toBeLessThan(60);
});
