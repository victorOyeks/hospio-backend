--liquibase formatted sql

--changeset hospio:005-restaurant-bar-columns
-- Outlets draw from an inventory store; menu items carry a recipe (JSON [{stockItemId, quantity}]) per portion.
ALTER TABLE fnb.outlets ADD COLUMN IF NOT EXISTS store_id VARCHAR(36);
ALTER TABLE fnb.menu_items ADD COLUMN IF NOT EXISTS recipe TEXT;

-- A line keeps the happy-hour discount it was sold with (an amount, tax inclusive when prices include tax) and why it was cancelled.
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS discount NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(300);
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS cancelled_by VARCHAR(36);

-- Who closed an order and how it was settled (PAYMENT, ROOM, COMPLIMENTARY or CANCELLED).
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS closed_by VARCHAR(36);
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS closed_by_name VARCHAR(150);
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS settled_by VARCHAR(20);
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS settled_at TIMESTAMP;
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(300);
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS paid_amount NUMERIC(14, 2) NOT NULL DEFAULT 0;

UPDATE fnb.orders SET settled_by = 'ROOM', settled_at = COALESCE(closed_at, updated_at) WHERE charged_to_room = TRUE;
UPDATE fnb.orders SET settled_by = 'PAYMENT', settled_at = COALESCE(closed_at, updated_at), paid_amount = total WHERE paid = TRUE AND charged_to_room = FALSE;
UPDATE fnb.orders SET settled_by = 'CANCELLED' WHERE status = 'CANCELLED';

CREATE INDEX IF NOT EXISTS idx_orders_hotel_settled ON fnb.orders (hotel_id, settled_at);
CREATE INDEX IF NOT EXISTS idx_orders_outlet_created ON fnb.orders (outlet_id, created_at);

--changeset hospio:006-menu-categories-order-payments
CREATE TABLE IF NOT EXISTS fnb.menu_categories
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    outlet_id   VARCHAR(36)  NOT NULL REFERENCES fnb.outlets (id),
    name        VARCHAR(60)  NOT NULL,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_menu_categories_outlet_name ON fnb.menu_categories (outlet_id, name);

-- One row per approved payment taken for an order: a split card + cash is two rows, and a payment event delivered twice lands once.
CREATE TABLE IF NOT EXISTS fnb.order_payments
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36)    NOT NULL,
    order_id    VARCHAR(36)    NOT NULL REFERENCES fnb.orders (id),
    payment_id  VARCHAR(36)    NOT NULL UNIQUE,
    method      VARCHAR(20)    NOT NULL,
    amount      NUMERIC(14, 2) NOT NULL,
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_order_payments_order ON fnb.order_payments (order_id);

--changeset hospio:007-restaurant-bar-row-level-security
CALL app.protect('fnb', 'menu_categories');
CALL app.protect('fnb', 'order_payments');
