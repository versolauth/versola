CREATE TABLE access_tokens (
    id BYTEA PRIMARY KEY,
    session_id BYTEA NOT NULL,
    user_id UUID NOT NULL,
    client_id TEXT NOT NULL,
    scope TEXT[] NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX access_tokens_user_id_idx ON access_tokens (user_id);
CREATE INDEX access_tokens_expires_at_idx ON access_tokens (expires_at);

CREATE TABLE refresh_tokens(
    id BYTEA PRIMARY KEY,
    session_id BYTEA NOT NULL,
    user_id UUID NOT NULL,
    client_id TEXT NOT NULL,
    scope TEXT[] NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_expires_at_idx ON refresh_tokens (expires_at);
