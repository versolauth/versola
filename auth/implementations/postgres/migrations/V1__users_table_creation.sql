CREATE TABLE users (
    id UUID NOT NULL PRIMARY KEY,
    email TEXT,
    phone TEXT,
    login TEXT,
    claims JSONB NOT NULL
);

CREATE UNIQUE INDEX users_email_idx
    ON users (email)
    WHERE email IS NOT NULL;

CREATE UNIQUE INDEX users_phone_idx
    ON users (phone)
    WHERE phone IS NOT NULL;

CREATE UNIQUE INDEX users_login_idx
    ON users (login)
    WHERE login IS NOT NULL;