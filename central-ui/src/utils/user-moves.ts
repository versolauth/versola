import type { UserMove, UserMoveError, UserMovePhase, UserMoveReason } from '../types';

/** The phases of a move in the order it passes them; `failed` is a move abandoned before the directory was changed. */
export const MOVE_PHASES: Array<{ id: UserMovePhase; label: string; text: string }> = [
  { id: 'leaving', label: 'Leaving', text: 'The old cell is told the user is leaving and answers temporarily unavailable for them.' },
  { id: 'exported', label: 'Exported', text: 'The user\'s data is read from the old cell as one sealed envelope.' },
  { id: 'arrived', label: 'Arrived', text: 'The new cell holds the data; the directory still names the old cell.' },
  { id: 'flipped', label: 'Flipped', text: 'The directory names the new cell. The old cell does not serve the user until it completes.' },
  { id: 'done', label: 'Done', text: 'The old cell dropped what it held.' },
  { id: 'abandoning', label: 'Abandoning', text: 'The move is being undone: the new cell forgets the user and the old cell serves them again.' },
  { id: 'failed', label: 'Failed', text: 'Abandoned before the directory was changed: the old cell serves the user again.' },
];

export const MOVE_REASONS: Record<UserMoveReason, string> = {
  admin: 'Requested by an administrator',
  drain: 'Draining the cell',
};

/** The closed set of codes a move records as its `error`: what central says went wrong, never what a cell answered. */
export const MOVE_ERRORS: Record<UserMoveError, string> = {
  depart_failed: 'The old cell could not be told the user is leaving.',
  source_lacks_user: 'The old cell does not hold this user.',
  export_failed: 'The user\'s data could not be read from the old cell.',
  arrive_failed: 'The new cell did not accept the user\'s data.',
  target_has_user: 'The new cell already has this user or one of their identifiers.',
  user_gone: 'The directory no longer names the old cell for this user: it was deleted or moved.',
  flip_failed: 'The directory could not be switched to the new cell. Central retries until it succeeds.',
  complete_failed: 'The old cell could not drop the user\'s data. Central retries until it succeeds.',
  source_changed: 'The user\'s data kept changing in the old cell while it was being carried.',
  departure_exists: 'The old cell already has another move of this user under way.',
  arrive_rejected: 'The new cell refused the user\'s data.',
  invalid_document: 'The new cell does not know a tenant, role or client the user\'s data names.',
  keep_failed: 'The new cell could not be told the move is final. Central retries until it succeeds.',
  cell_unavailable: 'A cell of the move is not registered or its address is not usable.',
};

export const phaseLabel = (phase: UserMovePhase): string => MOVE_PHASES.find(entry => entry.id === phase)?.label ?? phase;

export const reasonLabel = (reason: UserMoveReason): string => MOVE_REASONS[reason] ?? reason;

export const errorLabel = (code: UserMoveError): string => MOVE_ERRORS[code] ?? code;

/** A move that has not reached `done` or `failed`; the user's changes are refused meanwhile. */
export const moveUnfinished = (move: UserMove): boolean => move.phase !== 'done' && move.phase !== 'failed';
