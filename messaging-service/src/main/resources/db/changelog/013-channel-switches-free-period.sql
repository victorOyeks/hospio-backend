--liquibase formatted sql

--changeset hospio:messaging-013-channel-switches-free-period
--comment: Guest messages per channel (docs/guest-message-billing.md): a hotel switches WhatsApp and email on or off separately; the old single switch becomes both (on → both on, off → both off) and stays as "either is on". Free period (docs/plan-monthly-invoices.md, "Charges start on"): a message sent before the business's charges start is recorded at NGN 0 and marked free_period; it is never invoiced.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE messaging.comm_plans ADD COLUMN IF NOT EXISTS email_on BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE messaging.comm_plans ADD COLUMN IF NOT EXISTS whatsapp_on BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE messaging.comm_plans SET email_on = enabled, whatsapp_on = enabled;
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS free_period BOOLEAN NOT NULL DEFAULT FALSE;
