CREATE TABLE authorization_codes (
    code BYTEA PRIMARY KEY,
    client_id TEXT NOT NULL,
    user_id UUID NOT NULL,
    redirect_uri TEXT NOT NULL,
    scope TEXT[] NOT NULL,
    code_challenge TEXT NOT NULL,
    code_challenge_method TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX authorization_codes_expires_at_idx
    ON authorization_codes (expires_at);

