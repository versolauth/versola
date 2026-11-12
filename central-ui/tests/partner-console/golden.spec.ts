import { expect, test } from '@playwright/test';
import audit from './golden/audit.json' with { type: 'json' };
import clientCreated from './golden/client-created.json' with { type: 'json' };
import clients from './golden/clients.json' with { type: 'json' };
import edgeCreated from './golden/edge-created.json' with { type: 'json' };
import enrollmentToken from './golden/enrollment-token.json' with { type: 'json' };
import edges from './golden/edges.json' with { type: 'json' };
import errorInsufficient from './golden/error-insufficient-level.json' with { type: 'json' };
import errorNotFound from './golden/error-not-found.json' with { type: 'json' };
import me from './golden/me.json' with { type: 'json' };
import resources from './golden/resources.json' with { type: 'json' };
import settings from './golden/settings.json' with { type: 'json' };
import staff from './golden/staff.json' with { type: 'json' };
import { loadPartnerApp } from './fixtures';
import { PARTNER_NOW, asLevel, partnerSeedState } from './partner-fixtures';

/**
 * The mock cannot drift from the real edge (#963): what it answers has the shape of the golden responses in `golden/` (the edge's contract,
 * `partner-api.yaml`, and, once captured, a real run: see the README there). Every key the mock sends is in the golden file with a
 * compatible type, and every key the golden file always has is in the mock's answer unless it is listed as optional here.
 */
test.use({ locale: 'en-US', timezoneId: 'UTC' });

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(PARTNER_NOW) });
});

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

const typeOf = (value: Json) => (value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value);

/** The exemplar of a list: the union of its elements' keys, and the keys all of them have. */
function exemplarOf(list: Json[]): { union: Record<string, Json>; always: Set<string> } | null {
  const objects = list.filter((item): item is { [key: string]: Json } => typeOf(item) === 'object');
  if (objects.length === 0) return null;
  const union: Record<string, Json> = {};
  for (const object of objects) {
    for (const [key, value] of Object.entries(object)) {
      const had = union[key];
      // Objects found under the same key in several exemplars are merged, so a key only some of them have is still one the edge sends.
      union[key] = !(key in union) || had === null ? value : typeOf(had) === 'object' && typeOf(value) === 'object' ? { ...(had as object), ...(value as object) } as Json : had;
    }
  }
  const always = new Set(Object.keys(union).filter(key => objects.every(object => key in object)));
  return { union, always };
}

function conforms(at: string, actual: Json, golden: Json, optional: string[], problems: string[]) {
  if (golden === null || actual === null) return; // nullable in the contract: a null stands for any type
  if (typeOf(actual) !== typeOf(golden)) {
    problems.push(`${at}: the mock sends ${typeOf(actual)}, the edge ${typeOf(golden)}`);
    return;
  }
  if (Array.isArray(actual) && Array.isArray(golden)) {
    const exemplar = exemplarOf(golden);
    if (!exemplar) return;
    actual.forEach(item => conformsObject(`${at}[]`, item, exemplar.union, exemplar.always, optional, problems));
    return;
  }
  if (typeOf(actual) === 'object') {
    const goldenObject = golden as { [key: string]: Json };
    // `{}` in a golden file is a free-form object (the operator's policies): nothing to compare.
    if (Object.keys(goldenObject).length === 0) return;
    conformsObject(at, actual, goldenObject, new Set(Object.keys(goldenObject)), optional, problems);
  }
}

function conformsObject(at: string, actual: Json, golden: Record<string, Json>, always: Set<string>, optional: string[], problems: string[]) {
  if (typeOf(actual) !== 'object') {
    problems.push(`${at}: the mock sends ${typeOf(actual)}, the edge an object`);
    return;
  }
  const object = actual as { [key: string]: Json };
  for (const key of Object.keys(object)) if (!(key in golden)) problems.push(`${at}.${key}: the mock invents a field the edge does not send`);
  for (const key of always) if (!(key in object) && !optional.includes(`${at}.${key}`)) problems.push(`${at}.${key}: the edge always sends it and the mock does not`);
  for (const [key, value] of Object.entries(object)) if (key in golden) conforms(`${at}.${key}`, value, golden[key], optional, problems);
}

async function get(page: import('@playwright/test').Page, path: string) {
  return page.evaluate(async route => {
    const response = await fetch(`/partner/api${route}`, { headers: { Accept: 'application/json' } });
    return { status: response.status, body: await response.json() as Json };
  }, path);
}

test('what the mock answers to the reads has the shape of the edge\'s answers', async ({ page }) => {
  await loadPartnerApp(page, { path: '/partner.html?view=audit' });
  const problems: string[] = [];
  const check = async (route: string, golden: Json, optional: string[] = []) => {
    const answer = await get(page, route);
    expect(answer.status, route).toBe(200);
    conforms(route, answer.body, golden, optional, problems);
  };
  await check('/me', me as Json);
  await check('/clients', clients as Json, ['/clients.clients[].consentFlow', '/clients.clients[].policyUri', '/clients.clients[].tosUri', '/clients.clients[].logoUri', '/clients.clients[].approval.note', '/clients.clients[].approval.requestedAt']);
  await check('/resources', resources as Json, ['/resources.resources[].endpoints[].stepUpCondition', '/resources.resources[].endpoints[].stepUpAcr', '/resources.resources[].endpoints[].maxAge', '/resources.resources[].endpoints[].rateLimit']);
  await check('/edges', edges as Json, ['/edges.edges[].certificateNotAfter', '/edges.edges[].lastRenewedAt']);
  await check('/audit', audit as Json, ['/audit.events[].from', '/audit.events[].actor.byOperator']);
  await check('/staff', staff as Json);
  await check('/settings', settings as Json);
  expect(problems).toEqual([]);
});

test('a created client and a created edge have the edge\'s shape', async ({ page }) => {
  await loadPartnerApp(page, { path: '/partner.html?view=audit', state: { edges: partnerSeedState.edges.slice(0, 2) } });
  const problems: string[] = [];
  const created = await page.evaluate(async () => {
    const post = async (route: string, body: unknown) => {
      const response = await fetch(`/partner/api${route}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      return { status: response.status, body: await response.json() as Json };
    };
    return {
      client: await post('/clients', { id: 'pilot-pay-new', kind: 'web', clientName: 'New', redirectUris: ['https://pay.pilotpay.example/cb'], scope: ['openid'], accessTokenTtl: 300, refreshTokenTtl: 86400 }),
      edge: await post('/edges', { id: 'edge-pilot-new', publicUrl: 'https://login-new.pilotpay.example' }),
      token: await post('/edges/edge-pilot-new/enrollment-tokens', {}),
    };
  });
  expect(created.client.status).toBe(202);
  expect(created.edge.status).toBe(201);
  expect(created.token.status).toBe(201);
  conforms('POST /clients', created.client.body, clientCreated as Json, ['POST /clients.consentFlow', 'POST /clients.policyUri', 'POST /clients.tosUri', 'POST /clients.logoUri'], problems);
  conforms('POST /edges', created.edge.body, edgeCreated as Json, ['POST /edges.edge.certificateNotAfter', 'POST /edges.edge.lastRenewedAt'], problems);
  conforms('POST /edges/{id}/enrollment-tokens', created.token.body, enrollmentToken as Json, [], problems);
  expect(problems).toEqual([]);
});

test('the mock refuses with the edge\'s error bodies', async ({ page }) => {
  await loadPartnerApp(page, { path: '/partner.html?view=audit', state: asLevel('viewer') });
  const problems: string[] = [];
  const forbidden = await page.evaluate(async () => {
    const response = await fetch('/partner/api/clients/x', { method: 'DELETE' });
    return { status: response.status, body: await response.json() as Json };
  });
  expect(forbidden.status).toBe(403);
  conforms('DELETE /clients/x', forbidden.body, errorInsufficient as Json, [], problems);
  await loadPartnerApp(page, { path: '/partner.html?view=audit' });
  const missing = await page.evaluate(async () => {
    const response = await fetch('/partner/api/clients/x/withdraw', { method: 'POST' });
    return { status: response.status, body: await response.json() as Json };
  });
  expect(missing.status).toBe(404);
  conforms('POST /clients/x/withdraw', missing.body, errorNotFound as Json, [], problems);
  expect(problems).toEqual([]);
});
