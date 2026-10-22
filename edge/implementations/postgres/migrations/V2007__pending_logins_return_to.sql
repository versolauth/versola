-- Where a login sends the browser when it completes, a path of the preset's origin: the page that asked
-- for the sign-in, so a step-up returns the user to what they were doing. NULL: the preset's own post-login page.
ALTER TABLE pending_logins ADD COLUMN return_to TEXT;
