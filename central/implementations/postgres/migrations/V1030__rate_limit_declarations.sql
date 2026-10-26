-- A limit on how many calls carrying this client's tokens the edge lets through, as the JSON of
-- versola.util.http.DeclaredRateLimit (windows, burst, key, mode). NULL means no limit, which is every client so far.
-- Nullable and without a default: the code that sets it validates it first, and the previous release's INSERTs
-- do not name the column.
--
-- A limit on a resource endpoint needs no column: endpoints are JSON inside resources.endpoints, and `rateLimit` is a
-- new optional member of that JSON, absent in every endpoint stored so far.
ALTER TABLE oauth_clients ADD COLUMN rate_limit JSONB;
