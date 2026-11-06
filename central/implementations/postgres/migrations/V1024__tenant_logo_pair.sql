-- The identity provider's logo moves from the global system settings to the tenant, and becomes a pair: one file
-- for the light colour scheme and one for the dark one, both or neither. A tenant starts with the logo the
-- deployment had, in both schemes, so nothing disappears from a login page (an administrator can replace it
-- with a proper pair).
--
-- system_settings.identity_provider_logo is deliberately not dropped: the previous release still reads and
-- writes it while it serves traffic during the upgrade, and a missing column would break it. It is unused from
-- this release on and a later migration removes it.
ALTER TABLE challenge_settings ADD COLUMN logo_light TEXT;
ALTER TABLE challenge_settings ADD COLUMN logo_dark TEXT;

UPDATE challenge_settings
SET logo_light = s.identity_provider_logo,
    logo_dark = s.identity_provider_logo
FROM system_settings s
WHERE s.identity_provider_logo IS NOT NULL AND s.identity_provider_logo <> '';
