--liquibase formatted sql

--changeset hospio:payment-007-scale-indexes
-- Receipts and refunds by payment (looked up per payment), approved payments by their resolution time (settlement, fees),
-- payments of a booking.
CREATE INDEX IF NOT EXISTS idx_receipts_payment ON payment.receipts (payment_id);
CREATE INDEX IF NOT EXISTS idx_refunds_payment ON payment.refunds (payment_id);
CREATE INDEX IF NOT EXISTS idx_payment_requests_business_status_resolved ON payment.payment_requests (business_id, status, (COALESCE(resolved_at, created_at)));
CREATE INDEX IF NOT EXISTS idx_payment_requests_status_resolved ON payment.payment_requests (status, (COALESCE(resolved_at, created_at)));
CREATE INDEX IF NOT EXISTS idx_payment_requests_booking ON payment.payment_requests (booking_id) WHERE booking_id IS NOT NULL;
