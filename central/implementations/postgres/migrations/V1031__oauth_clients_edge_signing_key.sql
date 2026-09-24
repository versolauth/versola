-- The private key an edge fronting this client signs with: RFC 7523 §2.2 client assertions at
-- the token endpoint, and RFC 9101 request objects at the authorization endpoint. NULL means
-- no edge authenticates as this client by key -- it either uses the secret, or is not fronted.
--
-- The public half lives in the jwks column, which is what auth verifies against; registration
-- requires the two to match, since a key auth holds no counterpart for can only ever fail.
--
-- Encrypted at rest with the same AES key as the secret column (clientSecretsSecret) and
-- handed to an edge the same way, re-encrypted to that edge's registered RSA public key --
-- central is already the provisioning authority for a fronted client's credential, and a
-- private key is that credential in the same sense the secret is.
ALTER TABLE oauth_clients
    ADD COLUMN edge_signing_key BYTEA;
