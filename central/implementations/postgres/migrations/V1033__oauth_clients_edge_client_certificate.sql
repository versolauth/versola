-- The certificate and private key an edge fronting this client presents at the TLS handshake,
-- so that whatever terminates mutual TLS in front of auth recognises the connection as this
-- client's (RFC 8705 §2). NULL means no edge authenticates as this client by certificate -- it
-- either uses the secret or the edge signing key, or is not fronted.
--
-- What the certificate is compared against lives in the mtls_auth column: §2.1 matches the
-- registered subject value against the certificate, §2.2 matches the key inside it against
-- jwks. Registration requires that column to be set, since no certificate is ever read for a
-- client that registered neither.
--
-- Stored as the registered PEM text, both halves together, encrypted at rest with the same AES
-- key as the secret column (clientSecretsSecret) and handed to an edge the same way,
-- re-encrypted to that edge's registered RSA public key -- the same reasoning as
-- edge_signing_key, which this is the mutual-TLS counterpart of.
ALTER TABLE oauth_clients
    ADD COLUMN edge_client_certificate BYTEA;
