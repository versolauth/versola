CREATE TABLE pushed_authorization_requests (
    request_uri BYTEA PRIMARY KEY,
    client_id TEXT NOT NULL,
    params JSONB NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX pushed_authorization_requests_expires_at_idx
    ON pushed_authorization_requests (expires_at);
