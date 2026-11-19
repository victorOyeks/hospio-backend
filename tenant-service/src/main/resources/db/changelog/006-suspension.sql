--liquibase formatted sql

--changeset hospio:tenant-006-suspension
--comment: Real suspension (docs/suspension.md): why a business is suspended (UNPAID_INVOICE or OTHER), whether the note is shown to the business, the day the 7 days' notice was sent, who suspended it and the stage to put back on reactivation.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS suspension_type VARCHAR(20);
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS suspension_show_reason BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS suspension_notice_sent_on DATE;
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS stage_before_suspension VARCHAR(20);
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS suspended_by VARCHAR(36);
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS suspended_by_name VARCHAR(150);
-- Businesses suspended before this (a record only, by hand): treated as OTHER, so only an admin reactivates them.
UPDATE tenant.businesses SET suspension_type = 'OTHER', stage_before_suspension = 'LIVE' WHERE stage = 'SUSPENDED' AND suspension_type IS NULL;
