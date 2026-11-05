import { describe, expect, it } from 'vitest';
import { checkRange } from './activity-range';

const now = new Date('2026-11-04T09:50:00Z');
const retention = new Date('2026-10-28T09:50:00Z');
const draft = { fromDate: '1 Nov 2026', fromTime: '00:00', toDate: '4 Nov 2026', toTime: '12:00' };

describe('checking a custom range', () => {
  it('accepts a range inside the retained period and reads it in the zone', () => {
    const check = checkRange(draft, 'Asia/Almaty', retention, now);
    expect(check.fromProblem).toBe('');
    expect(check.toProblem).toBe('');
    expect(check.from?.toISOString()).toBe('2026-10-31T19:00:00.000Z');
    expect(check.to?.toISOString()).toBe('2026-11-04T07:00:00.000Z');
  });

  it('says To must be after From when it is not, and when it is equal', () => {
    expect(checkRange({ ...draft, toDate: '1 Nov 2026', toTime: '00:00' }, 'UTC', retention, now).toProblem).toBe('To must be after From.');
    expect(checkRange({ ...draft, toDate: '31 Oct 2026' }, 'UTC', retention, now).toProblem).toBe('To must be after From.');
  });

  it('refuses a From before retention, and a To in the future', () => {
    expect(checkRange({ ...draft, fromDate: '20 Oct 2026' }, 'UTC', retention, now).fromProblem).toBe('From is before the first event that can be shown (Oct 28, 2026, 09:50).');
    expect(checkRange({ ...draft, toDate: '5 Nov 2026' }, 'UTC', retention, now).toProblem).toBe('To is in the future.');
  });

  it('does not check against retention until a report has said what it is', () => {
    expect(checkRange({ ...draft, fromDate: '1 Jan 2020' }, 'UTC', null, now).fromProblem).toBe('');
  });

  it('asks for a date and a time in the form the fields show when one cannot be read', () => {
    const check = checkRange({ ...draft, fromDate: 'last week', toTime: '12 PM' }, 'UTC', retention, now);
    expect(check.fromProblem).toMatch(/1 Nov 2026/);
    expect(check.toProblem).toMatch(/14:30/);
    expect(check.from).toBeNull();
    expect(check.to).toBeNull();
  });
});
