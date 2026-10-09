CREATE TABLE tenants (
    id   TEXT PRIMARY KEY,
    description TEXT NOT NULL,
    edge_id TEXT REFERENCES edges(id) ON DELETE SET NULL
);

-- INSERT INTO tenants (id, description) VALUES ('default', 'Default');

