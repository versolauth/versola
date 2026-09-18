-- Central stored public JWKs only, so a key it generated could never sign anything: the
-- private half was discarded and auth had to find its own kid by matching the modulus of
-- the one static key in `jwt.private-key` (see #104). Generating a PS256 or ES256 key is
-- pointless while that holds -- auth has no EC private key to pair with an EC kid.
--
-- AES-GCM over PKCS#8, under the same `clientSecretsSecret` that already protects client
-- and resource secrets at rest, so this introduces no new key material or trust boundary.
--
-- NULL is meaningful: it marks a key as verify-only, which is exactly what every key
-- seeded from `bootstrap.jwks` is -- central was never given its private half.
ALTER TABLE jwks
    ADD COLUMN private_key BYTEA;

-- Which published key this tenant's tokens are signed with. A bare algorithm name could not
-- address a key: during a rotation two keys share one `alg`, and a stored `alg` can disagree
-- with the key behind the kid. The algorithm is read from the key's own JWK instead.
--
-- NULL keeps today's behaviour -- auth falls back to matching `jwt.private-key` against the
-- synced JWKS -- so an existing deployment, whose keys are all verify-only, is unaffected
-- until an operator generates a key and selects it.
--
-- The reference means a key cannot be deleted out from under a tenant still signing with it.
ALTER TABLE challenge_settings
    ADD COLUMN signing_key_id TEXT REFERENCES jwks (kid);
