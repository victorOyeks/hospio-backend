--liquibase formatted sql

--changeset hospio:property-002-room-price-approvals
-- A manager's base price change above the owner's threshold waits here until the owner answers (room_types is already protected).
ALTER TABLE property.room_types ADD COLUMN IF NOT EXISTS requested_base_price NUMERIC(14, 2);
ALTER TABLE property.room_types ADD COLUMN IF NOT EXISTS price_approval_id VARCHAR(36);
ALTER TABLE property.room_types ADD COLUMN IF NOT EXISTS price_requested_by_name VARCHAR(150);
ALTER TABLE property.room_types ADD COLUMN IF NOT EXISTS price_requested_at TIMESTAMP;
CREATE UNIQUE INDEX IF NOT EXISTS uq_room_types_price_approval ON property.room_types (price_approval_id);
