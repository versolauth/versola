CREATE TABLE auth_conversations (
    id UUID NOT NULL PRIMARY KEY,
    client_id TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    scope TEXT[] NOT NULL,
    code_challenge TEXT NOT NULL,
    code_challenge_method TEXT NOT NULL,
    user_id UUID,
    credential TEXT,
    step JSON NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX auth_conversations_credential_idx
    ON auth_conversations (credential)
    WHERE credential IS NOT NULL;