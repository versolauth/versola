-- Serialises concurrent refreshes of one edge session.
--
-- Two requests carrying the same expired EDGE_SESSION cookie used to read the same refresh token and
-- both present it to auth. Auth detects reuse of a rotated refresh token and revokes the whole token
-- family, so the loser of that race logged the user out. A request that arrives just after the winner
-- committed found no row for its old `jti` and was sent to log in as well.
--
-- A lease on the row decides which request refreshes. It is a column rather than an advisory lock or
-- SELECT ... FOR UPDATE because the refresh is an HTTP call to auth, and a database connection or
-- transaction must not be held across it; the lease also expires on its own if the owner dies.
--
-- `previous_access_token_id` and `access_token` let a request that lost the race (or came late) find
-- the row its stale cookie belonged to and be handed the winner's access token, without calling auth.
-- `access_token` is sealed with the same key as `refresh_token`.
--
-- `rotated_at` bounds that hand-out: a stale cookie is served the current token only for a short
-- window after the rotation (see EdgeService.rotationGracePeriod). Without it a stolen cookie of the
-- previous generation would keep working until the next rotation. NULL until the first rotation.
--
-- All three columns are nullable, so the previous release keeps working against this schema.

ALTER TABLE edge_sessions
    ADD COLUMN previous_access_token_id TEXT,
    ADD COLUMN access_token             BYTEA,
    ADD COLUMN refresh_lease_until      TIMESTAMP WITH TIME ZONE,
    ADD COLUMN rotated_at               TIMESTAMP WITH TIME ZONE;

CREATE INDEX edge_sessions_previous_access_token_id_idx ON edge_sessions (previous_access_token_id);
