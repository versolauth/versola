-- Explicit edge scope for resources and OAuth clients.
-- These links are intentionally separate from the tenant model: a tenant may
-- contain data that is not deployed to every edge serving that tenant.
CREATE TABLE edge_resources (
    edge_id     TEXT NOT NULL REFERENCES edges(id) ON DELETE CASCADE,
    resource_id TEXT NOT NULL REFERENCES resources(resource_id) ON DELETE CASCADE,
    PRIMARY KEY (edge_id, resource_id)
);

CREATE INDEX edge_resources_resource_id_idx ON edge_resources(resource_id);

CREATE TABLE edge_clients (
    edge_id   TEXT NOT NULL REFERENCES edges(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL REFERENCES oauth_clients(id) ON DELETE CASCADE,
    PRIMARY KEY (edge_id, client_id)
);

CREATE INDEX edge_clients_client_id_idx ON edge_clients(client_id);

-- Preserve the existing tenant-based assignments during migration. Rows whose
-- tenant is intentionally unassigned remain unavailable to edge sync.
INSERT INTO edge_resources (edge_id, resource_id)
SELECT t.edge_id, r.resource_id
FROM resources r
JOIN tenants t ON t.id = r.tenant_id
WHERE t.edge_id IS NOT NULL;

INSERT INTO edge_clients (edge_id, client_id)
SELECT t.edge_id, c.id
FROM oauth_clients c
JOIN tenants t ON t.id = c.tenant_id
WHERE t.edge_id IS NOT NULL;

-- Link changes must refresh the corresponding cached record. The resource and
-- client payloads still use their owning tenant/id, as existing sync events do.
CREATE OR REPLACE FUNCTION notify_edge_resource_change()
RETURNS trigger AS $$
DECLARE
  resource_row RECORD;
BEGIN
  SELECT tenant_id, resource_id INTO resource_row
  FROM resources
  WHERE resource_id = CASE WHEN TG_OP = 'DELETE' THEN OLD.resource_id ELSE NEW.resource_id END;
  IF resource_row IS NOT NULL THEN
    PERFORM pg_notify('resource_change', json_build_object(
      'tenantId', resource_row.tenant_id,
      'id', resource_row.resource_id,
      'op', 'UPDATE'
    )::text);
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER edge_resources_notify
AFTER INSERT OR UPDATE OR DELETE ON edge_resources
FOR EACH ROW EXECUTE FUNCTION notify_edge_resource_change();

CREATE OR REPLACE FUNCTION notify_edge_client_change()
RETURNS trigger AS $$
DECLARE
  client_row RECORD;
BEGIN
  SELECT id INTO client_row
  FROM oauth_clients
  WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.client_id ELSE NEW.client_id END;
  IF client_row IS NOT NULL THEN
    PERFORM pg_notify('client_change', json_build_object(
      'id', client_row.id,
      'op', 'UPDATE'
    )::text);
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER edge_clients_notify
AFTER INSERT OR UPDATE OR DELETE ON edge_clients
FOR EACH ROW EXECUTE FUNCTION notify_edge_client_change();
