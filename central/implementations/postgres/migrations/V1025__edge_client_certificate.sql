-- The client certificate an edge presents on central's mutual-TLS listener, as its RFC 8705 §3.1
-- x5t#S256 thumbprint. Central serves the edge surface (`/edge/*`) only there, and only to the
-- edge whose registered certificate the handshake presented: the TLS trust anchors say the
-- certificate is a genuine one, this says whose. Pinned rather than matched by subject so that
-- no certificate the anchors vouch for can stand in for another edge.
--
-- `old_certificate_thumbprint` is the previous one, accepted during a rotation window exactly as
-- `old_public_key_jwk` is. Both NULL is an edge that cannot call the surface at all.
ALTER TABLE edges
    ADD COLUMN certificate_thumbprint     TEXT,
    ADD COLUMN old_certificate_thumbprint TEXT;
