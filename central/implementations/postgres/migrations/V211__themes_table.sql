CREATE TABLE themes (
    id        TEXT PRIMARY KEY,
    tenant_id TEXT REFERENCES tenants(id) ON DELETE CASCADE,
    css       TEXT NOT NULL
);

ALTER TABLE oauth_clients ADD COLUMN theme TEXT NOT NULL REFERENCES themes(id);

CREATE OR REPLACE FUNCTION notify_theme_change()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('theme_change', '');
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER themes_notify
AFTER INSERT OR UPDATE OR DELETE ON themes
FOR EACH STATEMENT EXECUTE FUNCTION notify_theme_change();
