-- tenant_change — empty payload, reload all
CREATE OR REPLACE FUNCTION notify_tenant_change()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('tenant_change', '');
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tenants_notify
AFTER INSERT OR UPDATE OR DELETE ON tenants
FOR EACH STATEMENT EXECUTE FUNCTION notify_tenant_change();


-- client_change — JSON payload with tenantId, id and op
CREATE OR REPLACE FUNCTION notify_client_change()
RETURNS trigger AS $$
DECLARE
  rec RECORD;
BEGIN
  rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  PERFORM pg_notify('client_change', json_build_object('tenantId', rec.tenant_id, 'id', rec.id, 'op', TG_OP)::text);
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER oauth_clients_notify
AFTER INSERT OR UPDATE OR DELETE ON oauth_clients
FOR EACH ROW EXECUTE FUNCTION notify_client_change();


-- scope_change — JSON payload with tenantId, id and op
CREATE OR REPLACE FUNCTION notify_scope_change()
RETURNS trigger AS $$
DECLARE
  rec RECORD;
BEGIN
  rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  PERFORM pg_notify('scope_change', json_build_object('tenantId', rec.tenant_id, 'id', rec.id, 'op', TG_OP)::text);
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER oauth_scopes_notify
AFTER INSERT OR UPDATE OR DELETE ON oauth_scopes
FOR EACH ROW EXECUTE FUNCTION notify_scope_change();


-- role_change — JSON payload with tenantId, id and op
CREATE OR REPLACE FUNCTION notify_role_change()
RETURNS trigger AS $$
DECLARE
  rec RECORD;
BEGIN
  rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  PERFORM pg_notify('role_change', json_build_object('tenantId', rec.tenant_id, 'id', rec.id, 'op', TG_OP)::text);
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER roles_notify
AFTER INSERT OR UPDATE OR DELETE ON roles
FOR EACH ROW EXECUTE FUNCTION notify_role_change();


-- permission_change — JSON payload with tenantId, id and op
CREATE OR REPLACE FUNCTION notify_permission_change()
RETURNS trigger AS $$
DECLARE
  rec RECORD;
BEGIN
  rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  PERFORM pg_notify('permission_change', json_build_object('tenantId', rec.tenant_id, 'id', rec.id, 'op', TG_OP)::text);
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER permissions_notify
AFTER INSERT OR UPDATE OR DELETE ON permissions
FOR EACH ROW EXECUTE FUNCTION notify_permission_change();

CREATE OR REPLACE FUNCTION notify_resource_change()
RETURNS trigger AS $$
DECLARE
    rec RECORD;
    resolved_tenant_id TEXT;
    resolved_resource_id BIGINT;
BEGIN
    rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    resolved_tenant_id := rec.tenant_id;
    resolved_resource_id := rec.id;

    PERFORM pg_notify(
        'resource_change',
        json_build_object('tenantId', resolved_tenant_id, 'id', resolved_resource_id, 'op', TG_OP)::text
    );
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER resources_notify
AFTER INSERT OR UPDATE OR DELETE ON resources
FOR EACH ROW EXECUTE FUNCTION notify_resource_change();