-- Antifraud storage, second pass: two indexes nothing reads, the markers a rollup that runs once per interval needs,
-- what an update of a list entry leaves in the audit, and the counter reset.

-- af_event_subject_idx and af_event_dest_idx cost two B-tree inserts per event on the single writer's path and have no
-- reader: the only reader of subject_hash is the preview of a phone_hash entry, which reads a day of events anyway (and
-- is bounded by its statement timeout), and the conversion of a range is read from af_dest_stats. Dropping the index of
-- the partitioned table drops it from every partition, the ones created later included (they are made as partitions of
-- af_event and take whatever indexes it has). The BRIN index on `at` stays: it serves the rollup and the previews.
DROP INDEX IF EXISTS af_event_subject_idx;
DROP INDEX IF EXISTS af_event_dest_idx;

-- When the rollup of af_dest_stats last ran, by any instance. The instances all run it every stats-interval; the one
-- that finds the marker younger than half an interval, under the advisory lock, skips, so a minute is rolled up once
-- per interval and not once per instance. One row (id 1).
CREATE TABLE af_rollup_state (
    id       SMALLINT    NOT NULL PRIMARY KEY,
    last_run TIMESTAMPTZ NOT NULL
);

-- An update of an existing list entry (the same value added again) is audited as `update`, with what the entry had before.
-- The columns are empty for create, delete and expire records, and for those written before this column existed.
ALTER TABLE af_list_audit
    ADD COLUMN previous_expires_at TIMESTAMPTZ,
    ADD COLUMN previous_reason     TEXT,
    ADD COLUMN previous_source     TEXT,
    ADD COLUMN previous_shadow     BOOLEAN;

-- A reset of the counters (POST /antifraud/counters/reset): one row per request, which every instance reads in order, by
-- generation, and applies to its memory. The generation counts from 1 with no gap and is assigned under an advisory lock, so
-- the instances see the rows in the order they were committed and an instance that has applied N has applied every row up to N.
--
-- scope is velocity, budget or all. value_type (phone, ip, prefix) and the two values say what of the scope is reset; both
-- are empty for the whole scope. value_hash is the key the velocity counters hold the value under (the keyed hash of a phone or of
-- an address's network, never the value itself; the digits for a prefix) and dest_prefix the leading digits of a phone number
-- or the prefix itself, which is what the SMS budget is keyed by. The table is also the audit of resets: requested_by is who asked.
CREATE TABLE af_counter_reset (
    generation   BIGINT      NOT NULL PRIMARY KEY,
    scope        TEXT        NOT NULL,
    value_type   TEXT,
    value_hash   TEXT,
    dest_prefix  TEXT,
    requested_by TEXT        NOT NULL,
    at           TIMESTAMPTZ NOT NULL
);
