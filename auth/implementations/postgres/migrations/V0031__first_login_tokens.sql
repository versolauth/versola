-- One-time first-login token of the bootstrap admin (issue #824). Auth prints a link carrying the
-- token once, when it creates the temporary bootstrap password; opening the link starts a login
-- whose password step is already passed and goes straight to "set a permanent password".
--
-- Only a SHA-256 of the token is stored (the token itself is 256 random bits, so an unsalted hash
-- is enough), and the row is deleted when the token is used, when the user sets a permanent
-- password, when the temporary password is replaced or deleted, when the user is deleted, or when
-- the credential is reset. An expired row is removed by the cleanup job (`cleanup.tables` in
-- auth.conf) or replaced by the next bootstrap. At most one row per user: issuing a new token
-- replaces the previous one. No foreign key to `users`: the previous release deletes users without
-- knowing this table, and an orphan row only leads `consume` to an `access_denied`.
CREATE TABLE first_login_tokens (
    user_id UUID PRIMARY KEY,
    token_hash BYTEA NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Serves the cleanup job's `DELETE ... WHERE expires_at < now()`.
CREATE INDEX first_login_tokens_expires_at_idx
    ON first_login_tokens (expires_at);
