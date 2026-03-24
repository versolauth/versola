CREATE TABLE auth_conversations (
    id UUID NOT NULL PRIMARY KEY,
    client_id TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    scope TEXT[] NOT NULL,
    code_challenge TEXT NOT NULL,
    code_challenge_method TEXT NOT NULL,
    state TEXT,
    user_id UUID,
    credential TEXT,
    step JSON NOT NULL,
    requested_claims JSON,
    ui_locales TEXT[],
    nonce TEXT,
    response_type TEXT NOT NULL,
    user_email TEXT,
    user_phone TEXT,
    user_login TEXT,
    user_claims JSON,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX auth_conversations_credential_idx
    ON auth_conversations (credential)
    WHERE credential IS NOT NULL;