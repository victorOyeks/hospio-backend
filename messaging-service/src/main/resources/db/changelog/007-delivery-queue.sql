--liquibase formatted sql

--changeset hospio:messaging-007-delivery-queue
-- The deliveries table is the send queue (docs/email.md, "Sending"; scale audit 2026-11-13). A QUEUED row carries what to
-- send until it is finished (body_text, body_html, wa_template, wa_params are cleared once it is SENT or FAILED: temporary
-- passwords travel in them) and next_attempt_at: when a worker may claim it (now, after a back-off, or when a claim's lease
-- runs out). Rows QUEUED before this change waited in a process's memory, which this deploy's restart has emptied: they
-- are marked FAILED, as the old sweep would have done.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP;
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS body_text TEXT;
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS body_html TEXT;
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS wa_template VARCHAR(100);
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS wa_params TEXT;
UPDATE messaging.deliveries SET status = 'FAILED', error = 'Interrupted before it was sent (service restarted)'
WHERE status = 'QUEUED' AND body_text IS NULL;
CREATE INDEX IF NOT EXISTS idx_deliveries_queue ON messaging.deliveries (next_attempt_at) WHERE status = 'QUEUED';

--changeset hospio:messaging-007-notification-once
-- One notice per (event, user, role), enforced: two instances handling one event at once insert it once (ON CONFLICT DO
-- NOTHING). Duplicates made before (the race this closes) are removed first, keeping the oldest of each, so the index can be
-- built on any data. It replaces the plain lookup index of 006.
SELECT set_config('app.scope', 'platform', true);
DELETE FROM messaging.notifications a
USING messaging.notifications b
WHERE a.event_id IS NOT NULL
  AND a.event_id = b.event_id
  AND COALESCE(a.user_id, '') = COALESCE(b.user_id, '')
  AND COALESCE(a.role, '') = COALESCE(b.role, '')
  AND (a.created_at > b.created_at OR (a.created_at = b.created_at AND a.id > b.id));
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_event
    ON messaging.notifications (event_id, (COALESCE(user_id, '')), (COALESCE(role, ''))) WHERE event_id IS NOT NULL;
DROP INDEX IF EXISTS messaging.idx_notifications_event;
