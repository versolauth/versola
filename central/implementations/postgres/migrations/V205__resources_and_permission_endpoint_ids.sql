CREATE TABLE resources (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    resource    TEXT NOT NULL,
    endpoints   JSONB[] NOT NULL,
    UNIQUE (tenant_id, resource)
);
