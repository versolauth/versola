-- The subject line of an email template, one per locale, keyed like `localizations`. Empty for every existing row and for
-- every SMS template: auth then sends the subject of the SMTP settings, so a deployment behaves as before until someone
-- writes a subject into a template.
--
-- DEFAULT '{}' stays: the previous release keeps serving while `versola migrate` runs, and rolling back to it must work;
-- its INSERT does not name this column and the column is NOT NULL.
ALTER TABLE otp_templates ADD COLUMN subjects JSONB NOT NULL DEFAULT '{}'::jsonb;
