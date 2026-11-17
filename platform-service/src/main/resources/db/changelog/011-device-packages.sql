--liquibase formatted sql

--changeset hospio:platform-011-device-packages
-- Devices and internet Hospio360 supplies to a hotel (docs/plan-device-packages.md). device_catalogue: the standard items and prices
-- (platform-only). device_packages: one per hotel at a time: how it is paid (OWN_DEVICES, TRANSACTION, MONTHLY, ONE_TIME), the
-- one-off total and what has been paid, the transaction rate or the number of instalments, the monthly data plan. Its items copy
-- the catalogue prices (changeable per hotel). device_payments is the ledger of every amount collected. daily_fees carries the
-- device line of a day for a TRANSACTION package: the rate, that day's device fee and what was carried in from earlier days.
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS platform.device_catalogue
(
    id         VARCHAR(36)    PRIMARY KEY,
    name       VARCHAR(120)   NOT NULL,
    kind       VARCHAR(20)    NOT NULL,
    unit_price NUMERIC(14, 2) NOT NULL,
    active     BOOLEAN        NOT NULL DEFAULT TRUE,
    sort_order INT            NOT NULL DEFAULT 0,
    created_at TIMESTAMP      NOT NULL,
    updated_at TIMESTAMP      NOT NULL
);

CREATE TABLE IF NOT EXISTS platform.device_packages
(
    id                VARCHAR(36)    PRIMARY KEY,
    business_id       VARCHAR(36)    NOT NULL,
    hotel_id          VARCHAR(36)    NOT NULL,
    hotel_name        VARCHAR(150),
    status            VARCHAR(20)    NOT NULL,
    payment_option    VARCHAR(20)    NOT NULL,
    device_rate       NUMERIC(7, 4),
    months            INT,
    total             NUMERIC(14, 2) NOT NULL DEFAULT 0,
    paid              NUMERIC(14, 2) NOT NULL DEFAULT 0,
    data_plan_monthly NUMERIC(14, 2) NOT NULL DEFAULT 0,
    notes             VARCHAR(500),
    agreed_by         VARCHAR(36),
    agreed_by_name    VARCHAR(150),
    agreed_at         TIMESTAMP,
    installed_at      DATE,
    paid_off_at       TIMESTAMP,
    cancelled_at      TIMESTAMP,
    created_at        TIMESTAMP      NOT NULL,
    updated_at        TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_packages_hotel ON platform.device_packages (hotel_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_device_packages_status ON platform.device_packages (status);
CALL app.protect('platform', 'device_packages', 'hotel_id');

CREATE TABLE IF NOT EXISTS platform.device_package_items
(
    id           VARCHAR(36)    PRIMARY KEY,
    business_id  VARCHAR(36)    NOT NULL,
    hotel_id     VARCHAR(36)    NOT NULL,
    package_id   VARCHAR(36)    NOT NULL REFERENCES platform.device_packages (id) ON DELETE CASCADE,
    catalogue_id VARCHAR(36),
    name         VARCHAR(120)   NOT NULL,
    kind         VARCHAR(20)    NOT NULL,
    quantity     INT            NOT NULL,
    unit_price   NUMERIC(14, 2) NOT NULL,
    line_total   NUMERIC(14, 2) NOT NULL,
    created_at   TIMESTAMP      NOT NULL,
    updated_at   TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_package_items_package ON platform.device_package_items (package_id);
CALL app.protect('platform', 'device_package_items', 'hotel_id');

CREATE TABLE IF NOT EXISTS platform.device_payments
(
    id               VARCHAR(36)    PRIMARY KEY,
    business_id      VARCHAR(36)    NOT NULL,
    hotel_id         VARCHAR(36)    NOT NULL,
    package_id       VARCHAR(36)    NOT NULL REFERENCES platform.device_packages (id) ON DELETE CASCADE,
    source           VARCHAR(20)    NOT NULL,
    amount           NUMERIC(14, 2) NOT NULL,
    reference        VARCHAR(80)    NOT NULL,
    daily_fee_id     VARCHAR(36),
    invoice_id       VARCHAR(36),
    paid_on          DATE           NOT NULL,
    note             VARCHAR(300),
    recorded_by      VARCHAR(36),
    recorded_by_name VARCHAR(150),
    created_at       TIMESTAMP      NOT NULL,
    updated_at       TIMESTAMP      NOT NULL,
    CONSTRAINT uq_device_payments_ref UNIQUE (package_id, source, reference)
);
CREATE INDEX IF NOT EXISTS idx_device_payments_package ON platform.device_payments (package_id, paid_on DESC);
CALL app.protect('platform', 'device_payments', 'hotel_id');

ALTER TABLE platform.daily_fees ADD COLUMN IF NOT EXISTS device_package_id VARCHAR(36);
ALTER TABLE platform.daily_fees ADD COLUMN IF NOT EXISTS device_rate NUMERIC(7, 4) NOT NULL DEFAULT 0;
ALTER TABLE platform.daily_fees ADD COLUMN IF NOT EXISTS device_fee NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE platform.daily_fees ADD COLUMN IF NOT EXISTS device_carried_in NUMERIC(14, 2) NOT NULL DEFAULT 0;

INSERT INTO platform.device_catalogue (id, name, kind, unit_price, active, sort_order, created_at, updated_at) VALUES
    ('dc000000-0000-4000-8000-000000000001', 'Tablet (10-inch, with case)', 'ONE_OFF', 180000, TRUE, 1, now(), now()),
    ('dc000000-0000-4000-8000-000000000002', 'Wi-Fi router / modem', 'ONE_OFF', 65000, TRUE, 2, now(), now()),
    ('dc000000-0000-4000-8000-000000000003', 'Receipt printer', 'ONE_OFF', 85000, TRUE, 3, now(), now()),
    ('dc000000-0000-4000-8000-000000000004', 'Internet data plan (monthly)', 'MONTHLY', 30000, TRUE, 4, now(), now())
ON CONFLICT (id) DO NOTHING;
