-- The antifraud event log: what each OTP request and check did and how it ended, one row per step. Partitioned by
-- day so that retention is dropping a partition, which is instant and leaves nothing to vacuum; the partitions are
-- created and dropped by the service (AntifraudEvents), not by a migration.
--
-- The destination is stored as a keyed hash (subject_hash) and, for a phone number, its leading digits
-- (dest_prefix): enough to count and to group by range, not enough to read a number back.
-- decision, score and rules stay empty until decisions exist.
CREATE TABLE af_event (
    id              UUID        NOT NULL,
    at              TIMESTAMPTZ NOT NULL,
    tenant_id       TEXT,
    client_id       TEXT        NOT NULL,
    conversation_id UUID        NOT NULL,
    action          TEXT        NOT NULL,
    outcome         TEXT        NOT NULL,
    channel         TEXT        NOT NULL,
    subject_hash    TEXT        NOT NULL,
    dest_prefix     TEXT,
    user_agent      TEXT,
    decision        TEXT,
    score           INTEGER,
    rules           TEXT[]      NOT NULL,
    PRIMARY KEY (id, at)
) PARTITION BY RANGE (at);

-- Velocity and history of one destination.
CREATE INDEX af_event_subject_idx ON af_event (subject_hash, at);

-- Conversion per destination range: requests that were sent against codes that were verified.
CREATE INDEX af_event_dest_idx ON af_event (dest_prefix, action, outcome, at) WHERE dest_prefix IS NOT NULL;
