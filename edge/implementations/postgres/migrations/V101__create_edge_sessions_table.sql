CREATE TABLE edge_sessions (
    id BYTEA NOT NULL PRIMARY KEY,
    client_id TEXT NOT NULL,
    state TEXT,
    access_token_encrypted TEXT NOT NULL,
    refresh_token_encrypted TEXT,
    scope TEXT[] NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX edge_sessions_client_id_idx ON edge_sessions (client_id);
