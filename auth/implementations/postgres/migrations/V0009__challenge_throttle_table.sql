CREATE TABLE challenge_throttle (
    subject        TEXT NOT NULL,
    tenant_id      TEXT NOT NULL,
    challenge_type TEXT NOT NULL,
    attempts       JSONB NOT NULL,
    banned_until   TIMESTAMP WITH TIME ZONE,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Bumped on every write. Writers pass the version they read and their update only
    -- lands if it still matches, so two concurrent attempts cannot both be recorded on
    -- top of the same state (which would let one of them go uncounted).
    version        BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (subject, tenant_id, challenge_type)
);

CREATE INDEX challenge_throttle_expires_at_idx ON challenge_throttle (expires_at);
