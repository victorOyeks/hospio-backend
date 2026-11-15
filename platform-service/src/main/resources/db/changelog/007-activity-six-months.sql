--liquibase formatted sql

--changeset hospio:platform-007-activity-six-months
-- The activity log is kept six months (183 days) by default. The seeded 365 moves to 183; a value an admin chose stays.
SELECT set_config('app.scope', 'platform', true);
UPDATE platform.platform_settings SET value = '183', updated_at = now()
 WHERE group_name = 'security' AND setting_key = 'auditRetentionDays' AND value = '365' AND updated_by = 'seed';
