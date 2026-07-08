CREATE TABLE oauth_clients (
    id                TEXT NOT NULL PRIMARY KEY,
    tenant_id         TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    client_name       TEXT NOT NULL,
    redirect_uris     TEXT[] NOT NULL,
    scope             TEXT[] NOT NULL,
    external_audience TEXT[] NOT NULL,
    secret            BYTEA,
    previous_secret   BYTEA,
    access_token_ttl  BIGINT NOT NULL,
    refresh_token_ttl BIGINT NOT NULL,
    permissions       TEXT[] NOT NULL,
    auth_flow         JSONB,
    otp_template_id   TEXT NOT NULL
);

CREATE INDEX idx_oauth_clients_tenant_id ON oauth_clients (tenant_id);
