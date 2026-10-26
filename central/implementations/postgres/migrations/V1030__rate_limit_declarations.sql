-- The limits the owner of a resource sets for the clients of its audience, as the JSON of a list of {clientId, rateLimit}
-- (versola.central.configuration.resources.AudienceRateLimit): how much each client may take from this resource, counted at the
-- edge together with the edge's own policies. At most one per client, only for clients in `audience`; the service keeps it so.
--
-- NOT NULL with a default: the previous release's INSERTs do not name the column and keep serving during `versola migrate`, and
-- an existing row has none. The default can go once no release that omits the column is in use.
--
-- A limit on a resource endpoint needs no column: endpoints are JSON inside resources.endpoints, and `rateLimit` is a new
-- optional member of that JSON, absent in every endpoint stored so far.
ALTER TABLE resources ADD COLUMN audience_rate_limits JSONB NOT NULL DEFAULT '[]'::jsonb;
