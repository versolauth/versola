-- RFC 7523 §2.2 `private_key_jwt`: the JWK Set the client signs its assertions with. NULL
-- means the client does not use the method. Stored as the document registered rather than
-- as parsed key material, so JWK members central has no opinion on survive the round trip.
-- Mutually exclusive with mtls_auth, which registration enforces.
ALTER TABLE oauth_clients
    ADD COLUMN jwks JSONB;
