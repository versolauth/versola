CREATE TABLE themes (
    id        TEXT PRIMARY KEY,
    tenant_id TEXT REFERENCES tenants(id) ON DELETE CASCADE,
    css       TEXT NOT NULL
);

ALTER TABLE oauth_clients ADD COLUMN theme TEXT NOT NULL REFERENCES themes(id);
