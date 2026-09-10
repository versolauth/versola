CREATE TABLE tenants (
    id   TEXT PRIMARY KEY,
    description TEXT NOT NULL,
    signing_algorithm TEXT NOT NULL DEFAULT 'RS256'
);

-- INSERT INTO tenants (id, description) VALUES ('default', 'Default');

