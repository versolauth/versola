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

-- The scope actually granted on the consent screen, which may be a subset of the requested
-- `scope` when the client allows partial grants. NULL until consent has been resolved.
ALTER TABLE auth_conversations
    ADD COLUMN granted_scope TEXT[];

-- OIDC `prompt=consent` must re-prompt even when a matching grant is already on file, and the
-- decision is taken long after `/authorize` has returned, so the request's intent is persisted.
ALTER TABLE auth_conversations
    ADD COLUMN prompt_consent BOOLEAN NOT NULL DEFAULT FALSE;
