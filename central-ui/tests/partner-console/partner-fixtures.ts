import { partnerSeed } from '../../src/partner/seed';
import type { MockPartnerState } from '../../src/partner/mock-backend';

/** The clock the partner console's specs and screenshots run under, so the seed's relative dates never move. */
export const PARTNER_NOW = '2026-11-07T10:00:00Z';

/** Pilot Pay as its own staff see it: the demo seed pinned to `PARTNER_NOW`. */
export const partnerSeedState: MockPartnerState = { ...partnerSeed(Date.parse(PARTNER_NOW)), now: PARTNER_NOW };

/** The same console seen by a viewer, a developer, a suspended partner, or with central down. */
export const asLevel = (level: 'admin' | 'developer' | 'viewer', display = level === 'viewer' ? 'Aya Sato' : level === 'developer' ? 'Marat Lee' : 'Dana Kim'): Partial<MockPartnerState> => ({
  me: { ...partnerSeedState.me!, staff: { sub: level === 'viewer' ? 'u_9c0e1a77' : level === 'developer' ? 'u_2b91d4e0' : 'u_7f3a9c2e', display, level } },
});

export const suspendedPartner: Partial<MockPartnerState> = {
  me: { ...partnerSeedState.me!, partner: { ...partnerSeedState.me!.partner, status: 'suspended' } },
};

export const centralDown: Partial<MockPartnerState> = { centralUnavailable: true };

export const notAMember: Partial<MockPartnerState> = { notAMember: '+7 7•• ••• 11' };
