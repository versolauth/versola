/**
 * A rate limit declared where an API or a client is declared (`rateLimit` on a resource endpoint and on an OAuth client;
 * levels 2 and 3 of the rate limit design, issue #744 section 4): the wire form, the draft the editor works on, and the
 * helpers around it. The rules and messages mirror `DeclaredRateLimit.validate` in `util/.../DeclaredRateLimit.scala`,
 * which reuses the rules of `RateLimitPolicy`: central refuses a declaration that fails them, so the editor says the same
 * thing before the request.
 *
 * It applies together with the edge's own policies and a request must pass all of them, so the smallest limit wins. Absent
 * means no limit. `cost` is always 1.
 */
import {
  MAX_COUNT, MAX_WINDOWS, MAX_WINDOW_SECONDS, MODES, MODE_TEXT, modeText, windowText,
  type RateLimitMode, type RateLimitWindow, type WindowDraft,
} from './rate-limit-policies';

export { MODES, MODE_TEXT };
export type { RateLimitMode, RateLimitWindow, WindowDraft };

export type DeclaredBy = 'subject' | 'client' | 'none';
export type DeclaredOwner = 'endpoint' | 'audience';

/** What the resource sets for one client of its audience (`audienceRateLimits` on the resource). */
export interface AudienceRateLimit {
  clientId: string;
  rateLimit: DeclaredRateLimit;
}

export interface DeclaredRateLimit {
  limits: RateLimitWindow[];
  burst?: number;
  by?: DeclaredBy;
  mode?: RateLimitMode;
}

/** The limit as typed. An empty string is "not set". */
export interface DeclaredDraft {
  mode: RateLimitMode;
  by: DeclaredBy;
  limits: WindowDraft[];
  burst: string;
}

/** What a bucket can be counted by on each owner. For an audience client, `client` would mean the same as `none`, so it is not offered. */
export const BY_OPTIONS: Record<DeclaredOwner, ReadonlyArray<{ id: DeclaredBy; label: string; text: string }>> = {
  endpoint: [
    { id: 'none', label: 'The endpoint as a whole', text: 'One bucket for this endpoint: every caller together.' },
    { id: 'client', label: 'Each client', text: 'One bucket per OAuth client calling this endpoint.' },
    { id: 'subject', label: 'Each user', text: 'One bucket per user (the token’s subject) on this endpoint.' },
  ],
  audience: [
    { id: 'none', label: 'The client as a whole', text: 'One bucket for this client’s calls to this resource.' },
    { id: 'subject', label: 'Each user', text: 'One bucket per user of this client on this resource.' },
  ],
};

export const OWNER_NAME: Record<DeclaredOwner, string> = { endpoint: 'resource-endpoint', audience: 'resource-client' };

export function newDeclaredDraft(): DeclaredDraft {
  return { mode: 'shadow', by: 'none', limits: [{ limit: '', windowSeconds: '' }], burst: '' };
}

export function draftFromDeclared(limit: DeclaredRateLimit | null | undefined): DeclaredDraft | null {
  if (!limit) return null;
  return {
    mode: limit.mode ?? 'shadow',
    by: limit.by ?? 'none',
    limits: limit.limits.map(window => ({ limit: String(window.limit), windowSeconds: String(window.windowSeconds) })),
    // The server's default is 1: leaving the field empty means exactly that, so only a different value is shown.
    burst: limit.burst !== undefined && limit.burst !== 1 ? String(limit.burst) : '',
  };
}

/** The body: what the operator typed, with the defaults left out. Null when there is no limit. */
export function declaredFromDraft(draft: DeclaredDraft | null): DeclaredRateLimit | null {
  if (!draft) return null;
  const body: DeclaredRateLimit = {
    limits: draft.limits.map(window => ({ limit: Number(window.limit.trim()), windowSeconds: Number(window.windowSeconds.trim()) })),
    burst: draft.burst.trim() !== '' ? Number(draft.burst.trim()) : 1,
    by: draft.by,
    mode: draft.mode,
  };
  return body;
}

/** A stable text of the limit, to tell whether it changed. */
export function declaredSnapshot(draft: DeclaredDraft | null): string {
  return JSON.stringify(declaredFromDraft(draft));
}

function parseInt10(raw: string): number | null {
  return /^\d{1,9}$/.test(raw.trim()) ? Number(raw.trim()) : null;
}

/** The first message of each field that fails, keyed `limit.<j>`, `window.<j>`, `limits` (the windows as a whole) or `burst`. */
export function validateDeclared(draft: DeclaredDraft | null, owner: DeclaredOwner): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!draft) return errors;
  const name = OWNER_NAME[owner];
  if (!BY_OPTIONS[owner].some(option => option.id === draft.by)) {
    errors.by = 'by must be subject or none on an audience client: its limit is already counted for the client';
  }
  if (draft.limits.length === 0) errors.limits = `policy ${name}: limits must have at least one window`;
  if (draft.limits.length > MAX_WINDOWS) errors.limits = `policy ${name}: limits must have at most ${MAX_WINDOWS} windows`;
  const limits: number[] = [];
  const windows: number[] = [];
  draft.limits.forEach((window, j) => {
    const limit = parseInt10(window.limit);
    const seconds = parseInt10(window.windowSeconds);
    if (limit === null || limit < 1 || limit > MAX_COUNT) errors[`limit.${j}`] = `limit must be between 1 and ${MAX_COUNT}`;
    else limits.push(limit);
    if (seconds === null || seconds < 1 || seconds > MAX_WINDOW_SECONDS) errors[`window.${j}`] = `windowSeconds must be between 1 and ${MAX_WINDOW_SECONDS}`;
    else windows.push(seconds);
  });
  if (new Set(windows).size !== windows.length && !errors.limits) errors.limits = 'two limits have the same window';
  const burstRaw = draft.burst.trim();
  const burst = burstRaw === '' ? 1 : parseInt10(burstRaw);
  if (burst === null || burst < 1 || burst > MAX_COUNT) errors.burst = `burst must be between 1 and ${MAX_COUNT}`;
  else if (limits.length > 0 && burst > Math.min(...limits)) errors.burst = 'burst must not exceed the smallest limit';
  return errors;
}

export function hasErrors(draft: DeclaredDraft | null, owner: DeclaredOwner): boolean {
  return Object.keys(validateDeclared(draft, owner)).length > 0;
}

/** `Shadow · 600 / 60 s + 10000 / 3600 s · each user · burst 50` for a record row; "No limit" when there is none. */
export function declaredSummary(limit: DeclaredRateLimit | null | undefined, owner: DeclaredOwner): string {
  if (!limit) return 'No limit';
  const by = BY_OPTIONS[owner].find(option => option.id === (limit.by ?? 'none'));
  const parts = [modeText(limit.mode).split(' ')[0], limit.limits.map(windowText).join(' + '), (by?.label ?? 'each user').replace(/^The /, '').toLowerCase()];
  if (limit.burst && limit.burst > 1) parts.push(`burst ${limit.burst}`);
  return parts.join(' · ');
}
