-- RFC 9449 §9 at the resource server: whether every DPoP proof this edge checks must carry a
-- nonce it issued. Was `dpop.require-nonce` in each edge's own env file; an operator who wants
-- one edge to stop demanding one should not have to redeploy it.
--
-- TRUE for existing rows and for new ones: edge has required a nonce on every proxied call
-- since DPoP landed there, and a migration is not a deployment asking for less.
ALTER TABLE edges
    ADD COLUMN require_dpop_nonce BOOLEAN NOT NULL DEFAULT TRUE;
