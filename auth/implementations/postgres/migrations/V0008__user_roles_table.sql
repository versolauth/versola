CREATE TABLE user_roles (
    user_id      UUID   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id    TEXT   NOT NULL,
    role_id      TEXT   NOT NULL,
    PRIMARY KEY (user_id, tenant_id, role_id)
);