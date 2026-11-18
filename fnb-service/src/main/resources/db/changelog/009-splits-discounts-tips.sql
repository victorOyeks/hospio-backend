--liquibase formatted sql

--changeset hospio:fnb-009-splits-discounts-tips
-- Venue type, phase 4 (docs/plan-venue-type.md): a bill split into parts, each paid its own way; manual discounts and complimentary
-- items; requests from the floor for a void or a discount over the limit; tips; the outlets a person works.

-- What went on rooms through a part of a split bill (the rest of the bill is paid at the outlet).
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS room_amount NUMERIC(14, 2) NOT NULL DEFAULT 0;
-- A discount on the whole bill: a percentage or an amount, shared over the lines (order_lines.bill_discount).
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS bill_discount_pct NUMERIC(6, 2);
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS bill_discount_amount NUMERIC(14, 2);
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS discount_reason VARCHAR(300);
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS discount_by VARCHAR(36);
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS discount_by_name VARCHAR(150);
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS discount_approved_by_name VARCHAR(150);

-- order_lines.discount stays everything taken off the line (happy hour + by hand + its share of a bill discount), so every report
-- that reads it stays right; these say how much of it was given by hand and why.
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS manual_discount NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS bill_discount NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS complimentary BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS discount_reason VARCHAR(300);
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS discount_by VARCHAR(36);
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS discount_by_name VARCHAR(150);
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS discount_approved_by_name VARCHAR(150);
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS discount_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS fnb.order_splits
(
    id              VARCHAR(36)    PRIMARY KEY,
    business_id     VARCHAR(36)    NOT NULL,
    hotel_id        VARCHAR(36)    NOT NULL,
    order_id        VARCHAR(36)    NOT NULL REFERENCES fnb.orders (id),
    mode            VARCHAR(10)    NOT NULL,
    status          VARCHAR(10)    NOT NULL,
    amount          NUMERIC(14, 2) NOT NULL,
    created_by      VARCHAR(36),
    created_by_name VARCHAR(150),
    ended_by_name   VARCHAR(150),
    ended_at        TIMESTAMP,
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_order_splits_order ON fnb.order_splits (order_id, status);

CREATE TABLE IF NOT EXISTS fnb.order_split_parts
(
    id             VARCHAR(36)    PRIMARY KEY,
    business_id    VARCHAR(36)    NOT NULL,
    hotel_id       VARCHAR(36)    NOT NULL,
    split_id       VARCHAR(36)    NOT NULL REFERENCES fnb.order_splits (id),
    order_id       VARCHAR(36)    NOT NULL,
    position       INT            NOT NULL,
    label          VARCHAR(80)    NOT NULL,
    amount         NUMERIC(14, 2) NOT NULL,
    tax            NUMERIC(14, 2) NOT NULL DEFAULT 0,
    service_charge NUMERIC(14, 2) NOT NULL DEFAULT 0,
    paid_amount    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    status         VARCHAR(10)    NOT NULL,
    method         VARCHAR(20),
    room_number    VARCHAR(20),
    items          TEXT,
    paid_at        TIMESTAMP,
    paid_by_name   VARCHAR(150),
    created_at     TIMESTAMP      NOT NULL,
    updated_at     TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_order_split_parts_split ON fnb.order_split_parts (split_id);
CREATE INDEX IF NOT EXISTS idx_order_split_parts_order ON fnb.order_split_parts (order_id);

ALTER TABLE fnb.order_payments ADD COLUMN IF NOT EXISTS part_id VARCHAR(36);

-- A tip taken with a payment: never a sale. Kept against whoever served (the person who opened the sale).
CREATE TABLE IF NOT EXISTS fnb.order_tips
(
    id            VARCHAR(36)    PRIMARY KEY,
    business_id   VARCHAR(36)    NOT NULL,
    hotel_id      VARCHAR(36)    NOT NULL,
    order_id      VARCHAR(36)    NOT NULL REFERENCES fnb.orders (id),
    outlet_id     VARCHAR(36)    NOT NULL,
    payment_id    VARCHAR(36)    NOT NULL UNIQUE,
    part_id       VARCHAR(36),
    amount        NUMERIC(14, 2) NOT NULL,
    method        VARCHAR(20)    NOT NULL,
    staff_id      VARCHAR(36),
    staff_name    VARCHAR(150),
    taken_by_name VARCHAR(150),
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_order_tips_hotel_created ON fnb.order_tips (hotel_id, created_at);

-- A void or a discount someone on the floor may not do alone: waits for a manager (platform approvals, approval_id).
CREATE TABLE IF NOT EXISTS fnb.sale_requests
(
    id                VARCHAR(36)    PRIMARY KEY,
    business_id       VARCHAR(36)    NOT NULL,
    hotel_id          VARCHAR(36)    NOT NULL,
    order_id          VARCHAR(36)    NOT NULL REFERENCES fnb.orders (id),
    outlet_id         VARCHAR(36)    NOT NULL,
    line_id           VARCHAR(36),
    kind              VARCHAR(20)    NOT NULL,
    pct               NUMERIC(6, 2),
    amount            NUMERIC(14, 2),
    worth             NUMERIC(14, 2),
    summary           VARCHAR(300),
    reason            VARCHAR(300)   NOT NULL,
    status            VARCHAR(12)    NOT NULL,
    approval_id       VARCHAR(36)    NOT NULL UNIQUE,
    requested_by      VARCHAR(36),
    requested_by_name VARCHAR(150),
    decided_by        VARCHAR(36),
    decided_by_name   VARCHAR(150),
    decision_note     VARCHAR(500),
    decided_at        TIMESTAMP,
    created_at        TIMESTAMP      NOT NULL,
    updated_at        TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sale_requests_order ON fnb.sale_requests (order_id);
CREATE INDEX IF NOT EXISTS idx_sale_requests_hotel_status ON fnb.sale_requests (hotel_id, status, created_at);

-- The outlets a waiter, cashier, bar or kitchen person works. No rows for a person: every outlet of the location, as before.
CREATE TABLE IF NOT EXISTS fnb.staff_outlets
(
    id          VARCHAR(36) PRIMARY KEY,
    business_id VARCHAR(36) NOT NULL,
    hotel_id    VARCHAR(36) NOT NULL,
    user_id     VARCHAR(36) NOT NULL,
    outlet_id   VARCHAR(36) NOT NULL REFERENCES fnb.outlets (id),
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    UNIQUE (hotel_id, user_id, outlet_id)
);
CREATE INDEX IF NOT EXISTS idx_staff_outlets_user ON fnb.staff_outlets (hotel_id, user_id);

--changeset hospio:fnb-009-splits-discounts-tips-row-level-security
CALL app.protect('fnb', 'order_splits');
CALL app.protect('fnb', 'order_split_parts');
CALL app.protect('fnb', 'order_tips');
CALL app.protect('fnb', 'sale_requests');
CALL app.protect('fnb', 'staff_outlets');

--changeset hospio:fnb-009-order-split-parts
-- How many parts the order's bill is split into while a split stands (0: not split), so a board or list knows without a join.
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS split_parts INT NOT NULL DEFAULT 0;
