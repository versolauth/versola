-- The directory of a partition (versolauth/versola#547, #1041): which user an identifier belongs to and which cell
-- holds that user. It replaces `user_index`, which kept every phone, email and login in the clear and said nothing
-- about cells. Each cell has its own database, so uniqueness of an identifier across cells is decided here.

-- One row per user. `cell_id` is null on an installation that registered no cell: the user is in the one `auth` the
-- deployment has. A cell that holds users cannot be deleted from under them, which is what the reference says.
CREATE TABLE user_directory (
    user_id UUID NOT NULL PRIMARY KEY,
    cell_id TEXT REFERENCES cells (id)
);

-- One row per identifier of a user. `mac` is the keyed BLAKE3 of the identifier as `auth` stores it (a phone is
-- canonical E.164), so a copy of this table is not a list of the phone numbers of a country. The primary key is the
-- uniqueness of an identifier; a claim that loses the race reads the row that won.
CREATE TABLE identifier_directory (
    kind    TEXT  NOT NULL, -- phone | email | login
    mac     BYTEA NOT NULL,
    user_id UUID  NOT NULL REFERENCES user_directory (user_id) ON DELETE CASCADE,
    PRIMARY KEY (kind, mac)
);

-- A user has at most one identifier of a kind, and all of a user's identifiers are read and replaced together.
CREATE UNIQUE INDEX identifier_directory_user_kind_idx ON identifier_directory (user_id, kind);

-- Nothing reads `user_index` any more. Its rows cannot be carried over in SQL (the MAC is keyed), and there is no
-- deployment to carry them for: an installation is seeded again.
DROP TABLE user_index;
