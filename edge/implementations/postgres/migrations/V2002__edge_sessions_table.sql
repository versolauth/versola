CREATE TABLE edge_sessions (
    public_session_id TEXT NOT NULL,
    preset_id         TEXT NOT NULL,
    access_token_id   TEXT NOT NULL UNIQUE,
    refresh_token     BYTEA,
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (public_session_id, preset_id)
);

CREATE INDEX edge_sessions_expires_at_idx ON edge_sessions (expires_at);
