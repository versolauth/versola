CREATE TABLE pending_logins (
    state         TEXT PRIMARY KEY,
    code_verifier TEXT NOT NULL,
    preset_id     TEXT NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_pending_logins_expires_at ON pending_logins(expires_at);
