-- Outbox of users created by self-service registration, dispatched to central so its
-- user_index learns about accounts auth created on its own. Mirrors central's user_outbox:
-- id is UUIDv7 so it embeds creation time and provides natural ordering, successful
-- dispatches DELETE the row, and failures bump attempts and next_attempt_at.
CREATE TABLE user_registration_outbox (
    id              UUID        NOT NULL PRIMARY KEY,
    user_id         UUID        NOT NULL,
    payload         JSONB       NOT NULL,
    attempts        INT         NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX user_registration_outbox_next_attempt_at_idx
    ON user_registration_outbox (next_attempt_at);
CREATE INDEX user_registration_outbox_user_id_id_idx
    ON user_registration_outbox (user_id, id);

CREATE TABLE user_registration_outbox_dead (
    id              UUID        NOT NULL PRIMARY KEY,
    user_id         UUID        NOT NULL,
    payload         JSONB       NOT NULL,
    attempts        INT         NOT NULL,
    failed_at       TIMESTAMPTZ NOT NULL,
    error           TEXT
);
