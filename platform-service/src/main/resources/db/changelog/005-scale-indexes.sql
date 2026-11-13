--liquibase formatted sql

--changeset hospio:platform-005-scale-indexes
-- Health scores, alerts and the usage tab count a business's activity by event type over time.
CREATE INDEX IF NOT EXISTS idx_activity_business_event_time ON platform.activity_log (business_id, event_type, occurred_at DESC);
