-- Fail fast rather than stall: a migration that cannot get its locks in 5 seconds fails and is run again. Flyway runs a
-- migration in one transaction, so this holds for the whole file (as V0030 of auth does for its partitioned log).
SET LOCAL lock_timeout = '5s';

-- The audit log of central (#469): what an administrator did through the console's API, one row per change
-- (admin.<resource>.<operation>, built by the hook on every route) or per explicit event (admin.user.viewed,
-- admin.secret.revealed, admin.audit.queried, system.bootstrap). The same columns as auth's audit_event, which is what
-- the per-user report merges it with; the wire form is versola.util.audit.AuditEvent.
--
-- Partitioned by day, as the antifraud event log is: retention is dropping a partition, which is instant and leaves
-- nothing to vacuum. The partitions are created ahead and dropped by the service (DailyPartitions), not by a migration.
-- Long-term keeping is an operator's logical replication of this table to another database (deploy.md), so the rows
-- here are the hot window only.
CREATE TABLE audit_event (
    id          UUID        NOT NULL,   -- UUIDv7: ordered by time, which the cursor of the report relies on
    at          TIMESTAMPTZ NOT NULL,
    tenant_id   TEXT,                   -- the tenant of the actor's token; a target's tenant is in details.tenantId
    source      TEXT        NOT NULL,   -- always 'central' here; kept so a replica can hold every service's rows
    node_id     TEXT        NOT NULL,   -- the instance that wrote the row
    kind        TEXT        NOT NULL,
    outcome     TEXT        NOT NULL,   -- success | failure | denied
    reason      TEXT,                   -- the request's error code on a failure or denial
    severity    TEXT        NOT NULL,   -- info | notice | high, derived from kind and outcome
    actor_type  TEXT        NOT NULL,   -- admin | client | service | system (user and edge are other services')
    actor_id    TEXT,
    user_id     UUID,                   -- the target user of a user* action, never the actor
    client_id   TEXT,
    session_id  TEXT,
    ip          INET,
    user_agent  TEXT,
    trace_id    TEXT,
    target_type TEXT,
    target_id   TEXT,
    -- Kind-specific payload (versola.util.audit.AuditDetails); every insert of the service supplies it.
    details     JSONB       NOT NULL,
    PRIMARY KEY (id, at)
) PARTITION BY RANGE (at);

-- The window scans of the report and of the maintenance: BRIN, since rows arrive in time order.
CREATE INDEX audit_event_at_idx ON audit_event USING brin (at);

-- The per-user report: everything that was done to a user, newest first.
CREATE INDEX audit_event_user_idx ON audit_event (user_id, at) WHERE user_id IS NOT NULL;

-- Everything done from one address.
CREATE INDEX audit_event_ip_idx ON audit_event (ip, at) WHERE ip IS NOT NULL;

-- Everything one administrator did (central only: the auditor's first question).
CREATE INDEX audit_event_actor_idx ON audit_event (actor_id, at) WHERE actor_id IS NOT NULL;

-- Everything done to one object (a client, a role, a key).
CREATE INDEX audit_event_target_idx ON audit_event (target_type, target_id, at) WHERE target_id IS NOT NULL;

-- A row of a day that has no partition is written here instead of being refused, so a maintenance that cannot run
-- loses no events (the reasoning of V0030 of auth). Unlike the antifraud log, a day's rows are never moved out of
-- here into the day's partition: an insert-only logical replication would see them a second time. A day whose rows sit
-- here is not attached and the service reports it (audit_events_in_default) until the rows age out of retention.
CREATE TABLE audit_event_default PARTITION OF audit_event DEFAULT;

-- Append-only: a row is never changed or deleted by anybody but the service's own retention, which prunes the default
-- partition (dropping a day's partition fires no row trigger) under this transaction setting. An AFTER trigger rather
-- than BEFORE, because PostgreSQL 12, the oldest version the antifraud log supports, allows no BEFORE ROW trigger on
-- a partitioned table (13 does); raising in AFTER rolls the statement back all the same. The trigger is cloned onto
-- every partition created or attached later.
CREATE FUNCTION audit_event_immutable() RETURNS trigger AS $$
BEGIN
    IF current_setting('versola.audit_maintenance', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'audit_event is append-only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_immutable
    AFTER UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_immutable();
