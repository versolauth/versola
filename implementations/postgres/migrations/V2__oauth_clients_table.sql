CREATE TABLE oauth_clients (
    id TEXT PRIMARY KEY,
    client_name TEXT NOT NULL,
    redirect_uris TEXT[] NOT NULL,
    scope TEXT[] NOT NULL,
    secret BYTEA,
    previous_secret BYTEA,
    access_token_ttl BIGINT NOT NULL,
    access_token_type TEXT NOT NULL
);
