/**
 * A duration field is a number and a unit; the record stores seconds. The unit shown is the
 * largest that divides the value evenly, so 86400 reads as `1 day` and 900 as `15 minutes`.
 */
export type DurationUnit = 'seconds' | 'minutes' | 'hours' | 'days';

export const DURATION_UNITS: Array<{ id: DurationUnit; label: string; seconds: number }> = [
  { id: 'seconds', label: 'seconds', seconds: 1 },
  { id: 'minutes', label: 'minutes', seconds: 60 },
  { id: 'hours', label: 'hours', seconds: 3600 },
  { id: 'days', label: 'days', seconds: 86400 },
];

export type Duration = { value: number; unit: DurationUnit };

export function splitDuration(seconds: number): Duration {
  for (const unit of [...DURATION_UNITS].reverse()) {
    if (seconds > 0 && seconds % unit.seconds === 0) return { value: seconds / unit.seconds, unit: unit.id };
  }
  return { value: seconds, unit: 'seconds' };
}

export function durationSeconds(duration: Duration): number {
  const unit = DURATION_UNITS.find(candidate => candidate.id === duration.unit) ?? DURATION_UNITS[0];
  return Math.round(duration.value * unit.seconds);
}

/** `15m`, `24h`, `180d`, `45s`: the record value; the exact seconds go in the `title`. */
export function formatDurationShort(seconds: number): string {
  if (seconds <= 0) return '0s';
  const { value, unit } = splitDuration(seconds);
  return `${value}${unit[0]}`;
}
