-- Lightweight index of users mirrored from auth, used for fast lookup in central.
-- Source of truth for the full user record (claims, etc.) remains in auth.
CREATE TABLE user_index (
    id    UUID NOT NULL PRIMARY KEY,
    email TEXT,
    phone TEXT,
    login TEXT
);

CREATE UNIQUE INDEX user_index_email_idx ON user_index (email) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX user_index_phone_idx ON user_index (phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX user_index_login_idx ON user_index (login) WHERE login IS NOT NULL;

-- Outbox of user-related events to be dispatched to auth.
-- id is UUIDv7 so it embeds creation time and provides natural ordering.
-- Successful dispatches DELETE the row, failures bump attempts and next_attempt_at.
CREATE TABLE user_outbox (
    id              UUID        NOT NULL PRIMARY KEY,
    event_type      TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    attempts        INT         NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX user_outbox_next_attempt_at_idx ON user_outbox (next_attempt_at);
