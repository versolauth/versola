-- Cross-replica single-use enforcement for DPoP proofs at the resource server (RFC 9449 §11.1).
--
-- Edge runs as a fleet with no session affinity, so a per-pod record of seen proofs answers
-- "have *I* seen this?" and not "has anyone?". A proof replayed to a second replica inside the
-- `iat` window misses both local rings and is admitted twice; the exposure is `2 * iat-leeway`.
-- The in-memory ring stays in front of this table as an L1 -- it still short-circuits a replay
-- that lands on the pod that saw the original -- but the exact answer lives here.
--
-- The design is auth's `dpop_proofs` ring, deliberately unchanged: a row is placed in one of a
-- fixed ring of partitions chosen from the proof's own `iat`, and a slot is reclaimed by
-- truncating it once every proof it could hold has fallen outside the acceptance window. That
-- costs one truncate per slot instead of a delete per insert at the full rate of every proxied
-- request, and no vacuum churn.
--
-- The slot comes from the signed `iat` rather than from arrival time, which is what keeps the
-- check exact: a unique index on a partitioned table is only unique within a partition, so a
-- replay has to land in the partition already holding the original. `iat` is covered by the
-- proof's signature, so it cannot be moved to a fresh slot without invalidating the proof.
--
-- The geometry is fixed here rather than configured, so that it cannot drift out of step with
-- the code; it must match PostgresDpopProofRepository.{SlotCount, SlotWidth}. It is auth's
-- 12-slot geometry and not the 8 slots of the in-memory ring: this ring is written by one pod
-- and evicted by another, so it has to hold each slot open across the tolerated clock skew,
-- which the local ring needs no margin for -- there, the pod that accepted a proof is the pod
-- that forgets it. The two geometries are both correct and must not be "unified".
--
-- Every partition is UNLOGGED, individually: `iat` has already put an upper bound on how long
-- a record can matter, and `Dpop.verify` rejects on `iat` before this table is ever consulted,
-- so WAL would be buying durability for rows engineered to expire within the minute. A crash
-- empties the ring, which degrades to exactly the in-memory guard's answer for one
-- `iat-leeway` window -- the same floor the fail-degraded fallback already accepts. Persistence
-- is NOT inherited from the parent: a partition added later without the keyword silently
-- reverts to full WAL logging, so any new one must repeat it, and
-- PostgresDpopProofRepositorySpec asserts none has been missed. The parent itself cannot be
-- unlogged; PostgreSQL rejects `CREATE UNLOGGED TABLE ... PARTITION BY`.

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
