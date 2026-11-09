--liquibase formatted sql

--changeset hospio:007-operating-day-tills
-- Till movements (pay in, pay out, refunds, cash expenses) are entries too; expected cash = float + money in - money out.
ALTER TABLE finance.till_entries ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'PAYMENT';
ALTER TABLE finance.till_entries ADD COLUMN IF NOT EXISTS ref VARCHAR(80);
ALTER TABLE finance.till_entries ADD COLUMN IF NOT EXISTS created_by VARCHAR(36);
ALTER TABLE finance.till_entries ADD COLUMN IF NOT EXISTS created_by_name VARCHAR(150);
CREATE INDEX IF NOT EXISTS idx_till_entries_kind_ref ON finance.till_entries (kind, ref);
CREATE INDEX IF NOT EXISTS idx_till_entries_hotel_created ON finance.till_entries (hotel_id, created_at);
ALTER TABLE finance.tills ADD COLUMN IF NOT EXISTS cash_out NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE finance.tills ADD COLUMN IF NOT EXISTS closed_by_name VARCHAR(150);
ALTER TABLE finance.tills ADD COLUMN IF NOT EXISTS handed_over_to VARCHAR(36);
ALTER TABLE finance.tills ADD COLUMN IF NOT EXISTS handed_over_to_name VARCHAR(150);
ALTER TABLE finance.revenue_entries ADD COLUMN IF NOT EXISTS unattached_cash BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE finance.bank_deposits ADD COLUMN IF NOT EXISTS banked_by_name VARCHAR(150);

--changeset hospio:008-operating-day-expenses-and-audit
ALTER TABLE finance.expenses ADD COLUMN IF NOT EXISTS approver_level VARCHAR(20);
ALTER TABLE finance.expenses ADD COLUMN IF NOT EXISTS paid_by VARCHAR(36);
ALTER TABLE finance.expenses ADD COLUMN IF NOT EXISTS paid_by_name VARCHAR(150);
ALTER TABLE finance.expenses ADD COLUMN IF NOT EXISTS paid_at TIMESTAMP;
ALTER TABLE finance.night_audits ADD COLUMN IF NOT EXISTS report TEXT;
CREATE INDEX IF NOT EXISTS idx_clock_entries_hotel_in ON finance.clock_entries (hotel_id, clock_in);
CREATE INDEX IF NOT EXISTS idx_shifts_user_date ON finance.shifts (user_id, shift_date);
