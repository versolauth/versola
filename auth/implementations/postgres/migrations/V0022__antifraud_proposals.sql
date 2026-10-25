-- What the detectors found, as a proposal for a list entry: a person (or, later, a policy) decides whether it is
-- made. Nothing here changes what the rules do; a proposal becomes an entry of af_list_entry only through
-- AntifraudLists, when it is approved.
--
-- kind is the detector (prefix_anomaly, fake_verify). value_type and value say what to list, in the terms of
-- af_list_entry, and suggested_action is the list type to put it on (deny, watch or fake). evidence is what the
-- detector saw, for whoever decides. ttl_seconds is how long the entry lives once made (the proposal itself is
-- open for antifraud.detectors.open-for and then expires). tenant_id is never empty: a range seen under no tenant
-- is not proposed, since the entry would have to be global.
--
-- The columns that hold a kind (kind, value_type, suggested_action, status) carry no CHECK: the set of kinds is the service's,
-- and a new kind must not need a migration.
-- status: open until decided; approved and rejected carry who decided and when; expired means nobody did.
CREATE TABLE af_proposal (
    id               UUID        NOT NULL PRIMARY KEY,
    tenant_id        TEXT        NOT NULL,
    kind             TEXT        NOT NULL,
    value_type       TEXT        NOT NULL,
    value            TEXT        NOT NULL,
    evidence         JSONB       NOT NULL,
    suggested_action TEXT        NOT NULL,
    ttl_seconds      BIGINT      NOT NULL,
    status           TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    decided_by       TEXT,
    decided_at       TIMESTAMPTZ
);

-- One open proposal per value and detector: the detectors run on every instance every minute and insert with
-- ON CONFLICT DO NOTHING, so a repeated finding is not a second proposal, whichever instance makes it.
CREATE UNIQUE INDEX af_proposal_open_idx ON af_proposal (tenant_id, kind, value) WHERE status = 'open';

-- Whether a value was decided on lately (a rejected one is not proposed again at once), and the open ones of a tenant.
CREATE INDEX af_proposal_value_idx ON af_proposal (tenant_id, kind, value, created_at DESC);

-- The service removes decided and expired proposals by age, oldest first, in batches. (Expiring the open ones reads
-- the unique index: there are few of them.)
CREATE INDEX af_proposal_decided_idx ON af_proposal (created_at) WHERE status <> 'open';

-- A detector asks "was a code entered after a fake send?" per range, so the rollup counts those too.
-- DEFAULT: the rollup of the previous release names no such column while it still serves during `versola migrate`;
-- it can go once that release is no longer a rollback target.
ALTER TABLE af_dest_stats ADD COLUMN fake_verifies INTEGER NOT NULL DEFAULT 0;
