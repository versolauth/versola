ALTER TABLE edges
    ADD COLUMN revocation_cache_size INT NOT NULL DEFAULT 10000;
