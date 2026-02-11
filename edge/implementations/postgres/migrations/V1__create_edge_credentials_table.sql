CREATE TABLE edge_credentials (
    client_id TEXT NOT NULL PRIMARY KEY,
    client_secret_hash BYTEA NOT NULL,
    provider_url TEXT NOT NULL,
    scopes TEXT[] NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX edge_credentials_created_at_idx ON edge_credentials (created_at);

