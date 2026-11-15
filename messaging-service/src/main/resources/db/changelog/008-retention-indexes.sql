--liquibase formatted sql

--changeset hospio:messaging-008-retention-indexes
-- The nightly retention (docs/retention.md) finds notifications by age and chats by their last message; these keep both from
-- reading the whole table.
SELECT set_config('app.scope', 'platform', true);
CREATE INDEX IF NOT EXISTS idx_notifications_created ON messaging.notifications (created_at);
CREATE INDEX IF NOT EXISTS idx_conversations_last_activity ON messaging.conversations ((COALESCE(last_message_at, created_at)));
