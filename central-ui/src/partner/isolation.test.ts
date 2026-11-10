import { describe, expect, it } from 'vitest';

/**
 * A partner must never receive a bundle that contains operator screens or the admin API client
 * (#549 decision 4): nothing under src/partner may import them. Shared components and utils are
 * fine; the operator's views and `central-api.ts` are not. This is the early signal, on the
 * sources; the guarantee is on what ships and is checked on the build output by
 * `scripts/check-bundles.mjs` (`npm run check:bundles`, a CI step after `npm run build`).
 */
const FORBIDDEN = [
  'central-api', 'admin-app', 'navigation', 'tenant-selector', 'clients-list', 'client-form', 'client-kind-step', 'resources-list', 'resource-record',
  'edges-list', 'partners-list', 'partner-wizard', 'partner-approvals', 'users-list', 'user-record', 'user-activity', 'forms-list', 'form-record',
  'themes-list', 'theme-record', 'security-settings', 'system-settings', 'delivery-settings', 'keys-metadata', 'otp-templates-list',
  'authorization-details-list', 'scopes-list', 'permissions-list', 'roles-list', 'tenants-list', 'users-api', 'demo/seed', 'demo/main', 'demo/install',
];

const sources = import.meta.glob<string>('./**/*.ts', { query: '?raw', import: 'default', eager: true });

describe('src/partner', () => {
  const files = Object.entries(sources).filter(([path]) => !path.endsWith('.test.ts'));

  it('has files to check', () => {
    expect(files.length).toBeGreaterThan(5);
  });

  for (const [path, source] of files) {
    it(`${path} imports no operator view and no central-api`, () => {
      const imports = [...source.matchAll(/from\s+'([^']+)'|import\(\s*'([^']+)'\s*\)/g)].map(match => match[1] ?? match[2]);
      const offending = imports.filter(specifier => FORBIDDEN.some(name => specifier.endsWith(`/${name}`) || specifier.endsWith(`/${name}.ts`) || specifier.endsWith(`/${name}.js`)));
      expect(offending).toEqual([]);
    });
  }
});
