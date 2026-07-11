-- Global (non-tenant-scoped) settings.
-- E.g. - password credentials are stored globally
-- per user, so the password policy that governs them must be global too.
CREATE TABLE system_settings (
    id                     INTEGER PRIMARY KEY CHECK (id = 1), -- singleton row
    password_regex         TEXT NOT NULL,
    password_history_size  INT NOT NULL,
    password_num_different  INT NOT NULL
);


CREATE OR REPLACE FUNCTION notify_system_settings_change()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('system_settings_change', '');
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER system_settings_notify
AFTER INSERT OR UPDATE OR DELETE ON system_settings
FOR EACH STATEMENT EXECUTE FUNCTION notify_system_settings_change();
