--liquibase formatted sql

--changeset hospio:messaging-006-scale-indexes
-- Hot lookups that scanned whole tables (scale audit, 2026-11-13): a notice's dedupe by event, the delivery log at business
-- scope, a Paystack reference finding its invoice, the outbound queue by reference.
CREATE INDEX IF NOT EXISTS idx_notifications_event ON messaging.notifications (event_id, user_id, role) WHERE event_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_deliveries_business_created ON messaging.deliveries (business_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_comm_invoices_payment_ref ON messaging.comm_invoices (payment_reference) WHERE payment_reference IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_outbound_reference ON messaging.outbound_messages (reference) WHERE reference IS NOT NULL;
