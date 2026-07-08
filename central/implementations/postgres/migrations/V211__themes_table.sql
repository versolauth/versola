CREATE TABLE themes (
    id        TEXT PRIMARY KEY,
    tenant_id TEXT REFERENCES tenants(id) ON DELETE CASCADE,
    css       TEXT NOT NULL
);

ALTER TABLE oauth_clients ADD COLUMN theme TEXT NOT NULL REFERENCES themes(id);

CREATE INDEX idx_themes_tenant_id ON themes (tenant_id);
CREATE INDEX idx_oauth_clients_theme ON oauth_clients (theme);
