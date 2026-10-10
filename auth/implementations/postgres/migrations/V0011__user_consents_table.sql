-- Persistent OAuth/OIDC consent grants. A grant deliberately outlives the SSO session, so a
-- new session does not re-prompt: it is keyed by (user, client) only and revoked explicitly.
-- `expires_at` is NULL when the client's consent flow remembers the grant until revoked.
CREATE TABLE user_consents (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL,
    scope TEXT[] NOT NULL,
    granted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (user_id, client_id)
);
