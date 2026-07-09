CREATE TABLE sso_sessions (
    id BYTEA NOT NULL PRIMARY KEY,
    public_session_id UUID NOT NULL UNIQUE,
    client_id TEXT,
    user_id UUID NOT NULL,
    user_agent JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    amr JSONB NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    idle_expires_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX sessions_user_id_idx
    ON sso_sessions (user_id);

CREATE INDEX sso_sessions_expires_at_idx
    ON sso_sessions (expires_at);

CREATE INDEX sso_sessions_public_session_id_idx
    ON sso_sessions (public_session_id);