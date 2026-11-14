--liquibase formatted sql

--changeset hospio:finance-006-attendance-notices
-- Attendance notices (docs/workforce.md): the managers are told once when a rota shift has started with nobody clocked in,
-- and the person and the managers are reminded once about a clock-in that was never clocked out.
ALTER TABLE finance.shifts ADD COLUMN IF NOT EXISTS not_in_notified_at TIMESTAMP;
ALTER TABLE finance.clock_entries ADD COLUMN IF NOT EXISTS reminded_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_shifts_not_in_due ON finance.shifts (shift_date) WHERE not_in_notified_at IS NULL AND status <> 'CANCELLED';
CREATE INDEX IF NOT EXISTS idx_clock_entries_open ON finance.clock_entries (clock_in) WHERE clock_out IS NULL;
