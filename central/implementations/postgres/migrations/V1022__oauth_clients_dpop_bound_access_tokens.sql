-- RFC 9449 section 5.2 `dpop_bound_access_tokens`: a client registered with this flag always
-- uses DPoP, so the token endpoint refuses a token request from it that carries no proof
-- instead of falling back to a bearer token. Defaults to FALSE for every existing client:
-- DPoP stays opt-in per request unless a deployment asks otherwise.
ALTER TABLE oauth_clients
    ADD COLUMN dpop_bound_access_tokens BOOLEAN NOT NULL DEFAULT FALSE;
