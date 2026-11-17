--liquibase formatted sql

--changeset hospio:platform-012-device-invoices
-- Monthly device invoices (docs/plan-device-packages.md): the instalments of a MONTHLY package (1 to 6) and the data plan, every
-- month while the package lasts. Each is issued on its due day, auto-debited from the hotel's Moniepoint/OPay account, else paid by
-- Paystack or marked paid by hand. Overdue: a reminder at 3 days, an alert at 7, POS payments paused at 14 (device_pos_paused on
-- the hotel's fee collection, kept apart from the daily fee's own pause).
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS platform.device_invoices
(
    id             VARCHAR(36)    PRIMARY KEY,
    business_id    VARCHAR(36)    NOT NULL,
    hotel_id       VARCHAR(36)    NOT NULL,
    package_id     VARCHAR(36)    NOT NULL REFERENCES platform.device_packages (id) ON DELETE CASCADE,
    hotel_name     VARCHAR(150),
    number         VARCHAR(40)    NOT NULL UNIQUE,
    kind           VARCHAR(20)    NOT NULL,
    sequence       INT            NOT NULL,
    of_count       INT,
    amount         NUMERIC(14, 2) NOT NULL,
    due_on         DATE           NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    paid_amount    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    paid_at        TIMESTAMP,
    paid_via       VARCHAR(20),
    paid_reference VARCHAR(80),
    debit_ref      VARCHAR(60),
    debit_attempts INT            NOT NULL DEFAULT 0,
    last_debit_on  DATE,
    paystack_ref   VARCHAR(80),
    last_error     VARCHAR(500),
    reminded_at    TIMESTAMP,
    flagged_at     TIMESTAMP,
    paused_at      TIMESTAMP,
    created_at     TIMESTAMP      NOT NULL,
    updated_at     TIMESTAMP      NOT NULL,
    CONSTRAINT uq_device_invoices_seq UNIQUE (package_id, kind, sequence)
);
CREATE INDEX IF NOT EXISTS idx_device_invoices_status ON platform.device_invoices (status, due_on);
CREATE INDEX IF NOT EXISTS idx_device_invoices_package ON platform.device_invoices (package_id, due_on DESC);
CALL app.protect('platform', 'device_invoices', 'hotel_id');

ALTER TABLE platform.fee_collections ADD COLUMN IF NOT EXISTS device_pos_paused BOOLEAN NOT NULL DEFAULT FALSE;
