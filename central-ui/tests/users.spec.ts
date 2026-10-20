import { expect, test, type Page } from '@playwright/test';
import { findRequest, loadAdminApp } from './fixtures';
import { defaultMyPermissions } from './mocks';
import {
  chromeAgentId, chromeSessionId, dana, danaId, firefoxAgentId, quiet, shopManager, shopSupport, shopUser, userClients, userScopes,
} from './users-fixtures';

const tenant = 'tenant-alpha';
const usersPath = `/?view=users&tenant=${tenant}`;
const recordPath = (id = danaId, edit?: string) => `${usersPath}&id=${id}${edit ? `&edit=${edit}` : ''}`;

const state = {
  users: [dana, quiet],
  clients: { [tenant]: userClients },
  roles: { [tenant]: [shopManager, shopUser, shopSupport] },
  scopes: { [tenant]: userScopes },
};

const readOnly = { resources: { central: { permissions: ['users:read'] } }, isProd: false };
const searchBox = (page: Page) => page.getByRole('searchbox', { name: 'Search users' });
const search = async (page: Page, query: string) => {
  await searchBox(page).fill(query);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
};
const card = (page: Page, name: string) => page.getByRole('region', { name, exact: true });
const confirmDialog = (page: Page) => page.getByRole('dialog');

async function openRecord(page: Page, options: Parameters<typeof loadAdminApp>[1] = {}, path = recordPath()) {
  const api = await loadAdminApp(page, { path, state, ...options });
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  return api;
}

test.describe('search', () => {
  test('starts with an empty state and no request, then lists the match', async ({ page }) => {
    const api = await loadAdminApp(page, { path: usersPath, state });
    await expect(page.getByRole('heading', { name: 'Search for a user', exact: true })).toBeVisible();
    expect(api.requests.filter(request => request.pathname === '/users')).toHaveLength(0);

    await search(page, 'dana');
    const row = page.getByRole('row').filter({ hasText: 'Dana Reyes' });
    await expect(row).toContainText(danaId);
    await expect(row).toContainText('dana@example.com');
    await expect(row).toContainText(dana.phone);
    await expect(row).toContainText('dana');
    expect((await findRequest(api.requests, 'GET', '/users')).searchParams).toEqual({ login: 'dana' });
  });

  test('detects the field from what is typed and lets Search use it', async ({ page }) => {
    const api = await loadAdminApp(page, { path: usersPath, state });
    const field = page.getByLabel('Search by');

    await searchBox(page).fill(dana.email);
    await expect(field).toHaveValue('email');
    await searchBox(page).fill(dana.phone);
    await expect(field).toHaveValue('phone');
    await searchBox(page).fill(danaId);
    await expect(field).toHaveValue('id');
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: 'Dana Reyes' })).toBeVisible();
    expect((await findRequest(api.requests, 'GET', '/users')).searchParams).toEqual({ id: danaId });

    await searchBox(page).fill('plain');
    await expect(field).toHaveValue('login');
  });

  test('a user with no name is listed as No name with their ID', async ({ page }) => {
    await loadAdminApp(page, { path: usersPath, state });
    await search(page, 'sam');
    const row = page.getByRole('row').filter({ hasText: 'No name' });
    await expect(row).toContainText(quiet.id);
    await expect(row).toContainText('sam@example.com');
  });

  test('shows No users found when nothing matches', async ({ page }) => {
    await loadAdminApp(page, { path: usersPath, state });
    await search(page, 'missing');
    await expect(page.getByRole('heading', { name: 'No users found', exact: true })).toBeVisible();
  });

  test('a failing search shows the error card and Retry searches again', async ({ page }) => {
    await loadAdminApp(page, { path: usersPath, state });
    let fail = true;
    await page.route('**/users?login=*', async route => {
      if (fail) await route.fulfill({ status: 500, body: 'search is down' });
      else await route.fallback();
    });
    await search(page, 'dana');
    await expect(page.getByRole('heading', { name: 'Could not load users' })).toBeVisible();
    await expect(page.getByText('search is down')).toBeVisible();
    fail = false;
    await page.getByRole('button', { name: 'Retry' }).click();
    await expect(page.getByRole('row').filter({ hasText: 'Dana Reyes' })).toBeVisible();
  });

  test('a row opens the record, the menu opens it or copies the ID', async ({ page }) => {
    await loadAdminApp(page, { path: usersPath, state });
    await search(page, 'dana');
    await page.getByRole('button', { name: `Actions for ${danaId}` }).click();
    await expect(page.getByRole('menuitem')).toHaveText(['Open', 'Copy ID']);
    await page.getByRole('menuitem', { name: 'Open' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`id=${danaId}`));
    await page.getByRole('button', { name: 'Users', exact: true }).first().click();
    await expect(searchBox(page)).toHaveValue('dana');
    await expect(page.getByRole('row').filter({ hasText: 'Dana Reyes' })).toBeVisible();
  });

  test('a read-only operator sees no create button', async ({ page }) => {
    await loadAdminApp(page, {
      path: usersPath,
      state: { ...state, myPermissions: readOnly },
    });
    await expect(page.getByRole('button', { name: '+ Create User' })).toHaveCount(0);
  });
});

test.describe('create', () => {
  test('creates a user and opens their record', async ({ page }) => {
    const api = await loadAdminApp(page, { path: usersPath, state: { ...state, users: [] } });
    await page.getByRole('button', { name: '+ Create User', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Create New User' })).toBeVisible();
    await page.getByLabel('Email').fill('new@example.com');
    await page.getByLabel('Login').fill('newbie');
    await page.getByRole('button', { name: 'Create User', exact: true }).click();

    expect((await findRequest(api.requests, 'POST', '/users')).body).toEqual({ email: 'new@example.com', login: 'newbie' });
    await expect(page.getByRole('heading', { level: 1, name: 'No name' })).toBeVisible();
    await expect(card(page, 'Identifiers')).toContainText('new@example.com');
  });

  test('needs one identifier and a valid phone', async ({ page }) => {
    const api = await loadAdminApp(page, { path: usersPath, state: { ...state, users: [] } });
    await page.getByRole('button', { name: '+ Create User', exact: true }).click();
    await page.getByRole('button', { name: 'Create User', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('at least one of email, phone or login');

    await page.getByLabel('Phone').fill('12');
    await expect(page.getByLabel('Phone')).toHaveAttribute('aria-invalid', 'true');
    await page.getByRole('button', { name: 'Create User', exact: true }).click();
    expect(api.requests.filter(request => request.method === 'POST' && request.pathname === '/users')).toHaveLength(0);
  });

  test('a taken identifier is reported', async ({ page }) => {
    await loadAdminApp(page, { path: usersPath, state });
    await page.getByRole('button', { name: '+ Create User', exact: true }).click();
    await page.getByLabel('Login').fill('dana');
    await page.getByRole('button', { name: 'Create User', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('One of the identifiers is already taken by another user.');
  });
});

test.describe('record', () => {
  test('shows the header and every group', async ({ page }) => {
    await openRecord(page);
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
    await expect(page.getByText(danaId).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Copy user ID' })).toBeVisible();

    await expect(card(page, 'Identifiers')).toContainText('dana@example.com');
    await expect(card(page, 'Identifiers')).toContainText(dana.phone);
    await expect(card(page, `Roles in ${tenant}`)).toContainText('shop-manager');
    await expect(card(page, `Roles in ${tenant}`)).toContainText('user');
    await expect(card(page, 'Claims')).toContainText('Dana Reyes');
    await expect(card(page, 'Claims').getByText('true')).toBeVisible();
    await expect(card(page, 'Signed-in devices')).toBeVisible();
    await expect(card(page, 'Apps with access')).toBeVisible();
    await expect(card(page, 'Passkeys')).toBeVisible();
    await expect(card(page, 'Security actions')).toBeVisible();
    await expect(card(page, 'Fixed')).toContainText('Assigned by central');
    await expect(card(page, 'Delete user')).toBeVisible();
  });

  test('carries no counters', async ({ page }) => {
    await openRecord(page);
    await expect(page.getByText(/\d+ (devices?|apps?)\b/i)).toHaveCount(0);
    await expect(page.getByText(/\(\d+\)/)).toHaveCount(0);
  });

  test('loads the user by ID: a reload keeps the page', async ({ page }) => {
    await openRecord(page);
    await page.reload();
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
  });

  test('an unknown ID says the user was not found', async ({ page }) => {
    await loadAdminApp(page, { path: recordPath('00000000-0000-0000-0000-00000000dead'), state });
    await expect(page.getByRole('heading', { name: 'User not found' })).toBeVisible();
    await page.getByRole('button', { name: 'Back to users' }).click();
    await expect(page.getByRole('heading', { name: 'Search for a user' })).toBeVisible();
  });

  test('a failing user load shows the error card', async ({ page }) => {
    await loadAdminApp(page, { path: usersPath, state });
    await page.route('**/users?id=*', route => route.fulfill({ status: 500, body: 'users are down' }));
    await page.goto(recordPath());
    await expect(page.getByRole('heading', { name: 'Could not load user' })).toBeVisible();
    await expect(page.getByText('users are down')).toBeVisible();
  });

  test('without a tenant, says so and hides roles', async ({ page }) => {
    await loadAdminApp(page, { path: `/?view=users&id=${danaId}`, state: { ...state, tenants: [] } });
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
    await expect(page.getByText('Select a tenant to see roles and reset limits.')).toBeVisible();
    await expect(page.getByRole('region', { name: /^Roles in/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Reset password…' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Reset limits' })).toBeDisabled();
  });

  test('a read-only operator gets no Edit, sign-out, revoke, reset or delete', async ({ page }) => {
    await openRecord(page, {
      state: { ...state, myPermissions: readOnly },
    });
    await expect(card(page, 'Identifiers')).toBeVisible();
    for (const name of [/^Edit /, /^Sign out/, /^Forget device/, /^Revoke/, 'Reset password…', 'Reset limits', /^Rename/, /^Delete/]) {
      await expect(page.getByRole('button', { name })).toHaveCount(0);
    }
    await expect(card(page, 'Delete user')).toHaveCount(0);
  });

  test('a part that fails to load says so and retries without taking the page down', async ({ page }) => {
    await loadAdminApp(page, { path: usersPath, state });
    let fail = true;
    await page.route('**/users/passkeys?*', async route => {
      if (fail) await route.fulfill({ status: 500, body: 'passkeys are down' });
      else await route.fallback();
    });
    await page.goto(recordPath());
    await expect(card(page, 'Passkeys')).toContainText('passkeys are down');
    await expect(card(page, 'Identifiers')).toContainText('dana@example.com');
    fail = false;
    await card(page, 'Passkeys').getByRole('button', { name: 'Retry' }).click();
    await expect(card(page, 'Passkeys')).toContainText('iCloud Keychain');
  });
});

test.describe('group editors', () => {
  test('identifiers: sends only what changed and returns to the record', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Edit identifiers' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Edit identifiers' })).toBeVisible();
    await expect(page).toHaveURL(/edit=identifiers/);
    await expect(page.getByLabel('Email')).toHaveValue('dana@example.com');
    await page.getByLabel('Login').fill('dana-reyes');
    await page.getByRole('button', { name: 'Save identifiers' }).click();

    expect((await findRequest(api.requests, 'PATCH', '/users')).body).toEqual({ id: danaId, login: 'dana-reyes' });
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
    await expect(card(page, 'Identifiers')).toContainText('dana-reyes');
  });

  test('identifiers: clearing a field sends null', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Edit identifiers' }).click();
    await page.getByLabel('Phone').fill('');
    await page.getByRole('button', { name: 'Save identifiers' }).click();
    expect((await findRequest(api.requests, 'PATCH', '/users')).body).toEqual({ id: danaId, phone: null });
  });

  test('identifiers: a 409 names the problem and stays in the editor', async ({ page }) => {
    await openRecord(page);
    await page.getByRole('button', { name: 'Edit identifiers' }).click();
    await page.getByLabel('Login').fill('sam');
    await page.getByRole('button', { name: 'Save identifiers' }).click();
    await expect(page.getByRole('alert')).toContainText('One of the identifiers is already taken by another user.');
    await expect(page.getByRole('heading', { level: 1, name: 'Edit identifiers' })).toBeVisible();
  });

  test('identifiers: refuses an invalid phone and an empty set', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Edit identifiers' }).click();
    await page.getByLabel('Phone').fill('nope');
    await expect(page.getByLabel('Phone')).toHaveAttribute('aria-invalid', 'true');
    await page.getByRole('button', { name: 'Save identifiers' }).click();
    await page.getByLabel('Phone').fill('');
    await page.getByLabel('Email').fill('');
    await page.getByLabel('Login').fill('');
    await page.getByRole('button', { name: 'Save identifiers' }).click();
    await expect(page.getByRole('alert')).toContainText('at least one');
    expect(api.requests.filter(request => request.method === 'PATCH' && request.pathname === '/users')).toHaveLength(0);
  });

  test('cancel returns to the record without a request', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Edit identifiers' }).click();
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
    expect(api.requests.filter(request => request.method === 'PATCH')).toHaveLength(0);
  });

  test('roles: picks from the tenant and sends the difference', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Edit roles' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Edit roles' })).toBeVisible();
    await expect(page.getByRole('checkbox', { name: /shop-manager/ })).toBeChecked();
    await page.getByRole('checkbox', { name: /shop-manager/ }).uncheck();
    await page.getByRole('checkbox', { name: /support/ }).check();
    await page.getByRole('button', { name: 'Save roles' }).click();

    expect((await findRequest(api.requests, 'PATCH', '/users/roles')).body).toEqual({
      userId: danaId, tenantId: tenant, add: ['support'], remove: ['shop-manager'],
    });
    await expect(card(page, `Roles in ${tenant}`)).toContainText('support');
    await expect(card(page, `Roles in ${tenant}`)).not.toContainText('shop-manager');
  });

  test('roles: opening the editor from the URL shows the assigned roles ticked', async ({ page }) => {
    await openRecord(page, {}, recordPath(danaId, 'roles'));
    await expect(page.getByRole('checkbox', { name: /shop-manager/ })).toBeChecked();
    await expect(page.getByRole('checkbox', { name: /support/ })).not.toBeChecked();
  });

  test('roles: a user with none says so', async ({ page }) => {
    await openRecord(page, {}, recordPath(quiet.id));
    await expect(card(page, `Roles in ${tenant}`)).toContainText('No roles in this tenant.');
  });

  test('claims: edits a claim and removes another', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Edit claims' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Edit claims' })).toBeVisible();
    await page.locator('.claim-row', { hasText: 'name' }).first().locator('input[type=text]').fill('Dana R.');
    await page.locator('.claim-row', { hasText: 'locale' }).locator('input[type=checkbox]').uncheck();
    await page.getByRole('button', { name: 'Save claims' }).click();

    expect((await findRequest(api.requests, 'PATCH', '/users/claims')).body).toEqual({
      id: danaId, claims: { name: 'Dana R.', locale: null },
    });
    await expect(page.getByRole('heading', { level: 1, name: 'Dana R.' })).toBeVisible();
  });

  test('an editor does not open for a read-only operator', async ({ page }) => {
    await openRecord(page, {
      state: { ...state, myPermissions: readOnly },
    }, recordPath(danaId, 'identifiers'));
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
  });
});

test.describe('signed-in devices', () => {
  test('lists a device per user agent with its clients and the offline access marker', async ({ page }) => {
    await openRecord(page);
    const devices = card(page, 'Signed-in devices');
    await expect(devices).toContainText('Chrome 130 on macOS 14.6');
    await expect(devices).toContainText('Example Shop app on iOS 18.1');
    const chrome = devices.locator('.device', { hasText: 'Chrome 130 on macOS 14.6' });
    await expect(chrome.getByRole('row').filter({ hasText: 'storefront-web' })).toContainText('Storefront Web');
    await expect(chrome.getByRole('row').filter({ hasText: 'storefront-web' })).toContainText('Offline access');
    await expect(chrome.getByRole('row').filter({ hasText: 'admin-console' })).not.toContainText('Offline access');
    await expect(chrome.getByRole('button', { name: 'Go to Apps with access' })).toBeVisible();
  });

  test('shows when the session expires, the idle timeout when there is one, and how long the device is remembered', async ({ page }) => {
    await openRecord(page);
    const devices = card(page, 'Signed-in devices');
    const chrome = devices.locator('.device', { hasText: 'Chrome 130 on macOS 14.6' });
    await expect(chrome.locator('[data-life="session"]')).toContainText('Session expires');
    await expect(chrome.locator('[data-life="idle"]')).toContainText('Idle timeout');
    await expect(chrome.locator('[data-life="remembered"]')).toContainText('Device remembered until');
    const phone = devices.locator('.device', { hasText: 'Example Shop app on iOS 18.1' });
    await expect(phone.locator('[data-life="session"]')).toContainText('Session expires');
    await expect(phone.locator('[data-life="idle"]')).toHaveCount(0);
    await expect(phone.locator('[data-life="remembered"]')).toContainText('Device remembered until');
  });

  test('a session that ended but left offline access shows as a signed-out device, named by its user agent', async ({ page }) => {
    await openRecord(page);
    const gone = card(page, 'Signed-in devices').locator('.device', { hasText: 'Firefox 131 on Windows 10 / 11' });
    await expect(gone).toContainText('Signed out');
    await expect(gone).toContainText('Offline access issued on it is still valid');
    await expect(gone).toContainText('storefront-web');
    await expect(gone.locator('[data-life="remembered"]')).toContainText('Device remembered until');
    await expect(gone.locator('[data-life="session"]')).toHaveCount(0);
    await expect(gone.getByRole('button', { name: /^Sign out/ })).toHaveCount(0);
  });

  test('a signed-out device that holds nothing is still listed by name, remembered until a date', async ({ page }) => {
    await openRecord(page);
    const ipad = card(page, 'Signed-in devices').locator('.device', { hasText: 'Safari 17 on iOS 17.5' });
    await expect(ipad).toContainText('Signed out');
    await expect(ipad).toContainText('iOS');
    await expect(ipad.locator('[data-life="remembered"]')).toContainText('Device remembered until');
    await expect(ipad.locator('.device-clients')).toHaveCount(0);
    await expect(ipad).not.toContainText('Offline access');
  });

  test('a token whose session no device names still shows as an unnamed signed-out device', async ({ page }) => {
    const orphan = { ...dana, refreshTokens: [...dana.refreshTokens, {
      sessionId: 'sess-unknown', clientId: 'admin-console', scope: ['openid', 'offline_access'], issuedAt: dana.refreshTokens[0].issuedAt, expiresAt: dana.refreshTokens[0].expiresAt, dpopBound: false,
    }] };
    await openRecord(page, { state: { ...state, users: [orphan, quiet] } });
    const unnamed = card(page, 'Signed-in devices').locator('.device', { hasText: 'Signed-out device' });
    await expect(unnamed).toHaveCount(1);
    await expect(unnamed).toContainText('admin-console');
    await expect(unnamed.getByRole('button', { name: /^Forget device/ })).toHaveCount(0);
  });

  test('every device table shares one column grid', async ({ page }) => {
    await openRecord(page);
    const lefts = await card(page, 'Signed-in devices').locator('.device-clients').evaluateAll(tables =>
      tables.map(table => [...table.querySelectorAll('.dc.h > span')].map(cell => Math.round(cell.getBoundingClientRect().left))));
    expect(lefts.length).toBe(3);
    for (const row of lefts) expect(row).toEqual(lefts[0]);
  });

  test('the arrow moves to Apps with access', async ({ page }) => {
    await openRecord(page);
    await card(page, 'Signed-in devices').getByRole('button', { name: 'Go to Apps with access' }).first().click();
    await expect(card(page, 'Apps with access')).toBeFocused();
  });

  test('Sign out device ends that session only', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: /^Sign out device Chrome 130/ }).click();
    const request = await findRequest(api.requests, 'DELETE', '/users/sessions');
    expect(request.searchParams).toEqual({ id: danaId, sessionId: chromeSessionId });
    // The user agent is still remembered: the device stays listed, signed out, by name.
    const chrome = card(page, 'Signed-in devices').locator('.device', { hasText: 'Chrome 130 on macOS 14.6' });
    await expect(chrome).toContainText('Signed out');
    await expect(chrome.getByRole('button', { name: /^Sign out device/ })).toHaveCount(0);
    await expect(chrome.getByRole('button', { name: /^Forget device/ })).toBeVisible();
    await expect(card(page, 'Signed-in devices').locator('.device', { hasText: 'Example Shop app on iOS 18.1' })).toContainText('Signed in');
  });

  test('Sign out everywhere asks first, then ends every session', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Sign out everywhere…' }).click();
    await expect(confirmDialog(page)).toContainText('Dana Reyes');
    await expect(confirmDialog(page)).toContainText('offline access');
    await confirmDialog(page).getByRole('button', { name: 'Cancel' }).click();
    expect(api.requests.filter(request => request.method === 'DELETE')).toHaveLength(0);

    await page.getByRole('button', { name: 'Sign out everywhere…' }).click();
    await confirmDialog(page).getByRole('button', { name: 'Sign out everywhere' }).click();
    expect((await findRequest(api.requests, 'DELETE', '/users/sessions')).searchParams).toEqual({ userId: danaId });
    // Every device stays remembered, none is signed in, and the button has nothing left to do.
    await expect(card(page, 'Signed-in devices').locator('.chip.ok')).toHaveCount(0);
    await expect(card(page, 'Signed-in devices').locator('.device')).toHaveCount(4);
    await expect(page.getByRole('button', { name: 'Sign out everywhere…' })).toBeDisabled();
    await expect(card(page, 'Apps with access')).toContainText('No app holds offline access');
  });

  test('Refresh reloads sessions and offline access', async ({ page }) => {
    const api = await openRecord(page);
    const devicesLoaded = () => api.requests.filter(request => request.method === 'GET' && request.pathname === '/users/user-agents').length;
    const before = devicesLoaded();
    await card(page, 'Signed-in devices').getByRole('button', { name: 'Refresh' }).click();
    await expect.poll(devicesLoaded).toBe(before + 1);
  });

  test('a user with no session sees the empty line and cannot sign out everywhere', async ({ page }) => {
    await openRecord(page, {}, recordPath(quiet.id));
    await expect(card(page, 'Signed-in devices')).toContainText('Not signed in on any device.');
    await expect(page.getByRole('button', { name: 'Sign out everywhere…' })).toBeDisabled();
  });

  test('Forget device asks first, then removes that device, its session and its offline access', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: /^Forget device Chrome 130/ }).click();
    await expect(confirmDialog(page)).toContainText('Chrome 130 on macOS 14.6');
    await expect(confirmDialog(page)).toContainText('Dana Reyes');
    await confirmDialog(page).getByRole('button', { name: 'Cancel' }).click();
    expect(api.requests.filter(request => request.method === 'DELETE')).toHaveLength(0);

    await page.getByRole('button', { name: /^Forget device Chrome 130/ }).click();
    await confirmDialog(page).getByRole('button', { name: 'Forget', exact: true }).click();
    const request = await findRequest(api.requests, 'DELETE', '/users/user-agents');
    expect(request.searchParams).toEqual({ id: danaId, userAgentId: chromeAgentId });
    await expect(card(page, 'Signed-in devices')).not.toContainText('Chrome 130 on macOS 14.6');
    await expect(card(page, 'Signed-in devices')).toContainText('Example Shop app on iOS 18.1');
    // The token issued on that session is revoked with the device; the one on the phone stays.
    await expect(card(page, 'Apps with access').locator('.app[data-client="storefront-web"]')).toHaveCount(1);
    await expect(card(page, 'Apps with access').locator('.app[data-client="shop-mobile"]')).toHaveCount(1);
  });

  test('forgetting a signed-out device removes it and the offline access issued on it', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: /^Forget device Firefox 131/ }).click();
    await confirmDialog(page).getByRole('button', { name: 'Forget', exact: true }).click();
    expect((await findRequest(api.requests, 'DELETE', '/users/user-agents')).searchParams).toEqual({ id: danaId, userAgentId: firefoxAgentId });
    await expect(card(page, 'Signed-in devices')).not.toContainText('Firefox 131');
    await expect(card(page, 'Apps with access').locator('.app[data-client]')).toHaveCount(2);
  });

  test('a failing Forget device shows the error and keeps the device', async ({ page }) => {
    await openRecord(page);
    await page.route('**/users/user-agents?id=*', route => route.request().method() === 'DELETE'
      ? route.fulfill({ status: 404, body: 'User agent not found' })
      : route.fallback());
    await page.getByRole('button', { name: /^Forget device Safari 17/ }).click();
    await confirmDialog(page).getByRole('button', { name: 'Forget', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('User agent not found');
    await expect(card(page, 'Signed-in devices')).toContainText('Safari 17 on iOS 17.5');
  });

  test('a failing sign-out shows the error and keeps the device', async ({ page }) => {
    await openRecord(page);
    await page.route('**/users/sessions?id=*', route => route.fulfill({ status: 404, body: 'Session not found' }));
    await page.getByRole('button', { name: /^Sign out device Chrome 130/ }).click();
    await expect(page.getByRole('alert')).toContainText('Session not found');
    await expect(card(page, 'Signed-in devices')).toContainText('Chrome 130 on macOS 14.6');
  });
});

test.describe('apps with offline access', () => {
  test('one row per refresh token with scopes, dates, the DPoP chip and the device', async ({ page }) => {
    await openRecord(page);
    const apps = card(page, 'Apps with access');
    await expect(apps.locator('.app[data-client]')).toHaveCount(3);
    const mobile = apps.locator('.app[data-client="shop-mobile"]');
    await expect(mobile).toContainText('Shop mobile');
    await expect(mobile.locator('.tag')).toHaveText(['openid', 'orders', 'offline_access']);
    await expect(mobile).toContainText('DPoP-bound');
    await expect(mobile).toContainText('Example Shop app on iOS 18.1');
    const gone = apps.locator('.app[data-client="storefront-web"]').last();
    await expect(gone).toContainText('Signed out');
    await expect(gone).toContainText('Firefox 131 on Windows 10 / 11');
    await expect(apps.locator('.app[data-client="storefront-web"]').first()).not.toContainText('DPoP');
  });

  test('uses the words offline access, never long-lived', async ({ page }) => {
    await openRecord(page);
    await expect(page.getByText(/long-lived/i)).toHaveCount(0);
    await expect(card(page, 'Apps with access')).toContainText('offline access');
  });

  test('Revoke asks, then deletes that client\'s refresh tokens and leaves the sessions', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Revoke offline access of Shop mobile' }).click();
    await expect(confirmDialog(page)).toContainText('Shop mobile');
    await expect(confirmDialog(page)).toContainText('stays signed in');
    await confirmDialog(page).getByRole('button', { name: 'Revoke' }).click();

    const request = await findRequest(api.requests, 'DELETE', '/users/refresh-tokens');
    expect(request.searchParams).toEqual({ id: danaId, clientId: 'shop-mobile' });
    await expect(card(page, 'Apps with access').locator('.app[data-client="shop-mobile"]')).toHaveCount(0);
    await expect(card(page, 'Signed-in devices')).toContainText('Example Shop app on iOS 18.1');
    await expect(card(page, 'Signed-in devices').locator('.device', { hasText: 'Example Shop app' })).not.toContainText('Offline access');
  });

  test('cancelling the revoke sends nothing', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Revoke offline access of Shop mobile' }).click();
    await confirmDialog(page).getByRole('button', { name: 'Cancel' }).click();
    expect(api.requests.filter(request => request.method === 'DELETE')).toHaveLength(0);
  });

  test('empty and failing states', async ({ page }) => {
    await openRecord(page, {}, recordPath(quiet.id));
    await expect(card(page, 'Apps with access')).toContainText('No app holds offline access for this user.');

    await loadAdminApp(page, { path: usersPath, state });
    await page.route('**/users/refresh-tokens?*', route => route.fulfill({ status: 500, body: 'tokens are down' }));
    await page.goto(recordPath());
    await expect(card(page, 'Apps with access')).toContainText('tokens are down');
    await expect(card(page, 'Signed-in devices')).toContainText('Chrome 130 on macOS 14.6');
  });

  test('the revoked token of a signed-out device leaves it listed, with nothing issued on it', async ({ page }) => {
    await openRecord(page);
    const firefox = card(page, 'Signed-in devices').locator('.device', { hasText: 'Firefox 131 on Windows 10 / 11' });
    await expect(firefox.locator('.device-clients')).toHaveCount(1);
    await page.locator('.app[data-client="storefront-web"]').last().getByRole('button', { name: /^Revoke/ }).click();
    await confirmDialog(page).getByRole('button', { name: 'Revoke' }).click();
    // Revoking by client clears both storefront-web tokens, including the one on the live Chrome session.
    await expect(firefox).toBeVisible();
    await expect(firefox.locator('.device-clients')).toHaveCount(0);
    await expect(firefox).not.toContainText('Offline access');
  });
});

test.describe('passkeys', () => {
  test('lists passkeys with their properties', async ({ page }) => {
    await openRecord(page);
    const passkeys = card(page, 'Passkeys');
    await expect(passkeys).toContainText('iCloud Keychain');
    await expect(passkeys).toContainText('Multi-device');
    await expect(passkeys).toContainText('Synced');
    await expect(passkeys).toContainText('Unnamed passkey');
    await expect(passkeys).toContainText('Not synced');
    await expect(passkeys).toContainText('Last used never');
  });

  test('renames through the prompt', async ({ page }) => {
    const api = await openRecord(page);
    page.on('dialog', dialog => dialog.accept('Work laptop'));
    await page.getByRole('button', { name: 'Rename passkey iCloud Keychain' }).click();
    expect((await findRequest(api.requests, 'PATCH', '/users/passkeys')).body).toEqual({ userId: danaId, credentialId: 'cred-icloud', name: 'Work laptop' });
    await expect(card(page, 'Passkeys')).toContainText('Work laptop');
  });

  test('deletes after a confirmation', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Delete passkey iCloud Keychain' }).click();
    await confirmDialog(page).getByRole('button', { name: 'Delete' }).click();
    const request = await findRequest(api.requests, 'DELETE', '/users/passkeys');
    expect(request.searchParams).toEqual({ id: danaId, credentialId: 'cred-icloud' });
    await expect(card(page, 'Passkeys')).not.toContainText('iCloud Keychain');
  });

  test('none registered', async ({ page }) => {
    await openRecord(page, {}, recordPath(quiet.id));
    await expect(card(page, 'Passkeys')).toContainText('No passkeys registered.');
  });
});

test.describe('security actions', () => {
  test('Reset limits posts the tenant and identifiers and confirms', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Reset limits' }).click();
    expect((await findRequest(api.requests, 'POST', '/users/limits/reset')).body).toEqual({
      userId: danaId, tenantId: tenant, email: dana.email, phone: dana.phone,
    });
    await expect(card(page, 'Security actions').getByRole('status')).toHaveText('Limits reset.');
  });

  test('Reset password offers show outside production and displays the password once', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Reset password…' }).click();
    await page.getByLabel('Show — display it here').check();
    await page.getByRole('button', { name: 'Confirm' }).click();
    expect((await findRequest(api.requests, 'POST', '/users/password/reset')).body).toEqual({
      userId: danaId, channel: 'show', expiresInSeconds: 12 * 3600,
    });
    await expect(page.locator('.password-value')).toHaveText('Temp1234!');
    await page.getByRole('button', { name: 'Close' }).click();
    await expect(page.locator('.password-value')).toHaveCount(0);
  });

  test('Reset password sends the lifetime in seconds through email by default', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Reset password…' }).click();
    await page.getByLabel('Temporary password lifetime (hours)').fill('6');
    await page.getByRole('button', { name: 'Confirm' }).click();
    expect((await findRequest(api.requests, 'POST', '/users/password/reset')).body).toEqual({
      userId: danaId, channel: 'email', expiresInSeconds: 6 * 3600,
    });
    await expect(card(page, 'Security actions').getByRole('status')).toContainText('sent');
  });

  test('Reset password hides show in production and can be cancelled with Escape', async ({ page }) => {
    const api = await openRecord(page, { state: { ...state, myPermissions: { ...defaultMyPermissions, isProd: true } } });
    await page.getByRole('button', { name: 'Reset password…' }).click();
    await expect(page.getByLabel('Show — display it here')).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    expect(api.requests.filter(request => request.pathname === '/users/password/reset')).toHaveLength(0);
  });
});

test.describe('delete user', () => {
  test('names the user and the consequences, then deletes and returns to the search', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Delete user Dana Reyes' }).click();
    const dialog = confirmDialog(page);
    await expect(dialog).toContainText('Dana Reyes');
    await expect(dialog).toContainText('sessions end');
    await expect(dialog).toContainText('offline access is revoked');
    await expect(dialog).toContainText('passkeys are gone');
    await expect(dialog).toContainText('back-channel logout');
    await dialog.getByRole('button', { name: 'Delete', exact: true }).click();

    expect((await findRequest(api.requests, 'DELETE', '/users')).searchParams).toEqual({ id: danaId });
    await expect(page.getByRole('heading', { name: 'Search for a user' })).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: 'Deleted Dana Reyes.' })).toBeVisible();
    await expect(page).not.toHaveURL(/id=/);
  });

  test('cancelling deletes nothing', async ({ page }) => {
    const api = await openRecord(page);
    await page.getByRole('button', { name: 'Delete user Dana Reyes' }).click();
    await confirmDialog(page).getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
    expect(api.requests.filter(request => request.method === 'DELETE')).toHaveLength(0);
  });

  test('a deleted user drops out of the results', async ({ page }) => {
    await loadAdminApp(page, { path: usersPath, state });
    await search(page, 'dana');
    await page.getByRole('button', { name: /^Open user Dana Reyes/ }).click();
    await page.getByRole('button', { name: 'Delete user Dana Reyes' }).click();
    await confirmDialog(page).getByRole('button', { name: 'Delete', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: 'Dana Reyes' })).toHaveCount(0);
  });

  test('a failed delete keeps the page and shows the error', async ({ page }) => {
    await openRecord(page);
    await page.route('**/users?id=*', async route => {
      if (route.request().method() === 'DELETE') await route.fulfill({ status: 500, body: 'auth is down' });
      else await route.fallback();
    });
    await page.getByRole('button', { name: 'Delete user Dana Reyes' }).click();
    await confirmDialog(page).getByRole('button', { name: 'Delete', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('auth is down');
    await expect(page.getByRole('heading', { level: 1, name: 'Dana Reyes' })).toBeVisible();
  });
});

test('the offline access ID and device IDs are never broken mid-word', async ({ page }) => {
  await openRecord(page);
  const broken = await page.locator('versola-user-record').evaluate(host => {
    const ids = [...(host.shadowRoot?.querySelectorAll<HTMLElement>('.row-id') ?? [])];
    return ids.filter(id => {
      const range = document.createRange();
      range.selectNodeContents(id);
      return range.getClientRects().length > 1;
    }).length;
  });
  expect(broken).toBe(0);
});
