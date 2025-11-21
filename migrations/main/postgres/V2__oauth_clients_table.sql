-- OAuth Clients table with integrated secret management
CREATE TABLE oauth_clients (
    id TEXT PRIMARY KEY,
    client_name TEXT NOT NULL,
    redirect_uris TEXT[] NOT NULL,
    scope TEXT[] NOT NULL,
    secret_hash BYTEA,
    secret_salt BYTEA,
    previous_secret_hash BYTEA,
    previous_secret_salt BYTEA
);
