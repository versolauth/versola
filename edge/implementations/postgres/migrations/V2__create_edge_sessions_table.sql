CREATE TABLE edge_sessions (
    id BYTEA NOT NULL PRIMARY KEY,
    client_id TEXT NOT NULL,
    user_identifier TEXT NOT NULL,
    state TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (client_id) REFERENCES edge_credentials(client_id) ON DELETE CASCADE
);

CREATE INDEX edge_sessions_client_id_idx ON edge_sessions (client_id);
CREATE INDEX edge_sessions_user_identifier_idx ON edge_sessions (user_identifier);
CREATE INDEX edge_sessions_expires_at_idx ON edge_sessions (expires_at);

