CREATE TABLE refresh_tokens(
    id BYTEA PRIMARY KEY,
    -- Root token of the rotation chain this token belongs to; a freshly issued token is its
    -- own family. Kept on rotated-away rows so a token replayed any number of generations
    -- later still resolves to the family that has to be revoked.
    family_id BYTEA NOT NULL,
    -- Set when the token is exchanged for its successor. The row stays behind as the record
    -- of that exchange: unusable, but still resolvable to its family.
    rotated_at TIMESTAMP WITH TIME ZONE,
    -- MAC of the Idempotency-Key the exchange carried, if any. Lets the client that never
    -- received its response retry: presenting this token again with the same key continues
    -- the chain instead of being read as a replay. Only honoured while this row is the
    -- family's most recent exchange, so the key stops working the moment the chain moves on.
    idempotency_key BYTEA,
    -- Not indexed: the only lookup against this column (revoking the token issued by a
    -- replayed authorization code) is narrowed by session_id first, see
    -- PostgresSessionRepository.deleteByAccessToken. Indexing it would tax every rotation and
    -- every bound-token renewal to serve a rare admin-adjacent path.
    access_token BYTEA NOT NULL,
    -- When the access token named above expires. Recorded per row -- not derived from the
    -- client's current access_token_ttl -- because that TTL is mutable: a family-revocation
    -- push has to know how long the specific token it is revoking was actually valid for, not
    -- how long a token minted today would be. See PostgresSessionRepository.revokeFamily.
    access_token_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    session_id BYTEA NOT NULL,
    public_session_id TEXT NOT NULL,
    user_id UUID NOT NULL,
    client_id TEXT NOT NULL,
    audience TEXT[] NOT NULL,
    scope TEXT[] NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_claims JSONB,
    ui_locales TEXT[],
    nonce TEXT,
    acr TEXT,
    amr JSONB NOT NULL,
    auth_time TIMESTAMP WITH TIME ZONE NOT NULL,
    -- RFC 9449 §5: the JWK thumbprint this grant is bound to, NULL for a bearer grant.
    -- A bound token is never rotated: a copy of it is inert without the private key, so the
    -- family/rotated_at machinery above applies only to rows where this is NULL.
    cnf_jkt TEXT
);

CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_session_id_idx ON refresh_tokens (session_id);
CREATE INDEX refresh_tokens_expires_at_idx ON refresh_tokens (expires_at) where expires_at is not null;
-- Needed independent of sso_sessions: a refresh token's expiry slides forward on every use
-- while a session's does not, so a refresh token routinely outlives the session it was
-- issued under. Both invalidateByUserId (force-logout must reach tokens whose session has
-- already expired) and findRefreshTokensByUserId (admin-panel listing, same reason) require
-- refresh_tokens to be queryable by user_id on its own, not by joining through sso_sessions.
CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
