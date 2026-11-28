-- The antifraud policy of the deployment: the settings an operator tunes and the lists. With several cells each `auth`
-- has a database of its own, and one policy written to several of them could differ between them; so it is stored here,
-- once, and every cell copies it (versolauth/versola#547). What a cell counts (events, velocity, hits of an entry, what its
-- detectors propose) stays in the cell.
--
-- The columns that hold a kind (type, value_type, action) carry no CHECK: the set of kinds is auth's, which validates an
-- entry before it is stored here, and a new kind must not need a migration.

-- One row (id 1): the settings as JSON with every key, and the version the admin API hands out as the ETag and a cell
-- compares with what it holds. No row is "everything off" at version 0.
CREATE TABLE antifraud_settings (
    id         SMALLINT    NOT NULL PRIMARY KEY,
    settings   JSONB       NOT NULL,
    version    BIGINT      NOT NULL,
    updated_by TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- tenant_id NULL is a global entry. It is not a foreign key: an entry of a tenant that is gone matches nothing, and removing
-- a tenant must not change the lists without a record in antifraud_list_audit.
CREATE TABLE antifraud_list_entries (
    id         UUID        NOT NULL PRIMARY KEY,
    type       TEXT        NOT NULL,
    value_type TEXT        NOT NULL,
    value      TEXT        NOT NULL,
    tenant_id  TEXT,
    expires_at TIMESTAMPTZ,
    reason     TEXT,
    source     TEXT        NOT NULL,
    shadow     BOOLEAN     NOT NULL,
    created_by TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

-- One entry per value and list in a scope; adding it again refreshes it.
CREATE UNIQUE INDEX antifraud_list_entries_value_idx ON antifraud_list_entries (type, value_type, value, COALESCE(tenant_id, ''));

CREATE INDEX antifraud_list_entries_expires_idx ON antifraud_list_entries (expires_at) WHERE expires_at IS NOT NULL;

-- Who changed the lists and when. The entry is copied in, because the row it describes may be gone; the previous_* columns
-- are what an update replaced.
CREATE TABLE antifraud_list_audit (
    id                  UUID        NOT NULL PRIMARY KEY,
    at                  TIMESTAMPTZ NOT NULL,
    action              TEXT        NOT NULL,
    actor               TEXT        NOT NULL,
    entry_id            UUID        NOT NULL,
    type                TEXT        NOT NULL,
    value_type          TEXT        NOT NULL,
    value               TEXT        NOT NULL,
    tenant_id           TEXT,
    expires_at          TIMESTAMPTZ,
    reason              TEXT,
    previous_expires_at TIMESTAMPTZ,
    previous_reason     TEXT,
    previous_source     TEXT,
    previous_shadow     BOOLEAN
);

CREATE INDEX antifraud_list_audit_at_idx ON antifraud_list_audit (at DESC, id);
CREATE INDEX antifraud_list_audit_entry_idx ON antifraud_list_audit (entry_id, at DESC, id);

-- Moved in the transaction of every change to antifraud_list_entries: what a cell compares with the version of its copy,
-- so that it reads the entries again only when they changed.
CREATE TABLE antifraud_list_version (
    id      SMALLINT NOT NULL PRIMARY KEY,
    version BIGINT   NOT NULL
);
INSERT INTO antifraud_list_version (id, version) VALUES (1, 0);
