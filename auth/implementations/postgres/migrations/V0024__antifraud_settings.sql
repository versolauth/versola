-- Antifraud settings an operator changes during an attack (thresholds, rule weights, shadow rules, limits on codes sent
-- to a range of numbers, the proof-of-work difficulty), kept apart from the configuration because a restart is too slow
-- for them and empties the in-memory counters. One row (id 1), a Tuning as JSON with every key, seeded below with
-- everything off, so there is always one. The configuration says nothing of these values.
--
-- The service keeps the tuning in memory and rereads it when af_settings_version changes, so the table is never read on a
-- request.
CREATE TABLE af_settings (
    id         SMALLINT    NOT NULL PRIMARY KEY,
    settings   TEXT        NOT NULL,
    updated_by TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- The seed is Tuning.off; PostgresAntifraudSettingsRepositorySpec checks that they are the same.
INSERT INTO af_settings (id, settings, updated_by, updated_at) VALUES (
    1,
    '{"challengeAt":0,"reviewAt":0,"blockAt":0,'
        '"pow":{"minDifficulty":14,"maxDifficulty":18,"scoreStep":15},'
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

-- Bumped in the transaction of every change to af_settings, as af_list_version is for the lists. A change made by hand in
-- SQL does not bump it; the instances reload everything once a minute regardless.
CREATE TABLE af_settings_version (
    id      SMALLINT NOT NULL PRIMARY KEY,
    version BIGINT   NOT NULL
);
INSERT INTO af_settings_version (id, version) VALUES (1, 0);
