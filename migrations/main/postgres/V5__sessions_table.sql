/* There will be 2 types of records in this table:
   1. Session record, which stores session_id, user_id, expires_at. Ex
   2. Refresh token record, which stores session_id, refresh_token, client_id, expires_at
*/
CREATE TABLE sso_sessions (
    id BYTEA NOT NULL PRIMARY KEY,
    refresh_token BYTEA,
    client_id TEXT,
    user_id UUID NOT NULL,
    expire_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX sessions_refresh_token_idx
    ON sso_sessions (refresh_token)
    WHERE refresh_token IS NOT NULL;

CREATE INDEX sessions_user_id_idx
    ON sso_sessions (user_id);