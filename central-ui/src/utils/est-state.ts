import type { ClientEstState } from '../types';

const day = (iso: string): string => iso.slice(0, 10);

/**
 * Where a client that obtains its certificate through EST (RFC 7030) stands, in one line for the
 * client's summary: not enrolled yet, or when the certificate it holds expires and, where chains
 * end, when the chain does and an operator has to extend it.
 */
export function estStateSummary(est: ClientEstState): string {
  if (est.pending) {
    return 'EST enrolment pending';
  }

  const parts = ['EST'];
  if (est.certificateExpiresAt) {
    parts.push(`certificate expires ${day(est.certificateExpiresAt)}`);
  }
  if (est.chainEndsAt) {
    parts.push(`chain ends ${day(est.chainEndsAt)}, extend it before then`);
  }
  return parts.length === 1 ? 'EST' : `${parts[0]}: ${parts.slice(1).join(', ')}`;
}
