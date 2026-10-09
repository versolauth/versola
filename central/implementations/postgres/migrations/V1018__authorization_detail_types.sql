-- RFC 9396 authorization detail type registry: the per-tenant vocabulary of
-- `authorization_details` types the AS accepts, each with the JSON Schema
-- (2020-12) its objects are validated against.
CREATE TABLE authorization_detail_types (
    tenant_id   TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    type        TEXT NOT NULL,
    description JSONB NOT NULL,
    schema      JSONB NOT NULL,
    PRIMARY KEY (tenant_id, type)
);

CREATE OR REPLACE FUNCTION notify_authorization_detail_type_change()
RETURNS trigger AS $$
DECLARE
  rec RECORD;
BEGIN
  rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  PERFORM pg_notify(
    'authorization_detail_type_change',
    json_build_object('tenantId', rec.tenant_id, 'id', rec.type, 'op', TG_OP)::text
  );
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER authorization_detail_types_notify
AFTER INSERT OR UPDATE OR DELETE ON authorization_detail_types
FOR EACH ROW EXECUTE FUNCTION notify_authorization_detail_type_change();
