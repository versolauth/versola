-- The two switches of a tenant's audit log, set on the Security page of the console and synchronised to auth with the rest of
-- the tenant's settings (they replace the `audit.events` keys of the services' config). One JSON object, like `passkey_settings`:
--   log          the tenant keeps an audit log at all. false: nothing is recorded for the tenant's events. Events that belong to
--                no tenant are recorded whatever any tenant chose.
--   tokenRefresh whether a refresh_token grant is an event (`token.issued`); it is the most frequent event there is, so it is
--                false unless the tenant asks. Refusals and the reuse of a refresh token are recorded either way.
-- DEFAULT: the INSERT of the previous release does not name this column and keeps serving while `versola migrate` runs, and a
-- tenant that exists must keep what it had (the log on, refreshes off). It can go once that release is no longer a rollback target.
ALTER TABLE challenge_settings ADD COLUMN audit_settings JSONB NOT NULL DEFAULT '{"log": true, "tokenRefresh": false}'::jsonb;
