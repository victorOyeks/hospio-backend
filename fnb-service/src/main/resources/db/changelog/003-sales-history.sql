--liquibase formatted sql

--changeset hospio:008-sales-history
-- Sales history: who settled an order, who cancelled a line, and each payment's reference and taker.
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS settled_by_user VARCHAR(36);
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS settled_by_name VARCHAR(150);
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS cancelled_by_name VARCHAR(150);
ALTER TABLE fnb.order_payments ADD COLUMN IF NOT EXISTS reference VARCHAR(100);
ALTER TABLE fnb.order_payments ADD COLUMN IF NOT EXISTS taken_by VARCHAR(36);
ALTER TABLE fnb.order_payments ADD COLUMN IF NOT EXISTS taken_by_name VARCHAR(150);

-- Orders settled before this release: whoever closed them in the same moment settled them.
UPDATE fnb.orders SET settled_by_user = closed_by, settled_by_name = closed_by_name
WHERE settled_by IN ('PAYMENT', 'ROOM', 'COMPLIMENTARY') AND settled_by_name IS NULL AND closed_by IS NOT NULL
  AND settled_at IS NOT NULL AND closed_at IS NOT NULL AND ABS(EXTRACT(EPOCH FROM (closed_at - settled_at))) < 5;

-- The history is read by hotel and day, newest first, optionally by who opened the sale.
CREATE INDEX IF NOT EXISTS idx_orders_hotel_created ON fnb.orders (hotel_id, created_at);
CREATE INDEX IF NOT EXISTS idx_orders_hotel_opened_by ON fnb.orders (hotel_id, opened_by, created_at);
