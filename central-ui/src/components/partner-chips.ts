import { html } from 'lit';
import type { Edge, Partner, PartnerStatus } from '../types';
import { PARTNER_STATUS_LABELS } from '../utils/partners';
import { statusIcon } from './status-icon';

/** The status chip of a partner (#549), shared by Partners and Edges. The host needs `chipStyles`. */
export function partnerStatusChip(status: PartnerStatus) {
  const label = PARTNER_STATUS_LABELS[status];
  if (status === 'active') return html`<span class="chip ok">${statusIcon('check', 'icon', 14)}${label}</span>`;
  if (status === 'suspended') return html`<span class="chip danger">${label}</span>`;
  return html`<span class="chip">${label}</span>`;
}

export type CertificateState = { label: string; tone: 'ok' | 'warn' | 'danger' | 'muted' };

/** The state of a partner edge's certificate, in words: not enrolled yet, renewing by itself, or refused because the partner is suspended. */
export function edgeCertificateState(edge: Pick<Edge, 'certificateNotAfter' | 'lastRenewedAt'>, partner: Pick<Partner, 'status'> | null): CertificateState {
  if (!edge.certificateNotAfter) return { label: 'Waiting for enrollment', tone: 'muted' };
  if (partner?.status === 'suspended') return { label: 'Renewal refused', tone: 'danger' };
  return { label: 'Renews by itself', tone: 'ok' };
}

export function certificateChip(state: CertificateState) {
  return html`<span class="chip ${state.tone === 'muted' ? 'muted-chip' : state.tone}">${state.tone === 'ok' ? statusIcon('check', 'icon', 14) : ''}${state.label}</span>`;
}
