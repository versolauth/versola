-- The App Attest keys (#423) an iOS app has had attested to this edge, and the counter of the last assertion each has made.
--
-- An iOS app proves a request with a key that is not its DPoP key (App Attest signs only what it is handed), so edge has to
-- remember the key it was shown at attestation to check later assertions with. The counter is what makes an assertion single
-- use: it is moved forward by one statement that only succeeds when the new value is greater, so two replicas given the same
-- assertion cannot both accept it. Android needs none of this -- its attested key is the DPoP key.
--
-- One row per install and client. `key_id` is the key's identity as App Attest names it (the hash of its public key), in
-- unpadded base64url. `public_key` is the key as X.509 SubjectPublicKeyInfo. `environment` is the App Attest environment the key was
-- attested in (`production` or `development`), checked again at every assertion: a policy that now accepts only production does not go
-- on accepting a development key. `expires_at` is when the key is forgotten if nobody uses it: it is moved forward by every
-- assertion, and the `cleanup` block's `attested_keys` entry deletes the rows past it. An app that comes back after that gets
-- `this key has not been attested` and attests a new key, which is the path an unknown key takes anyway. No DEFAULT: the writer
-- names every column. A new table, so the previous release, which never reads it, runs on this schema and a rollback works.
CREATE TABLE attested_keys (
    client_id    TEXT        NOT NULL,
    key_id       TEXT        NOT NULL,
    public_key   BYTEA       NOT NULL,
    environment  TEXT        NOT NULL,
    counter      BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (client_id, key_id)
);

-- What the cleanup of expired rows scans.
CREATE INDEX attested_keys_expires_at_idx ON attested_keys (expires_at);
