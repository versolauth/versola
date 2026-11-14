import { expect } from '@playwright/test';
import type { PartnerRequestLog } from '../../src/partner/mock-backend';

/**
 * What the partner console may send (the contract of `edge/open-api/partner-api.yaml`, as `partner-api.ts` types it): one
 * row per write, with the only fields its body may carry. A partner has no way to name a group, a group kind, an owner, an
 * edge binding or a party: the edge fixes all of them from the certificate that names the partner. A write the table does
 * not list, a field it does not allow, or a forbidden key anywhere in a body fails the spec that sent it.
 *
 * `loadPartnerApp` records every request of the page and `tests/partner-console/fixtures.ts` checks them after each test, so
 * every spec of the partner console enforces this, not only the one that is about it.
 */
const CLIENT = ['id', 'kind', 'clientName', 'logoUri', 'policyUri', 'tosUri', 'redirectUris', 'scope', 'accessTokenTtl', 'refreshTokenTtl', 'backChannelLogoutUri', 'authMethod'];

/** `null` is a write with no body. */
export const WRITE_CONTRACT: Array<{ method: 'POST' | 'PUT' | 'DELETE'; path: RegExp; fields: string[] | null }> = [
  { method: 'POST', path: /^\/clients$/, fields: CLIENT },
  { method: 'PUT', path: /^\/clients\/[^/]+$/, fields: CLIENT },
  { method: 'POST', path: /^\/clients\/[^/]+\/withdraw$/, fields: null },
  { method: 'DELETE', path: /^\/clients\/[^/]+$/, fields: null },
  { method: 'POST', path: /^\/resources$/, fields: ['resourceId', 'resource', 'audience', 'endpoints'] },
  { method: 'PUT', path: /^\/resources\/[^/]+$/, fields: ['resourceId', 'resource', 'audience', 'endpoints'] },
  { method: 'POST', path: /^\/resources\/[^/]+\/rotate-secret$/, fields: null },
  { method: 'DELETE', path: /^\/resources\/[^/]+$/, fields: null },
  { method: 'POST', path: /^\/edges$/, fields: ['id', 'publicUrl'] },
  { method: 'DELETE', path: /^\/edges\/[^/]+$/, fields: null },
  { method: 'POST', path: /^\/edges\/[^/]+\/enrollment-tokens$/, fields: null },
  { method: 'DELETE', path: /^\/edges\/[^/]+\/enrollment-tokens$/, fields: null },
  { method: 'POST', path: /^\/revocations$/, fields: ['by', 'value'] },
  { method: 'POST', path: /^\/staff$/, fields: ['identifier', 'level', 'expiresInDays'] },
  { method: 'PUT', path: /^\/staff\/[^/]+$/, fields: ['level'] },
  { method: 'DELETE', path: /^\/staff\/[^/]+$/, fields: null },
  { method: 'PUT', path: /^\/settings$/, fields: ['contact', 'defaults'] },
  { method: 'PUT', path: /^\/profile$/, fields: ['displayName', 'logoUri'] },
];

/** Keys that name who owns something or where it is served: the edge decides them, so no body of the partner console carries one, at any depth. */
export const FORBIDDEN_KEYS = /^(groupId|group_id|groupKind|group_kind|group|party|first_party|firstParty|partnerId|partner_id|partner|owner|ownerId|ownership|edgeId|edge_id|binding|edgeBinding)$/i;

/** What no control, option, label or copy of the partner console may say. The operator's Owner step and Edges Group and Kind columns are named here. */
export const FORBIDDEN_TEXT = [/first[\s_-]?party/i, /group[\s_-]?kind/i, /who owns the client/i, /decides consent, limits and which edges serve the client/i];

const valuesOf = (value: unknown): string[] => {
  if (Array.isArray(value)) return value.flatMap(valuesOf);
  if (value && typeof value === 'object') return Object.values(value).flatMap(valuesOf);
  return typeof value === 'string' ? [value] : [];
};

const keysOf = (value: unknown, path = ''): string[] => {
  if (Array.isArray(value)) return value.flatMap((item, index) => keysOf(item, `${path}[${index}]`));
  if (value && typeof value === 'object') return Object.entries(value).flatMap(([key, inner]) => [`${path}.${key}`, ...keysOf(inner, `${path}.${key}`)]);
  return [];
};

export function expectWritesWithinContract(requests: PartnerRequestLog[]) {
  for (const request of requests.filter(candidate => candidate.method !== 'GET')) {
    const row = WRITE_CONTRACT.find(candidate => candidate.method === request.method && candidate.path.test(request.pathname));
    expect(row, `${request.method} ${request.pathname} is not a write of the partner contract`).toBeDefined();
    if (row!.fields === null) {
      expect(Object.keys((request.body ?? {}) as object), `${request.method} ${request.pathname} takes no body`).toEqual([]);
      continue;
    }
    const body = request.body as Record<string, unknown>;
    expect(Object.keys(body).filter(key => !row!.fields!.includes(key)), `${request.method} ${request.pathname}: fields outside the contract`).toEqual([]);
    expect(keysOf(body).filter(key => FORBIDDEN_KEYS.test(key.split('.').pop()!.replace(/\[\d+\]$/, ''))), `${request.method} ${request.pathname}: a key that says who owns or serves`).toEqual([]);
    expect(valuesOf(body).filter(value => FORBIDDEN_TEXT.some(pattern => pattern.test(value))), `${request.method} ${request.pathname}: a value that says first-party`).toEqual([]);
  }
}
