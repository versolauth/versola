CREATE TABLE refresh_tokens(
    id BYTEA PRIMARY KEY,
    previous_id BYTEA,
    access_token BYTEA UNIQUE NOT NULL,
    session_id BYTEA NOT NULL,
    user_id UUID NOT NULL,
    client_id TEXT NOT NULL,
    external_audience TEXT[] NOT NULL,
    scope TEXT[] NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_claims JSONB,
    ui_locales TEXT[],
    nonce TEXT
);

CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_expires_at_idx ON refresh_tokens (expires_at) where expires_at is not null;
