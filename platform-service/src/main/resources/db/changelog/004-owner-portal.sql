--liquibase formatted sql

--changeset hospio:platform-004-owner-portal
-- Owner portal (docs/owner-portal.md). A ticket's category says what it is for (OWNERSHIP_TRANSFER, CHANGE_UNDO; null for
-- ordinary tickets). An owner asks H.M.S to undo one of its changes: the request and its ticket are kept on the change.
-- Reports: a schedule (status SCHEDULED) runs at send_at Lagos time and leaves a READY row per run (schedule_id).
ALTER TABLE platform.support_tickets ADD COLUMN IF NOT EXISTS category VARCHAR(30);
CREATE INDEX IF NOT EXISTS idx_support_tickets_category ON platform.support_tickets (business_id, category, status);
ALTER TABLE platform.platform_changes ADD COLUMN IF NOT EXISTS undo_requested_at TIMESTAMP;
ALTER TABLE platform.platform_changes ADD COLUMN IF NOT EXISTS undo_requested_by_name VARCHAR(150);
ALTER TABLE platform.platform_changes ADD COLUMN IF NOT EXISTS undo_ticket_id VARCHAR(36);
ALTER TABLE platform.reports ADD COLUMN IF NOT EXISTS name VARCHAR(100);
ALTER TABLE platform.reports ADD COLUMN IF NOT EXISTS send_at VARCHAR(5);
ALTER TABLE platform.reports ADD COLUMN IF NOT EXISTS next_run_at TIMESTAMP;
ALTER TABLE platform.reports ADD COLUMN IF NOT EXISTS last_run_at TIMESTAMP;
ALTER TABLE platform.reports ADD COLUMN IF NOT EXISTS requested_by_name VARCHAR(150);
ALTER TABLE platform.reports ADD COLUMN IF NOT EXISTS schedule_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_reports_due ON platform.reports (status, next_run_at);
