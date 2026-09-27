-- What a client was registered from, and when.
--
-- The console's registration wizard picks a combination -- what the client is, and how much
-- assurance its deployment can carry -- and applies the settings that combination implies.
-- Until now that choice was thrown away the moment the row was written, so the edit screen
-- had nothing to say "this differs from what you asked for" against. Reading it back out of
-- the settings is not the same thing: once a TTL or a PAR requirement has been edited,
-- several combinations fit the row, and the one a diff is shown against would move under the
-- operator as they edit.
--
-- Nullable, and stays that way. Every row that predates this migration was registered
-- without a template, and so was every client registered through the API rather than the
-- wizard; a backfilled guess would be indistinguishable from a recorded choice.
ALTER TABLE oauth_clients ADD COLUMN template JSONB;

-- The default covers the rows already here, whose registration time is not recorded anywhere
-- to recover -- they are dated to the migration, which is the earliest moment this column can
-- honestly claim to know about. Kept as the column default afterwards so a row inserted by
-- anything that does not state a time still gets one, rather than failing NOT NULL.
ALTER TABLE oauth_clients ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
