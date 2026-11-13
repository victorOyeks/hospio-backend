--liquibase formatted sql

--changeset hospio:maintenance-003-scale-indexes
-- The 15-minute overdue sweep reads work orders past due that haven't been flagged.
CREATE INDEX IF NOT EXISTS idx_repairs_overdue_due ON maintenance.repairs (due_at) WHERE overdue_notified = false;
