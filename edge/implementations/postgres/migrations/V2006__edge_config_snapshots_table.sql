-- The last configuration central served, one row per sync endpoint, for starting edge while
-- central is unreachable (#566). `body` is the response as received: the credentials in it are
-- still encrypted to this edge's key. `mac` is an HMAC over key, save time and body under a key
-- derived from that private key; a row that fails it is never served.
CREATE TABLE edge_config_snapshots (
    key TEXT PRIMARY KEY,
    body BYTEA NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL,
    mac BYTEA NOT NULL
);
