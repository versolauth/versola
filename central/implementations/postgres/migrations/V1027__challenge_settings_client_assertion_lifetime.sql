-- RFC 7523 §3: furthest into the future a client assertion's exp may sit, and so the window
-- a jti has to be remembered for. Defaults to five minutes, which is what a client library
-- mints by default and what the replay ring in auth is sized for.
ALTER TABLE challenge_settings
    ADD COLUMN client_assertion_max_lifetime_seconds INT NOT NULL DEFAULT 300;
