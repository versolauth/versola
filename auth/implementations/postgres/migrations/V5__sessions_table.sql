CREATE TABLE sso_sessions (
    id BYTEA NOT NULL PRIMARY KEY,
    client_id TEXT,
    user_id UUID NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX sessions_user_id_idx
    ON sso_sessions (user_id);