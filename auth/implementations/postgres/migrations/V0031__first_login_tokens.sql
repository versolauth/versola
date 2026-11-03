-- One-time first-login token of the bootstrap admin (issue #824). Auth prints a link carrying the
-- token once, when it creates the temporary bootstrap password; opening the link starts a login
-- whose password step is already passed and goes straight to "set a permanent password".
--
-- Only a SHA-256 of the token is stored (the token itself is 256 random bits, so an unsalted hash
-- is enough), and the row is deleted when the token is used, when it expires, when the user sets a
-- permanent password, or when the credential is reset. At most one row per user: issuing a new
-- token replaces the previous one.
CREATE TABLE first_login_tokens (
    user_id UUID PRIMARY KEY,
    token_hash BYTEA NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
