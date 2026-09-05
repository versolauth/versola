CREATE TABLE refresh_tokens(
    id BYTEA PRIMARY KEY,
    -- Root token of the rotation chain this token belongs to; a freshly issued token is its
    -- own family. Kept on rotated-away rows so a token replayed any number of generations
    -- later still resolves to the family that has to be revoked.
    family_id BYTEA NOT NULL,
    -- Set when the token is exchanged for its successor. The row stays behind as the record
    -- of that exchange: unusable, but still resolvable to its family.
    rotated_at TIMESTAMP WITH TIME ZONE,
    access_token BYTEA UNIQUE NOT NULL,
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
    auth_time TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_session_id_idx ON refresh_tokens (session_id);
CREATE INDEX refresh_tokens_expires_at_idx ON refresh_tokens (expires_at) where expires_at is not null;
