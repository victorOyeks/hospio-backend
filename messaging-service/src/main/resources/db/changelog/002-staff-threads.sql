--liquibase formatted sql

--changeset hospio:messaging-002-staff-threads
-- Owner portal · Messaging (docs/owner-portal.md): a staff thread is between the person who started it and one person
-- (audience PERSON, assigned_to) or a hotel's managers (audience MANAGERS). "Message all managers" starts one thread per
-- manager, tied by broadcast_id. Each side's last read is kept so unread counts are per side.
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS created_by VARCHAR(36);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS created_by_name VARCHAR(150);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS recipient_name VARCHAR(150);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS audience VARCHAR(20);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS broadcast_id VARCHAR(36);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS last_preview VARCHAR(300);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS creator_read_at TIMESTAMP;
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS recipient_read_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_conversations_threads ON messaging.conversations (business_id, kind, audience, last_message_at);
