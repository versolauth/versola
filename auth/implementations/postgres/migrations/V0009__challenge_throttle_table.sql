CREATE TABLE challenge_throttle (
    subject        TEXT NOT NULL,
    tenant_id      TEXT NOT NULL,
    challenge_type TEXT NOT NULL,
    attempts       JSONB NOT NULL,
    banned_until   TIMESTAMP WITH TIME ZONE,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (subject, tenant_id, challenge_type)
);

CREATE INDEX challenge_throttle_expires_at_idx ON challenge_throttle (expires_at);
