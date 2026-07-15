CREATE TABLE jwks (
    kid TEXT PRIMARY KEY,
    jwk JSONB NOT NULL
);

CREATE OR REPLACE FUNCTION notify_jwks_change()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('jwks_change', '');
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER jwks_notify
AFTER INSERT OR UPDATE OR DELETE ON jwks
FOR EACH STATEMENT EXECUTE FUNCTION notify_jwks_change();
