-- OAuth Scopes table
CREATE TABLE oauth_scopes (
    name TEXT PRIMARY KEY,
    description TEXT NOT NULL,
    claims TEXT[] NOT NULL
);

-- Insert preset OAuth and OpenID scopes
INSERT INTO oauth_scopes (name, description, claims) VALUES
    ('openid', 'OpenID Connect authentication', ARRAY['sub']),
    ('profile', 'Access to basic profile information', ARRAY['name', 'family_name', 'given_name', 'middle_name', 'nickname', 'preferred_username', 'profile', 'picture', 'website', 'gender', 'birthdate', 'zoneinfo', 'locale', 'updated_at']),
    ('email', 'Access to email address', ARRAY['email', 'email_verified']),
    ('address', 'Access to postal address', ARRAY['address']),
    ('phone', 'Access to phone number', ARRAY['phone_number', 'phone_number_verified']),
    ('offline_access', 'Access to refresh tokens for offline access', ARRAY[]::TEXT[]),
    ('read', 'Read access to user data', ARRAY['sub', 'name', 'email']),
    ('write', 'Write access to user data', ARRAY['sub']),
    ('admin', 'Administrative access', ARRAY['sub', 'name', 'email', 'role', 'permissions']);
