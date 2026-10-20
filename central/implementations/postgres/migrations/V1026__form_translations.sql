-- The texts of a login form in one language, kept apart from the form: a form version is immutable, so a language
-- added to the form's own record needs a new version of every form, and a version that central re-seeds from its
-- resources loses it. These texts belong to the language, not to a version of the form: they are applied over the
-- form's own texts whenever the form is served, whichever version is active.
--
-- A translation belongs to a registered locale and goes with it: deleting the locale deletes its translations.
CREATE TABLE form_translations (
    form_id TEXT NOT NULL,
    locale  TEXT NOT NULL REFERENCES locales (code) ON DELETE CASCADE,
    texts   JSONB NOT NULL,
    PRIMARY KEY (form_id, locale)
);
