CREATE TABLE otp_templates (
    id            TEXT NOT NULL,
    tenant_id     TEXT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    localizations JSONB NOT NULL,
    PRIMARY KEY (id, tenant_id)
);

-- otp_template_change — fired on otp_templates row changes
CREATE OR REPLACE FUNCTION notify_otp_template_change()
RETURNS trigger AS $$
DECLARE
  rec RECORD;
BEGIN
  rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  PERFORM pg_notify(
    'otp_template_change',
    json_build_object('tenantId', rec.tenant_id, 'id', rec.id, 'op', TG_OP)::text
  );
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER otp_templates_notify
AFTER INSERT OR UPDATE OR DELETE ON otp_templates
FOR EACH ROW EXECUTE FUNCTION notify_otp_template_change();
