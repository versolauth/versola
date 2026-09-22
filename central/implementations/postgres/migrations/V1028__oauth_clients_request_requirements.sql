-- RFC 9101 §10.5 `require_signed_request_object` and RFC 9126 §6.2
-- `require_pushed_authorization_requests`: what a client must use to state an authorization
-- request. Both default to FALSE, which is what every already registered client was doing --
-- the requirement only takes effect once an operator turns it on.
ALTER TABLE oauth_clients
    ADD COLUMN require_signed_request_object BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN require_pushed_authorization_requests BOOLEAN NOT NULL DEFAULT FALSE;
