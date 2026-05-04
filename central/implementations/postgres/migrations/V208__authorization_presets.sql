CREATE TABLE authorization_presets (
    id                             TEXT PRIMARY KEY,
    tenant_id                      TEXT NOT NULL,
    client_id                      TEXT NOT NULL,
    description                    TEXT NOT NULL,
    redirect_uri                   TEXT NOT NULL,
    scope                          TEXT[] NOT NULL,
    response_type                  TEXT NOT NULL,
    ui_locales                     TEXT[],
    custom_parameters              JSONB NOT NULL,
    FOREIGN KEY (tenant_id, client_id) REFERENCES oauth_clients(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_authorization_presets_tenant_client ON authorization_presets(tenant_id, client_id);

CREATE OR REPLACE FUNCTION notify_preset_change()
    RETURNS trigger AS $$
DECLARE
    rec RECORD;
BEGIN
    rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    PERFORM pg_notify('preset_change', json_build_object('tenantId', rec.tenant_id, 'id', rec.id, 'op', TG_OP)::text);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER authorization_presets_notify
    AFTER INSERT OR UPDATE OR DELETE ON authorization_presets
    FOR EACH ROW EXECUTE FUNCTION notify_preset_change();