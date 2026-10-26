-- Antifraud settings an operator changes during an attack (thresholds, rule weights, shadow rules, limits on codes sent
-- to a range of numbers, timings), kept apart from the configuration because a restart is too slow for them and empties
-- the in-memory counters. A row is a patch (a TuningPatch as JSON): what it leaves out is the layer below's, the
-- tenant's from the global row (tenant_id '') and that from the configuration.
--
-- The service keeps the result in memory and rebuilds it when af_settings_version changes, so the table is read in bulk
-- and never on a request.
CREATE TABLE af_settings (
    tenant_id  TEXT        NOT NULL PRIMARY KEY,
    settings   TEXT        NOT NULL,
    updated_by TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Who changed what and when; the rows before and after are copied in, because the row may be gone.
CREATE TABLE af_settings_audit (
    id        UUID        NOT NULL PRIMARY KEY,
    at        TIMESTAMPTZ NOT NULL,
    actor     TEXT        NOT NULL,
    tenant_id TEXT        NOT NULL,
    before    TEXT,
    after     TEXT
);

CREATE INDEX af_settings_audit_at_idx ON af_settings_audit (at DESC, id);
CREATE INDEX af_settings_audit_tenant_idx ON af_settings_audit (tenant_id, at DESC, id);

-- Bumped in the transaction of every change to af_settings, as af_list_version is for the lists. A change made by hand in
-- SQL does not bump it; the instances reload everything once a minute regardless.
CREATE TABLE af_settings_version (
    id      SMALLINT NOT NULL PRIMARY KEY,
    version BIGINT   NOT NULL
);
INSERT INTO af_settings_version (id, version) VALUES (1, 0);
