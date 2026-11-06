-- What a client changes in the theme it uses (brand colour, corners, page, colour scheme), as the JSON of
-- ThemeOverrides. NULL when it changes nothing, which is every client so far. Nullable and without a default:
-- the code that inserts supplies it, and the previous release's INSERTs do not name the column.
ALTER TABLE oauth_clients ADD COLUMN theme_overrides JSONB;
