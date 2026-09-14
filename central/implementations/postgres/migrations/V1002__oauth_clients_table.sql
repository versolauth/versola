CREATE TABLE oauth_clients (
    id                TEXT NOT NULL PRIMARY KEY,
    tenant_id         TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    client_name       TEXT NOT NULL,
    redirect_uris     TEXT[] NOT NULL,
    scope             TEXT[] NOT NULL,
    secret            BYTEA,
    previous_secret   BYTEA,
    access_token_ttl  BIGINT NOT NULL,
    refresh_token_ttl BIGINT NOT NULL,
    permissions       TEXT[] NOT NULL,
    auth_flow         JSONB,
    otp_template_id   TEXT NOT NULL,
    front_channel_logout_uri TEXT,
    front_channel_logout_session_required BOOLEAN NOT NULL DEFAULT FALSE,
    back_channel_logout_uri TEXT,
    -- RFC 8705 §2.1 `tls_client_auth`. NULL means the client does not authenticate with a
    -- certificate.
    mtls_auth JSONB,
    -- RFC 8705 §3.4. Only consulted for clients that authenticate some other way: a client
    -- with mtls_auth set binds regardless, see OAuthClientRecord.bindsAccessTokens.
    certificate_bound_access_tokens BOOLEAN NOT NULL DEFAULT FALSE
);
