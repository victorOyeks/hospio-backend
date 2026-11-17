--liquibase formatted sql

--changeset hospio:platform-013-data-plan-controls
-- The data plan after installation (docs/plan-device-packages.md): billed from data_plan_from (the install day by default), stopped
-- from data_plan_stopped_on (no invoices due on or after it), its price changeable for invoices issued later; and the real provider
-- subscription Hospio360 renews for the hotel's router (renewed through a date, by whom).
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS data_plan_from DATE;
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS data_plan_stopped_on DATE;
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS data_plan_changed_by_name VARCHAR(150);
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS data_plan_changed_at TIMESTAMP;
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS data_plan_renewed_through DATE;
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS data_plan_renewed_by_name VARCHAR(150);
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS data_plan_renewed_at TIMESTAMP;
