/**
 * How long the temporary password of an admin reset may live, in hours. Central answers 400 outside
 * this range (3600 to 604800 seconds), so the dialog holds the field to it before anything is sent.
 */
export const MIN_TEMPORARY_PASSWORD_TTL_HOURS = 1;
export const MAX_TEMPORARY_PASSWORD_TTL_HOURS = 168;

export type TemporaryPasswordTtl = { ok: true; hours: number } | { ok: false; message: string };

/** Reads what was typed in the lifetime field: a whole number of hours inside the range, or the reason it is not. */
export function parseTemporaryPasswordTtlHours(raw: string): TemporaryPasswordTtl {
  const text = raw.trim();
  if (text === '') return { ok: false, message: 'Enter the lifetime in hours.' };
  const value = Number(text);
  if (!Number.isFinite(value)) return { ok: false, message: 'Enter the lifetime as a number of hours.' };
  if (!Number.isInteger(value)) return { ok: false, message: 'Use a whole number of hours.' };
  if (value < MIN_TEMPORARY_PASSWORD_TTL_HOURS) {
    return { ok: false, message: `The lifetime is at least ${MIN_TEMPORARY_PASSWORD_TTL_HOURS} hour.` };
  }
  if (value > MAX_TEMPORARY_PASSWORD_TTL_HOURS) {
    return { ok: false, message: `The lifetime is at most ${MAX_TEMPORARY_PASSWORD_TTL_HOURS} hours (7 days).` };
  }
  return { ok: true, hours: value };
}

/** The range as the hint under the field says it. */
export const TEMPORARY_PASSWORD_TTL_HINT =
  `From ${MIN_TEMPORARY_PASSWORD_TTL_HOURS} hour to ${MAX_TEMPORARY_PASSWORD_TTL_HOURS} hours (7 days).`;
