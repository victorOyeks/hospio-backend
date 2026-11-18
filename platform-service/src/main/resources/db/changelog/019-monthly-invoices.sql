--liquibase formatted sql

--changeset hospio:platform-019-monthly-invoices
-- One monthly invoice per business (docs/plan-monthly-invoices.md), replacing the daily debit and the separate device and guest-message
-- invoices: issued on the 1st at 07:00 Lagos for the month before (HIN-YYYYMM-0001), due 7 days later, 3 days' grace, then a soft
-- restriction (POS push, new sales and new bookings pause) until it is paid through Hospio360's Paystack link or recorded by an admin.
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS platform.monthly_invoices
(
    id                    VARCHAR(36)    PRIMARY KEY,
    business_id           VARCHAR(36)    NOT NULL,
    hotel_id              VARCHAR(36),
    business_name         VARCHAR(200),
    number                VARCHAR(30)    NOT NULL UNIQUE,
    month                 VARCHAR(7)     NOT NULL,
    issued_on             DATE           NOT NULL,
    issued_at             TIMESTAMP      NOT NULL,
    due_on                DATE           NOT NULL,
    grace_until           DATE           NOT NULL,
    platform_total        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    messages_total        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    devices_total         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    carried_in_total      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    adjustments_total     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    vat_pct               NUMERIC(6, 3)  NOT NULL DEFAULT 0,
    vat_amount            NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total                 NUMERIC(14, 2) NOT NULL DEFAULT 0,
    paid_amount           NUMERIC(14, 2) NOT NULL DEFAULT 0,
    status                VARCHAR(20)    NOT NULL,
    carried_into_id       VARCHAR(36),
    paid_at               TIMESTAMP,
    paid_via              VARCHAR(30),
    paystack_ref          VARCHAR(80),
    extended_until        DATE,
    extension_reason      VARCHAR(300),
    extended_by_name      VARCHAR(150),
    restricted_at         TIMESTAMP,
    restriction_lifted_at TIMESTAMP,
    void_reason           VARCHAR(300),
    reissue_of_id         VARCHAR(36),
    replaced_by_id        VARCHAR(36),
    created_at            TIMESTAMP      NOT NULL,
    updated_at            TIMESTAMP      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_monthly_invoices_live ON platform.monthly_invoices (business_id, month) WHERE status <> 'VOID';
CREATE INDEX IF NOT EXISTS idx_monthly_invoices_status ON platform.monthly_invoices (status, due_on);
CREATE INDEX IF NOT EXISTS idx_monthly_invoices_business ON platform.monthly_invoices (business_id, month DESC);
CALL app.protect('platform', 'monthly_invoices', NULL);

CREATE TABLE IF NOT EXISTS platform.monthly_invoice_lines
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36),
    invoice_id  VARCHAR(36)    NOT NULL REFERENCES platform.monthly_invoices (id) ON DELETE CASCADE,
    section     VARCHAR(20)    NOT NULL,
    hotel_name  VARCHAR(150),
    label       VARCHAR(300)   NOT NULL,
    detail      VARCHAR(500),
    base_amount NUMERIC(14, 2),
    rate        NUMERIC(6, 3),
    amount      NUMERIC(14, 2) NOT NULL,
    source_type VARCHAR(30),
    source_id   VARCHAR(36),
    sort_order  INT            NOT NULL DEFAULT 0,
    created_by_name VARCHAR(150),
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_monthly_invoice_lines_invoice ON platform.monthly_invoice_lines (invoice_id, sort_order);
CALL app.protect('platform', 'monthly_invoice_lines', NULL);

CREATE TABLE IF NOT EXISTS platform.monthly_invoice_payments
(
    id               VARCHAR(36)    PRIMARY KEY,
    business_id      VARCHAR(36)    NOT NULL,
    hotel_id         VARCHAR(36),
    invoice_id       VARCHAR(36)    NOT NULL REFERENCES platform.monthly_invoices (id) ON DELETE CASCADE,
    receipt_number   VARCHAR(30)    NOT NULL UNIQUE,
    amount           NUMERIC(14, 2) NOT NULL,
    via              VARCHAR(30)    NOT NULL,
    reference        VARCHAR(100)   NOT NULL,
    paid_on          DATE           NOT NULL,
    recorded_by_name VARCHAR(150),
    note             VARCHAR(300),
    created_at       TIMESTAMP      NOT NULL,
    updated_at       TIMESTAMP      NOT NULL,
    CONSTRAINT uq_monthly_invoice_payment_ref UNIQUE (via, reference)
);
CREATE INDEX IF NOT EXISTS idx_monthly_invoice_payments_invoice ON platform.monthly_invoice_payments (invoice_id, created_at);
CALL app.protect('platform', 'monthly_invoice_payments', NULL);

-- Every notice and admin action on an invoice: the reminder log (one row per kind and Lagos day, so a rerun never repeats a reminder)
-- and the audit trail of extensions, credits, payments recorded, voids.
CREATE TABLE IF NOT EXISTS platform.monthly_invoice_events
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36),
    invoice_id  VARCHAR(36)  NOT NULL REFERENCES platform.monthly_invoices (id) ON DELETE CASCADE,
    kind        VARCHAR(30)  NOT NULL,
    on_date     DATE         NOT NULL,
    recipient   VARCHAR(200),
    outcome     VARCHAR(40),
    note        VARCHAR(500),
    actor_name  VARCHAR(150),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_monthly_invoice_notice ON platform.monthly_invoice_events (invoice_id, kind, on_date)
    WHERE kind IN ('ISSUED', 'REMINDER', 'DUE_TODAY', 'OVERDUE', 'FINAL_WARNING', 'RESTRICTED');
CREATE INDEX IF NOT EXISTS idx_monthly_invoice_events_invoice ON platform.monthly_invoice_events (invoice_id, created_at);
CALL app.protect('platform', 'monthly_invoice_events', NULL);

-- What each daily fee and device schedule item was billed on.
ALTER TABLE platform.daily_fees ADD COLUMN IF NOT EXISTS monthly_invoice_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_daily_fees_invoice ON platform.daily_fees (monthly_invoice_id);
ALTER TABLE platform.device_invoices ADD COLUMN IF NOT EXISTS monthly_invoice_id VARCHAR(36);
ALTER TABLE platform.device_invoices ADD COLUMN IF NOT EXISTS monthly_invoice_number VARCHAR(30);

-- No more automatic debits: switch every hotel's debit off (fee_collections keeps the POS pauses, now set by the monthly invoice).
UPDATE platform.fee_collections SET debit_enabled = FALSE WHERE debit_enabled = TRUE;
UPDATE platform.daily_fees SET status = 'ACCRUED' WHERE status IN ('SHADOW', 'SCHEDULED');
