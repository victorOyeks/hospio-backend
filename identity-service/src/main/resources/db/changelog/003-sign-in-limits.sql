--liquibase formatted sql

--changeset hospio:007-sign-in-limits
-- Wrong passwords lock sign-in for a while; wrong phone codes are counted so the code dies after a few tries.
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS failed_sign_in_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS sign_in_locked_until TIMESTAMP;
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS phone_code_attempts INT NOT NULL DEFAULT 0;
-- Lookups by the last ten digits, so +234 803 555 0192 and 08035550192 are the same number.
CREATE INDEX IF NOT EXISTS idx_user_accounts_phone_digits ON identity.user_accounts (right(regexp_replace(phone, '[^0-9]', '', 'g'), 10));
