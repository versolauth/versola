-- RFC 9449 section 10 `dpop_jkt`: the key thumbprint an authorization request commits its code
-- to, checked against the proof presented at redemption. Carried on the conversation because
-- the code is issued long after `/authorize` accepted the parameter. Nullable: a request that
-- names no key leaves redemption unconstrained, as it was before this column existed.
ALTER TABLE auth_conversations
    ADD COLUMN dpop_jkt TEXT;

ALTER TABLE authorization_codes
    ADD COLUMN dpop_jkt TEXT;
