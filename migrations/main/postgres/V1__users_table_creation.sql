CREATE TABLE users (
    id UUID NOT NULL PRIMARY KEY,
    email TEXT UNIQUE,
    first_name TEXT,
    middle_name TEXT,
    last_name TEXT,
    birth_date DATE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);