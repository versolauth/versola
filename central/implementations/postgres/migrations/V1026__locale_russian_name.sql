-- A locale's name is written in that language: the login forms' language selector shows it as it is.
-- Russian was seeded as 'Russian'. Only that exact seeded value is corrected; a name an administrator
-- has set is left alone.
UPDATE locales SET name = 'Русский' WHERE code = 'ru' AND name = 'Russian';
