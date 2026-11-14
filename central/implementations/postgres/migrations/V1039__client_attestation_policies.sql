-- App attestation of a native client's `/native/start` (#423): whether edge asks the app to prove it is the genuine build
-- on a genuine device, and which builds that is.
--
-- A table of its own rather than columns on oauth_clients: the client record is wide and travels through every
-- registration request, and this is set by a separate call. A client with no row is `off`, which is what every client
-- was before this table, so nothing is backfilled and the previous release, which never reads it, keeps working on this
-- schema.
--
-- ON DELETE CASCADE: deleting a client leaves no policy to enforce.
CREATE TABLE client_attestation_policies (
    client_id  TEXT        PRIMARY KEY REFERENCES oauth_clients (id) ON DELETE CASCADE,
    -- `off` | `report` | `enforce`. `report` verifies and counts without refusing, so an operator sees what `enforce` would
    -- turn away before turning it on.
    mode       TEXT        NOT NULL,
    -- The builds the policy accepts: {"ios": {"appIds": ["TEAMID.com.example"], "environment": "production"},
    -- "android": [{"packageName": "com.example", "certificateDigests": ["<hex SHA-256 of the signing certificate>"]}]}.
    -- Kept while the mode is `off`, so an operator can prepare them before switching it on.
    identities JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
