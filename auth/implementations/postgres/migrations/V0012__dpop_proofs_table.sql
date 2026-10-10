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
-- the code; it must match PostgresDpopProofRepository.{SlotCount, SlotWidth}. The ring holds
-- more slots than the acceptance window needs, because acceptance and eviction are decided
-- against different instances' clocks -- see PostgresDpopProofRepository.MaxClockSkew.
--
-- Every partition is UNLOGGED, individually: `iat` has already put an upper bound on how long
-- a record can matter, and `Dpop.verify` rejects on `iat` before this table is ever consulted,
-- so WAL would be buying durability for rows engineered to expire within the minute. A crash
-- empties the ring and costs one `iat-leeway` window of replay protection, which is the same
-- exposure a restart already carries. Persistence is NOT inherited from the parent -- a
-- partition added later without the keyword silently reverts to full WAL logging, so any new
-- one must repeat it, and PostgresDpopProofRepositorySpec asserts none has been missed. The
-- parent itself cannot be unlogged; PostgreSQL rejects `CREATE UNLOGGED TABLE ... PARTITION BY`.

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

CREATE UNLOGGED TABLE dpop_proofs_0 PARTITION OF dpop_proofs FOR VALUES IN (0);
CREATE UNLOGGED TABLE dpop_proofs_1 PARTITION OF dpop_proofs FOR VALUES IN (1);
CREATE UNLOGGED TABLE dpop_proofs_2 PARTITION OF dpop_proofs FOR VALUES IN (2);
CREATE UNLOGGED TABLE dpop_proofs_3 PARTITION OF dpop_proofs FOR VALUES IN (3);
CREATE UNLOGGED TABLE dpop_proofs_4 PARTITION OF dpop_proofs FOR VALUES IN (4);
CREATE UNLOGGED TABLE dpop_proofs_5 PARTITION OF dpop_proofs FOR VALUES IN (5);
CREATE UNLOGGED TABLE dpop_proofs_6 PARTITION OF dpop_proofs FOR VALUES IN (6);
CREATE UNLOGGED TABLE dpop_proofs_7 PARTITION OF dpop_proofs FOR VALUES IN (7);
CREATE UNLOGGED TABLE dpop_proofs_8 PARTITION OF dpop_proofs FOR VALUES IN (8);
CREATE UNLOGGED TABLE dpop_proofs_9 PARTITION OF dpop_proofs FOR VALUES IN (9);
CREATE UNLOGGED TABLE dpop_proofs_10 PARTITION OF dpop_proofs FOR VALUES IN (10);
CREATE UNLOGGED TABLE dpop_proofs_11 PARTITION OF dpop_proofs FOR VALUES IN (11);
