CREATE TABLE phone_settings (
    tenant_id        TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    allowed_prefixes TEXT[] NOT NULL,
    PRIMARY KEY (tenant_id)
);
