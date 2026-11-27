-- Users moving to another cell, and what is left of the ones that moved (versolauth/versola#1051).
--
-- A cell that is emptied or rebalanced hands a user to another cell: central starts the move, this cell marks the user
-- `leaving` (every request that would read or change the user is then answered "temporarily unavailable"), the user's rows are
-- exported to the new cell, and once central names the new cell the old one completes the move: the rows are deleted and the
-- user is `moved`. The rules (which state follows which) are in the code, not here.
CREATE TABLE user_departures (
    user_id UUID PRIMARY KEY,
    -- The cell the user is going to, as central named it. Not a reference: cells are not rows of this database.
    to_cell TEXT NOT NULL,
    -- The id central gave the move: a repeat of the same move is accepted, another one is refused while this departure stands, and
    -- the export carries it, so that an arrival can be bound to the move it belongs to.
    move_id UUID NOT NULL,
    -- `leaving` or `moved`.
    state TEXT NOT NULL,
    -- When the user was marked, and when the move was completed.
    at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- What is left of a moved user's refresh tokens, so that a token presented here later is answered `moved` and not
-- `invalid_grant`: the id is the MAC of the token (the key of `refresh_tokens`), nothing of the token itself. A row is
-- of no use past `expires_at`, which is also what the cleanup of expired rows reads.
CREATE TABLE departed_refresh_tokens (
    id BYTEA PRIMARY KEY,
    to_cell TEXT NOT NULL,
    -- The client the token was issued to: `moved` is answered only to that client, any other is told what it is told for a token
    -- that does not exist.
    client_id TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX departed_refresh_tokens_expires_at_idx ON departed_refresh_tokens (expires_at);

-- A user that arrived from another cell, and the move that brought them: an envelope is acted on only for the move it was made for,
-- and a move id that was seen is not taken again (a replay of an old envelope must not resurrect or overwrite anything). The row
-- stays when the user is deleted or moves on, and is removed by the hourly cleanup once it is a day old and the user is not here.
CREATE TABLE user_arrivals (
    user_id UUID PRIMARY KEY,
    move_id UUID NOT NULL,
    -- The cell that sealed the envelope.
    from_cell TEXT NOT NULL,
    -- `pending` (the rows are here, the directory still names the other cell) or `kept` (central flipped the directory).
    state TEXT NOT NULL,
    -- When the rows were written, or last replaced, or kept.
    at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX user_arrivals_at_idx ON user_arrivals (at);
