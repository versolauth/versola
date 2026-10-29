-- Antifraud settings an operator changes during an attack (thresholds, rule weights, shadow rules, limits on codes sent
-- to a range of numbers, the proof-of-work difficulty), kept apart from the configuration because a restart is too slow
-- for them and empties the in-memory counters. One row (id 1), a Tuning as JSON with every key. The table is created
-- empty: auth's bootstrap writes the first row (everything off) if there is none, and a deployment without one is taken to
-- have everything off. The configuration says nothing of these values.
--
-- The service keeps the tuning in memory and rereads it when af_settings_version changes, so the table is never read on a
-- request.
CREATE TABLE af_settings (
    id         SMALLINT    NOT NULL PRIMARY KEY,
    settings   JSONB       NOT NULL,
    updated_by TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Bumped in the transaction of every change to af_settings (the row is created by the first change), as af_list_version is
-- for the lists. A change made by hand in SQL does not bump it; the instances reload everything once a minute regardless.
CREATE TABLE af_settings_version (
    id      SMALLINT NOT NULL PRIMARY KEY,
    version BIGINT   NOT NULL
);
