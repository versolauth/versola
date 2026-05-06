CREATE TABLE edges (
    id                 TEXT PRIMARY KEY,
    public_key_jwk     JSONB NOT NULL,
    old_public_key_jwk JSONB
);

ALTER TABLE tenants
    ADD COLUMN edge_id TEXT REFERENCES edges(id) ON DELETE SET NULL;