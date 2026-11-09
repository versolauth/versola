-- One-time links (issue #824): a random token that someone is sent and that is spent by the first request
-- carrying it. The first purpose is `first_login`: the bootstrap admin's link. Auth prints it once, when it
-- creates the temporary bootstrap password; opening it starts a login whose password step is already passed
-- and goes straight to "set a permanent password". Other purposes (an invitation, a password reset sent by
-- email) are new values of `purpose` and a handler in auth, not new tables.
--
-- Only a SHA-256 of the token is stored (the token itself is 256 random bits, so an unsalted hash is enough).
-- A `first_login` row is deleted when the link is used, when the user sets a permanent password, when the
-- temporary password is replaced or deleted, or when the credential is reset; every row of a user goes when
-- the user is deleted. An expired row is removed by the cleanup job (`cleanup.tables` in auth.conf) or, for
-- `first_login`, replaced by the next bootstrap. At most one row per user and purpose: issuing a new link of
-- that purpose replaces the previous one. No foreign key to `users`: the previous release deletes users
-- without knowing this table, and an orphan row only leads `consume` to an `access_denied`.
CREATE TABLE one_time_links (
    user_id UUID NOT NULL,
    purpose TEXT NOT NULL,
    token_hash BYTEA NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, purpose)
);

-- Serves the cleanup job's `DELETE ... WHERE expires_at < now()`.
CREATE INDEX one_time_links_expires_at_idx
    ON one_time_links (expires_at);
