--liquibase formatted sql

--changeset hospio:004-staff-account-status
-- Owner portal · Staff: a pause ends by itself on paused_until (null = until someone resumes it); a suspension
-- or pause keeps its reason, who did it and when; the job title the owner or manager sets on the person.
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS paused_until TIMESTAMP;
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS status_reason VARCHAR(500);
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS status_changed_by VARCHAR(150);
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS status_changed_at TIMESTAMP;
ALTER TABLE identity.user_accounts ADD COLUMN IF NOT EXISTS job_title VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_user_accounts_paused_until ON identity.user_accounts (paused_until) WHERE paused_until IS NOT NULL;
