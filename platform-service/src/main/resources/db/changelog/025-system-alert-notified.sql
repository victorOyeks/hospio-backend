--liquibase formatted sql

--changeset hospio:platform-025-system-alert-notified
--comment: System health → Alerts on/off (docs/system-health.md): a condition raised while alerts are off is kept but nobody is told; notified says whether the Super admins were told, so switching alerts back on tells only what is still there and a "recovered" note goes only for a problem someone heard about.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE platform.system_alert_states ADD COLUMN IF NOT EXISTS notified BOOLEAN NOT NULL DEFAULT TRUE;
