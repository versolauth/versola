-- Antifraud settings an operator changes during an attack (thresholds, rule weights, shadow rules, limits on codes sent
-- to a range of numbers, timings), kept apart from the configuration because a restart is too slow for them and empties
-- the in-memory counters. A row is a TuningPatch as JSON. The global row (tenant_id '') has every key, is seeded below
-- with everything off, and is the only source of what applies to a tenant that has no row; a tenant's row is a patch over
-- it, and what it leaves out is the global row's. The configuration says nothing of these values.
--
-- The service keeps the result in memory and rebuilds it when af_settings_version changes, so the table is read in bulk
-- and never on a request.
CREATE TABLE af_settings (
    tenant_id  TEXT        NOT NULL PRIMARY KEY,
    settings   TEXT        NOT NULL,
    updated_by TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Who changed what and when; the rows before and after are copied in, because the row may be gone.
-- The seed is Tuning.off; PostgresAntifraudSettingsRepositorySpec checks that they are the same.
INSERT INTO af_settings (tenant_id, settings, updated_by, updated_at) VALUES (
    '',
    '{"delayAt":0,"challengeAt":0,"reviewAt":0,"blockAt":0,"delayMillis":2000,"fakeSendDelayMillis":0,'
        '"thresholds":{'
            '"phone":{"perMinute":0,"perTenMinutes":0,"perHour":0,"perDay":0,"weight":50},'
            '"ip":{"perMinute":0,"perTenMinutes":0,"perHour":0,"perDay":0,"weight":20},'
            '"device":{"perMinute":0,"perTenMinutes":0,"perHour":0,"perDay":0,"weight":60},'
            '"asn":{"perMinute":0,"perTenMinutes":0,"perHour":0,"perDay":0,"weight":20},'
            '"prefix":{"perMinute":0,"perTenMinutes":0,"perHour":0,"perDay":0,"weight":30},'
            '"trustedDiscount":40},'
        '"smsLimits":{"default":{"sentPerHour":0,"minVolume":0,"minConversion":0.1},"byPrefix":{}},'
        '"ruleWeights":{},"shadowRules":[]}',
    'migration',
    now()
);

CREATE TABLE af_settings_audit (
    id        UUID        NOT NULL PRIMARY KEY,
    at        TIMESTAMPTZ NOT NULL,
    actor     TEXT        NOT NULL,
    tenant_id TEXT        NOT NULL,
    before    TEXT,
    after     TEXT
);

CREATE INDEX af_settings_audit_at_idx ON af_settings_audit (at DESC, id);
CREATE INDEX af_settings_audit_tenant_idx ON af_settings_audit (tenant_id, at DESC, id);

-- Bumped in the transaction of every change to af_settings, as af_list_version is for the lists. A change made by hand in
-- SQL does not bump it; the instances reload everything once a minute regardless.
CREATE TABLE af_settings_version (
    id      SMALLINT NOT NULL PRIMARY KEY,
    version BIGINT   NOT NULL
);
INSERT INTO af_settings_version (id, version) VALUES (1, 0);
