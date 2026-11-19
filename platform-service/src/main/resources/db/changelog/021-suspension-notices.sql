--liquibase formatted sql

--changeset hospio:platform-021-suspension-notices
--comment: Real suspension (docs/suspension.md): the 7 days' notice sent to an owner before a business is suspended for unpaid invoices (Terms 7.3). One row per notice: the day sent, the day the suspension may start, the invoices named. Platform-only table (admins read it).
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS platform.suspension_notices
(
    id              VARCHAR(36)   PRIMARY KEY,
    business_id     VARCHAR(36)   NOT NULL,
    business_name   VARCHAR(255),
    sent_on         DATE          NOT NULL,
    suspend_on      DATE          NOT NULL,
    invoice_numbers VARCHAR(1000) NOT NULL,
    total           NUMERIC(14, 2) NOT NULL DEFAULT 0,
    recipient       VARCHAR(255),
    outcome         VARCHAR(30)   NOT NULL,
    sent_by         VARCHAR(36),
    sent_by_name    VARCHAR(150),
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_suspension_notices_business ON platform.suspension_notices (business_id, created_at DESC);
ALTER TABLE platform.suspension_notices ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.suspension_notices FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_rw ON platform.suspension_notices;
CREATE POLICY platform_rw ON platform.suspension_notices USING (app.is_platform()) WITH CHECK (app.is_platform());
