ALTER TABLE challenge_settings
    ADD COLUMN temporary_password_ttl_seconds INT NOT NULL DEFAULT 43200;
