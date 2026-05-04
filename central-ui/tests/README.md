# Central UI Tests

## Setup

### 1. Install Playwright

First, you need to install Playwright as a dev dependency:

```bash
cd central-ui
npm install --save-dev @playwright/test
```

### 2. Install Playwright Browsers

Install the browsers that Playwright will use for testing:

```bash
npx playwright install
```

This will download Chromium, Firefox, and WebKit browsers.

---

## Running Tests

### Run All Tests

```bash
cd central-ui
npm run test:ui
```

This will:
1. Start the dev server automatically (`vite dev`)
2. Run all tests in headless mode
3. Stop the server after tests complete

### Run Tests in Headed Mode (See Browser)

```bash
npm run test:ui:headed
```

This opens a browser window so you can watch the tests execute.

### Run Specific Test File

```bash
npx playwright test authorization-presets
```

### Run Single Test

```bash
npx playwright test authorization-presets -g "loads presets when client is expanded"
```

### Debug Mode

```bash
npx playwright test --debug
```

This opens the Playwright Inspector for step-by-step debugging.

---

## Test Structure

```
central-ui/
├── tests/
│   ├── fixtures.ts              # Shared test fixtures
│   ├── mocks.ts                 # API mocking harness
│   ├── app-shell.spec.ts        # App shell tests
│   ├── tenants.spec.ts          # Tenant management tests
│   ├── clients.spec.ts          # Client tests
│   ├── authorization-presets.spec.ts  # ⭐ Authorization preset tests
│   ├── scopes.spec.ts           # Scope tests
│   ├── permissions.spec.ts      # Permission tests
│   ├── resources.spec.ts        # Resource tests
│   ├── roles.spec.ts            # Role tests
│   └── edges.spec.ts            # Edge tests
└── playwright.config.ts         # Playwright configuration
```

---

## Test Files

### Authorization Presets Tests

**File:** `tests/authorization-presets.spec.ts`

**Test Cases (9):**
1. ✅ Loads presets when client is expanded
2. ✅ Caches presets and does not reload on subsequent expands
3. ✅ Displays loaded presets in the expanded client card
4. ✅ Expands individual preset to show full details
5. ✅ Adds a new preset with auto-generated UUID
6. ✅ Edits an existing preset
7. ✅ Deletes a preset
8. ✅ Validates preset form - redirect URI must be in client allowed URIs
9. ✅ Handles backend validation errors gracefully

---

## Writing Tests

### Basic Test Structure

```typescript
import { expect, test } from '@playwright/test';
import { loadAdminApp } from './fixtures';

test('my test', async ({ page }) => {
  // Setup: Load app with mock data
  const api = await loadAdminApp(page, {
    path: '/?view=clients&tenant=tenant-alpha',
    state: { 
      clients: { 'tenant-alpha': [/* ... */] }
    },
  });

  // Action: Interact with the page
  await page.getByRole('button', { name: 'Click me' }).click();

  // Assertion: Verify expected behavior
  await expect(page.getByText('Success')).toBeVisible();
  
  // Verify API calls
  expect(api.requests.some(req => 
    req.pathname === '/v1/configuration/clients'
  )).toBeTruthy();
});
```

### Using Mock API

The test harness automatically mocks all `/v1/configuration/**` endpoints.

**Mock State:**
```typescript
type MockConfigState = {
  tenants: TenantDto[];
  clients: Record<string, ClientDto[]>;
  scopes: Record<string, ScopeDto[]>;
  permissions: Record<string, PermissionDto[]>;
  resources: Record<string, ResourceDto[]>;
  roles: Record<string, RoleDto[]>;
  edges: EdgeDto[];
  authorizationPresets: Record<string, AuthorizationPresetDto[]>;
};
```

**Example:**
```typescript
await loadAdminApp(page, {
  state: {
    authorizationPresets: {
      'my-client': [
        {
          id: 'preset-1',
          clientId: 'my-client',
          name: 'Login',
          redirectUri: 'https://example.com/callback',
          scope: ['openid'],
          responseType: 'code',
        },
      ],
    },
  },
});
```

---

## CI/CD Integration

### GitHub Actions Example

```yaml
- name: Install dependencies
  run: cd central-ui && npm ci

- name: Install Playwright Browsers
  run: cd central-ui && npx playwright install --with-deps

- name: Run Playwright tests
  run: cd central-ui && npm run test:ui
```

---

## Troubleshooting

### Port Already in Use

If you get "port already in use" error:
```bash
# Kill process using port 4173
lsof -ti:4173 | xargs kill -9
```

### Tests Timing Out

Increase timeout in `playwright.config.ts`:
```typescript
timeout: 60_000, // 60 seconds
```

### Browser Not Found

Reinstall browsers:
```bash
npx playwright install --force
```

---

## More Information

- [Playwright Documentation](https://playwright.dev)
- [Authorization Presets Tests Details](./AUTHORIZATION_PRESETS_TESTS.md)
- [Test Specification](./PLAYWRIGHT_SPEC.md)
