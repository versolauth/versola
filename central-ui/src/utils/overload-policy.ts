/**
 * The overload policy of an edge (`PUT /configuration/edges/overload`): the wire form, the field
 * table the editor is drawn from, and the pure helpers around it. The ranges and messages mirror
 * `OverloadPolicy.validate` in `util/.../OverloadPolicy.scala`: central refuses a policy that
 * fails it and an edge does not apply one, so the editor says the same thing before the request.
 *
 * Every field is optional and means "what the edge's deployment starts with" when absent, so the
 * body carries only what the operator set.
 */

export type OverloadMode = 'off' | 'shadow' | 'enforce';

export interface InboundOverloadPolicy {
  mode?: OverloadMode;
  maxInFlight?: number;
  continuationShare?: number;
  startShare?: number;
  retryAfterSeconds?: number;
  adaptive?: {
    minLimit?: number;
    maxLimit?: number;
    tolerance?: number;
    smoothing?: number;
    lagTargetMillis?: number;
  };
}

export interface OutboundOverloadPolicy {
  mode?: OverloadMode;
  maxConcurrent?: number;
  maxQueue?: number;
  queueTimeoutMillis?: number;
  requestTimeoutMillis?: number;
  breaker?: {
    enabled?: boolean;
    failureRatio?: number;
    minCalls?: number;
    windowSeconds?: number;
    openSeconds?: number;
    probeCalls?: number;
  };
  retryBudget?: {
    ratio?: number;
    minPerSecond?: number;
  };
}

export interface OverloadPolicy {
  inbound?: InboundOverloadPolicy;
  outbound?: OutboundOverloadPolicy;
}

/** A field's text as typed; an empty string is "not set". Keyed by the dotted path of the field. */
export type OverloadDraft = Record<string, string>;

export interface NumberField {
  /** Dotted path in the policy, also the message's field name: `inbound.adaptive.minLimit`. */
  path: string;
  label: string;
  /** `int` takes whole numbers; `number` any decimal. */
  kind: 'int' | 'number';
  min: number;
  max: number;
  unit?: string;
  /** What the edge uses when the field is empty and no environment value is set. */
  fallback: string;
  /** The environment variable that is the base when the field is left empty, if there is one. */
  env?: string;
  /** One line on what the field does. */
  hint: string;
}

export const MODES: ReadonlyArray<{ id: OverloadMode; label: string }> = [
  { id: 'off', label: 'Off' },
  { id: 'shadow', label: 'Shadow' },
  { id: 'enforce', label: 'Enforce' },
];

export const INBOUND_MODE_TEXT: Record<OverloadMode | '', string> = {
  '': 'Deployment default: HTTP_ADMISSION_MODE when the edge sets it, otherwise shadow.',
  off: 'Does nothing: no counting, no refusing, no learning.',
  shadow: 'Learns the limit and counts what enforce would refuse; refuses nothing. The built-in default.',
  enforce: 'Refuses requests above the learned limit with 503 and Retry-After; a new login first, proxied calls last.',
};

export const OUTBOUND_MODE_TEXT: Record<OverloadMode | '', string> = {
  '': 'Deployment default: HTTP_CLIENT_GUARD_MODE when the edge sets it, otherwise shadow.',
  off: 'Does nothing: calls are not counted, queued or timed out.',
  shadow: 'Counts what enforce would reject; every call goes through. The built-in default.',
  enforce: 'Limits calls in flight per peer, queues a bounded number, times out slow ones (503 or 504).',
};

export const INBOUND_MODE_PATH = 'inbound.mode';
export const OUTBOUND_MODE_PATH = 'outbound.mode';
export const BREAKER_ENABLED_PATH = 'outbound.breaker.enabled';

export const INBOUND_MAIN: NumberField[] = [
  {
    path: 'inbound.maxInFlight', label: 'Starting limit', kind: 'int', min: 1, max: 100000, unit: 'requests',
    fallback: '256', env: 'HTTP_ADMISSION_MAX_IN_FLIGHT',
    hint: 'Where the learned limit starts (maxInFlight); it is clamped into the bounds below.',
  },
  {
    path: 'inbound.continuationShare', label: 'Continuation share', kind: 'number', min: 0.01, max: 1,
    fallback: '0.85', env: 'HTTP_ADMISSION_CONTINUATION_SHARE',
    hint: 'Share of the limit work already begun (code exchange, logout) may use.',
  },
  {
    path: 'inbound.startShare', label: 'Start share', kind: 'number', min: 0.01, max: 1,
    fallback: '0.65', env: 'HTTP_ADMISSION_START_SHARE',
    hint: 'Share of the limit a new login may use; not above the continuation share.',
  },
  {
    path: 'inbound.retryAfterSeconds', label: 'Retry-After', kind: 'int', min: 1, max: 60, unit: 'seconds',
    fallback: '1', env: 'HTTP_ADMISSION_RETRY_AFTER',
    hint: 'How long a refused client is told to wait.',
  },
  {
    path: 'inbound.adaptive.minLimit', label: 'Lowest limit', kind: 'int', min: 1, max: 100000, unit: 'requests',
    fallback: '16',
    hint: 'The learned limit never goes below this.',
  },
  {
    path: 'inbound.adaptive.maxLimit', label: 'Highest limit', kind: 'int', min: 1, max: 100000, unit: 'requests',
    fallback: '1024',
    hint: 'The learned limit never goes above this.',
  },
];

export const INBOUND_ADVANCED: NumberField[] = [
  {
    path: 'inbound.adaptive.tolerance', label: 'Tolerance', kind: 'number', min: 1, max: 5,
    fallback: '1.5',
    hint: 'How much slower than usual a call may get before the limit shrinks (1.5: half again).',
  },
  {
    path: 'inbound.adaptive.smoothing', label: 'Smoothing', kind: 'number', min: 0.01, max: 1,
    fallback: '0.2',
    hint: 'Share of each new estimate that replaces the limit; lower is steadier.',
  },
  {
    path: 'inbound.adaptive.lagTargetMillis', label: 'Lag target', kind: 'int', min: 5, max: 10000, unit: 'ms',
    fallback: '50',
    hint: 'How late the executor may run a timer before it counts as stalled.',
  },
];

export const OUTBOUND_MAIN: NumberField[] = [
  {
    path: 'outbound.maxConcurrent', label: 'Concurrent calls per peer', kind: 'int', min: 1, max: 100000, unit: 'calls',
    fallback: 'the connection pool size (128)', env: 'HTTP_CLIENT_GUARD_MAX_CONCURRENT',
    hint: 'Calls in flight towards one peer (auth, a resource backend).',
  },
  {
    path: 'outbound.maxQueue', label: 'Queue length', kind: 'int', min: 1, max: 100000, unit: 'calls',
    fallback: '256', env: 'HTTP_CLIENT_GUARD_MAX_QUEUE',
    hint: 'Calls allowed to wait for a place; one more is rejected at once.',
  },
  {
    path: 'outbound.queueTimeoutMillis', label: 'Queue timeout', kind: 'int', min: 1, max: 600000, unit: 'ms',
    fallback: '1000', env: 'HTTP_CLIENT_GUARD_QUEUE_TIMEOUT',
    hint: 'The longest a call waits for a place.',
  },
  {
    path: 'outbound.requestTimeoutMillis', label: 'Request timeout', kind: 'int', min: 1, max: 600000, unit: 'ms',
    fallback: '10000', env: 'HTTP_CLIENT_GUARD_REQUEST_TIMEOUT',
    hint: 'The longest a call may take to the response headers.',
  },
];

export const BREAKER_FIELDS: NumberField[] = [
  {
    path: 'outbound.breaker.failureRatio', label: 'Failure ratio', kind: 'number', min: 0.01, max: 1,
    fallback: '0.5', hint: 'Share of failed calls in the window that opens the circuit.',
  },
  {
    path: 'outbound.breaker.minCalls', label: 'Minimum calls', kind: 'int', min: 1, max: 10000, unit: 'calls',
    fallback: '20', hint: 'Calls the window must hold before the ratio counts.',
  },
  {
    path: 'outbound.breaker.windowSeconds', label: 'Window', kind: 'int', min: 1, max: 300, unit: 'seconds',
    fallback: '10', hint: 'The period the failure ratio is measured over.',
  },
  {
    path: 'outbound.breaker.openSeconds', label: 'Open for', kind: 'int', min: 1, max: 600, unit: 'seconds',
    fallback: '5', hint: 'How long the circuit refuses at once before probing.',
  },
  {
    path: 'outbound.breaker.probeCalls', label: 'Probe calls', kind: 'int', min: 1, max: 100, unit: 'calls',
    fallback: '3', hint: 'Calls let through to test a half-open circuit.',
  },
];

export const RETRY_FIELDS: NumberField[] = [
  {
    path: 'outbound.retryBudget.ratio', label: 'Retry ratio', kind: 'number', min: 0, max: 1,
    fallback: '0.1', hint: 'Retries allowed per call made.',
  },
  {
    path: 'outbound.retryBudget.minPerSecond', label: 'Retries per second', kind: 'number', min: 0, max: 1000, unit: 'per second',
    fallback: '5', hint: 'Retries always allowed, whatever the ratio.',
  },
];

export const NUMBER_FIELDS: NumberField[] = [
  ...INBOUND_MAIN, ...INBOUND_ADVANCED, ...OUTBOUND_MAIN, ...BREAKER_FIELDS, ...RETRY_FIELDS,
];

const FIELD_BY_PATH = new Map(NUMBER_FIELDS.map(field => [field.path, field]));

/** The built-in defaults of the adaptive bounds, which no environment variable changes. */
const DEFAULT_MIN_LIMIT = 16;
const DEFAULT_MAX_LIMIT = 1024;
const DEFAULT_MAX_IN_FLIGHT = 256;

// ── policy <-> draft ──

function readPath(policy: OverloadPolicy, path: string): unknown {
  let node: unknown = policy;
  for (const part of path.split('.')) {
    if (node === null || typeof node !== 'object') return undefined;
    node = (node as Record<string, unknown>)[part];
  }
  return node;
}

function writePath(target: Record<string, unknown>, path: string, value: unknown) {
  const parts = path.split('.');
  let node = target;
  for (const part of parts.slice(0, -1)) {
    const next = node[part];
    if (next === null || typeof next !== 'object') node[part] = {};
    node = node[part] as Record<string, unknown>;
  }
  node[parts[parts.length - 1]] = value;
}

const ALL_PATHS = [INBOUND_MODE_PATH, OUTBOUND_MODE_PATH, BREAKER_ENABLED_PATH, ...NUMBER_FIELDS.map(field => field.path)];

export function emptyDraft(): OverloadDraft {
  return Object.fromEntries(ALL_PATHS.map(path => [path, '']));
}

/** The editor's starting text for what central holds; an absent field is an empty one. */
export function draftFromPolicy(policy: OverloadPolicy | undefined | null): OverloadDraft {
  const draft = emptyDraft();
  if (!policy) return draft;
  for (const path of ALL_PATHS) {
    const value = readPath(policy, path);
    if (value !== undefined && value !== null) draft[path] = String(value);
  }
  return draft;
}

/** The body of the PUT: only what the operator set. Empty groups are left out; `{}` means "nothing set". */
export function policyFromDraft(draft: OverloadDraft): OverloadPolicy {
  const policy: Record<string, unknown> = {};
  for (const path of ALL_PATHS) {
    const raw = (draft[path] ?? '').trim();
    if (raw === '') continue;
    if (path === INBOUND_MODE_PATH || path === OUTBOUND_MODE_PATH) writePath(policy, path, raw);
    else if (path === BREAKER_ENABLED_PATH) writePath(policy, path, raw === 'true');
    else writePath(policy, path, Number(raw));
  }
  return policy as OverloadPolicy;
}

/** The dotted paths of the fields a policy sets, in screen order. */
export function setPaths(policy: OverloadPolicy | undefined | null): string[] {
  if (!policy) return [];
  return ALL_PATHS.filter(path => {
    const value = readPath(policy, path);
    return value !== undefined && value !== null;
  });
}

export function isEmptyPolicy(policy: OverloadPolicy | undefined | null): boolean {
  return setPaths(policy).length === 0;
}

// ── validation ──

/** A number as the server writes it in a message: whole doubles carry `.0` (`1.0`), integers do not. */
function bound(value: number, kind: 'int' | 'number'): string {
  return kind === 'number' && Number.isInteger(value) ? value.toFixed(1) : String(value);
}

export function rangeMessage(field: NumberField): string {
  return `${field.path} must be between ${bound(field.min, field.kind)} and ${bound(field.max, field.kind)}`;
}

function parseNumber(field: NumberField, raw: string): number | null {
  if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(raw)) return null;
  const value = Number(raw);
  if (!Number.isFinite(value)) return null;
  if (field.kind === 'int' && !Number.isInteger(value)) return null;
  return value;
}

/** The same checks, with the same words, as `OverloadPolicy.validate`; the message of each field that fails, by path. */
export function validateDraft(draft: OverloadDraft): Record<string, string> {
  const errors: Record<string, string> = {};
  const numbers = new Map<string, number>();
  for (const field of NUMBER_FIELDS) {
    const raw = (draft[field.path] ?? '').trim();
    if (raw === '') continue;
    const value = parseNumber(field, raw);
    if (value === null || value < field.min || value > field.max) errors[field.path] = rangeMessage(field);
    else numbers.set(field.path, value);
  }

  const start = numbers.get('inbound.startShare');
  const continuation = numbers.get('inbound.continuationShare');
  if (start !== undefined && continuation !== undefined && start > continuation) {
    errors['inbound.startShare'] = 'inbound.startShare must not exceed inbound.continuationShare: a new login never gets more room than work already begun';
  }
  const min = numbers.get('inbound.adaptive.minLimit');
  const max = numbers.get('inbound.adaptive.maxLimit');
  if (min !== undefined && max !== undefined && min > max) {
    errors['inbound.adaptive.minLimit'] = 'inbound.adaptive.minLimit must not exceed inbound.adaptive.maxLimit';
  }
  return errors;
}

/**
 * A soft note, not an error: the starting limit is clamped into the bounds, so one outside them
 * is not where the limit starts. The bounds have no environment base, so the answer is exact.
 */
export function startingLimitNote(draft: OverloadDraft): string {
  const errors = validateDraft(draft);
  const number = (path: string, fallback: number): number => {
    const raw = (draft[path] ?? '').trim();
    const field = FIELD_BY_PATH.get(path)!;
    if (raw === '' || errors[path]) return fallback;
    return parseNumber(field, raw) ?? fallback;
  };
  const raw = (draft['inbound.maxInFlight'] ?? '').trim();
  if (raw === '' || errors['inbound.maxInFlight']) return '';
  const start = number('inbound.maxInFlight', DEFAULT_MAX_IN_FLIGHT);
  const min = number('inbound.adaptive.minLimit', DEFAULT_MIN_LIMIT);
  const max = Math.max(min, number('inbound.adaptive.maxLimit', DEFAULT_MAX_LIMIT));
  if (start < min) return `The limit starts at ${min}, the lowest limit, not at ${start}.`;
  if (start > max) return `The limit starts at ${max}, the highest limit, not at ${start}.`;
  return '';
}

// ── display ──

const MODE_LABEL: Record<OverloadMode, string> = { off: 'Off', shadow: 'Shadow', enforce: 'Enforce' };

export function modeLabel(mode: OverloadMode | undefined): string {
  return mode ? `${MODE_LABEL[mode]} (set here)` : 'Deployment default';
}

/** `path value` for each field the policy sets, for the record card's chips. */
export function setSummary(policy: OverloadPolicy | undefined | null): string[] {
  if (!policy) return [];
  return setPaths(policy)
    .filter(path => path !== INBOUND_MODE_PATH && path !== OUTBOUND_MODE_PATH)
    .map(path => `${path.replace(/^(inbound|outbound)\./, '')} ${String(readPath(policy, path))}`);
}
