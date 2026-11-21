--liquibase formatted sql

--changeset hospio:finance-008-supplier-bills
-- Supplier bills from deliveries: receiving a delivery in Inventory makes one bill here, linked to the delivery (one bill per delivery,
-- so a replayed event never makes a second), its purchase order, the supplier and the supplier's invoice number. Manual bills leave
-- them empty. The table is already row-level protected per tenant (app.protect in 011-bills).
ALTER TABLE finance.bills ADD COLUMN IF NOT EXISTS supplier_id VARCHAR(36);
ALTER TABLE finance.bills ADD COLUMN IF NOT EXISTS delivery_id VARCHAR(36);
ALTER TABLE finance.bills ADD COLUMN IF NOT EXISTS purchase_order_id VARCHAR(36);
ALTER TABLE finance.bills ADD COLUMN IF NOT EXISTS invoice_number VARCHAR(60);
CREATE UNIQUE INDEX IF NOT EXISTS uq_bills_delivery ON finance.bills (delivery_id) WHERE delivery_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bills_hotel_payee ON finance.bills (hotel_id, lower(payee));
