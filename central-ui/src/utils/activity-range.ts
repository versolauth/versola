import { formatDateTime, parseFieldsInZone } from './date-format';

export type RangeDraft = { fromDate: string; fromTime: string; toDate: string; toTime: string };

/** The messages for each end (empty when fine) and the instants the fields read as, null when one cannot be read. */
export type RangeCheck = { fromProblem: string; toProblem: string; from: Date | null; to: Date | null };

/** What is wrong with a draft range, in the words the fields show; empty strings when it can be applied. `now` is the
 *  latest `To` allowed, `retentionFrom` the earliest `From`. */
export function checkRange(draft: RangeDraft, zone: string, retentionFrom: Date | null, now: Date): RangeCheck {
  const from = parseFieldsInZone(draft.fromDate, draft.fromTime, zone);
  const to = parseFieldsInZone(draft.toDate, draft.toTime, zone);
  const problems = { from: '', to: '' };
  if (!from) problems.from = 'Enter the date like 1 Nov 2026 and the time like 14:30 (24-hour).';
  if (!to) problems.to = 'Enter the date like 1 Nov 2026 and the time like 14:30 (24-hour).';
  if (from && retentionFrom && from.getTime() < retentionFrom.getTime()) problems.from = `From is before the first event that can be shown (${formatDateTime(retentionFrom, zone, { seconds: false })}).`;
  if (to && to.getTime() > now.getTime()) problems.to = 'To is in the future.';
  if (from && to && !problems.to && to.getTime() <= from.getTime()) problems.to = 'To must be after From.';
  return { fromProblem: problems.from, toProblem: problems.to, from, to };
}

