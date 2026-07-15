CREATE TABLE edge_refresh_tokens (
    id               TEXT PRIMARY KEY,
    preset_id        TEXT NOT NULL,
    refresh_token    BYTEA NOT NULL,
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX edge_refresh_tokens_preset_id_idx ON edge_refresh_tokens (preset_id);
CREATE INDEX edge_refresh_tokens_expires_at_idx ON edge_refresh_tokens (expires_at);
