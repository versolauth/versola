CREATE TABLE user_passwords (
    id SERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    password BYTEA NOT NULL,
    salt BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX user_passwords_user_id_idx
    ON user_passwords (user_id);

