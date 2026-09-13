-- Per-driver HdrHistogram snapshots, written every 60 s and merged by the coordinator into the
-- campaign report (dev spec §11, §12). §11 asks for these to be "written to the emulator DB" and
-- names no table; this is that table.
--
-- The only LOGGED table in this schema, deliberately. Everything else here is reconstructible by
-- a re-seed, which is what justifies UNLOGGED (§6) -- and UNLOGGED means *truncated* on crash
-- recovery, not merely stale. These rows are not reconstructible by anything: they are the
-- campaign's measured latency, accumulated over a run that takes hours, and the merged p99 is the
-- whole deliverable. Losing them to a driver-unrelated Postgres restart would mean re-running the
-- campaign. The database-wide `synchronous_commit = off` from V0001 still applies, so a commit
-- here is fast; it is only the WAL that this table buys back.
CREATE TABLE vu_metric_snapshots (
    campaign      TEXT        NOT NULL,
    driver_id     TEXT        NOT NULL,
    captured_at   TIMESTAMPTZ NOT NULL,
    -- Track F's wire-envelope version (DriverHistogramReport.version). Stored so a coordinator
    -- from a later build refuses a payload it cannot read instead of decoding it into a
    -- plausible-looking histogram of the wrong shape.
    wire_version  INT         NOT NULL,
    kind          SMALLINT    NOT NULL,   -- 0 = step, 1 = flow
    -- NULL for a flow, which §11 labels on its own rather than inside a scenario. The one
    -- nullable column in this schema that means "not applicable" rather than "not yet known".
    scenario      TEXT,
    name          TEXT        NOT NULL,   -- the step name, or the flow name when kind = 1
    unit          TEXT        NOT NULL,
    sample_count  BIGINT      NOT NULL,
    -- HdrHistogram's own compressed encoding, base64url'd. Buckets, not quantiles: quantiles do
    -- not add, and merging across drivers is the entire reason this table exists rather than a
    -- Prometheus query.
    histogram     TEXT        NOT NULL
);

-- Snapshot identity, for idempotency: a driver that retries a failed snapshot write must not
-- double-count its buckets into the merge. An expression index rather than a primary key because
-- `scenario` is nullable and NULLs are not comparable in a PK.
CREATE UNIQUE INDEX vu_metric_snapshots_identity_idx
    ON vu_metric_snapshots (campaign, driver_id, captured_at, kind, COALESCE(scenario, ''), name);

-- Serves the report: every snapshot of one campaign, in interval order, across all drivers.
CREATE INDEX vu_metric_snapshots_campaign_idx
    ON vu_metric_snapshots (campaign, captured_at);
