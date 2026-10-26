/**
 * The rate limit policies of an edge (`PUT /configuration/edges/rate-limits`): the wire form, the
 * draft the editor works on and the pure helpers around it. The rules and messages mirror
 * `RateLimitPolicy.validate` and `validateAll` in `util/.../RateLimitPolicy.scala`: central refuses a
 * set that fails them and an edge does not apply one, so the editor says the same thing before the request.
 *
 * The set is replaced as a whole; an empty list removes it and the edge applies no rate limit. `shadow` counts what
 * would be refused; `enforce` refuses with `429` and `Retry-After`. `GET .../rate-limits/recommended` is the starting set
 * (all in `shadow`) that the editor adds with one click.
 */

export type RateLimitDimension = 'client' | 'subject' | 'session' | 'jkt' | 'ip';
export type RateLimitScope = 'edge-wide' | 'resource' | 'endpoint';
export type RateLimitMode = 'off' | 'shadow' | 'enforce';

export interface RateLimitWindow {
  limit: number;
  windowSeconds: number;
}

export interface RateLimitPolicy {
  name: string;
  by?: RateLimitDimension[];
  scope?: RateLimitScope;
  limits: RateLimitWindow[];
  burst?: number;
  cost?: number;
  mode?: RateLimitMode;
}

/** The body of the PUT and the field central returns per edge. */
export interface RateLimitPolicies {
  policies: RateLimitPolicy[];
}

export const MAX_POLICIES = 64;
export const MAX_WINDOWS = 8;
export const MAX_NAME_LENGTH = 48;
export const MAX_COUNT = 1_000_000;
export const MAX_COST = 1000;
export const MAX_WINDOW_SECONDS = 86_400;
const NAME_PATTERN = /^[a-z][a-z0-9_-]{0,47}$/;

/** What the editor offers to count by. `ip` is not offered: the edge has no address check yet. */
export const DIMENSIONS: ReadonlyArray<{ id: RateLimitDimension; label: string }> = [
  { id: 'client', label: 'Client' },
  { id: 'subject', label: 'User (subject)' },
  { id: 'session', label: 'Session' },
  { id: 'jkt', label: 'DPoP key' },
];

export const SCOPES: ReadonlyArray<{ id: RateLimitScope; label: string; text: string }> = [
  { id: 'edge-wide', label: 'Whole edge', text: 'One bucket per key for every call this edge proxies. Checked right after the access token is verified.' },
  { id: 'resource', label: 'Per resource', text: 'One bucket per key and resource. Checked after the caller’s permissions are resolved.' },
  { id: 'endpoint', label: 'Per endpoint', text: 'One bucket per key and registered endpoint (the route, not the concrete path). Checked after the caller’s permissions are resolved.' },
];

export const MODES: ReadonlyArray<{ id: RateLimitMode; label: string }> = [
  { id: 'off', label: 'Off' },
  { id: 'shadow', label: 'Shadow' },
  { id: 'enforce', label: 'Enforce' },
];

export const MODE_TEXT: Record<RateLimitMode, string> = {
  off: 'The policy is ignored.',
  shadow: 'Counts what would be refused (edge_rate_limit_decisions_total, result would_limit) and refuses nothing.',
  enforce: 'Refuses calls over the limit with 429 and Retry-After, before they reach the resource. Read would_limit in shadow first.',
};

/** A window as typed; an empty string is "not set". */
export interface WindowDraft {
  limit: string;
  windowSeconds: string;
}

export interface PolicyDraft {
  /** Identifies the block across edits and removals; never sent. */
  key: number;
  name: string;
  mode: RateLimitMode;
  by: RateLimitDimension[];
  scope: RateLimitScope;
  limits: WindowDraft[];
  burst: string;
  cost: string;
}

export interface RateLimitDraft {
  policies: PolicyDraft[];
}

let nextKey = 1;

/** A starting policy from the recommended set as the editor's draft block. */
export function draftFromPolicy(policy: RateLimitPolicy): PolicyDraft {
  return draftFromPolicies({ policies: [policy] }).policies[0];
}

export function newPolicyDraft(): PolicyDraft {
  return { key: nextKey++, name: '', mode: 'shadow', by: ['client'], scope: 'edge-wide', limits: [{ limit: '', windowSeconds: '' }], burst: '', cost: '' };
}

export function emptyDraft(): RateLimitDraft {
  return { policies: [] };
}

export function draftFromPolicies(set: RateLimitPolicies | undefined | null): RateLimitDraft {
  return {
    policies: (set?.policies ?? []).map(policy => ({
      key: nextKey++,
      name: policy.name,
      mode: policy.mode ?? 'shadow',
      by: [...(policy.by ?? [])],
      scope: policy.scope ?? 'edge-wide',
      limits: policy.limits.map(window => ({ limit: String(window.limit), windowSeconds: String(window.windowSeconds) })),
      // The server's defaults are 1: leaving the field empty means exactly that, so only a different value is shown.
      burst: policy.burst !== undefined && policy.burst !== 1 ? String(policy.burst) : '',
      cost: policy.cost !== undefined && policy.cost !== 1 ? String(policy.cost) : '',
    })),
  };
}

/** The body of the PUT: what the operator typed, with the defaults left out. */
export function policiesFromDraft(draft: RateLimitDraft): RateLimitPolicies {
  return {
    policies: draft.policies.map(policy => {
      const body: RateLimitPolicy = {
        name: policy.name.trim(),
        by: DIMENSION_ORDER.filter(dimension => policy.by.includes(dimension)),
        scope: policy.scope,
        limits: policy.limits.map(window => ({ limit: Number(window.limit.trim()), windowSeconds: Number(window.windowSeconds.trim()) })),
        mode: policy.mode,
      };
      if (policy.burst.trim() !== '') body.burst = Number(policy.burst.trim());
      if (policy.cost.trim() !== '') body.cost = Number(policy.cost.trim());
      return body;
    }),
  };
}

const DIMENSION_ORDER: RateLimitDimension[] = ['ip', 'client', 'subject', 'session', 'jkt'];

export function isEmptyPolicies(set: RateLimitPolicies | undefined | null): boolean {
  return !set || set.policies.length === 0;
}

// ── validation ──

function parseInt10(raw: string): number | null {
  return /^\d{1,9}$/.test(raw.trim()) ? Number(raw.trim()) : null;
}

/** The first message of each field that fails, keyed `<n>.name`, `<n>.limit.<j>`, `<n>.window.<j>`, `<n>.burst`, `<n>.cost`, `<n>.limits` (the block's windows as a whole) or `set`. */
export function validateDraft(draft: RateLimitDraft): Record<string, string> {
  const errors: Record<string, string> = {};
  if (draft.policies.length > MAX_POLICIES) errors.set = `at most ${MAX_POLICIES} rate limit policies`;
  const seen = new Map<string, number>();
  draft.policies.forEach((policy, n) => {
    const name = policy.name.trim();
    if (!NAME_PATTERN.test(name)) {
      errors[`${n}.name`] = `name must be 1 to ${MAX_NAME_LENGTH} characters: a lower-case letter, then lower-case letters, digits, '_' or '-'`;
    } else if (seen.has(name)) {
      errors[`${n}.name`] = 'two rate limit policies have the same name';
    } else {
      seen.set(name, n);
    }

    if (policy.limits.length === 0) errors[`${n}.limits`] = 'limits must have at least one window';
    if (policy.limits.length > MAX_WINDOWS) errors[`${n}.limits`] = `limits must have at most ${MAX_WINDOWS} windows`;
    const limits: number[] = [];
    const windows: number[] = [];
    policy.limits.forEach((window, j) => {
      const limit = parseInt10(window.limit);
      const seconds = parseInt10(window.windowSeconds);
      if (limit === null || limit < 1 || limit > MAX_COUNT) errors[`${n}.limit.${j}`] = `limit must be between 1 and ${MAX_COUNT}`;
      else limits.push(limit);
      if (seconds === null || seconds < 1 || seconds > MAX_WINDOW_SECONDS) errors[`${n}.window.${j}`] = `windowSeconds must be between 1 and ${MAX_WINDOW_SECONDS}`;
      else windows.push(seconds);
    });
    if (new Set(windows).size !== windows.length && !errors[`${n}.limits`]) errors[`${n}.limits`] = 'two limits have the same window';

    const burstRaw = policy.burst.trim();
    const burst = burstRaw === '' ? 1 : parseInt10(burstRaw);
    if (burst === null || burst < 1 || burst > MAX_COUNT) errors[`${n}.burst`] = `burst must be between 1 and ${MAX_COUNT}`;
    else if (limits.length > 0 && burst > Math.min(...limits)) errors[`${n}.burst`] = 'burst must not exceed the smallest limit';

    const costRaw = policy.cost.trim();
    const cost = costRaw === '' ? 1 : parseInt10(costRaw);
    if (cost === null || cost < 1 || cost > MAX_COST) errors[`${n}.cost`] = `cost must be between 1 and ${MAX_COST}`;
    else if (burst !== null && burst >= 1 && cost > burst) errors[`${n}.cost`] = 'cost must not exceed burst, or a request could never pass';
  });
  return errors;
}

// ── display ──

const DIMENSION_LABEL: Record<RateLimitDimension, string> = {
  ip: 'address', client: 'client', subject: 'user', session: 'session', jkt: 'DPoP key',
};
const SCOPE_LABEL: Record<RateLimitScope, string> = { 'edge-wide': '', resource: 'per resource', endpoint: 'per endpoint' };

export function windowText(window: RateLimitWindow): string {
  return `${window.limit} / ${window.windowSeconds} s`;
}

export function modeText(mode: RateLimitMode | undefined): string {
  return mode === 'off' ? 'Off' : mode === 'enforce' ? 'Enforce' : 'Shadow';
}

/** `shadow · 100 / 60 s · by client, per endpoint · burst 5` for the record card's row. */
export function summary(policy: RateLimitPolicy): string {
  const by = (policy.by ?? []).map(dimension => DIMENSION_LABEL[dimension]);
  const counted = [by.length ? `by ${by.join(', ')}` : 'all calls', SCOPE_LABEL[policy.scope ?? 'edge-wide']].filter(Boolean).join(', ');
  const parts = [modeText(policy.mode).split(' ')[0], policy.limits.map(windowText).join(' + '), counted];
  if (policy.burst && policy.burst > 1) parts.push(`burst ${policy.burst}`);
  if (policy.cost && policy.cost > 1) parts.push(`cost ${policy.cost}`);
  return parts.join(' · ');
}

export function countText(set: RateLimitPolicies | undefined | null): string {
  const n = set?.policies.length ?? 0;
  return n === 0 ? 'Nothing: this edge applies no rate limit. Edit to add the recommended policies' : n === 1 ? '1 policy' : `${n} policies`;
}
