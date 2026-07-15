-- Lightweight index of users used for fast lookup in central (routing keys only).
-- Source of truth for the full user record (claims, roles, etc.) remains in auth.
CREATE TABLE user_index (
    id     UUID NOT NULL PRIMARY KEY,
    email  TEXT,
    phone  TEXT,
    login  TEXT
);

CREATE UNIQUE INDEX user_index_email_idx ON user_index (email) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX user_index_phone_idx ON user_index (phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX user_index_login_idx ON user_index (login) WHERE login IS NOT NULL;

-- Outbox of user-related events to be dispatched to auth.
-- id is UUIDv7 so it embeds creation time and provides natural ordering.
-- Successful dispatches DELETE the row, failures bump attempts and next_attempt_at.
CREATE TABLE user_outbox (
    id              UUID        NOT NULL PRIMARY KEY,
    user_id         UUID        NOT NULL,
    event_type      TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    attempts        INT         NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX user_outbox_next_attempt_at_idx ON user_outbox (next_attempt_at);
CREATE INDEX user_outbox_user_id_id_idx ON user_outbox (user_id, id);

-- Dead letter table for events that exceeded max attempts.
CREATE TABLE user_outbox_dead (
    id              UUID        NOT NULL PRIMARY KEY,
    user_id         UUID        NOT NULL,
    event_type      TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    attempts        INT         NOT NULL,
    failed_at       TIMESTAMPTZ NOT NULL,
    error           TEXT
);

