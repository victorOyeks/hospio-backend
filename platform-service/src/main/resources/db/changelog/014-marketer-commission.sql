--liquibase formatted sql

--changeset hospio:platform-014-marketer-commission
-- Marketer commission on collected daily fees (docs/plan-marketers.md): 20 % of the platform fee actually collected, 12 months per
-- business, 5 % WHT, monthly statements on the 5th, manual payout by bank transfer, minimum NGN 5,000 (smaller amounts roll over).
SELECT set_config('app.scope', 'platform', true);

-- The agreement the marketer accepts in the portal, and bank details that are re-checked by finance after every change.
ALTER TABLE platform.marketer_profiles ADD COLUMN IF NOT EXISTS agreement_version VARCHAR(30);
ALTER TABLE platform.marketer_profiles ADD COLUMN IF NOT EXISTS agreement_accepted_at TIMESTAMP;
ALTER TABLE platform.marketer_profiles ADD COLUMN IF NOT EXISTS agreement_accepted_by VARCHAR(36);
ALTER TABLE platform.marketer_profiles ADD COLUMN IF NOT EXISTS agreement_accepted_name VARCHAR(150);
ALTER TABLE platform.marketer_profiles ADD COLUMN IF NOT EXISTS bank_saved_at TIMESTAMP;
ALTER TABLE platform.marketer_profiles ADD COLUMN IF NOT EXISTS bank_change_note VARCHAR(500);
ALTER TABLE platform.marketer_profiles ADD COLUMN IF NOT EXISTS bank_checked_at TIMESTAMP;
ALTER TABLE platform.marketer_profiles ADD COLUMN IF NOT EXISTS bank_checked_by_name VARCHAR(150);

-- The agreement's 12-month window (from the business's first collected fee) and why it ended.
ALTER TABLE platform.marketers ADD COLUMN IF NOT EXISTS window_start DATE;
ALTER TABLE platform.marketers ADD COLUMN IF NOT EXISTS window_end DATE;
ALTER TABLE platform.marketers ADD COLUMN IF NOT EXISTS ended_reason VARCHAR(300);

-- A commission is now one line per collection (or reversal): source DAILY_FEE / CLAWBACK, the daily fee it came from and the debit
-- reference that collected it (unique per source, so a debit never earns twice).
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS source VARCHAR(20);
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS source_id VARCHAR(36);
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS source_ref VARCHAR(100);
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS fee_date DATE;
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS earned_on DATE;
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS share_pct NUMERIC(5, 2);
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS wht_pct NUMERIC(5, 2);
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS statement_id VARCHAR(36);
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS note VARCHAR(300);
CREATE UNIQUE INDEX IF NOT EXISTS uq_commissions_source_ref ON platform.commissions (source, source_ref) WHERE source_ref IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_commissions_open ON platform.commissions (profile_id, statement_id, month);
CREATE INDEX IF NOT EXISTS idx_commissions_source ON platform.commissions (source_id);

-- What Hospio360 gave back of a collected day (provider reversal or refund to the hotel); the commission on it is clawed back.
ALTER TABLE platform.daily_fees ADD COLUMN IF NOT EXISTS refunded NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE platform.daily_fees ADD COLUMN IF NOT EXISTS refund_note VARCHAR(300);

-- One statement per marketer and month: DRAFT (made on the 5th, the marketer has 7 days to query) → QUERIED → APPROVED (finance)
-- → PAID (bank transfer recorded); CARRIED_OVER when what is payable is under the minimum (it moves into the next statement).
CREATE TABLE IF NOT EXISTS platform.marketer_statements
(
    id                   VARCHAR(36)    PRIMARY KEY,
    profile_id           VARCHAR(36)    NOT NULL REFERENCES platform.marketer_profiles (id),
    month                VARCHAR(7)     NOT NULL,
    status               VARCHAR(20)    NOT NULL,
    line_count           INTEGER        NOT NULL DEFAULT 0,
    fees_collected       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    gross                NUMERIC(14, 2) NOT NULL DEFAULT 0,
    clawback             NUMERIC(14, 2) NOT NULL DEFAULT 0,
    wht                  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    net                  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    carried_in           NUMERIC(14, 2) NOT NULL DEFAULT 0,
    payable              NUMERIC(14, 2) NOT NULL DEFAULT 0,
    minimum_payout       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    carried_to_id        VARCHAR(36),
    query_until          TIMESTAMP,
    query_note           VARCHAR(1000),
    queried_at           TIMESTAMP,
    query_answer         VARCHAR(1000),
    approved_at          TIMESTAMP,
    approved_by          VARCHAR(36),
    approved_by_name     VARCHAR(150),
    paid_amount          NUMERIC(14, 2),
    paid_reference       VARCHAR(100),
    paid_on              DATE,
    paid_recorded_at     TIMESTAMP,
    paid_recorded_by     VARCHAR(36),
    paid_recorded_name   VARCHAR(150),
    paid_email_sent_at   TIMESTAMP,
    bank_name            VARCHAR(100),
    bank_account         VARCHAR(30),
    bank_account_name    VARCHAR(150),
    created_at           TIMESTAMP      NOT NULL,
    updated_at           TIMESTAMP      NOT NULL,
    UNIQUE (profile_id, month)
);
CREATE INDEX IF NOT EXISTS idx_marketer_statements_month ON platform.marketer_statements (month DESC, status);
ALTER TABLE platform.marketer_statements ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.marketer_statements FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_rw ON platform.marketer_statements USING (app.is_platform()) WITH CHECK (app.is_platform());

-- The decided terms: 12 months (was 24). Only the old default changes; a share or term set by hand stays.
ALTER TABLE platform.marketer_profiles ALTER COLUMN months SET DEFAULT 12;
ALTER TABLE platform.marketers ALTER COLUMN months SET DEFAULT 12;
UPDATE platform.marketer_profiles SET months = 12 WHERE months = 24;
UPDATE platform.marketers SET months = 12 WHERE months = 24 AND status IN ('PENDING', 'ACTIVE');
UPDATE platform.platform_settings SET value = '12' WHERE group_name = 'fees' AND setting_key = 'marketerMonths' AND value = '24';
