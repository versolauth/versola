-- External OAuth Clients table for managing provider credentials
CREATE TABLE external_oauth_clients (
    id SERIAL PRIMARY KEY,
    provider TEXT NOT NULL,
    client_id TEXT NOT NULL,
    password BYTEA NOT NULL,
    old_password BYTEA
);

-- Create unique constraint on provider and client_id combination
CREATE UNIQUE INDEX external_oauth_clients_provider_client_id_idx ON external_oauth_clients (client_id, provider);