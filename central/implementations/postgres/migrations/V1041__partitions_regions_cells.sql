-- The rest of the topology of #547 (#557): where an edge site is, and which cells there are.
--
--   partition  one jurisdiction: its own issuer, keys and directory. A deployment has one.
--   region     one metro, a failure domain inside a partition. A cell and an edge site are each in one region.
--   cell       the capacity unit: one auth deployment with its own Postgres, holding a fixed set of users.
--
-- Nothing routes by these yet: an installation with no row here behaves as before. The values a row may hold
-- (the states of a cell, a weight that is not negative, well-formed addresses) are checked by central before it
-- writes, not by the tables.
CREATE TABLE partitions (
    id TEXT PRIMARY KEY
);

CREATE TABLE regions (
    id           TEXT PRIMARY KEY,
    partition_id TEXT NOT NULL REFERENCES partitions (id)
);

-- Where the operator's own edge site is. Nullable: an edge registered before regions existed, and a partner's
-- edge, has none.
ALTER TABLE edges ADD COLUMN region_id TEXT REFERENCES regions (id);

CREATE TABLE cells (
    -- The id written in front of the opaque values the cell issues (#558), so it is never reused or renamed.
    id                   TEXT PRIMARY KEY,
    region_id            TEXT NOT NULL REFERENCES regions (id),
    -- Where an edge reaches the cell's auth: its internal address, and its mutual-TLS listener with the leaf
    -- certificates the listener is pinned by. A set, so that a certificate can be replaced without a gap.
    internal_url         TEXT NOT NULL,
    mtls_url             TEXT,
    trusted_certificates TEXT[] NOT NULL,
    -- open | closed | draining: whether new users are placed in the cell. A cell in any state still serves the
    -- users it holds.
    state                TEXT NOT NULL,
    -- The cell's share of new users among the open cells of its region.
    weight               INTEGER NOT NULL
);
