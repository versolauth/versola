CREATE TABLE edges (
    id                 TEXT PRIMARY KEY,
    public_key_jwk     JSONB NOT NULL,
    old_public_key_jwk JSONB
);

ALTER TABLE tenants
    ADD COLUMN edge_id TEXT REFERENCES edges(id) ON DELETE SET NULL;

CREATE INDEX idx_tenants_edge_id ON tenants (edge_id);

-- edge_change — empty payload, reload all
CREATE OR REPLACE FUNCTION notify_edge_change()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('edge_change', '');
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER edges_notify
AFTER INSERT OR UPDATE OR DELETE ON edges
FOR EACH STATEMENT EXECUTE FUNCTION notify_edge_change();