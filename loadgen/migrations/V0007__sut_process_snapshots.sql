-- Readings of the SUT *services'* own /metrics, taken at the campaign's two boundaries, so the
-- report can state what the run cost the processes in front of the databases V0005 brackets.
--
-- Without this the report holds an exact operation count and an exact database cost and nothing
-- about the service between them, so cost per login -- the figure the sizing campaign exists to
-- produce -- can only be assembled afterwards from a Prometheus range query over a window
-- guessed to approximate boundaries only the coordinator knows.
--
-- Separate from V0005 and V0006 for the reason V0006 is separate from V0005: the three brackets
-- share a campaign and nothing else. A row here is identified by a service rather than a
-- database or a pooler, and carries one restart witness (`started_at_epoch_seconds`) where a
-- pg_stat row carries five reset instants and a pooler row carries none.
--
-- LOGGED for V0005's reason.
CREATE TABLE vu_sut_process_snapshots (
    campaign     TEXT        NOT NULL,
    -- The operator's label for one SUT service (`sut-process-stats.services[].name`), matching
    -- the names used for the databases where a service has one, so a reader can put the two
    -- sections side by side.
    service      TEXT        NOT NULL,
    phase        SMALLINT    NOT NULL,   -- 0 = before the run, 1 = after it, as in V0005
    captured_at  TIMESTAMPTZ NOT NULL,
    -- `process_start_time_seconds`, verbatim: seconds since the epoch as the exposition states
    -- them. This section's equivalent of V0005's reset instants -- every counter in `statistics`
    -- is cumulative since process start, so a pair whose values differ spans a restart and may
    -- not be subtracted. DOUBLE PRECISION rather than TIMESTAMPTZ because it is compared for
    -- equality across the pair and never read as a time; rounding it to an instant would risk
    -- two restarts within one second of each other comparing equal.
    --
    -- NULL when the exposition omitted it, which the delta treats as a restart: unprovable and
    -- proven have the same consequence for a subtraction.
    started_at_epoch_seconds DOUBLE PRECISION,
    -- The reading itself (`versola.loadgen.sut.SutProcessStats`). One document for V0005's
    -- reason: which series a service publishes is a function of what it runs, and a relational
    -- shape would need a migration each time that changes.
    statistics   JSONB       NOT NULL
);

-- V0005's identity index, for V0005's reason: two rows of one phase would leave the report
-- picking one of them, and which it picked would decide the campaign's CPU figure.
CREATE UNIQUE INDEX vu_sut_process_snapshots_identity_idx
    ON vu_sut_process_snapshots (campaign, service, phase);
