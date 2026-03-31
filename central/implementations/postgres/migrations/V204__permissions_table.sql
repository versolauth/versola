CREATE TABLE permissions (
    tenant_id   TEXT REFERENCES tenants(id) ON DELETE CASCADE,
    id          TEXT NOT NULL,
    description JSONB NOT NULL,
    endpoint_ids UUID[] NOT NULL,
    PRIMARY KEY (tenant_id, id)
);