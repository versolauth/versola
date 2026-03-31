CREATE TABLE oauth_scopes (
    id          TEXT NOT NULL,
    tenant_id   TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    description JSONB NOT NULL,
    claims      JSONB[] NOT NULL,
    PRIMARY KEY (tenant_id, id)
);