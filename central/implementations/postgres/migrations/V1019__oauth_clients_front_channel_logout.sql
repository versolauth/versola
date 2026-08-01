ALTER TABLE oauth_clients
    ADD COLUMN front_channel_logout_uri TEXT,
    ADD COLUMN front_channel_logout_session_required BOOLEAN NOT NULL DEFAULT FALSE;
