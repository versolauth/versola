-- A 1% sample of executed steps, for post-hoc forensics only (dev spec §6). The campaign's
-- actual latency numbers come from the per-driver HdrHistograms (§11), never from here -- this
-- table exists to answer "what did that one user's session actually do" after the run.
--
-- Deliberately unindexed and without a primary key: it is append-only on the write-behind path
-- at ~5M rows/day (design doc §6.6), and nothing reads it while a campaign is running. Every
-- index would be paid for on every insert to speed up a query that is run by hand, once, over a
-- table small enough to scan. Add one when a forensic query proves too slow, not before.
CREATE UNLOGGED TABLE vu_events (
    at          TIMESTAMPTZ NOT NULL,
    user_id     BIGINT      NOT NULL,
    scenario    TEXT        NOT NULL,
    step        TEXT        NOT NULL,
    outcome     TEXT        NOT NULL,
    latency_ms  INT         NOT NULL
);
