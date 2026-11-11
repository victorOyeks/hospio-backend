--liquibase formatted sql

--changeset hospio:010-six-digit-pins
-- PINs are now exactly 6 digits (docs/shared-devices.md). Stored PINs are hashed, so a shorter one cannot be told apart:
-- every PIN is cleared and each person sets a 6-digit one after their next email sign-in (the lock screen shows
-- "No PIN yet"). The table is under forced row-level security, so this runs as the platform.
SELECT set_config('app.scope', 'platform', true);
UPDATE identity.user_accounts SET pin_hash = NULL, failed_pin_attempts = 0 WHERE pin_hash IS NOT NULL;
