CREATE TABLE conversations (
    auth_id UUID NOT NULL PRIMARY KEY,
    steps JSONB NOT NULL,
    current_step JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX conversations_created_at_idx ON conversations (created_at);
CREATE INDEX conversations_updated_at_idx ON conversations (updated_at);
