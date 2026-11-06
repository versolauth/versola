import { describe, expect, it } from 'vitest';
import { estStateSummary } from './est-state';

describe('estStateSummary', () => {
  it('says a client that has not enrolled is pending', () => {
    expect(estStateSummary({ pending: true })).toBe('EST enrolment pending');
  });

  it('gives the expiry of the certificate and the end of the chain', () => {
    expect(
      estStateSummary({
        pending: false,
        certificateExpiresAt: '2026-11-05T08:45:27Z',
        chainEndsAt: '2027-10-22T00:00:00Z',
      }),
    ).toBe('EST: certificate expires 2026-11-05, chain ends 2027-10-22, extend it before then');
  });

  it('leaves out the chain where central extends it itself', () => {
    expect(estStateSummary({ pending: false, certificateExpiresAt: '2026-11-05T08:45:27Z', chainEndsAt: null })).toBe(
      'EST: certificate expires 2026-11-05',
    );
  });
});
