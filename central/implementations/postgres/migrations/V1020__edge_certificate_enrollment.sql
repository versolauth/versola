-- Clients whose edge generates its own key and enrols for a certificate (#463): central stores no
-- certificate and no key for them, only that they are enrolled and when and by whom the last
-- certificate was signed (the start of the audit trail #462 designs).
--
-- The certificate itself lives only on the edge that asked for it, one per edge replica, each with
-- its own key; all of them carry the subject the client registered, which is what auth recognises
-- it by.
--
-- ON DELETE CASCADE: deleting a client leaves nothing to enrol for.
CREATE TABLE edge_certificate_enrollment (
    client_id        TEXT        PRIMARY KEY REFERENCES oauth_clients (id) ON DELETE CASCADE,
    enrolled_at      TIMESTAMPTZ NOT NULL,
    last_serial      TEXT,
    last_issued_at   TIMESTAMPTZ,
    last_edge_id     TEXT
);
