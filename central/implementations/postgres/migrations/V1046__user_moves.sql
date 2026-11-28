-- Moving a user from one cell to another (versolauth/versola#1051, #547 addendum 3.3), driven by central.
--
-- One row per user: a finished (`done`) or abandoned (`failed`) move is replaced by the next one, so the table is as large as
-- the number of users that were ever moved and no larger. Not a reference to `user_directory`: the user may be deleted while a
-- move is under way, and the move then has to be able to say so and clean up.
--
-- `phase`: leaving | exported | arrived | flipped | done | failed | abandoning (given up before the flip, the clean-up not finished).
-- `reason`: admin | drain. The values a row may hold are
-- decided by central before it writes, not by the table.
CREATE TABLE user_moves (
    user_id     UUID        NOT NULL PRIMARY KEY,
    from_cell   TEXT        NOT NULL,
    to_cell     TEXT        NOT NULL,
    phase       TEXT        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    -- Failed attempts of the step the move is at; set back to 0 when it reaches the next phase.
    attempts    INT         NOT NULL,
    -- The closed code of the last failure (or the reason the move was abandoned), never the text of an answer.
    error       TEXT,
    reason      TEXT        NOT NULL,
    -- Nothing works on the move before this time: the settle time after the source cell was told, the backoff after a failure, and
    -- the lease of the instance that took it (it is moved forward when a row is claimed, so that another instance skips it and takes
    -- it over if the first one died).
    not_before  TIMESTAMPTZ NOT NULL,
    -- When the source cell acknowledged that the user is leaving; null until then. The phase stays `leaving` for as long as the
    -- settle time and the export take, and this is what tells the first step from the next one.
    departed_at TIMESTAMPTZ,
    -- Names this move at both cells, new at every start: the target binds the arrival to it, so that an envelope of another move
    -- (or a replayed one) is refused. The row of the next move of the user carries another.
    move_id     UUID        NOT NULL,
    -- The digest of the rows the target was last given, as the source computed it when it exported them; null until the first
    -- delivery. Before the flip the source is asked again and a different digest is delivered again.
    digest      TEXT
);

-- What the processor looks the unfinished moves up by.
CREATE INDEX user_moves_phase_idx ON user_moves (phase);

-- What `countInCell` of the topology answer and the drain of a cell look the users of a cell up by. Built on a table with a row
-- per user of the partition, which takes a moment and a write lock while it runs.
CREATE INDEX user_directory_cell_idx ON user_directory (cell_id);
