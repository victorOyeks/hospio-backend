--liquibase formatted sql

--changeset hospio:fnb-004-scale-indexes
-- Closed sales (Sales history, reports) by hotel and closing time.
CREATE INDEX IF NOT EXISTS idx_orders_hotel_closed ON fnb.orders (hotel_id, closed_at DESC) WHERE closed_at IS NOT NULL;
