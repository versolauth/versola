-- A theme can be built from tokens -- a built-in base theme plus the values it overrides -- instead of
-- being a whole stylesheet. Its css then holds only the theme's own additions (it may be empty), and
-- auth puts the current default stylesheet and the CSS generated from the tokens before it.
-- Existing themes have no tokens and keep working exactly as before, as full stylesheets.
ALTER TABLE themes ADD COLUMN tokens JSONB;
