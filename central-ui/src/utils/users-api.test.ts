import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { configureCentralApi } from './central-api';
import { USER_MOVING_MESSAGE, failureText, searchUsers } from './users-api';

describe('failureText', () => {
  it('turns central\'s user_moving answer into a readable message', () => {
    expect(failureText(409, 'the user is being moved to another cell, try again when the move is over', 'Update failed (409)')).toBe(USER_MOVING_MESSAGE);
  });

  it('leaves another 409 and every other status as central said it', () => {
    expect(failureText(409, ' identifier taken ', 'x')).toBe('identifier taken');
    expect(failureText(500, 'the user is being moved to another cell', 'x')).toBe('the user is being moved to another cell');
  });

  it('falls back to the caller\'s text when the body is empty', () => {
    expect(failureText(502, '  ', 'Update failed (502)')).toBe('Update failed (502)');
  });
});

describe('searchUsers', () => {
  beforeEach(() => configureCentralApi({ baseUrl: 'http://central.test' }));
  afterEach(() => { vi.unstubAllGlobals(); configureCentralApi({ baseUrl: null }); });
  const answer = (users: unknown[]) => vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ users }), { status: 200 })));
  const move = { userId: 'u', from: 'kz-1', to: 'kz-2', phase: 'leaving', reason: 'admin', startedAt: 'a', updatedAt: 'b', attempts: 0 };

  it('carries the cell and the latest move of the record', async () => {
    answer([{ id: 'u', claims: {}, cell: 'kz-1', move }]);
    expect(await searchUsers('id', 'u')).toEqual([{ id: 'u', claims: {}, cell: 'kz-1', move }]);
  });

  it('reads an absent cell and move as none', async () => {
    answer([{ id: 'u', claims: {} }, { id: 'v', claims: {}, cell: null, move: null }]);
    expect(await searchUsers('id', 'u')).toMatchObject([{ cell: null, move: null }, { cell: null, move: null }]);
  });
});
