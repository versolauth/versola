CREATE TABLE edges (
    id                 TEXT PRIMARY KEY,
    public_key_jwk     JSONB NOT NULL,
    old_public_key_jwk JSONB,
    -- RFC 9449 §9 at the resource server: whether every DPoP proof this edge checks must carry a
    -- nonce it issued. Per edge rather than in each edge's own env file, so an operator who wants
    -- one edge to stop demanding one does not have to redeploy it. A new edge requires one
    -- (EdgeRecord.DefaultRequireDpopNonce): edge has required a nonce on every proxied call
    -- since DPoP landed there.
    require_dpop_nonce BOOLEAN NOT NULL
);

-- edge_change — empty payload, reload all
CREATE OR REPLACE FUNCTION notify_edge_change()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('edge_change', '');
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER edges_notify
AFTER INSERT OR UPDATE OR DELETE ON edges
FOR EACH STATEMENT EXECUTE FUNCTION notify_edge_change();