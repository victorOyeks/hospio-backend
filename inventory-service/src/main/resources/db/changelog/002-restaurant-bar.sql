--liquibase formatted sql

--changeset hospio:006-movement-order-reference
-- Selling a dish or drink draws its recipe from the outlet's store, one USED movement per stock item with the reference ORDER-{orderId};
-- the unique index makes a settled order that is delivered twice count once, and speeds up the "already done?" lookup.
CREATE INDEX IF NOT EXISTS idx_stock_movements_reference ON inventory.stock_movements (reference);
CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_movements_order_item ON inventory.stock_movements (reference, item_id) WHERE kind = 'USED' AND reference LIKE 'ORDER-%';
