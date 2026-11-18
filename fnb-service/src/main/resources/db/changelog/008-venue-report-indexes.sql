--liquibase formatted sql

--changeset hospio:fnb-008-venue-report-indexes
-- Venue dashboards and reports (docs/plan-venue-type.md phase 2): money taken at the outlets by when it was taken, per location.
CREATE INDEX IF NOT EXISTS idx_order_payments_hotel_created ON fnb.order_payments (hotel_id, created_at);
-- Open tabs of a location (dashboard attention): the few live tabs, any day.
CREATE INDEX IF NOT EXISTS idx_orders_hotel_open_tabs ON fnb.orders (hotel_id, created_at) WHERE kind = 'TAB' AND status NOT IN ('CLOSED', 'CANCELLED');
