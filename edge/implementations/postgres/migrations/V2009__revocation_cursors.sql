-- Where this edge has read auth's revocation feed up to, one row per source (a source is a cell of auth,
-- named by its mutual-TLS URL; there is one today), and which pod is reading it.
--
-- Edge pods share the database and nothing else, and only one of them should call the feed at a time. The
-- row is also the coordination: a pod takes the lease (`lease_until` in the future, `leased_by` the pod),
-- runs one cycle, and gives the lease back. A pod that dies loses it when `lease_until` passes; one that hangs
-- holds it for one lease. It is a column and not an advisory lock because a cycle is an HTTP call to auth,
-- and no connection or transaction may stay open across that (the same reason as
-- `edge_sessions.refresh_lease_until`). Every comparison uses the database's clock, never a pod's.
--
-- `cursor` is what the feed handed out last, as received; the empty string is the beginning of the list, which
-- is where a new edge, or one that lost its row, reads from. `lease_until` is NOT NULL: a row nobody holds
-- carries a past instant (the epoch for a row just seeded), so taking the row is one comparison.
--
-- `last_read_at` is when a pod last read the feed to its end and gave the lease back: the same figure for every pod
-- of the edge, which is what `revocation_feed_lag_seconds` is measured from. A row just seeded carries its creation
-- time, so a feed that never reads reports a lag that rises from there. `updated_at` is when the cursor last moved.
-- No DEFAULT: the writer names every column.
CREATE TABLE revocation_cursors (
    source      TEXT PRIMARY KEY,
    cursor      TEXT NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
    leased_by   TEXT,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_read_at TIMESTAMP WITH TIME ZONE NOT NULL
);
