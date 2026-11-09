--liquibase formatted sql

--changeset hospio:010-payment-order-id
-- A payment taken at the kitchen or bar names the order it settles; fnb closes the order when the payment is approved.
ALTER TABLE payment.payment_requests ADD COLUMN IF NOT EXISTS order_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_payment_requests_order ON payment.payment_requests (order_id);
