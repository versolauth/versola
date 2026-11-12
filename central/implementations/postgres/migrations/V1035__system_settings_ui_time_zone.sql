-- The time zone the admin console shows times in for an administrator whose profile has no `zoneinfo` of their own: a name of the
-- IANA time zone database, checked when it is set. NULL means none: the console then reads times in the browser's zone, as it did
-- before this column. Additive; the settings row's trigger already announces a change to it.
ALTER TABLE system_settings ADD COLUMN ui_time_zone TEXT;
