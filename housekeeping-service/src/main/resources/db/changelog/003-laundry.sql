--liquibase formatted sql

--changeset hospio:008-laundry-prices-orders-batches
-- Laundry (docs/laundry-maintenance.md): the price list, priced guest orders with express, settlement and cancel, and hotel linen batches
-- sent soiled by housekeeping and returned by laundry. WASHING becomes IN_PROGRESS.
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE housekeeping.laundry_prices
(
    id            VARCHAR(36)    PRIMARY KEY,
    business_id   VARCHAR(36)    NOT NULL,
    hotel_id      VARCHAR(36)    NOT NULL,
    name          VARCHAR(100)   NOT NULL,
    category      VARCHAR(20)    NOT NULL DEFAULT 'WASH_IRON',
    price         NUMERIC(14, 2) NOT NULL,
    express_price NUMERIC(14, 2) NOT NULL,
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    sort_order    INTEGER        NOT NULL DEFAULT 0,
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP      NOT NULL
);
CREATE UNIQUE INDEX ux_laundry_prices_name ON housekeeping.laundry_prices (hotel_id, lower(name));
CALL app.protect('housekeeping', 'laundry_prices');

ALTER TABLE housekeeping.laundry_orders ADD COLUMN number VARCHAR(20);
ALTER TABLE housekeeping.laundry_orders ADD COLUMN lines TEXT;
ALTER TABLE housekeeping.laundry_orders ADD COLUMN express BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE housekeeping.laundry_orders ADD COLUMN promised_at TIMESTAMP;
ALTER TABLE housekeeping.laundry_orders ADD COLUMN note VARCHAR(300);
ALTER TABLE housekeeping.laundry_orders ADD COLUMN charged_at TIMESTAMP;
ALTER TABLE housekeeping.laundry_orders ADD COLUMN paid_at TIMESTAMP;
ALTER TABLE housekeeping.laundry_orders ADD COLUMN paid_amount NUMERIC(14, 2);
ALTER TABLE housekeeping.laundry_orders ADD COLUMN payment_method VARCHAR(20);
ALTER TABLE housekeeping.laundry_orders ADD COLUMN cancel_reason VARCHAR(300);
ALTER TABLE housekeeping.laundry_orders ADD COLUMN cancelled_at TIMESTAMP;
ALTER TABLE housekeeping.laundry_orders ADD COLUMN received_by_name VARCHAR(150);
UPDATE housekeeping.laundry_orders SET status = 'IN_PROGRESS' WHERE status = 'WASHING';
UPDATE housekeeping.laundry_orders o SET number = 'LDR-' || lpad(x.n::text, 5, '0')
FROM (SELECT id, row_number() OVER (PARTITION BY hotel_id ORDER BY created_at) AS n FROM housekeeping.laundry_orders) x
WHERE x.id = o.id AND o.number IS NULL;
CREATE UNIQUE INDEX ux_laundry_orders_number ON housekeeping.laundry_orders (hotel_id, number);
CREATE UNIQUE INDEX ux_laundry_orders_payment ON housekeeping.laundry_orders (payment_id) WHERE payment_id IS NOT NULL;

CREATE TABLE housekeeping.linen_batches
(
    id               VARCHAR(36)    PRIMARY KEY,
    business_id      VARCHAR(36)    NOT NULL,
    hotel_id         VARCHAR(36)    NOT NULL,
    number           VARCHAR(20)    NOT NULL,
    status           VARCHAR(20)    NOT NULL DEFAULT 'SENT',
    lines            TEXT           NOT NULL,
    note             VARCHAR(300),
    sent_by          VARCHAR(36),
    sent_by_name     VARCHAR(150),
    received_at      TIMESTAMP,
    received_by_name VARCHAR(150),
    returned_at      TIMESTAMP,
    returns          INTEGER        NOT NULL DEFAULT 0,
    closed_reason    VARCHAR(300),
    condemned_value  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    lost_value       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_at       TIMESTAMP      NOT NULL,
    updated_at       TIMESTAMP      NOT NULL
);
CREATE UNIQUE INDEX ux_linen_batches_number ON housekeeping.linen_batches (hotel_id, number);
CALL app.protect('housekeeping', 'linen_batches');
