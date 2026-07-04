CREATE TABLE resources (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    alias       TEXT NOT NULL,
    resource    TEXT NOT NULL,
    endpoints   JSONB[] NOT NULL,
    UNIQUE (tenant_id, resource),
    UNIQUE (tenant_id, alias)
);
