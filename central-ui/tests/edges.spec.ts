import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { findRequest, loadAdminApp, sidePanel, sidePanelDialog, expectSheetWidth } from './fixtures';

/**
 * Edges on the table + side panel pattern: the list with the key and DPoP nonce state,
 * registering an edge in the panel (which lands on the new record with the one-time private
 * key banner), rotating and retiring keys, the DPoP nonce editor, delete, the read-only
 * console and the sheet below 720px.
 */
const edgesPath = '/?view=edges';
const alphaEdge = { id: 'edge-alpha', hasOldKey: false, requireDpopNonce: true };
const bravoEdge = { id: 'edge-bravo', hasOldKey: true, requireDpopNonce: false };
const tenants = [{ id: 'tenant-alpha', description: 'Alpha Workspace', edgeId: 'edge-alpha' }, { id: 'tenant-bravo', description: 'Bravo Workspace', edgeId: null }];
const readOnly = { resources: { central: { permissions: ['edges:read', 'tenants:read'] } }, isProd: false };

const panel = sidePanel;

test('lists edges with their tenants, key state and DPoP nonce, filters and searches', async ({ page }) => {
  await loadAdminApp(page, { path: edgesPath, state: { edges: [alphaEdge, bravoEdge], tenants } });

  const table = page.locator('versola-data-table');
  await expect(table.getByRole('columnheader')).toHaveText(['Edge', 'Tenants', 'Key', 'DPoP nonce', 'Actions']);
  const alpha = table.getByRole('row').filter({ hasText: 'edge-alpha' });
  await expect(alpha).toContainText('tenant-alpha');
  await expect(alpha).toContainText('Key current');
  await expect(alpha).toContainText('Required');
  const bravo = table.getByRole('row').filter({ hasText: 'edge-bravo' });
  await expect(bravo).toContainText('Key rotation');
  await expect(bravo).toContainText('Not required');

  await page.getByRole('button', { name: 'Key rotation', exact: true }).click();
  await expect(table.getByRole('button', { name: /^Open edge/ })).toHaveText([/edge-bravo/]);
  await page.getByRole('button', { name: 'No tenants', exact: true }).click();
  await expect(table.getByRole('button', { name: /^Open edge/ })).toHaveText([/edge-bravo/]);
  await page.getByRole('button', { name: 'All', exact: true }).click();
  await page.getByLabel('Search edges').fill('alpha');
  await expect(table.getByRole('button', { name: /^Open edge/ })).toHaveCount(1);
});

test('opens an edge and shows its key, DPoP nonce, linked tenants and Fixed', async ({ page }) => {
  await loadAdminApp(page, { path: edgesPath, state: { edges: [alphaEdge, bravoEdge], tenants } });

  await page.getByRole('button', { name: 'Open edge edge-bravo' }).click();
  const dialog = panel(page, 'edge-bravo');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expect(page).toHaveURL(/id=edge-bravo/);
  await expect(dialog.getByText('A key rotation is open: both keys verify')).toBeVisible();
  await expect(dialog.getByRole('region', { name: 'Signing key' })).toContainText('Rotation open');
  await expect(dialog.getByRole('button', { name: 'Retire old key' })).toBeVisible();
  await expect(dialog.getByRole('region', { name: 'DPoP nonce' })).toContainText('Not required');
  await expect(dialog.getByRole('region', { name: 'Linked tenants' })).toContainText('No tenants assigned to this edge.');
  await expect(dialog.getByRole('region', { name: 'Fixed' })).toContainText('Set at registration');

  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Open edge edge-bravo' })).toBeFocused();

  await page.getByRole('button', { name: 'Open edge edge-alpha' }).click();
  await expect(panel(page, 'edge-alpha').getByRole('region', { name: 'Linked tenants' })).toContainText('tenant-alpha');
});

test('registers an edge in the panel and lands on it with the one-time private key banner', async ({ page }) => {
  const api = await loadAdminApp(page, { path: edgesPath, state: { edges: [] } });

  await page.getByRole('button', { name: '+ Create Edge' }).click();
  const dialog = panel(page, 'Register edge');
  await dialog.getByLabel('Edge ID *').fill('new');
  await expect(dialog.getByText('Registered as edge-new')).toBeVisible();
  await dialog.getByRole('button', { name: 'Register edge', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/edges')).body).toEqual({ id: 'edge-new' });
  const record = panel(page, 'edge-new');
  await expect(record.getByText('Edge key generated')).toBeVisible();
  await expect(record.locator('.secret-value')).toHaveCount(3);
  await expect(record.getByRole('button', { name: 'Copy private key' })).toBeVisible();
  await expect(page.locator('versola-data-table')).toContainText('edge-new');

  await record.getByRole('button', { name: "I've saved it" }).click();
  await expect(record.getByText('Edge key generated')).toHaveCount(0);
});

test('refuses an invalid or duplicate edge ID before submitting', async ({ page }) => {
  const api = await loadAdminApp(page, { path: edgesPath, state: { edges: [alphaEdge] } });

  await page.getByRole('button', { name: '+ Create Edge' }).click();
  const dialog = panel(page, 'Register edge');
  const id = dialog.getByLabel('Edge ID *');
  await id.fill('Bad-ID');
  await expect(id).toHaveAttribute('aria-invalid', 'true');
  await expect(dialog.getByText('Lowercase letters, digits and dashes.')).toBeVisible();
  await id.fill('alpha');
  await expect(dialog.getByText('An edge with this ID already exists.')).toBeVisible();
  await dialog.getByRole('button', { name: 'Register edge', exact: true }).click();
  expect(api.requests.some(request => request.method === 'POST' && request.pathname === '/configuration/edges')).toBeFalsy();
});

// RFC 9449 section 9 is per edge and lives in central, so the console is where it is turned
// off -- not in the edge's own config file, which no longer carries it.
test('turns off the DPoP nonce requirement in the group editor and reflects it after the save', async ({ page }) => {
  const api = await loadAdminApp(page, { path: edgesPath, state: { edges: [alphaEdge] } });

  await page.getByRole('button', { name: 'Open edge edge-alpha' }).click();
  await panel(page, 'edge-alpha').getByRole('button', { name: 'Edit dpop nonce' }).click();
  const editor = panel(page, 'Edit DPoP nonce');
  await expect(page).toHaveURL(/edit=dpop/);
  await expect(editor).toContainText('use_dpop_nonce');
  await expect(editor).toContainText('Governs this edge only');
  const toggle = editor.getByRole('checkbox', { name: 'Required on every proxied call' });
  await expect(toggle).toBeChecked();
  await toggle.uncheck();
  await editor.getByRole('button', { name: 'Save DPoP nonce' }).click();

  const request = await findRequest(api.requests, 'PUT', '/configuration/edges/dpop');
  expect(request.searchParams).toEqual({ edgeId: 'edge-alpha' });
  expect(request.body).toEqual({ requireDpopNonce: false });
  await expect(panel(page, 'edge-alpha').getByRole('region', { name: 'DPoP nonce' })).toContainText('Not required');
  await expect(page.locator('versola-data-table').getByRole('row').filter({ hasText: 'edge-alpha' })).toContainText('Not required');
});

test('rotates the key, shows the new private key, then retires the old key', async ({ page }) => {
  const api = await loadAdminApp(page, { path: edgesPath, state: { edges: [alphaEdge] } });

  await page.getByRole('button', { name: 'Open edge edge-alpha' }).click();
  const record = panel(page, 'edge-alpha');
  await record.getByRole('button', { name: 'Rotate key', exact: true }).click();
  await page.getByRole('dialog', { name: 'Rotate edge key' }).getByRole('button', { name: 'Rotate key', exact: true }).click();

  expect((await findRequest(api.requests, 'POST', '/configuration/edges/rotate-key')).searchParams).toEqual({ edgeId: 'edge-alpha' });
  await expect(record.getByText('Edge key rotated')).toBeVisible();
  await expect(record.getByText('A key rotation is open: both keys verify')).toBeVisible();
  await expect(record.locator('.chip', { hasText: 'Key rotation' })).toBeVisible();

  await record.getByRole('button', { name: 'Retire old key' }).click();
  await page.getByRole('dialog', { name: 'Retire old key' }).getByRole('button', { name: 'Retire old key' }).click();
  expect((await findRequest(api.requests, 'DELETE', '/configuration/edges/old-key')).searchParams).toEqual({ edgeId: 'edge-alpha' });
  await expect(record.locator('.chip', { hasText: 'Key current' })).toBeVisible();
  await expect(record.getByRole('button', { name: 'Retire old key' })).toHaveCount(0);
});

test('deletes an edge from the panel through the confirm dialog and reaches the empty state', async ({ page }) => {
  const api = await loadAdminApp(page, { path: edgesPath, state: { edges: [bravoEdge] } });

  await page.getByRole('button', { name: 'Open edge edge-bravo' }).click();
  await panel(page, 'edge-bravo').getByRole('button', { name: 'Delete edge edge-bravo' }).click();
  await page.getByRole('dialog', { name: 'Delete edge' }).getByRole('button', { name: 'Delete', exact: true }).click();

  expect((await findRequest(api.requests, 'DELETE', '/configuration/edges')).searchParams).toEqual({ edgeId: 'edge-bravo' });
  await expect(panel(page, 'edge-bravo')).toHaveCount(0);
  await expect(page.getByText('No edges yet')).toBeVisible();
  await expect(page.getByRole('button', { name: '+ Create Edge' })).toBeVisible();
});

test('read-only console: no create, no Edit, no key actions, no danger card', async ({ page }) => {
  await loadAdminApp(page, { path: edgesPath, state: { edges: [bravoEdge], myPermissions: readOnly } });

  await expect(page.getByRole('button', { name: '+ Create Edge' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Actions for edge-bravo' }).click();
  await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy edge ID']);
  await page.getByRole('menuitem', { name: 'Open' }).click();
  const dialog = panel(page, 'edge-bravo');
  await expect(dialog.getByRole('button', { name: /^Edit/ })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Rotate key' })).toHaveCount(0);
  await expect(dialog.getByRole('region', { name: 'Delete edge' })).toHaveCount(0);
});

test('below 720px the panel is a full-screen sheet', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await loadAdminApp(page, { path: `${edgesPath}&id=edge-bravo`, state: { edges: [bravoEdge] } });

  const dialog = panel(page, 'edge-bravo');
  await expect(dialog.getByRole('dialog')).toBeVisible();
  await expectSheetWidth(page, (await dialog.getByRole('dialog').boundingBox())?.width);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
});

test('asks before the panel closes on a private key that is shown only once', async ({ page }) => {
  await loadAdminApp(page, { path: edgesPath, state: { edges: [] } });

  await page.getByRole('button', { name: '+ Create Edge' }).click();
  const dialog = panel(page, 'Register edge');
  await dialog.getByLabel('Edge ID *').fill('new');
  await dialog.getByRole('button', { name: 'Register edge', exact: true }).click();
  const record = panel(page, 'edge-new');
  await expect(record.getByText('Edge key generated')).toBeVisible();

  await page.keyboard.press('Escape');
  const confirm = page.getByRole('dialog', { name: 'Leave without saving the private key?' });
  await confirm.getByRole('button', { name: 'Stay' }).click();
  await expect(record.getByText('Edge key generated')).toBeVisible();

  await page.keyboard.press('Escape');
  await confirm.getByRole('button', { name: 'Leave' }).click();
  await expect(panel(page, 'edge-new')).toHaveCount(0);
});

test.describe('overload protection', () => {
  const policy = { inbound: { mode: 'enforce', maxInFlight: 512, adaptive: { maxLimit: 1536 } }, outbound: { mode: 'shadow' } };
  const editPath = '/?view=edges&id=edge-alpha&edit=overload';
  const alpha = { ...alphaEdge, overloadPolicy: policy };

  test('the edge panel says what is set here and what comes from the deployment', async ({ page }) => {
    await loadAdminApp(page, { path: '/?view=edges&id=edge-alpha', state: { edges: [alphaEdge, { ...bravoEdge, overloadPolicy: policy }] } });
    const card = panel(page, 'edge-alpha').getByRole('region', { name: 'Overload protection' });
    await expect(card).toContainText('Deployment default');
    await expect(card).toContainText('Nothing: every value comes from the deployment');
    await expect(card.getByRole('button', { name: 'Reset to deployment defaults' })).toHaveCount(0);

    await page.getByRole('button', { name: 'Open edge edge-bravo' }).click();
    const custom = panel(page, 'edge-bravo').getByRole('region', { name: 'Overload protection' });
    await expect(custom).toContainText('Enforce (set here)');
    await expect(custom).toContainText('Shadow (set here)');
    await expect(custom).toContainText('maxInFlight 512');
  });

  test('every field names what an empty value means', async ({ page }) => {
    await loadAdminApp(page, { path: editPath, state: { edges: [alphaEdge] } });
    const dialog = panel(page, 'Edit overload protection');
    await expect(dialog.getByText('HTTP_ADMISSION_MAX_IN_FLIGHT if the edge sets it, otherwise 256')).toBeVisible();
    await expect(dialog.getByText('HTTP_CLIENT_GUARD_REQUEST_TIMEOUT if the edge sets it, otherwise 10000')).toBeVisible();
    await expect(dialog.getByLabel('Highest limit')).toHaveAttribute('placeholder', '1024');
    await expect(dialog.getByLabel('Tolerance')).toBeHidden();
    await dialog.getByText('Advanced').click();
    await expect(dialog.getByLabel('Tolerance')).toBeVisible();
  });

  test('saves only the fields that were set', async ({ page }) => {
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [alphaEdge] } });
    const dialog = panel(page, 'Edit overload protection');
    await dialog.getByRole('group', { name: 'Mode' }).first().getByRole('button', { name: 'Enforce' }).click();
    await dialog.getByLabel('Highest limit').fill('1536');
    await dialog.getByRole('button', { name: 'Save overload protection' }).click();

    const request = await findRequest(api.requests, 'PUT', '/configuration/edges/overload');
    expect(request.searchParams.edgeId).toBe('edge-alpha');
    expect(request.body).toEqual({ inbound: { mode: 'enforce', adaptive: { maxLimit: 1536 } } });
    await expect(panel(page, 'edge-alpha').getByText('Overload protection saved')).toBeVisible();
    await expect(panel(page, 'edge-alpha').getByRole('region', { name: 'Overload protection' })).toContainText('Enforce (set here)');
  });

  test('validates inline with the server messages and sends nothing while a field is wrong', async ({ page }) => {
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [alphaEdge] } });
    const dialog = panel(page, 'Edit overload protection');
    await dialog.getByLabel('Starting limit').fill('0');
    await expect(dialog.getByText('inbound.maxInFlight must be between 1 and 100000')).toBeVisible();
    await expect(dialog.getByLabel('Starting limit')).toHaveAttribute('aria-invalid', 'true');
    await dialog.getByLabel('Start share').fill('0.9');
    await dialog.getByLabel('Continuation share').fill('0.5');
    await expect(dialog.getByText(/inbound.startShare must not exceed inbound.continuationShare/)).toBeVisible();

    await dialog.getByRole('button', { name: 'Save overload protection' }).click();
    await expect(dialog.getByLabel('Starting limit')).toBeFocused();
    expect(api.requests.some(request => request.method === 'PUT' && request.pathname === '/configuration/edges/overload')).toBe(false);

    await dialog.getByLabel('Starting limit').fill('8');
    await expect(dialog.getByText('The limit starts at 16, the lowest limit, not at 8.')).toBeVisible();
  });

  test('shows what central refuses and keeps the draft', async ({ page }) => {
    await loadAdminApp(page, { path: editPath, state: { edges: [alpha], overloadRejection: 'inbound.adaptive.maxLimit must be between 1 and 100000' } });
    const dialog = panel(page, 'Edit overload protection');
    await dialog.getByRole('button', { name: 'Save overload protection' }).click();
    await expect(dialog.getByRole('alert').filter({ hasText: 'must be between 1 and 100000' })).toBeVisible();
    await expect(dialog.getByLabel('Starting limit')).toHaveValue('512');
  });

  test('one click sends one request and the controls wait for it', async ({ page }) => {
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [alpha], overloadDelayMs: 600 } });
    const dialog = panel(page, 'Edit overload protection');
    const save = dialog.getByRole('button', { name: 'Save overload protection' });
    await save.dblclick();
    await expect(dialog.getByRole('button', { name: 'Saving…' })).toBeDisabled();
    await expect(dialog.getByLabel('Starting limit')).toBeDisabled();
    await expect(panel(page, 'edge-alpha').getByText('Overload protection saved')).toBeVisible();
    expect(api.requests.filter(request => request.method === 'PUT' && request.pathname === '/configuration/edges/overload')).toHaveLength(1);
  });

  test('Reset to deployment defaults sends {} after a confirmation', async ({ page }) => {
    const api = await loadAdminApp(page, { path: '/?view=edges&id=edge-alpha', state: { edges: [alpha] } });
    const card = panel(page, 'edge-alpha').getByRole('region', { name: 'Overload protection' });
    await card.getByRole('button', { name: 'Reset to deployment defaults' }).click();
    await page.getByRole('button', { name: 'Reset', exact: true }).click();

    expect((await findRequest(api.requests, 'PUT', '/configuration/edges/overload')).body).toEqual({});
    await expect(card).toContainText('Nothing: every value comes from the deployment');
    await expect(panel(page, 'edge-alpha').getByText('returned to the deployment defaults')).toBeVisible();
  });

  test('the editor has no axe violations in either theme, errors showing', async ({ page }) => {
    for (const scheme of ['light', 'dark'] as const) {
      await page.addInitScript(value => localStorage.setItem('versola-theme', value), scheme);
      await loadAdminApp(page, { path: editPath, state: { edges: [alpha] } });
      const dialog = panel(page, 'Edit overload protection');
      await dialog.getByLabel('Starting limit').fill('0');
      await dialog.getByText('Advanced').click();
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(results.violations.map(v => `${v.id}: ${v.nodes.length}`), scheme).toEqual([]);
    }
  });

  test('a console without edges:manage can read it but not edit it', async ({ page }) => {
    await loadAdminApp(page, { path: '/?view=edges&id=edge-alpha', state: { edges: [alpha], myPermissions: readOnly } });
    const card = panel(page, 'edge-alpha').getByRole('region', { name: 'Overload protection' });
    await expect(card).toContainText('Enforce (set here)');
    await expect(card.getByRole('button')).toHaveCount(0);
  });
});

test.describe('access log', () => {
  const policy = { mode: 'sampled', successSampleRate: 0.1 };
  const editPath = '/?view=edges&id=edge-alpha&edit=access-log';
  const alpha = { ...alphaEdge, accessLogPolicy: policy };
  const put = (request: { method: string; pathname: string }) => request.method === 'PUT' && request.pathname === '/configuration/edges/access-log';

  test('the edge panel says what is set here and what the edge defaults to', async ({ page }) => {
    await loadAdminApp(page, { path: '/?view=edges&id=edge-alpha', state: { edges: [alphaEdge, { ...bravoEdge, accessLogPolicy: policy }] } });
    const card = panel(page, 'edge-alpha').getByRole('region', { name: 'Access log' });
    await expect(card).toContainText('Default (off)');
    await expect(card).toContainText('Nothing: the edge uses its defaults');
    await expect(card).toContainText('Auth and central always log every request');
    await expect(card.getByRole('button', { name: 'Reset to defaults' })).toHaveCount(0);

    await page.getByRole('button', { name: 'Open edge edge-bravo' }).click();
    const custom = panel(page, 'edge-bravo').getByRole('region', { name: 'Access log' });
    await expect(custom).toContainText('Sampled (set here)');
    await expect(custom).toContainText('successSampleRate 0.1');
  });

  test('every field names what an empty value means, and each mode says what it does', async ({ page }) => {
    await loadAdminApp(page, { path: editPath, state: { edges: [alphaEdge] } });
    const dialog = panel(page, 'Edit access log');
    await expect(dialog.getByLabel('Slow threshold')).toHaveAttribute('placeholder', '500');
    await expect(dialog.getByLabel('Sample rate')).toHaveAttribute('placeholder', '0.05');
    await expect(dialog.getByText('Empty: 500 ms')).toBeVisible();
    await expect(dialog.getByText('Empty: 0.05')).toBeVisible();
    await expect(dialog.getByText('Only requests that failed (status 400 or above, or an error) and slow ones are logged.')).toBeVisible();
    await dialog.getByRole('button', { name: 'Full', exact: true }).click();
    await expect(dialog.getByText('Every request gets a log line.')).toBeVisible();
  });

  test('saves only the fields that were set', async ({ page }) => {
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [alphaEdge] } });
    const dialog = panel(page, 'Edit access log');
    await dialog.getByRole('button', { name: 'Full', exact: true }).click();
    await dialog.getByLabel('Slow threshold').fill('250');
    await dialog.getByRole('button', { name: 'Save access log' }).click();

    const request = await findRequest(api.requests, 'PUT', '/configuration/edges/access-log');
    expect(request.searchParams.edgeId).toBe('edge-alpha');
    expect(request.body).toEqual({ mode: 'full', slowMillis: 250 });
    await expect(panel(page, 'edge-alpha').getByText('Access log saved')).toBeVisible();
    await expect(panel(page, 'edge-alpha').getByRole('region', { name: 'Access log' })).toContainText('Full (set here)');
    expect(api.requests.some(request => request.method === 'PUT' && request.pathname === '/configuration/edges/overload')).toBe(false);
  });

  test('validates inline with the server messages and sends nothing while a field is wrong', async ({ page }) => {
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [alphaEdge] } });
    const dialog = panel(page, 'Edit access log');
    await dialog.getByLabel('Sample rate').fill('1.5');
    await expect(dialog.getByText('successSampleRate must be between 0.0 and 1.0')).toBeVisible();
    await expect(dialog.getByLabel('Sample rate')).toHaveAttribute('aria-invalid', 'true');
    await dialog.getByLabel('Slow threshold').fill('-1');
    await expect(dialog.getByText('slowMillis must be between 0 and 600000')).toBeVisible();

    await dialog.getByRole('button', { name: 'Save access log' }).click();
    await expect(dialog.getByLabel('Slow threshold')).toBeFocused();
    expect(api.requests.some(put)).toBe(false);
  });

  test('shows what central refuses and keeps the draft', async ({ page }) => {
    await loadAdminApp(page, { path: editPath, state: { edges: [alpha], accessLogRejection: 'mode must be full, sampled or off' } });
    const dialog = panel(page, 'Edit access log');
    await dialog.getByRole('button', { name: 'Save access log' }).click();
    await expect(dialog.getByRole('alert').filter({ hasText: 'mode must be full, sampled or off' })).toBeVisible();
    await expect(dialog.getByLabel('Sample rate')).toHaveValue('0.1');
  });

  test('one click sends one request and the controls wait for it', async ({ page }) => {
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [alpha], accessLogDelayMs: 600 } });
    const dialog = panel(page, 'Edit access log');
    await dialog.getByRole('button', { name: 'Save access log' }).dblclick();
    await expect(dialog.getByRole('button', { name: 'Saving…' })).toBeDisabled();
    await expect(dialog.getByLabel('Sample rate')).toBeDisabled();
    await expect(panel(page, 'edge-alpha').getByText('Access log saved')).toBeVisible();
    expect(api.requests.filter(put)).toHaveLength(1);
  });

  test('Reset to defaults sends {} after a confirmation', async ({ page }) => {
    const api = await loadAdminApp(page, { path: '/?view=edges&id=edge-alpha', state: { edges: [alpha] } });
    const card = panel(page, 'edge-alpha').getByRole('region', { name: 'Access log' });
    await card.getByRole('button', { name: 'Reset to defaults' }).click();
    await page.getByRole('button', { name: 'Reset', exact: true }).click();

    expect((await findRequest(api.requests, 'PUT', '/configuration/edges/access-log')).body).toEqual({});
    await expect(card).toContainText('Nothing: the edge uses its defaults');
    await expect(panel(page, 'edge-alpha').getByText('returned to the defaults')).toBeVisible();
  });

  test('the editor has no axe violations in either theme, errors showing', async ({ page }) => {
    for (const scheme of ['light', 'dark'] as const) {
      await page.addInitScript(value => localStorage.setItem('versola-theme', value), scheme);
      await loadAdminApp(page, { path: editPath, state: { edges: [alpha] } });
      const dialog = panel(page, 'Edit access log');
      await dialog.getByLabel('Sample rate').fill('2');
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(results.violations.map(v => `${v.id}: ${v.nodes.length}`), scheme).toEqual([]);
    }
  });

  test('a console without edges:manage can read it but not edit it', async ({ page }) => {
    await loadAdminApp(page, { path: '/?view=edges&id=edge-alpha', state: { edges: [alpha], myPermissions: readOnly } });
    const card = panel(page, 'edge-alpha').getByRole('region', { name: 'Access log' });
    await expect(card).toContainText('Sampled (set here)');
    await expect(card.getByRole('button')).toHaveCount(0);
  });
});

test.describe('rate limits', () => {
  const set = { policies: [{ name: 'per-client', by: ['client'], scope: 'edge-wide', limits: [{ limit: 100, windowSeconds: 60 }], burst: 5, mode: 'shadow' }] };
  const editPath = '/?view=edges&id=edge-alpha&edit=rate-limits';
  const alpha = { ...alphaEdge, rateLimitPolicies: set };
  const put = (request: { method: string; pathname: string }) => request.method === 'PUT' && request.pathname === '/configuration/edges/rate-limits';

  test('the edge panel says what is set and that nothing is refused yet', async ({ page }) => {
    await loadAdminApp(page, { path: '/?view=edges&id=edge-alpha', state: { edges: [alphaEdge, { ...bravoEdge, rateLimitPolicies: set }] } });
    const card = panel(page, 'edge-alpha').getByRole('region', { name: 'Rate limits' });
    await expect(card).toContainText('Nothing: this edge applies no rate limit');
    await expect(card).toContainText('Start in shadow (counts only), then enforce');
    await expect(card.getByRole('button', { name: 'Remove all policies' })).toHaveCount(0);

    await page.getByRole('button', { name: 'Open edge edge-bravo' }).click();
    const custom = panel(page, 'edge-bravo').getByRole('region', { name: 'Rate limits' });
    await expect(custom).toContainText('1 policy');
    await expect(custom).toContainText('per-client');
    await expect(custom).toContainText('Shadow · 100 / 60 s · by client · burst 5');
  });

  test('adds a policy, saves the set as typed and shows it on the card', async ({ page }) => {
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [alphaEdge] } });
    const dialog = panel(page, 'Edit rate limits');
    await expect(dialog.getByText('Start in shadow, then enforce')).toBeVisible();
    await dialog.getByRole('button', { name: 'Add policy' }).click();
    await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toBeFocused();
    await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('per-endpoint');
    await dialog.getByLabel('Scope').selectOption('endpoint');
    await dialog.getByRole('checkbox', { name: 'User (subject)' }).check();
    await dialog.getByLabel('Window 1: requests').fill('10');
    await dialog.getByLabel('Window 1: seconds').fill('1');
    await dialog.getByRole('button', { name: 'Add window' }).click();
    await dialog.getByLabel('Window 2: requests').fill('500');
    await dialog.getByLabel('Window 2: seconds').fill('3600');
    await dialog.getByRole('button', { name: 'Save rate limits' }).click();

    const request = await findRequest(api.requests, 'PUT', '/configuration/edges/rate-limits');
    expect(request.searchParams.edgeId).toBe('edge-alpha');
    expect(request.body).toEqual({
      policies: [{ name: 'per-endpoint', by: ['client', 'subject'], scope: 'endpoint', limits: [{ limit: 10, windowSeconds: 1 }, { limit: 500, windowSeconds: 3600 }], mode: 'shadow' }],
    });
    await expect(panel(page, 'edge-alpha').getByText('Rate limit policies saved')).toBeVisible();
    await expect(panel(page, 'edge-alpha').getByRole('region', { name: 'Rate limits' })).toContainText('Shadow · 10 / 1 s + 500 / 3600 s · by client, user, per endpoint');
    expect(api.requests.some(request => request.method === 'PUT' && request.pathname === '/configuration/edges/overload')).toBe(false);
  });

  test('with no policy the editor offers the recommended set, all in shadow, and adds only what is missing', async ({ page }) => {
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [alphaEdge] } });
    const dialog = panel(page, 'Edit rate limits');
    await expect(dialog.getByText('No policies yet')).toBeVisible();
    await dialog.getByRole('button', { name: 'Add recommended policies' }).click();
    await expect(dialog.getByRole('group', { name: /^Policy / })).toHaveCount(3);
    await expect(dialog.getByRole('button', { name: 'Shadow', exact: true }).first()).toHaveAttribute('aria-pressed', 'true');
    await dialog.getByRole('textbox', { name: 'Name', exact: true }).first().fill('mine');
    // a second click adds the two that are not there yet, not a duplicate of the renamed one's name
    await dialog.getByRole('button', { name: 'Add recommended policies' }).click();
    await expect(dialog.getByRole('group', { name: /^Policy / })).toHaveCount(4);
    await dialog.getByRole('textbox', { name: 'Name', exact: true }).nth(1).fill('p2');
    await dialog.getByRole('textbox', { name: 'Name', exact: true }).nth(2).fill('p3');
    await dialog.getByRole('button', { name: 'Save rate limits' }).click();
    const body = (await findRequest(api.requests, 'PUT', '/configuration/edges/rate-limits')).body as { policies: Array<{ name: string; mode: string }> };
    expect(body.policies.map(policy => policy.name)).toEqual(['mine', 'p2', 'p3', 'per-client']);
    expect(body.policies.every(policy => policy.mode === 'shadow')).toBe(true);
  });

  test('Enforce is offered, says what it does, and is saved as chosen', async ({ page }) => {
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [alpha] } });
    const dialog = panel(page, 'Edit rate limits');
    await expect(dialog.getByRole('button', { name: 'Shadow', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await dialog.getByRole('button', { name: 'Off', exact: true }).click();
    await expect(dialog.getByText('The policy is ignored.')).toBeVisible();
    await dialog.getByRole('button', { name: 'Enforce', exact: true }).click();
    await expect(dialog.getByText('Refuses calls over the limit with 429 and Retry-After')).toBeVisible();
    await dialog.getByRole('button', { name: 'Save rate limits' }).click();
    expect((await findRequest(api.requests, 'PUT', '/configuration/edges/rate-limits')).body).toMatchObject({ policies: [{ name: 'per-client', mode: 'enforce' }] });
    await expect(panel(page, 'edge-alpha').getByRole('region', { name: 'Rate limits' })).toContainText('Enforce · 100 / 60 s');
  });

  test('validates inline with the server messages and sends nothing while a field is wrong', async ({ page }) => {
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [alphaEdge] } });
    const dialog = panel(page, 'Edit rate limits');
    await dialog.getByRole('button', { name: 'Add policy' }).click();
    // an empty required field is quiet until the first submit
    await expect(dialog.getByRole('alert')).toHaveCount(0);
    await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('Bad Name');
    await expect(dialog.getByText('name must be 1 to 48 characters')).toBeVisible();
    await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toHaveAttribute('aria-invalid', 'true');
    await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('ok');
    await dialog.getByLabel('Window 1: requests').fill('10');
    await dialog.getByLabel('Window 1: seconds').fill('60');
    await dialog.getByText('Advanced').click();
    await dialog.getByLabel('Burst').fill('11');
    await expect(dialog.getByText('burst must not exceed the smallest limit')).toBeVisible();

    await dialog.getByRole('button', { name: 'Save rate limits' }).click();
    await expect(dialog.getByLabel('Burst')).toBeFocused();
    expect(api.requests.some(put)).toBe(false);

    await dialog.getByLabel('Burst').fill('');
    await dialog.getByLabel('Window 1: requests').fill('');
    await dialog.getByRole('button', { name: 'Save rate limits' }).click();
    await expect(dialog.getByText('limit must be between 1 and 1000000')).toBeVisible();
    expect(api.requests.some(put)).toBe(false);
  });

  test('shows what central refuses and keeps the draft', async ({ page }) => {
    await loadAdminApp(page, { path: editPath, state: { edges: [alpha], rateLimitRejection: 'two rate limit policies have the same name' } });
    const dialog = panel(page, 'Edit rate limits');
    await dialog.getByRole('button', { name: 'Save rate limits' }).click();
    await expect(dialog.getByRole('alert').filter({ hasText: 'two rate limit policies have the same name' })).toBeVisible();
    await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toHaveValue('per-client');
  });

  test('one click sends one request and the controls wait for it', async ({ page }) => {
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [alpha], rateLimitDelayMs: 600 } });
    const dialog = panel(page, 'Edit rate limits');
    await dialog.getByRole('button', { name: 'Save rate limits' }).dblclick();
    await expect(dialog.getByRole('button', { name: 'Saving…' })).toBeDisabled();
    await expect(dialog.getByRole('textbox', { name: 'Name', exact: true })).toBeDisabled();
    await expect(panel(page, 'edge-alpha').getByText('Rate limit policies saved')).toBeVisible();
    expect(api.requests.filter(put)).toHaveLength(1);
  });

  test('removing a policy and saving sends the rest; Remove all policies sends an empty list after a confirmation', async ({ page }) => {
    const two = { policies: [...set.policies, { ...set.policies[0], name: 'second' }] };
    const api = await loadAdminApp(page, { path: editPath, state: { edges: [{ ...alphaEdge, rateLimitPolicies: two }] } });
    const dialog = panel(page, 'Edit rate limits');
    await dialog.getByRole('button', { name: 'Remove policy second' }).click();
    await dialog.getByRole('button', { name: 'Save rate limits' }).click();
    expect((await findRequest(api.requests, 'PUT', '/configuration/edges/rate-limits')).body).toMatchObject({ policies: [{ name: 'per-client' }] });

    const card = panel(page, 'edge-alpha').getByRole('region', { name: 'Rate limits' });
    await card.getByRole('button', { name: 'Remove all policies' }).click();
    await page.getByRole('button', { name: 'Remove all', exact: true }).click();
    await expect(card).toContainText('Nothing: this edge applies no rate limit');
    expect(api.requests.filter(put).at(-1)?.body).toEqual({ policies: [] });
  });

  test('the editor has no axe violations in either theme, errors showing', async ({ page }) => {
    for (const scheme of ['light', 'dark'] as const) {
      await page.addInitScript(value => localStorage.setItem('versola-theme', value), scheme);
      await loadAdminApp(page, { path: editPath, state: { edges: [alpha] } });
      const dialog = panel(page, 'Edit rate limits');
      await dialog.getByRole('textbox', { name: 'Name', exact: true }).fill('Bad');
      await dialog.getByRole('button', { name: 'Add policy' }).click();
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
      expect(results.violations.map(v => `${v.id}: ${v.nodes.length}`), scheme).toEqual([]);
    }
  });

  test('a console without edges:manage can read it but not edit it', async ({ page }) => {
    await loadAdminApp(page, { path: '/?view=edges&id=edge-alpha', state: { edges: [alpha], myPermissions: readOnly } });
    const card = panel(page, 'edge-alpha').getByRole('region', { name: 'Rate limits' });
    await expect(card).toContainText('per-client');
    await expect(card.getByRole('button')).toHaveCount(0);
  });
});

test('rate limits: the number of edge pods is saved with the set and shown on the card', async ({ page }) => {
  const alpha = { ...alphaEdge, rateLimitPolicies: { policies: [{ name: 'per-client', by: ['client'], scope: 'edge-wide', limits: [{ limit: 100, windowSeconds: 60 }], mode: 'shadow' }] } };
  const api = await loadAdminApp(page, { path: '/?view=edges&id=edge-alpha&edit=rate-limits', state: { edges: [alpha] } });
  const dialog = sidePanel(page, 'Edit rate limits');
  await dialog.getByLabel('Pods').fill('0');
  await expect(dialog.getByText('replicas must be between 1 and 1000')).toBeVisible();
  await dialog.getByLabel('Pods').fill('3');
  await dialog.getByRole('button', { name: 'Save rate limits' }).click();
  expect((await findRequest(api.requests, 'PUT', '/configuration/edges/rate-limits')).body).toMatchObject({ replicas: 3, policies: [{ name: 'per-client' }] });
  await expect(sidePanel(page, 'edge-alpha').getByRole('region', { name: 'Rate limits' })).toContainText('3 (each pod enforces 1/3 of a limit)');
});
