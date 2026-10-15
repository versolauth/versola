-- A contact becomes verified once an OTP sent to it has been confirmed. DEFAULT FALSE is kept on
-- purpose: these are NOT NULL columns added to a table that already has rows, and the previous
-- release's INSERTs do not name them while it keeps serving during `versola migrate`.
ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE;
