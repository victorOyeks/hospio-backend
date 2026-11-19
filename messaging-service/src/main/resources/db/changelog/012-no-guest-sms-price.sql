--liquibase formatted sql

--changeset hospio:messaging-012-no-guest-sms-price
--comment: Guest messages never go by SMS (SMS is only for sign-in and verification codes, which are free), so there is no SMS price any more (docs/guest-message-billing.md). The platform default and the per-hotel SMS prices go; an invoice's sms_count and sms_amount stay, so an older invoice that charged SMS still shows it.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE messaging.comm_prices DROP COLUMN IF EXISTS sms_price;
ALTER TABLE messaging.comm_plans DROP COLUMN IF EXISTS sms_price;
