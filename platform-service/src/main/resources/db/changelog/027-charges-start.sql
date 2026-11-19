--liquibase formatted sql

--changeset hospio:platform-027-charges-start
--comment: Free until charges start (docs/plan-monthly-invoices.md, "Charges start on"): one row per business with the day its platform fee starts. No row or no date = free (fee days worked out for visibility as FREE, never invoiced). Existing businesses get a date so nothing billed today changes; new businesses start free.
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS platform.billing_starts
(
    id                VARCHAR(36)  PRIMARY KEY,
    business_id       VARCHAR(36)  NOT NULL UNIQUE,
    business_name     VARCHAR(255),
    charges_start_on  DATE,
    set_by            VARCHAR(36),
    set_by_name       VARCHAR(150),
    reason            VARCHAR(500),
    reminded_for      DATE,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL
);
ALTER TABLE platform.billing_starts ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_starts FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_rw ON platform.billing_starts;
CREATE POLICY platform_rw ON platform.billing_starts USING (app.is_platform()) WITH CHECK (app.is_platform());

-- Every change of the date, kept for good (Changes by Hospio360 has the same change, but audit entries are deleted after 7 days).
CREATE TABLE IF NOT EXISTS platform.billing_start_changes
(
    id                VARCHAR(36)  PRIMARY KEY,
    business_id       VARCHAR(36)  NOT NULL,
    before_on         DATE,
    after_on          DATE,
    description       VARCHAR(300) NOT NULL,
    reason            VARCHAR(500) NOT NULL,
    made_by           VARCHAR(36),
    made_by_name      VARCHAR(150),
    days_redone       INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_billing_start_changes_business ON platform.billing_start_changes (business_id, created_at DESC);
ALTER TABLE platform.billing_start_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.billing_start_changes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_rw ON platform.billing_start_changes;
CREATE POLICY platform_rw ON platform.billing_start_changes USING (app.is_platform()) WITH CHECK (app.is_platform());

-- What a FREE day's platform fee would have been (shown, never billed); 0 on a billed day. fee + waived_fee = rate × sales.
ALTER TABLE platform.daily_fees ADD COLUMN IF NOT EXISTS waived_fee NUMERIC(14, 2) NOT NULL DEFAULT 0;

-- Existing businesses: the contract start date (never later than their first day with a fee), else their first worked-out day,
-- else (live, at risk or suspended with no day yet) the day they were created. Leads and drafts with none of these start free.
INSERT INTO platform.billing_starts (id, business_id, business_name, charges_start_on, set_by_name, reason, created_at, updated_at)
SELECT gen_random_uuid()::text, s.id, s.name, s.start_on, 'Hospio360 (migration)',
       'Existing business on 19 Nov 2026: ' || s.basis, now(), now()
FROM (
    SELECT b.id, b.name,
           CASE
               WHEN b.contract_starts_on IS NOT NULL AND ff.first_fee IS NOT NULL THEN LEAST(b.contract_starts_on, ff.first_fee)
               WHEN b.contract_starts_on IS NOT NULL THEN b.contract_starts_on
               WHEN fd.first_day IS NOT NULL THEN fd.first_day
               WHEN b.stage IN ('LIVE', 'AT_RISK', 'SUSPENDED') THEN (b.created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Africa/Lagos')::date
           END AS start_on,
           CASE
               WHEN b.contract_starts_on IS NOT NULL AND ff.first_fee IS NOT NULL AND ff.first_fee < b.contract_starts_on
                   THEN 'first day with a fee (before the contract start date)'
               WHEN b.contract_starts_on IS NOT NULL THEN 'contract start date'
               WHEN fd.first_day IS NOT NULL THEN 'first day worked out'
               ELSE 'created (live with no day yet)'
           END AS basis
    FROM tenant.businesses b
    LEFT JOIN (SELECT business_id, MIN(fee_date) AS first_day FROM platform.daily_fees GROUP BY business_id) fd ON fd.business_id = b.id
    LEFT JOIN (SELECT business_id, MIN(fee_date) AS first_fee FROM platform.daily_fees WHERE fee > 0 GROUP BY business_id) ff ON ff.business_id = b.id
) s
WHERE s.start_on IS NOT NULL
ON CONFLICT (business_id) DO NOTHING;
