CREATE TABLE passkeys (
    id BYTEA NOT NULL PRIMARY KEY,
    user_id UUID NOT NULL,
    webauthn_user_id BYTEA NOT NULL,
    public_key BYTEA NOT NULL,
    signature_counter BIGINT NOT NULL,
    device_type TEXT NOT NULL,
    backed_up BOOLEAN NOT NULL,
    backup_eligible BOOLEAN NOT NULL,
    transports TEXT[],
    attestation_object BYTEA,
    client_data_json BYTEA,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX passkeys_user_id_idx ON passkeys (user_id);
CREATE INDEX passkeys_webauthn_user_id_idx ON passkeys (webauthn_user_id);
CREATE UNIQUE INDEX passkeys_user_id_webauthn_user_id_idx ON passkeys (user_id, webauthn_user_id);
