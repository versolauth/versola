-- How auth delivers one-time codes and temporary passwords: the SMTP server and the SMS provider,
-- one record per deployment (#832). Set in the admin console, read by auth through
-- /configuration/delivery-settings/sync. No row until the first save: that is how auth tells
-- "nothing stored, keep reading the blocks of auth.conf" from "stored".
CREATE TABLE delivery_settings (
    id                    INTEGER PRIMARY KEY CHECK (id = 1), -- singleton row
    -- Each block without its password, as the JSON of SmtpDeliverySettings and
    -- OtpProviderDeliverySettings; NULL when the block is not configured.
    smtp                  JSONB,
    otp_provider          JSONB,
    -- AES-GCM under central's clientSecretsSecret, like client and resource secrets. A column of
    -- its own so that the JSON never carries a password.
    smtp_password         BYTEA,
    otp_provider_password BYTEA
);

CREATE OR REPLACE FUNCTION notify_delivery_settings_change()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('delivery_settings_change', '');
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER delivery_settings_notify
AFTER INSERT OR UPDATE OR DELETE ON delivery_settings
FOR EACH STATEMENT EXECUTE FUNCTION notify_delivery_settings_change();
