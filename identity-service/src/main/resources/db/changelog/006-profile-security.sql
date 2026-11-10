--liquibase formatted sql

--changeset hospio:009-profile-security
-- My profile & security (docs/profile.md): photo, password age, 2-step with an authenticator app and email preferences.
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS photo_key VARCHAR(255);
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS photo_version BIGINT;
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP;
-- The secret is kept AES-GCM encrypted; the pending one waits for the first code before 2-step is on.
ALTER TABLE identity.user_accounts ALTER COLUMN two_step_secret TYPE VARCHAR(200);
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS two_step_pending_secret VARCHAR(200);
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS two_step_enabled_at TIMESTAMP;
-- The last 30-second step a code was accepted for: a code works once.
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS two_step_last_step BIGINT;
-- After the right password, the sign-in waits for a code: the hash of the one-time token that carries it to the code step.
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS two_step_challenge_hash VARCHAR(128);
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS two_step_challenge_expires_at TIMESTAMP;
-- Email categories the person turned off (comma separated); empty means every email.
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS email_off VARCHAR(200);

-- A session is one sign-in; its refresh tokens rotate but keep the session id (the access token's sid).
ALTER TABLE identity.refresh_tokens ADD COLUMN IF NOT EXISTS session_id VARCHAR(36);
ALTER TABLE identity.refresh_tokens ADD COLUMN IF NOT EXISTS user_agent VARCHAR(300);
ALTER TABLE identity.refresh_tokens ADD COLUMN IF NOT EXISTS location VARCHAR(100);
ALTER TABLE identity.refresh_tokens ADD COLUMN IF NOT EXISTS signed_in_at TIMESTAMP;
-- Set on every token of a session that was signed out (not on a token that was just rotated).
ALTER TABLE identity.refresh_tokens ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP;
UPDATE identity.refresh_tokens SET session_id = id WHERE session_id IS NULL;
UPDATE identity.refresh_tokens SET signed_in_at = created_at WHERE signed_in_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_session ON identity.refresh_tokens (session_id);

-- One-time recovery codes for 2-step, SHA-256 hashed. Only identity reads them, as the platform, like refresh tokens.
CREATE TABLE identity.recovery_codes
(
    id         VARCHAR(36)  PRIMARY KEY,
    user_id    VARCHAR(36)  NOT NULL REFERENCES identity.user_accounts (id),
    code_hash  VARCHAR(128) NOT NULL UNIQUE,
    used_at    TIMESTAMP,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);
CREATE INDEX idx_recovery_codes_user ON identity.recovery_codes (user_id);
ALTER TABLE identity.recovery_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.recovery_codes FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_only ON identity.recovery_codes USING (app.is_platform()) WITH CHECK (app.is_platform());
