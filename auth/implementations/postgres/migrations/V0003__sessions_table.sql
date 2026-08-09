CREATE TABLE user_agents (
    id UUID NOT NULL PRIMARY KEY,
    user_id UUID NOT NULL,
    platform TEXT,
    os TEXT,
    browser TEXT,
    version TEXT,
    raw TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX user_agents_expires_at_idx
    ON user_agents (expires_at);

CREATE INDEX user_agents_user_id_idx
    ON user_agents (user_id);

CREATE TABLE sso_sessions (
    id BYTEA NOT NULL PRIMARY KEY,
    public_id TEXT NOT NULL UNIQUE,
    clients JSONB NOT NULL,
    user_id UUID NOT NULL,
    user_agent_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    amr JSONB NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    idle_expires_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX sessions_user_id_idx
    ON sso_sessions (user_id);

CREATE INDEX sso_sessions_expires_at_idx
    ON sso_sessions (expires_at);