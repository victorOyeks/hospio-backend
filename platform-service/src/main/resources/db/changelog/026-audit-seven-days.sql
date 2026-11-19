--liquibase formatted sql

--changeset hospio:platform-026-audit-seven-days
--comment: Audit logs are kept 7 days (owner decision, 19 Nov 2026; docs/data-retention.md). The activity log and Changes by Hospio360 are deleted nightly once older than the setting (7 by default, never less). Before an activity entry goes, its count is added to activity_daily_counts (business, Lagos day, event type, how many: no person, no text) and its person to activity_people_seen (business, person, last seen), so the admin Usage tab (8 weeks), health scores and alerts keep working. Neither is an audit trail.
SELECT set_config('app.scope', 'platform', true);

CREATE TABLE IF NOT EXISTS platform.activity_daily_counts
(
    business_id VARCHAR(36) NOT NULL,
    day         DATE        NOT NULL,
    event_type  VARCHAR(80) NOT NULL,
    events      BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (business_id, event_type, day)
);
CALL app.protect('platform', 'activity_daily_counts', NULL);

CREATE TABLE IF NOT EXISTS platform.activity_people_seen
(
    business_id  VARCHAR(36) NOT NULL,
    actor_id     VARCHAR(36) NOT NULL,
    last_seen_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (business_id, actor_id)
);
CREATE INDEX IF NOT EXISTS idx_activity_people_seen_time ON platform.activity_people_seen (business_id, last_seen_at DESC);
CALL app.protect('platform', 'activity_people_seen', NULL);

-- The nightly delete finds Changes by Hospio360 by age.
CREATE INDEX IF NOT EXISTS idx_platform_changes_created ON platform.platform_changes (created_at);

-- 7 days, whatever was set before (the owner's decision replaces the six months).
UPDATE platform.platform_settings SET value = '7', updated_at = now(), updated_by = 'seed'
 WHERE group_name = 'security' AND setting_key = 'auditRetentionDays';
