-- `pg_stat_*` readings taken from the *system under test's* databases at the campaign's two
-- boundaries, so the report can state what the run cost Postgres (runbook 05-report-spec.md §3)
-- and the WAL cycle of 07-wal-tuning.md has a before and an after to difference.
--
-- LOGGED, and for V0004's reason rather than in spite of it. Everything UNLOGGED in this schema
-- is reconstructible by a re-seed; these rows are not reconstructible by anything. A `pg_stat_*`
-- counter is cumulative since the last reset, so the value it had when the campaign started
-- exists nowhere once the campaign is running -- the SUT has moved on, and no later query can
-- recover it. UNLOGGED means *truncated* on crash recovery, and truncating the "before" row
-- turns a ten-hour run into a number with nothing to subtract from it. The database-wide
-- `synchronous_commit = off` from V0001 still applies, so the two commits per campaign this
-- table takes are as cheap as every other write here.
--
-- Two rows per database per campaign, not a time series: §3 asks what the run did, and
-- 07-wal-tuning.md's method is explicitly "снимки до и после при фиксированном числе
-- транзакций". Sampling in between would need a reset-safe merge and would still not answer
-- that question any better.
CREATE TABLE vu_sut_stat_snapshots (
    campaign            TEXT        NOT NULL,
    -- The operator's label for one SUT database (`sut-stats.databases[].name`), not its
    -- connection URL: the report names auth, central and edge, and the credentials that reach
    -- them are configuration that may change between runs of the same campaign.
    database            TEXT        NOT NULL,
    phase               SMALLINT    NOT NULL,   -- 0 = before the run, 1 = after it
    captured_at         TIMESTAMPTZ NOT NULL,
    -- `server_version_num` of the captured database. Which views were readable is a function of
    -- it, so a snapshot whose WAL section is absent can be told apart from one taken against a
    -- server that simply has no `pg_stat_wal`.
    server_version_num  INT         NOT NULL,
    -- `pg_stat_database.stats_reset`, `pg_stat_wal.stats_reset`, and the same column from
    -- `pg_stat_checkpointer`/`pg_stat_bgwriter`, `pg_stat_io` and `pg_stat_statements_info`.
    -- Columns rather than fields inside the payload because they are the precondition for
    -- differencing the pair at all: 07-wal-tuning.md resets the counters before every step of
    -- its cycle, and a difference taken across a reset is not a small error, it is the second
    -- reading on its own. Five and not two because `pg_stat_reset_shared('io'/'checkpointer')`
    -- and `pg_stat_statements_reset()` each reset one section independently of the other four.
    stats_reset_at              TIMESTAMPTZ,
    wal_stats_reset_at          TIMESTAMPTZ,
    checkpointer_stats_reset_at TIMESTAMPTZ,
    wal_io_stats_reset_at       TIMESTAMPTZ,
    statements_stats_reset_at   TIMESTAMPTZ,
    -- The reading itself (`versola.loadgen.sut.SutStats`). One document rather than a column per
    -- statistic: the set of columns Postgres exposes changes with its major version -- 17 moved
    -- the checkpoint counters, 18 moved the WAL I/O timings and renamed `op_bytes` -- so a
    -- relational shape here would need a migration per Postgres release, and an absent statistic
    -- would arrive as a NULL indistinguishable from "this server reported zero". The report
    -- reads whole snapshots and differences them in Scala; nothing queries inside this value.
    statistics          JSONB       NOT NULL
);

-- One reading per boundary per database. A retried capture must not leave two "before" rows for
-- the same run, because which of them the report picked would decide the result; the earliest
-- capture of each phase is the one that stands (see `PostgresSutStatSnapshotRepository.append`).
CREATE UNIQUE INDEX vu_sut_stat_snapshots_identity_idx
    ON vu_sut_stat_snapshots (campaign, database, phase);
