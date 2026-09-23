-- RFC 9449 §5.1 proof key policy, per client: which signing algorithms a proof from it may
-- use, and how long an RSA proof key's modulus must be.
--
-- An empty dpop_signing_algs is "no narrowing" -- the client is held to whatever
-- dpop_signing_alg_values_supported advertises, which is what every already registered client
-- was held to. A NULL dpop_min_rsa_key_size leaves the RFC 7518 §3.3 floor of 2048 bits, which
-- auth applies whether or not a client registered anything; the column can only raise it.
ALTER TABLE oauth_clients
    ADD COLUMN dpop_signing_algs     TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN dpop_min_rsa_key_size INTEGER;
