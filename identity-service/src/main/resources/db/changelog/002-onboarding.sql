--liquibase formatted sql

--changeset hospio:006-phone-verification
-- Phone confirmation for self-serve owners: a 6-digit code (only its hash is kept) and whether it was confirmed.
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS phone_code_hash VARCHAR(128);
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS phone_code_expires_at TIMESTAMP;

--changeset hospio:007-invites
-- Invite links every created login gets (/accept-invite?token=). Only the token hash is stored.
-- Scoped by business like role_grants; platform admins' invites have no business. The public accept
-- endpoints read invites as the system, so the policy is written by hand to allow the null business.
CREATE TABLE identity.invites
(
    id          VARCHAR(36)  PRIMARY KEY,
    user_id     VARCHAR(36)  NOT NULL REFERENCES identity.user_accounts (id),
    business_id VARCHAR(36),
    token_hash  VARCHAR(128) NOT NULL UNIQUE,
    expires_at  TIMESTAMP    NOT NULL,
    accepted_at TIMESTAMP,
    created_by  VARCHAR(36),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE INDEX idx_invites_user ON identity.invites (user_id);
ALTER TABLE identity.invites ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.invites FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_rw ON identity.invites
    USING (business_id IS NULL OR app.can_see(business_id, NULL))
    WITH CHECK (business_id IS NULL OR app.can_see(business_id, NULL));
