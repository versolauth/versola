-- An edge belongs to a group, and a client is served by a group of edges rather than by one
-- edge (several edges can serve one client for geo-routing). `kind` belongs to the group and is
-- set by the operator only: `first_party` groups hold the operator's own edges, `partner`
-- groups hold a partner's. It is what central consults before it hands an edge configuration,
-- so it is deliberately not something an edge's own settings can claim.
CREATE TABLE edge_groups (
    id   TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('first_party', 'partner'))
);

-- Every edge that exists today, and every internal client, belongs here.
INSERT INTO edge_groups (id, kind) VALUES ('default', 'first_party');

ALTER TABLE edges ADD COLUMN group_id TEXT NOT NULL DEFAULT 'default' REFERENCES edge_groups (id);

-- One-time enrollment: an operator creates a token for a registered edge, the edge trades it
-- (once, within `expires_at`) and a certificate request for its certificate. Only the hash is
-- kept, so a leaked table cannot enrol anything.
CREATE TABLE edge_enrollment_tokens (
    token_hash TEXT PRIMARY KEY,
    edge_id    TEXT NOT NULL REFERENCES edges (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);

-- Which certificates central issued to which edge, for the operator to see what is about to
-- expire. The certificate itself is not stored.
CREATE TABLE edge_certificates (
    edge_id   TEXT NOT NULL REFERENCES edges (id) ON DELETE CASCADE,
    serial    TEXT NOT NULL,
    not_after TIMESTAMPTZ NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (edge_id, serial)
);
