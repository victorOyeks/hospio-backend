--liquibase formatted sql

--changeset hospio:fnb-007-outlet-stations
-- Outlets serving each other (a patio lounge selling the kitchen's pepper soup): a menu item can be made at another outlet
-- (prepared_at); each order line keeps where it is made, so that outlet sees it as a ticket while the bill stays with the outlet
-- that sold it. An outlet's own POS terminal (terminal_id) is preselected when taking payment for its orders.
ALTER TABLE fnb.menu_items ADD COLUMN IF NOT EXISTS prepared_at VARCHAR(36);
ALTER TABLE fnb.order_lines ADD COLUMN IF NOT EXISTS prepared_at VARCHAR(36);
ALTER TABLE fnb.outlets ADD COLUMN IF NOT EXISTS terminal_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_order_lines_prepared_at ON fnb.order_lines (prepared_at, status) WHERE prepared_at IS NOT NULL;
