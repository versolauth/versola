import type { PartnerMe, ProfileApproval } from './partner-types';

/**
 * The edge cannot read a waiting profile change from the operator (`GET /profile` always answers `approval: null`), so the
 * console keeps what the 202 of `PUT /profile` said, in this browser, until the partner's name and logo are what it asked for
 * or a month has passed since it was sent from here. Another browser or another member does not see it; that is the price until central gives a read.
 */
const KEY = (partnerId: string) => `versola-partner-profile-approval:${partnerId}`;
const KEEP_MS = 30 * 24 * 60 * 60 * 1000;

export function rememberProfileApproval(partnerId: string, approval: ProfileApproval, now = Date.now()): void {
  try { localStorage.setItem(KEY(partnerId), JSON.stringify({ approval, savedAt: now })); } catch { /* storage may be off: the notice still shows once */ }
}

/** The change this browser sent that the operator has not applied yet, or null. */
export function recallProfileApproval(partner: PartnerMe['partner'], now = Date.now()): ProfileApproval | null {
  let entry: { approval?: ProfileApproval; savedAt?: number } | null;
  try { entry = JSON.parse(localStorage.getItem(KEY(partner.id)) ?? 'null') as { approval?: ProfileApproval; savedAt?: number } | null; } catch { return null; }
  const stored = entry?.approval;
  if (!stored?.requested) return null;
  const applied = stored.requested.displayName === partner.displayName && (stored.requested.logoUri ?? null) === (partner.logoUri ?? null);
  if (applied || now - (entry?.savedAt ?? 0) > KEEP_MS) {
    try { localStorage.removeItem(KEY(partner.id)); } catch { /* nothing to clean */ }
    return null;
  }
  return stored;
}
