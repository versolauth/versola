CREATE TABLE dpop_proofs (
    jkt TEXT NOT NULL,
    jti TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (jkt, jti)
);

CREATE INDEX dpop_proofs_expires_at_idx
    ON dpop_proofs (expires_at);
