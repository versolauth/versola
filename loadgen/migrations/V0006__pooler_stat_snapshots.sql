-- PgBouncer admin console readings taken at the campaign's two boundaries, so the report can
-- state what the pooler in front of the SUT cost the run (runbook 05-report-spec.md §4).
--
-- Separate from V0005 rather than a `kind` column on it. The two instruments share a bracket and
-- nothing else: a `pg_stat_*` row is identified by a database and carries five reset instants
-- that decide whether it may be differenced, while a pooler row is identified by a PgBouncer
-- instance and has no reset instant to carry -- the admin console exposes none. One table would
-- be five columns that are NULL for half its rows and a `database` column meaning two things.
--
-- LOGGED for V0005's reason, and it applies with less slack rather than more: PgBouncer's totals
-- are cumulative since its process started and it publishes no reset instant at all, so a lost
-- "before" row cannot be reconstructed even in principle, and `PoolerStatsDelta` cannot detect
-- that it is missing the way a `stats_reset` mismatch is detectable.
CREATE TABLE vu_pooler_stat_snapshots (
    campaign     TEXT        NOT NULL,
    -- The operator's label for one pooler (`pooler-stats.poolers[].name`), not its admin console
    -- URL: 04-pgbouncer.md puts one pooler per Postgres instance, so the report names them the
    -- way it names the databases behind them.
    pooler       TEXT        NOT NULL,
    phase        SMALLINT    NOT NULL,   -- 0 = before the run, 1 = after it, as in V0005
    captured_at  TIMESTAMPTZ NOT NULL,
    -- `SHOW VERSION`, verbatim. Which counters the reading carries is a function of the build,
    -- and a section absent because this PgBouncer does not report it has to be tellable from one
    -- absent because the capture half-failed. It is also the only restart evidence the console
    -- offers other than a counter going backwards: a differing string across the pair is a
    -- restart onto a different binary.
    version      TEXT        NOT NULL,
    -- The reading itself (`versola.loadgen.sut.PoolerStats`). One document for V0005's reason,
    -- which is stronger here: `SHOW STATS` takes no column list, so the set of statistics is
    -- PgBouncer's to choose per release, and a relational shape would need a migration per
    -- release of the pooler.
    statistics   JSONB       NOT NULL
);

-- V0005's identity index, for V0005's reason: re-capturing a boundary that already landed must
-- not leave two rows of one phase, because which of them the report read would decide the
-- campaign's pooler numbers. First capture of each phase stands.
CREATE UNIQUE INDEX vu_pooler_stat_snapshots_identity_idx
    ON vu_pooler_stat_snapshots (campaign, pooler, phase);
