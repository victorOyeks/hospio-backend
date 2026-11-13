--liquibase formatted sql

--changeset hospio:messaging-004-delivery-reports
-- Infobip's delivery reports (docs/sms-whatsapp.md): an SMS or WhatsApp moves from SENT (the provider took it) to DELIVERED
-- (on the phone) or FAILED with the network's reason (e.g. "Rejected - Destination on DND list"). Found by provider_message_id.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_deliveries_provider_message ON messaging.deliveries (provider_message_id);
