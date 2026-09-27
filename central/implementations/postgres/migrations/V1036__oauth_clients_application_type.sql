-- OIDC Dynamic Client Registration §2 `application_type`: what kind of program a client is,
-- held apart from how it authenticates (`auth_method`).
--
-- The two used to be read off each other -- a native app was a client with auth method
-- `none` -- which cannot describe an app fronted by edge (#421): native (App Link redirect,
-- DPoP key on the device) and confidential at once, since edge authenticates as it with
-- `tls_client_auth`.
--
-- Every existing row is `web`: nothing registered before this column could have stated
-- otherwise, and `web` is what the rules applied to those rows all along assumed.
ALTER TABLE oauth_clients
    ADD COLUMN application_type TEXT NOT NULL DEFAULT 'web'
        CHECK (application_type IN ('web', 'native'));
