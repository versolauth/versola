CREATE TABLE authorization_codes (
    code BYTEA PRIMARY KEY,
    client_id TEXT NOT NULL,
    user_id UUID NOT NULL,
    session_id BYTEA NOT NULL,
    public_session_id TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    scope TEXT[] NOT NULL,
    resources TEXT[] NOT NULL,
    -- Optional as on the conversation it ends: both or neither, held by the CHECK below.
    code_challenge TEXT,
    code_challenge_method TEXT,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_claims JSONB,
    ui_locales TEXT[],
    nonce TEXT,
    used BOOLEAN NOT NULL,
    access_token BYTEA NOT NULL,
    -- The rotation family the exchange of this code will start, generated here rather than at
    -- the exchange so that a replay of the code -- which has only the code row to go on -- can
    -- name what the first exchange issued and revoke it.
    family_id TEXT NOT NULL,
    acr TEXT,
    amr JSONB NOT NULL,
    auth_time TIMESTAMP WITH TIME ZONE NOT NULL,
    -- RFC 9396 `authorization_details`, stored verbatim; see auth_conversations.
    authorization_details JSONB[],
    -- RFC 9449 section 10 `dpop_jkt` the code is bound to; see auth_conversations.
    dpop_jkt TEXT,
    CONSTRAINT authorization_codes_pkce_pair
        CHECK ((code_challenge IS NULL) = (code_challenge_method IS NULL))
);

CREATE INDEX authorization_codes_expires_at_idx
    ON authorization_codes (expires_at);

