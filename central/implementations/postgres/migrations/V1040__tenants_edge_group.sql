-- A tenant is served by a group of edges, not by one edge (#557): every edge of the group reads the tenant's
-- configuration, which is what lets one tenant have several edge sites. Only the operator's own groups are named
-- here; what a partner's edge receives is decided by who owns a client, not by the tenant.
--
-- Nullable like `edge_id` was: a tenant no edge serves has none.
ALTER TABLE tenants ADD COLUMN edge_group_id TEXT REFERENCES edge_groups (id) ON DELETE SET NULL;

-- Every tenant keeps being served by the edge it named, through that edge's group. A deployment where two edges of
-- one group served different tenants now has both edges serving both: the edges have to be put in different groups
-- before this runs if that split is relied on.
UPDATE tenants
SET edge_group_id = edges.group_id
FROM edges
JOIN edge_groups ON edge_groups.id = edges.group_id
WHERE edges.id = tenants.edge_id
  AND edge_groups.kind = 'first_party';

-- `tenants.edge_id` stays for this release: the previous one still reads and writes it while `versola migrate` runs,
-- and a rollback to it must find the column. Nothing in this release reads it; a later migration drops it.
