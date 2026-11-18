--liquibase formatted sql

--changeset hospio:identity-010-phone-code-sent-at
-- When the last phone confirmation code was sent: no new code goes out within hospio.auth.phone-code-cooldown-seconds (60)
-- of it. Kept apart from phone_code_expires_at because that is cleared when the code is used or dropped after too many
-- wrong tries, and the code lifetime is configurable.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS phone_code_sent_at TIMESTAMP;
