-- Per range of phone numbers and minute, what the OTP log (af_event) shows: how many codes were asked for, sent,
-- verified. Rolled up from af_event by the service every minute (AntifraudEvents), so that a question about a range
-- reads a few hundred small rows instead of scanning the day's events. The conversion of a range is verified / sent.
--
-- dest_prefix is the leading digits of the number, country code included, cut to the length the rollup was run with
-- (4 by default). tenant_id is '' for events of a client the service did not know.
CREATE TABLE af_dest_stats (
    tenant_id       TEXT        NOT NULL,
    dest_prefix     TEXT        NOT NULL,
    minute          TIMESTAMPTZ NOT NULL,
    requests        INTEGER     NOT NULL,
    sent            INTEGER     NOT NULL,
    suppressed      INTEGER     NOT NULL,
    throttled       INTEGER     NOT NULL,
    provider_errors INTEGER     NOT NULL,
    verified        INTEGER     NOT NULL,
    wrong_codes     INTEGER     NOT NULL,
    PRIMARY KEY (tenant_id, dest_prefix, minute)
);

-- Retention deletes by age.
CREATE INDEX af_dest_stats_minute_idx ON af_dest_stats (minute);

-- The rollup reads the last minutes of af_event by time alone, and the other indexes of af_event lead with a
-- subject or a range. Events are written in time order, so a BRIN index on `at` is nearly free to maintain and
-- lets the rollup (and any report over a window) read just the blocks of that window instead of the day.
CREATE INDEX af_event_at_idx ON af_event USING brin (at);
