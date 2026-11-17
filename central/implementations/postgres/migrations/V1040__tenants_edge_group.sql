-- A tenant is served by a group of edges, not by one edge (#557): every edge of the group reads the tenant's
-- configuration, which is what lets one tenant have several edge sites. Only the operator's own groups are named
-- here; what a partner's edge receives is decided by who owns a client, not by the tenant.
--
-- Nullable like `edge_id` was: a tenant no edge serves has none.
ALTER TABLE tenants ADD COLUMN edge_group_id TEXT REFERENCES edge_groups (id) ON DELETE SET NULL;

-- Every tenant keeps being served by the edge it named, through that edge's group.
UPDATE tenants
SET edge_group_id = edges.group_id
FROM edges
JOIN edge_groups ON edge_groups.id = edges.group_id
WHERE edges.id = tenants.edge_id
  AND edge_groups.kind = 'first_party';

ALTER TABLE tenants DROP COLUMN edge_id;
