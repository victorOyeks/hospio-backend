--liquibase formatted sql

--changeset hospio:005-payment-list-indexes
-- The paged Payments list reads a hotel's (or a business's) payments newest first within a window (docs/pagination.md).
CREATE INDEX IF NOT EXISTS idx_payment_requests_hotel_created ON payment.payment_requests (hotel_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payment_requests_business_created ON payment.payment_requests (business_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_refunds_hotel_created ON payment.refunds (hotel_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_disputes_hotel_created ON payment.disputes (hotel_id, created_at DESC);
