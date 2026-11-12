-- Revoking every grant of one client (#962: the kill switch of a partner) finds the client's live families by `client_id`;
-- without an index that is a scan of the table that holds every client's tokens, first-party ones included. Only live
-- rows are of interest: a rotated token is never revoked again, so it stays out of the index.
CREATE INDEX refresh_tokens_client_live_idx ON refresh_tokens (client_id, family_id) WHERE rotated_at IS NULL;
