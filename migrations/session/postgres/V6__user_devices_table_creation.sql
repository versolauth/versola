CREATE TABLE user_devices (
  user_id UUID NOT NULL,
  device_id UUID NOT NULL,
  auth_id UUID NOT NULL UNIQUE,
  refresh_token_blake3 TEXT UNIQUE,
  expire_at TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (user_id, device_id)
);

CREATE INDEX user_devices_refresh_token_idx ON user_devices (refresh_token_blake3);