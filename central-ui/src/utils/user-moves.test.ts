import { describe, expect, it } from 'vitest';
import type { UserMove } from '../types';
import { MOVE_ERRORS, MOVE_PHASES, MOVE_REASONS, errorLabel, moveUnfinished, phaseLabel, reasonLabel } from './user-moves';

const move = (phase: UserMove['phase']): UserMove => ({
  userId: 'u', from: 'kz-1', to: 'kz-2', phase, reason: 'admin', startedAt: '2026-11-20T10:00:00Z', updatedAt: '2026-11-20T10:00:05Z', attempts: 0,
});

describe('user moves', () => {
  it('labels every phase, reason and error code the API knows, and falls back to the code for one it does not', () => {
    expect(MOVE_PHASES.map(phase => phase.id)).toEqual(['leaving', 'exported', 'arrived', 'flipped', 'done', 'abandoning', 'failed']);
    expect(Object.keys(MOVE_REASONS)).toEqual(['admin', 'drain']);
    expect(Object.keys(MOVE_ERRORS)).toEqual([
      'depart_failed', 'source_lacks_user', 'export_failed', 'arrive_failed', 'target_has_user', 'user_gone', 'flip_failed', 'complete_failed',
      'source_changed', 'departure_exists', 'arrive_rejected', 'invalid_document', 'keep_failed', 'cell_unavailable',
    ]);
    expect(phaseLabel('flipped')).toBe('Flipped');
    expect(reasonLabel('drain')).toBe('Draining the cell');
    expect(errorLabel('target_has_user')).toContain('already has this user');
    expect(phaseLabel('later' as never)).toBe('later');
  });

  it('treats a move as unfinished until it is done or failed', () => {
    expect(['leaving', 'exported', 'arrived', 'flipped', 'abandoning'].map(phase => moveUnfinished(move(phase as UserMove['phase'])))).toEqual([true, true, true, true, true]);
    expect(moveUnfinished(move('done'))).toBe(false);
    expect(moveUnfinished(move('failed'))).toBe(false);
  });
});
