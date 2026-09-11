-- Single-use enforcement for DPoP proofs (RFC 9449 §11.1).
--
-- A proof is only ever accepted while its `iat` sits inside the configured leeway, so a record
-- has to outlive the proof it guards by that leeway and not a moment longer. Stamping each row
-- with an expiry and sweeping it costs a delete for every insert, at the token endpoint's full
-- request rate, plus the vacuum that churn implies. Instead a row is placed in one of a fixed
-- ring of partitions chosen from the proof's own `iat`, and a slot is reclaimed by truncating
-- it once every proof it could hold has fallen outside the acceptance window.
--
-- The slot comes from the signed `iat` rather than from arrival time, which is what keeps the
-- check exact: a unique index on a partitioned table is only unique within a partition, so a
-- replay has to land in the partition already holding the original. `iat` is covered by the
-- proof's signature, so it cannot be moved to a fresh slot without invalidating the proof.
--
-- The geometry is fixed here rather than configured, so that it cannot drift out of step with
-- the code; it must match PostgresDpopProofRepository.{SlotCount, SlotWidth}.

CREATE TABLE dpop_proofs (
    slot INTEGER NOT NULL,
    -- RFC 9449 §11.1 asks servers tracking `jti` values to "store only a hash thereof". A
    -- 128-bit BLAKE3 digest over `jkt` and `jti` also caps the row at a fixed width, so an
    -- oversized `jti` cannot be used to inflate the table -- the memory exhaustion the same
    -- section warns about. A digest collision between two distinct proofs conflicts on the
    -- primary key and is reported as a replay, so it fails closed.
    digest BYTEA NOT NULL,
    PRIMARY KEY (slot, digest)
) PARTITION BY LIST (slot);

CREATE TABLE dpop_proofs_0 PARTITION OF dpop_proofs FOR VALUES IN (0);
CREATE TABLE dpop_proofs_1 PARTITION OF dpop_proofs FOR VALUES IN (1);
CREATE TABLE dpop_proofs_2 PARTITION OF dpop_proofs FOR VALUES IN (2);
CREATE TABLE dpop_proofs_3 PARTITION OF dpop_proofs FOR VALUES IN (3);
CREATE TABLE dpop_proofs_4 PARTITION OF dpop_proofs FOR VALUES IN (4);
CREATE TABLE dpop_proofs_5 PARTITION OF dpop_proofs FOR VALUES IN (5);
CREATE TABLE dpop_proofs_6 PARTITION OF dpop_proofs FOR VALUES IN (6);
CREATE TABLE dpop_proofs_7 PARTITION OF dpop_proofs FOR VALUES IN (7);
