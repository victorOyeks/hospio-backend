--liquibase formatted sql

--changeset hospio:platform-006-activity-retention
-- The nightly retention delete finds entries by age, and the dashboard's recent Hospio360 changes read the newest few;
-- both walk this index instead of the whole log. (event_id is already UNIQUE, which the insert-on-conflict relies on.)
CREATE INDEX IF NOT EXISTS idx_activity_occurred ON platform.activity_log (occurred_at DESC);
