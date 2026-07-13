CREATE TABLE user_passwords (
    id SERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    password BYTEA NOT NULL,
    salt BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NULL
);

CREATE INDEX user_passwords_user_id_idx
    ON user_passwords (user_id);

CREATE INDEX user_passwords_expires_at_idx
    ON user_passwords (expires_at)
    WHERE expires_at IS NOT NULL;

