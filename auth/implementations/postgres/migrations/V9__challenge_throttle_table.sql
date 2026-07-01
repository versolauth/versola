CREATE TABLE challenge_throttle (
    id             UUID NOT NULL PRIMARY KEY,
    tenant_id      TEXT NOT NULL,
    subject        TEXT NOT NULL,
    challenge_type TEXT NOT NULL,
    attempts       JSONB NOT NULL,
    banned_until   TIMESTAMP WITH TIME ZONE,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (tenant_id, subject, challenge_type)
);

CREATE INDEX challenge_throttle_expires_at_idx ON challenge_throttle (expires_at);
