--liquibase formatted sql

--changeset hospio:identity-008-scale-indexes
-- Sign-in looks a person up by email ignoring case (upper(email) = upper(?)); a user's live refresh tokens; role grants
-- that expire (hourly job) and a business's grants.
CREATE INDEX IF NOT EXISTS idx_user_accounts_email_upper ON identity.user_accounts (upper(email));
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_live ON identity.refresh_tokens (user_id, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_role_grants_expires ON identity.role_grants (expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_role_grants_business ON identity.role_grants (business_id);
