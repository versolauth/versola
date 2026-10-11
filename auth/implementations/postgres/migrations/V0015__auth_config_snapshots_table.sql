-- The last configuration central served, one row per sync endpoint, for starting auth while
-- central is unreachable (#566). `body` is the response as received: the secrets in it are
-- still encrypted under the auth-central transport secret. `mac` is an HMAC over key, save
-- time and body under a key derived from that secret; a row that fails it is never served.
CREATE TABLE auth_config_snapshots (
    key TEXT PRIMARY KEY,
    body BYTEA NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL,
    mac BYTEA NOT NULL
);
