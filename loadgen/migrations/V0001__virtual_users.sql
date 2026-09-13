-- The emulator's own bookkeeping, never the SUT's schema (dev spec §6). Everything here is
-- reconstructible by a re-seed, which is what buys the two properties this store is tuned for:
-- UNLOGGED tables (no WAL for the emulator's writes) and asynchronous commit. At 10M modelled
-- users the write rate mirrors the refresh rate -- ~420 writes/s at peak -- and this store must
-- never become the bottleneck that the campaign then attributes to the SUT (design doc §6.6).

-- synchronous_commit belongs to the database rather than to a table, so it is set here rather
-- than left to whoever writes the Helm values: a store deployed without it is not visibly
-- different, it is just slower under load, which is exactly the failure that reads as a SUT
-- regression. The EXCEPTION arm keeps the migration applicable by a role that does not own the
-- database (a managed instance, or a shared dev box) -- the setting is then the deployment's
-- job, and the warning says so instead of failing the boot.
DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I SET synchronous_commit = off', current_database());
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE WARNING 'Could not set synchronous_commit = off on %: not the database owner. '
                      'Set it in the server/Helm configuration instead (dev spec §6).',
                      current_database();
END
$$;

-- No DEFAULT on any column (versolauth/versola#267): every insert into this schema comes from
-- the seeder or a driver and supplies every value, so a field someone forgot to write fails at
-- the call site instead of silently acquiring a value nobody chose.
CREATE UNLOGGED TABLE vu_users (
    id              BIGINT      NOT NULL PRIMARY KEY,   -- dense, not UUID: it is the shard key
    sut_user_id     UUID,                               -- NULL until registered/seeded
    phone           TEXT        NOT NULL UNIQUE,
    password        TEXT,                               -- plaintext, test-only
    activity_class  SMALLINT    NOT NULL,
    platform        SMALLINT    NOT NULL,
    credential      SMALLINT    NOT NULL,               -- otp | otp-password | passkey
    role            SMALLINT    NOT NULL,
    passkey_key     BYTEA,                              -- PKCS#8 P-256 private key
    passkey_cred_id TEXT,
    state           SMALLINT    NOT NULL,               -- planned | registered | broken
    shard           SMALLINT    NOT NULL,
    last_seen_at    TIMESTAMPTZ
);

-- Serves the driver's slice load: `WHERE shard = ? AND id > ? ORDER BY id LIMIT ?`, the keyset
-- pagination in VirtualUserRepository.loadShardSlice. Leading `shard` makes one driver's users
-- one contiguous stretch of this index even though `shard = id % count` scatters them across
-- the heap, and the trailing `id` makes the paging a range scan with no sort.
CREATE INDEX vu_users_shard_idx ON vu_users (shard, id);

-- Serves the coordinator's population counts: `SELECT state, count(*) FROM vu_users GROUP BY
-- state` every 60 s for the registration controller and `GET /status` (dev spec §12). Without
-- it that is a full scan of 10-20M rows on a fixed timer, competing with the drivers' writes;
-- with it the planner can take an index-only scan of a smallint btree.
CREATE INDEX vu_users_state_idx ON vu_users (state);
