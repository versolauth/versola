-- OAuth Clients table with integrated secret management
-- Using BLAKE3 MAC with salt+pepper for client secret storage (ADR 00)
-- secret format: BLAKE3-MAC (32 bytes) || salt (16 bytes) = 48 bytes total
CREATE TABLE oauth_clients (
    id TEXT PRIMARY KEY,
    client_name TEXT NOT NULL,
    redirect_uris TEXT[] NOT NULL,
    scope TEXT[] NOT NULL,
    secret BYTEA,              -- BLAKE3 MAC (32 bytes) || Per-client salt (16 bytes) = 48 bytes
    previous_secret BYTEA      -- Previous secret for rotation, same 48 bytes format
);
