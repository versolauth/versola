-- Single-use enforcement for RFC 7523 `private_key_jwt` client assertions (§3: the server
-- "MAY ensure that JWTs are not replayed by maintaining the set of used jti values").
--
-- Same design as dpop_proofs, and for the same reason: a record only has to outlive the
-- assertion it guards, so a fixed ring of partitions reclaimed by TRUNCATE costs no delete per
-- insert and no vacuum churn at the token endpoint's full request rate. See
-- V0014__dpop_proofs_table.sql for the argument in full.
--
-- A separate ring rather than a share of that one, because the two windows are different
-- sizes. A proof is acceptable for `iat-leeway` either side of its `iat` (90s at most, by that
-- ring's geometry); an assertion is acceptable from whenever it was minted until its `exp`,
-- which a tenant may set as far out as ChallengeSettingsRecord.MaxClientAssertionMaxLifetime-
-- Seconds. Widening the DPoP ring to cover that would multiply the storage of every proof it
-- holds to suit a far rarer row.
--
-- The slot comes from the signed `exp` rather than from arrival time: a unique index on a
-- partitioned table is only unique within a partition, so a replay has to land in the
-- partition already holding the original, and `exp` is covered by the assertion's signature.
-- See PostgresClientAssertionRepository for how a one-sided `[minted, exp]` window is centred
-- onto the symmetric window this ring's eviction geometry is proven safe for.
--
-- The geometry is fixed here rather than configured, so that it cannot drift out of step with
-- the code; it must match PostgresClientAssertionRepository.{SlotCount, SlotWidth}.
--
-- Every partition is UNLOGGED, individually, for the reason V0014 gives: `exp` already bounds
-- how long a record can matter, and a crash costs one lifetime window of replay protection,
-- the same exposure a restart already carries. Persistence is NOT inherited from the parent,
-- so any partition added later must repeat the keyword; PostgresClientAssertionRepositorySpec
-- asserts none has been missed.

CREATE TABLE client_assertions (
    slot INTEGER NOT NULL,
    -- A 128-bit BLAKE3 digest over the client id and the `jti`, which caps the row at a fixed
    -- width so an oversized `jti` cannot be used to inflate the table. The client id is folded
    -- in so that the pair, not the `jti` alone, has to be unique -- an accidental collision
    -- between two unrelated clients' assertions cannot then be reported as a replay. A digest
    -- collision conflicts on the primary key and is reported as a replay, so it fails closed.
    digest BYTEA NOT NULL,
    PRIMARY KEY (slot, digest)
) PARTITION BY LIST (slot);

CREATE UNLOGGED TABLE client_assertions_0 PARTITION OF client_assertions FOR VALUES IN (0);
CREATE UNLOGGED TABLE client_assertions_1 PARTITION OF client_assertions FOR VALUES IN (1);
CREATE UNLOGGED TABLE client_assertions_2 PARTITION OF client_assertions FOR VALUES IN (2);
CREATE UNLOGGED TABLE client_assertions_3 PARTITION OF client_assertions FOR VALUES IN (3);
CREATE UNLOGGED TABLE client_assertions_4 PARTITION OF client_assertions FOR VALUES IN (4);
CREATE UNLOGGED TABLE client_assertions_5 PARTITION OF client_assertions FOR VALUES IN (5);
CREATE UNLOGGED TABLE client_assertions_6 PARTITION OF client_assertions FOR VALUES IN (6);
CREATE UNLOGGED TABLE client_assertions_7 PARTITION OF client_assertions FOR VALUES IN (7);
CREATE UNLOGGED TABLE client_assertions_8 PARTITION OF client_assertions FOR VALUES IN (8);
CREATE UNLOGGED TABLE client_assertions_9 PARTITION OF client_assertions FOR VALUES IN (9);
CREATE UNLOGGED TABLE client_assertions_10 PARTITION OF client_assertions FOR VALUES IN (10);
CREATE UNLOGGED TABLE client_assertions_11 PARTITION OF client_assertions FOR VALUES IN (11);
