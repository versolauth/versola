CREATE TABLE email_verifications (
    email TEXT NOT NULL PRIMARY KEY,
    auth_id UUID NOT NULL,
    device_id UUID,
    code TEXT NOT NULL,
    times_sent smallint
);

CREATE INDEX email_verifications_token_idx ON email_verifications (auth_id);
