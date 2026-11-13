--liquibase formatted sql

--changeset hospio:messaging-005-guest-message-billing
-- Guest messages as a paid service (docs/guest-message-billing.md). A hotel opts in (comm_plans); every email, SMS or
-- WhatsApp it sends outside (guests, companies, suppliers) is priced when it goes out (deliveries.billable, sent_via,
-- unit_price) and billed on a monthly invoice (comm_invoices) due ten days after issue. Codes, sign-in, invites and
-- resets are never billable. Prices: comm_prices' one row is the platform default; a hotel's own prices override it.
SELECT set_config('app.scope', 'platform', true);

CREATE TABLE IF NOT EXISTS messaging.comm_prices
(
    id             VARCHAR(20)   PRIMARY KEY,
    email_price    NUMERIC(12,2) NOT NULL,
    sms_price      NUMERIC(12,2) NOT NULL,
    whatsapp_price NUMERIC(12,2) NOT NULL,
    updated_by     VARCHAR(150),
    updated_at     TIMESTAMP     NOT NULL DEFAULT now()
);
INSERT INTO messaging.comm_prices (id, email_price, sms_price, whatsapp_price) VALUES ('DEFAULT', 5, 10, 25)
    ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS messaging.comm_plans
(
    id               VARCHAR(36)   PRIMARY KEY,
    business_id      VARCHAR(36)   NOT NULL,
    hotel_id         VARCHAR(36)   NOT NULL,
    enabled          BOOLEAN       NOT NULL DEFAULT false,
    email_price      NUMERIC(12,2),
    sms_price        NUMERIC(12,2),
    whatsapp_price   NUMERIC(12,2),
    changed_by       VARCHAR(150),
    changed_at       TIMESTAMP,
    suspended_at     TIMESTAMP,
    suspended_reason VARCHAR(300),
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_comm_plans_hotel ON messaging.comm_plans (hotel_id);
CALL app.protect('messaging', 'comm_plans');

CREATE TABLE IF NOT EXISTS messaging.comm_invoices
(
    id                VARCHAR(36)   PRIMARY KEY,
    business_id       VARCHAR(36)   NOT NULL,
    hotel_id          VARCHAR(36)   NOT NULL,
    number            VARCHAR(30)   NOT NULL,
    month             VARCHAR(7)    NOT NULL,
    hotel_name        VARCHAR(200),
    email_count       INTEGER       NOT NULL DEFAULT 0,
    sms_count         INTEGER       NOT NULL DEFAULT 0,
    whatsapp_count    INTEGER       NOT NULL DEFAULT 0,
    email_amount      NUMERIC(14,2) NOT NULL DEFAULT 0,
    sms_amount        NUMERIC(14,2) NOT NULL DEFAULT 0,
    whatsapp_amount   NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_amount      NUMERIC(14,2) NOT NULL DEFAULT 0,
    status            VARCHAR(20)   NOT NULL,
    issued_at         TIMESTAMP     NOT NULL,
    due_on            DATE          NOT NULL,
    paid_at           TIMESTAMP,
    paid_via          VARCHAR(20),
    payment_reference VARCHAR(100),
    paid_by_name      VARCHAR(150),
    note              VARCHAR(300),
    created_at        TIMESTAMP     NOT NULL,
    updated_at        TIMESTAMP     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_comm_invoices_number ON messaging.comm_invoices (number);
CREATE UNIQUE INDEX IF NOT EXISTS uq_comm_invoices_hotel_month ON messaging.comm_invoices (hotel_id, month);
CREATE INDEX IF NOT EXISTS idx_comm_invoices_status ON messaging.comm_invoices (status, due_on);
CALL app.protect('messaging', 'comm_invoices');

ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS billable BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS sent_via VARCHAR(20);
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS unit_price NUMERIC(12,2);
ALTER TABLE messaging.deliveries ADD COLUMN IF NOT EXISTS invoice_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_deliveries_billing ON messaging.deliveries (hotel_id, billable, created_at);
CREATE INDEX IF NOT EXISTS idx_deliveries_invoice ON messaging.deliveries (invoice_id);
