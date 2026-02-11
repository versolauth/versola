CREATE TABLE edge_tokens (
    session_id BYTEA NOT NULL PRIMARY KEY,
    client_id TEXT NOT NULL,
    access_token_hash BYTEA NOT NULL,
    refresh_token_hash BYTEA,
    scope TEXT[] NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (session_id) REFERENCES edge_sessions(id) ON DELETE CASCADE,
    FOREIGN KEY (client_id) REFERENCES edge_credentials(client_id) ON DELETE CASCADE
);

CREATE INDEX edge_tokens_access_token_hash_idx ON edge_tokens (access_token_hash);
CREATE INDEX edge_tokens_client_id_idx ON edge_tokens (client_id);
CREATE INDEX edge_tokens_expires_at_idx ON edge_tokens (expires_at);

