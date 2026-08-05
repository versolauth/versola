CREATE TABLE server_metadata (
    id TEXT PRIMARY KEY,
    metadata JSONB NOT NULL
);

CREATE OR REPLACE FUNCTION notify_metadata_change()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('metadata_change', '');
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER metadata_notify
AFTER INSERT OR UPDATE OR DELETE ON server_metadata
FOR EACH STATEMENT EXECUTE FUNCTION notify_metadata_change();
