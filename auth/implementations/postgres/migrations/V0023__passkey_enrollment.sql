-- What auth remembers of the passkey offers made to a user, for the tenant's grace period (policy
-- required_after_grace): when the first offer was made (the grace in days runs from it) and how many were skipped
-- (the grace in skips counts them). A row exists only for users of a tenant whose policy has a grace.
--
-- No foreign key to users: the users table may be sharded by user_id, and the user deletion removes the row explicitly, like the user's passkeys.
CREATE TABLE passkey_enrollment (
    user_id          UUID NOT NULL PRIMARY KEY,
    first_offered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    skips            INT NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
