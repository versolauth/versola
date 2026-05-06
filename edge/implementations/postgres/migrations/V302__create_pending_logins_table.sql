CREATE TABLE pending_logins (
    login_id      TEXT PRIMARY KEY,
    state         TEXT NOT NULL UNIQUE,
    code_verifier TEXT NOT NULL,
    preset_id     TEXT NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_pending_logins_expires_at ON pending_logins(expires_at);
