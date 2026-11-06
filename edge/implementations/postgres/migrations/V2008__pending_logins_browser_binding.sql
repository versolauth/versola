-- SHA-256 of the secret the browser that began the login holds in its EDGE_LOGIN_<state> cookie, so that
-- /complete finishes a login only for that browser. Nullable: a login begun before this release has none
-- and is accepted without the cookie until it expires (10 minutes).
ALTER TABLE pending_logins ADD COLUMN browser_binding_hash TEXT;
