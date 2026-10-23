-- Antifraud lists: values the rules recognise at once. deny blocks, fake answers as if the step succeeded and does
-- nothing, watch only adds to the score, allow outweighs score-based rules (never a deny of the same specificity;
-- see AntifraudLists). tenant_id NULL is a global entry, applying to every tenant.
--
-- The service keeps all entries in memory and refreshes them every few seconds, so the table is read in bulk, not
-- per request; expires_at is honoured when a value is looked up, and expired rows are removed by the service.
-- A phone number or address is stored as the keyed hash of the event log (phone_hash), never as itself.
-- hit_count and last_hit_at are written in batches from the counters the instances keep in memory.
CREATE TABLE af_list_entry (
    id          UUID        NOT NULL PRIMARY KEY,
    type        TEXT        NOT NULL CHECK (type IN ('deny', 'allow', 'watch', 'fake')),
    value_type  TEXT        NOT NULL CHECK (value_type IN
                    ('phone_hash', 'prefix', 'ip', 'cidr', 'asn', 'device_id', 'ua_hash', 'ja4_hash', 'email_domain')),
    value       TEXT        NOT NULL,
    tenant_id   TEXT,
    expires_at  TIMESTAMPTZ,
    reason      TEXT,
    source      TEXT        NOT NULL,
    -- A shadow entry is matched and counted but enforces nothing: a dry run before it is turned on.
    shadow      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by  TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    hit_count   BIGINT      NOT NULL DEFAULT 0,
    last_hit_at TIMESTAMPTZ
)
-- The hit counters are updated in place every few seconds; no indexed column changes, so with room on the page the
-- update stays on it (HOT) and leaves no dead tuple in an index.
WITH (fillfactor = 80);

-- One entry per value and list in a scope; adding it again refreshes it.
CREATE UNIQUE INDEX af_list_entry_value_idx ON af_list_entry (type, value_type, value, COALESCE(tenant_id, ''));

CREATE INDEX af_list_entry_expires_idx ON af_list_entry (expires_at) WHERE expires_at IS NOT NULL;

-- Who changed the lists and when. The entry is copied in, because the row it describes may be gone.
CREATE TABLE af_list_audit (
    id         UUID        NOT NULL PRIMARY KEY,
    at         TIMESTAMPTZ NOT NULL,
    action     TEXT        NOT NULL CHECK (action IN ('create', 'delete', 'expire')),
    actor      TEXT        NOT NULL,
    entry_id   UUID        NOT NULL,
    type       TEXT        NOT NULL,
    value_type TEXT        NOT NULL,
    value      TEXT        NOT NULL,
    tenant_id  TEXT,
    expires_at TIMESTAMPTZ,
    reason     TEXT
);

-- Newest first for the whole log, and for one entry; the service removes records older than antifraud.lists.audit-retention.
CREATE INDEX af_list_audit_at_idx ON af_list_audit (at DESC, id);
CREATE INDEX af_list_audit_entry_idx ON af_list_audit (entry_id, at DESC, id);

-- Bumped in the transaction of every change to af_list_entry, so that an instance asks "has anything changed?" with
-- one tiny query and rebuilds its snapshot only when it has. A change made by hand in SQL does not bump it; the
-- instances reload everything once a minute regardless.
CREATE TABLE af_list_version (
    id      SMALLINT NOT NULL PRIMARY KEY CHECK (id = 1),
    version BIGINT   NOT NULL
);
INSERT INTO af_list_version (id, version) VALUES (1, 0);
