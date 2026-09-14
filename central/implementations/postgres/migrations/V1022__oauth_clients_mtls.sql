-- RFC 8705 §2.1: the single subject value a client's certificate must carry for
-- `tls_client_auth`. NULL means the client does not use mutual-TLS client authentication.
ALTER TABLE oauth_clients
    ADD COLUMN mtls_auth JSONB;

-- RFC 8705 §3.4 `tls_client_certificate_bound_access_tokens`. Independent of the column above:
-- a client authenticating with a secret may still present a certificate to have its tokens
-- bound to it, which is the whole point of §3 being separable from §2.
ALTER TABLE oauth_clients
    ADD COLUMN certificate_bound_access_tokens BOOLEAN NOT NULL DEFAULT FALSE;
