-- One row per bump of antifraud_list_version, written in the transaction that bumps it (versolauth/versola#1052): the entry
-- that changed and whether it was removed (a delete) or stored (a new entry, or an upsert that changed one). A cell that is
-- a few versions behind is answered from these rows (the entries they name that are still stored, and the ids that are not)
-- instead of the whole list; a cell further behind than the rows reach is answered the whole list. A purge of expired
-- entries writes none: it does not bump.
-- Rows are kept for 7 days and pruned by the cleanup that runs every 30 seconds (AntifraudListService.ChangesKept), so the
-- table holds a week of list changes, which is small.
CREATE TABLE antifraud_list_changes (
    version  BIGINT      NOT NULL PRIMARY KEY,
    entry_id UUID        NOT NULL,
    removed  BOOLEAN     NOT NULL,
    at       TIMESTAMPTZ NOT NULL
);

-- Pruning reads by age.
CREATE INDEX antifraud_list_changes_at_idx ON antifraud_list_changes (at);
