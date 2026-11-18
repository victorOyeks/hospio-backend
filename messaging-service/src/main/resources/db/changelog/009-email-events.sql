--liquibase formatted sql

--changeset hospio:messaging-009-email-events
-- Brevo's webhook (docs/email.md, "Delivery tracking"): an email moves from SENT to DELIVERED, or FAILED on a hard or soft bounce,
-- a block or an invalid address; a spam complaint and the first open are noted without changing the status. provider_event is the
-- provider's last word ("hard_bounce", "delivered", "spam"…), provider_event_at when it came. Brevo's message id is recorded as
-- "<id@smtp-relay.mailin.fr> · Brevo API 201", so the event is found by its prefix (text_pattern_ops lets LIKE 'x%' use the index).
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS provider_event VARCHAR(30);
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS provider_event_at TIMESTAMP;
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS opened_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_deliveries_provider_message_prefix ON messaging.deliveries (provider_message_id text_pattern_ops);
-- The connection checks read the latest outcomes per channel.
CREATE INDEX IF NOT EXISTS idx_deliveries_channel_created ON messaging.deliveries (channel, created_at);
