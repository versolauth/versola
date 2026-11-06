-- How many times each policy section of an edge (overload, accessLog, rateLimits) has changed and when, by the name of the section in
-- PATCH /configuration/edges/policies: {"rateLimits": {"revision": 3, "updatedAt": "2026-10-27T10:00:00Z"}}. A client may name a revision in
-- the patch and have it refused (409) if the section has moved on. NULL -- no section ever changed -- reads as revision 0 everywhere.
-- Nullable, no default: the previous release's inserts do not name it.
ALTER TABLE edges ADD COLUMN policy_meta JSONB;
