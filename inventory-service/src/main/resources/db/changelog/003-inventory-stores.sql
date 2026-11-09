--liquibase formatted sql

--changeset hospio:007-inventory-stores
-- Inventory and stores (docs/inventory-stores.md): reorder-up-to level, movement cost and team, names kept with the rows,
-- supplier address, order notes and approval details, delivery invoice, count notes and variance value.
ALTER TABLE inventory.stock_items ADD COLUMN IF NOT EXISTS par_level NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE inventory.stock_movements ADD COLUMN IF NOT EXISTS unit_cost NUMERIC(14, 2);
ALTER TABLE inventory.stock_movements ADD COLUMN IF NOT EXISTS team VARCHAR(30);
ALTER TABLE inventory.stock_movements ADD COLUMN IF NOT EXISTS by_name VARCHAR(150);
CREATE INDEX IF NOT EXISTS idx_stock_movements_hotel_time ON inventory.stock_movements (hotel_id, created_at);
ALTER TABLE inventory.stock_requests ADD COLUMN IF NOT EXISTS requested_by_name VARCHAR(150);
ALTER TABLE inventory.stock_requests ADD COLUMN IF NOT EXISTS issued_by_name VARCHAR(150);
ALTER TABLE inventory.stock_requests ADD COLUMN IF NOT EXISTS reason VARCHAR(300);
ALTER TABLE inventory.suppliers ADD COLUMN IF NOT EXISTS address VARCHAR(300);
ALTER TABLE inventory.suppliers ADD COLUMN IF NOT EXISTS notes VARCHAR(500);
ALTER TABLE inventory.purchase_orders ADD COLUMN IF NOT EXISTS note VARCHAR(500);
ALTER TABLE inventory.purchase_orders ADD COLUMN IF NOT EXISTS created_by_name VARCHAR(150);
ALTER TABLE inventory.purchase_orders ADD COLUMN IF NOT EXISTS approver_level VARCHAR(20);
ALTER TABLE inventory.purchase_orders ADD COLUMN IF NOT EXISTS decision_note VARCHAR(500);
ALTER TABLE inventory.purchase_orders ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(300);
ALTER TABLE inventory.purchase_orders ADD COLUMN IF NOT EXISTS sent_to VARCHAR(255);
ALTER TABLE inventory.purchase_orders ADD COLUMN IF NOT EXISTS received_value NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE inventory.deliveries ADD COLUMN IF NOT EXISTS invoice_number VARCHAR(60);
ALTER TABLE inventory.deliveries ADD COLUMN IF NOT EXISTS received_by_name VARCHAR(150);
ALTER TABLE inventory.deliveries ADD COLUMN IF NOT EXISTS summary VARCHAR(300);
ALTER TABLE inventory.stock_counts ADD COLUMN IF NOT EXISTS note VARCHAR(500);
ALTER TABLE inventory.stock_counts ADD COLUMN IF NOT EXISTS started_by_name VARCHAR(150);
ALTER TABLE inventory.stock_counts ADD COLUMN IF NOT EXISTS counted_by_name VARCHAR(150);
ALTER TABLE inventory.stock_counts ADD COLUMN IF NOT EXISTS approved_by_name VARCHAR(150);
ALTER TABLE inventory.stock_counts ADD COLUMN IF NOT EXISTS variance_value NUMERIC(14, 2);
ALTER TABLE inventory.stock_counts ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(300);
