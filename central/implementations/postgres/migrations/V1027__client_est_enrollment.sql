-- Clients that obtain their mTLS client certificate through EST (RFC 7030, served by auth, issued by central).
-- ON DELETE CASCADE throughout: deleting a client leaves nothing to enrol or renew.
CREATE TABLE client_est_enrollments (
    client_id        TEXT        PRIMARY KEY REFERENCES oauth_clients (id) ON DELETE CASCADE,
    enrolled_at      TIMESTAMPTZ NOT NULL,
    -- The right to renew belongs to one generation of the client's chain: a re-enrolment, a revocation and a detected
    -- fork each start a new one, and a certificate of an earlier generation can no longer renew.
    generation       INTEGER     NOT NULL,
    -- When the chain of renewals began (the enrolment, or the last extension); NULL until the first enrolment, and after
    -- a revocation until the next.
    chain_started_at TIMESTAMPTZ
);

-- One-time enrolment tokens an operator creates for a client. Only the hash is kept, so a leaked table enrols nothing.
CREATE TABLE client_est_tokens (
    token_hash TEXT        PRIMARY KEY,
    client_id  TEXT        NOT NULL REFERENCES oauth_clients (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ
);

-- Deleting a client cascades here by client; a cleanup of expired tokens goes by time.
CREATE INDEX client_est_tokens_client_idx ON client_est_tokens (client_id);
CREATE INDEX client_est_tokens_expires_idx ON client_est_tokens (expires_at);

-- Every certificate issued through EST, found by thumbprint when one is presented to renew. The certificate is not stored.
-- `superseded_at` is when a renewal replaced it: a certificate that renews again long after that is a copy of the chain.
CREATE TABLE client_est_certificates (
    thumbprint    TEXT        PRIMARY KEY,
    client_id     TEXT        NOT NULL REFERENCES oauth_clients (id) ON DELETE CASCADE,
    serial        TEXT        NOT NULL,
    not_after     TIMESTAMPTZ NOT NULL,
    issued_at     TIMESTAMPTZ NOT NULL,
    generation    INTEGER     NOT NULL,
    superseded_at TIMESTAMPTZ
);

CREATE INDEX client_est_certificates_client_idx ON client_est_certificates (client_id);
