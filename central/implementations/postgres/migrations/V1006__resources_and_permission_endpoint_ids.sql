CREATE TABLE resources (
    resource_id        TEXT PRIMARY KEY,
    tenant_id          TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    resource           TEXT NOT NULL,
    endpoints          JSONB[] NOT NULL,
    credential         BYTEA,
    previous_credential BYTEA,
    UNIQUE (tenant_id, resource)
);