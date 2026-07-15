-- OAuth Roles table
-- Roles define sets of permissions that can be assigned to users, scoped per tenant
CREATE TABLE roles (
    id           TEXT NOT NULL,
    tenant_id    TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    description  JSONB NOT NULL,
    permissions  TEXT[] NOT NULL,
    active       BOOLEAN NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

