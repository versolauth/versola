CREATE TABLE auth_conversations (
    id UUID NOT NULL PRIMARY KEY,
    step TEXT,
    user_id UUID NOT NULL
);

CREATE INDEX auth_conversations_user_id_idx ON auth_conversations (user_id);