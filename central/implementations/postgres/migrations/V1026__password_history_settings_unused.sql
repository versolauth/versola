-- A user keeps a single password, so the history length and the "different passwords" count are no longer read or
-- written. The columns are deliberately not dropped: the previous release still selects them, and during a rolling
-- update it keeps running against this schema. They get the defaults the previous release would have seeded, so
-- nothing has to write them any more; a later release drops them.
ALTER TABLE system_settings
    ALTER COLUMN password_history_size SET DEFAULT 2,
    ALTER COLUMN password_num_different SET DEFAULT 1;
