CREATE TABLE locales (
    code       TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    is_default BOOLEAN NOT NULL,
    active     BOOLEAN NOT NULL
);

CREATE TABLE forms (
    id TEXT NOT NULL,
    version INT NOT NULL,
    active BOOLEAN NOT NULL,
    style TEXT NOT NULL,
    js_source TEXT,
    js_compiled TEXT,
    localizations JSONB NOT NULL,
    properties JSONB NOT NULL,
    PRIMARY KEY (id, version)
);

-- form_change — fired on forms row changes
CREATE OR REPLACE FUNCTION notify_form_change()
RETURNS trigger AS $$
DECLARE
  rec RECORD;
BEGIN
  rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  PERFORM pg_notify('form_change', json_build_object('id', rec.id, 'version', rec.version, 'op', TG_OP)::text);
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER forms_notify
AFTER INSERT OR UPDATE OR DELETE ON forms
FOR EACH ROW EXECUTE FUNCTION notify_form_change();
