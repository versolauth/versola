-- One row per live device credential set: a mobile refresh token or an edge cookie session.
-- Written on the critical path of every refresh exchange (§7.4), so it carries no foreign key
-- to vu_users: the reference is guaranteed by construction (both tables are written by the same
-- seeder, and a driver only ever touches its own shard), and the constraint would cost a
-- referential lookup on every insert plus an ordering dependency on the re-seed.
CREATE UNLOGGED TABLE vu_sessions (
    id                 BIGINT      NOT NULL PRIMARY KEY,
    user_id            BIGINT      NOT NULL,
    kind               SMALLINT    NOT NULL,            -- mobile-token | web-cookie
    client_id          TEXT        NOT NULL,
    refresh_token      TEXT,
    edge_cookie        TEXT,
    access_expires_at  TIMESTAMPTZ,
    refresh_expires_at TIMESTAMPTZ,
    acr                TEXT,
    auth_time          TIMESTAMPTZ,
    -- Bumped before every refresh exchange, never after (§7.4). The dev spec writes this column
    -- with `DEFAULT 0`; dropped per versolauth/versola#267 -- the generation of a new session is
    -- a decision its creator makes, and a session inserted without one is a bug worth failing on.
    generation         INT         NOT NULL,
    shard              SMALLINT    NOT NULL
);

-- Serves `WHERE user_id = ?`: the scenario engine picking a user's sessions before deciding
-- between a refresh and a full login (design doc §2.3).
CREATE INDEX vu_sessions_user_idx ON vu_sessions (user_id);

-- Serves the driver's startup load of its own live sessions:
-- `WHERE shard = ? AND refresh_expires_at > now() ORDER BY refresh_expires_at LIMIT ?`.
-- Leading `shard` keeps it to one driver's slice; the trailing expiry both filters the dead
-- sessions inside the index and gives the scan its order.
CREATE INDEX vu_sessions_shard_idx ON vu_sessions (shard, refresh_expires_at);
