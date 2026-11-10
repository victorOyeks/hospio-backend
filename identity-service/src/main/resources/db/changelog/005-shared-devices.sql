--liquibase formatted sql

--changeset hospio:008-shared-devices
-- Shared devices (docs/shared-devices.md): a tablet set up at one hotel for some roles. Only the hash of its device token is
-- kept. The token grants nothing by itself: it shows the lock screen and lets a person of that hotel unlock with their PIN.
-- Scoped by business and hotel like the rest; the lock screen and PIN unlock read devices as the system before anyone is signed in.
CREATE TABLE identity.shared_devices
(
    id                VARCHAR(36)  PRIMARY KEY,
    business_id       VARCHAR(36)  NOT NULL,
    hotel_id          VARCHAR(36)  NOT NULL,
    hotel_name        VARCHAR(150),
    name              VARCHAR(60)  NOT NULL,
    roles             VARCHAR(300) NOT NULL,
    token_hash        VARCHAR(128) NOT NULL UNIQUE,
    auto_lock_minutes INTEGER      NOT NULL DEFAULT 5,
    created_by        VARCHAR(36),
    created_by_name   VARCHAR(150),
    last_used_at      TIMESTAMP,
    last_user_id      VARCHAR(36),
    last_user_name    VARCHAR(150),
    revoked_at        TIMESTAMP,
    revoked_by_name   VARCHAR(150),
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL
);
CREATE INDEX idx_shared_devices_hotel ON identity.shared_devices (hotel_id);
CALL app.protect('identity', 'shared_devices', 'hotel_id');

-- A session opened with a PIN on a shared device: its refresh token names the device, so locking or removing the device ends it.
ALTER TABLE identity.refresh_tokens ADD COLUMN IF NOT EXISTS device_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_device ON identity.refresh_tokens (device_id) WHERE device_id IS NOT NULL;
