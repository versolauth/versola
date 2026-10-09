-- Which clients' `edge_client_certificate` central issued itself (#440), and until when it is
-- valid. Presence of a row is what makes central renew a certificate: one an operator supplied has
-- no row and is left alone.
--
-- No key material here -- the certificate and its key are oauth_clients.edge_client_certificate.
-- A table of its own rather than columns on oauth_clients, so that what the audit trail (#462)
-- later needs to record can grow here without touching the client record or its sync.
--
-- ON DELETE CASCADE: deleting a client leaves nothing to renew.
CREATE TABLE client_certificate_issuance (
    client_id   TEXT        PRIMARY KEY REFERENCES oauth_clients (id) ON DELETE CASCADE,
    serial      TEXT        NOT NULL,
    not_after   TIMESTAMPTZ NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX client_certificate_issuance_not_after_idx ON client_certificate_issuance (not_after);
