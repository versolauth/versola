ALTER TABLE challenge_settings
    ADD COLUMN signing_algorithm TEXT NOT NULL DEFAULT 'RS256';
