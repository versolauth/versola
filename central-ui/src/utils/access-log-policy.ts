/**
 * The access-log policy of an edge (`PUT /configuration/edges/access-log`): the wire form, the
 * field table the editor is drawn from, and the pure helpers around it. The ranges and messages
 * mirror `AccessLogPolicy.validate` in `util/.../AccessLog.scala`: central refuses a policy that
 * fails it and an edge does not apply one, so the editor says the same thing before the request.
 *
 * Every field is optional and means "the edge's built-in default" when absent (there is no
 * environment base, unlike the overload policy), so the body carries only what the operator set.
 * auth and central have no such setting: they always log every request.
 */

export type AccessLogMode = 'full' | 'sampled' | 'off';

export interface AccessLogPolicy {
  mode?: AccessLogMode;
  slowMillis?: number;
  successSampleRate?: number;
}

/** A field's text as typed; an empty string is "not set". Keyed by the field's name in the policy. */
export type AccessLogDraft = Record<string, string>;

export interface AccessLogNumberField {
  path: 'slowMillis' | 'successSampleRate';
  label: string;
  kind: 'int' | 'number';
  min: number;
  max: number;
  unit?: string;
  /** What the edge uses when the field is empty. */
  fallback: string;
  hint: string;
}

/** The edge's built-in defaults, written once here for display; `PostgresEdgeApp` declares the real ones. */
export const DEFAULT_MODE: AccessLogMode = 'off';

export const MODES: ReadonlyArray<{ id: AccessLogMode; label: string }> = [
  { id: 'full', label: 'Full' },
  { id: 'sampled', label: 'Sampled' },
  { id: 'off', label: 'Off' },
];

export const MODE_PATH = 'mode';

export const MODE_TEXT: Record<AccessLogMode | '', string> = {
  '': 'Default: off. Only requests that failed (status 400 or above, or an error) and slow ones are logged.',
  full: 'Every request gets a log line. The most detail and the most log volume; use it while investigating.',
  sampled: 'Failures and slow requests, plus a share of the successful ones (the sample rate), chosen per trace so every service in a trace agrees.',
  off: 'Only requests that failed (status 400 or above, or an error) and slow ones. Successful requests are in the metrics.',
};

export const NUMBER_FIELDS: AccessLogNumberField[] = [
  {
    path: 'slowMillis', label: 'Slow threshold', kind: 'int', min: 0, max: 600000, unit: 'ms', fallback: '500',
    hint: 'A request at least this slow is always logged, in every mode. Also lifts a slow successful outbound call to INFO.',
  },
  {
    path: 'successSampleRate', label: 'Sample rate', kind: 'number', min: 0, max: 1, fallback: '0.05',
    hint: 'Sampled mode only: the share of traces whose successful requests are logged (0.05 is one trace in 20).',
  },
];

const FIELD_BY_PATH = new Map(NUMBER_FIELDS.map(field => [field.path as string, field]));
const ALL_PATHS: string[] = [MODE_PATH, ...NUMBER_FIELDS.map(field => field.path)];

export function emptyDraft(): AccessLogDraft {
  return Object.fromEntries(ALL_PATHS.map(path => [path, '']));
}

/** The editor's starting text for what central holds; an absent field is an empty one. */
export function draftFromPolicy(policy: AccessLogPolicy | undefined | null): AccessLogDraft {
  const draft = emptyDraft();
  if (!policy) return draft;
  for (const path of ALL_PATHS) {
    const value = (policy as Record<string, unknown>)[path];
    if (value !== undefined && value !== null) draft[path] = String(value);
  }
  return draft;
}

/** The body of the PUT: only what the operator set. `{}` means "nothing set". */
export function policyFromDraft(draft: AccessLogDraft): AccessLogPolicy {
  const policy: Record<string, unknown> = {};
  for (const path of ALL_PATHS) {
    const raw = (draft[path] ?? '').trim();
    if (raw === '') continue;
    policy[path] = path === MODE_PATH ? raw : Number(raw);
  }
  return policy as AccessLogPolicy;
}

/** The names of the fields a policy sets, in screen order. */
export function setPaths(policy: AccessLogPolicy | undefined | null): string[] {
  if (!policy) return [];
  return ALL_PATHS.filter(path => {
    const value = (policy as Record<string, unknown>)[path];
    return value !== undefined && value !== null;
  });
}

export function isEmptyPolicy(policy: AccessLogPolicy | undefined | null): boolean {
  return setPaths(policy).length === 0;
}

// ── validation ──

/** A number as the server writes it in a message: whole doubles carry `.0` (`1.0`), integers do not. */
function bound(value: number, kind: 'int' | 'number'): string {
  return kind === 'number' && Number.isInteger(value) ? value.toFixed(1) : String(value);
}

export function rangeMessage(field: AccessLogNumberField): string {
  return `${field.path} must be between ${bound(field.min, field.kind)} and ${bound(field.max, field.kind)}`;
}

function parseNumber(field: AccessLogNumberField, raw: string): number | null {
  if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(raw)) return null;
  const value = Number(raw);
  if (!Number.isFinite(value)) return null;
  if (field.kind === 'int' && !Number.isInteger(value)) return null;
  return value;
}

/** The same checks, with the same words, as `AccessLogPolicy.validate`; the message of each field that fails, by name. */
export function validateDraft(draft: AccessLogDraft): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const field of NUMBER_FIELDS) {
    const raw = (draft[field.path] ?? '').trim();
    if (raw === '') continue;
    const value = parseNumber(field, raw);
    if (value === null || value < field.min || value > field.max) errors[field.path] = rangeMessage(field);
  }
  return errors;
}

// ── display ──

const MODE_LABEL: Record<AccessLogMode, string> = { full: 'Full', sampled: 'Sampled', off: 'Off' };

export function modeLabel(mode: AccessLogMode | undefined): string {
  return mode ? `${MODE_LABEL[mode]} (set here)` : `Default (${MODE_LABEL[DEFAULT_MODE].toLowerCase()})`;
}

/** `name value` for each field besides the mode that the policy sets, for the record card's chips. */
export function setSummary(policy: AccessLogPolicy | undefined | null): string[] {
  if (!policy) return [];
  return setPaths(policy)
    .filter(path => path !== MODE_PATH)
    .map(path => `${path} ${String((policy as Record<string, unknown>)[path])}`);
}

export function fieldFor(path: string): AccessLogNumberField | undefined {
  return FIELD_BY_PATH.get(path);
}
