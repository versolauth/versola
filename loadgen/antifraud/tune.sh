#!/usr/bin/env bash
# Writes the stored antifraud settings of the local stack's auth from a JSON file (loadgen/antifraud/tuning/*.json)
# and bumps af_settings_version, so that auth rereads them within antifraud.tuning.refresh-interval (5 s). In a
# deployment these are set through central's admin API (PUT /antifraud/settings, see deploy.md); the stack here is
# driven by the utility client, which has no antifraud permission, so the row is written as the API would write it.
#
#   loadgen/antifraud/tune.sh loadgen/antifraud/tuning/evaluate.json            # AF_PG_URL defaults to the README's Postgres
#   AF_PG_URL=postgresql://dev:1234@localhost:5433/auth loadgen/antifraud/tune.sh loadgen/antifraud/tuning/sms-budget.json
set -euo pipefail
file="${1:?usage: tune.sh <settings.json>}"
url="${AF_PG_URL:-postgresql://dev:1234@localhost:5433/auth}"
settings="$(tr -d '\n' < "$file")"
psql "$url" -v ON_ERROR_STOP=1 -q <<SQL
INSERT INTO af_settings (id, settings, updated_by, updated_at)
VALUES (1, '$settings'::jsonb, 'loadgen tune.sh', now())
ON CONFLICT (id) DO UPDATE SET settings = EXCLUDED.settings, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at;
INSERT INTO af_settings_version (id, version) VALUES (1, 1)
ON CONFLICT (id) DO UPDATE SET version = af_settings_version.version + 1;
SQL
echo "antifraud settings written from $file; auth rereads them within a few seconds"
