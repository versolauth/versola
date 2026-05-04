CREATE TABLE oauth_clients (
    id                TEXT NOT NULL,
    tenant_id         TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    client_name       TEXT NOT NULL,
    redirect_uris     TEXT[] NOT NULL,
    scope             TEXT[] NOT NULL,
    external_audience TEXT[] NOT NULL,
    secret            BYTEA,
    previous_secret   BYTEA,
    access_token_ttl  BIGINT NOT NULL,
    permissions       TEXT[] NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
