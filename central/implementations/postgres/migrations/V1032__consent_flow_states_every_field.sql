-- The consent flow of a client no longer has implicit values for requiredScopes and offlineAccessOptional: a stored flow
-- must state them. Rows written before those fields existed lack them; the left side only supplies what is missing.
UPDATE oauth_clients
SET consent_flow = jsonb_build_object('requiredScopes', '[]'::jsonb, 'offlineAccessOptional', false) || consent_flow
WHERE consent_flow IS NOT NULL
  AND NOT (consent_flow ? 'requiredScopes' AND consent_flow ? 'offlineAccessOptional');
