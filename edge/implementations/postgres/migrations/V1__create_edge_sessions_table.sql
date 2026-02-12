CREATE TABLE edge_sessions (
    id BYTEA NOT NULL PRIMARY KEY,
    client_id TEXT NOT NULL,
    user_identifier TEXT NOT NULL,
    state TEXT,
    access_token_encrypted TEXT NOT NULL,
    refresh_token_encrypted TEXT,
    token_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    scope TEXT[] NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    session_expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX edge_sessions_client_id_idx ON edge_sessions (client_id);
CREATE INDEX edge_sessions_user_identifier_idx ON edge_sessions (user_identifier);
