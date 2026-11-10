--liquibase formatted sql

--changeset hospio:housekeeping-004-lost-found
-- Lost & found: where and who in words, the owner it went back to (with the ID they showed and who handed it over), disposal after the
-- hold period, and when the front office was told. lost_found_items is already protected (forced row-level security).
ALTER TABLE housekeeping.lost_found_items ADD COLUMN IF NOT EXISTS room_number VARCHAR(20);
ALTER TABLE housekeeping.lost_found_items ADD COLUMN IF NOT EXISTS found_by_name VARCHAR(150);
ALTER TABLE housekeeping.lost_found_items ADD COLUMN IF NOT EXISTS details VARCHAR(500);
ALTER TABLE housekeeping.lost_found_items ADD COLUMN IF NOT EXISTS returned_by VARCHAR(36);
ALTER TABLE housekeeping.lost_found_items ADD COLUMN IF NOT EXISTS returned_by_name VARCHAR(150);
ALTER TABLE housekeeping.lost_found_items ADD COLUMN IF NOT EXISTS return_id_note VARCHAR(200);
ALTER TABLE housekeeping.lost_found_items ADD COLUMN IF NOT EXISTS return_how VARCHAR(40);
ALTER TABLE housekeeping.lost_found_items ADD COLUMN IF NOT EXISTS disposed_at TIMESTAMP;
ALTER TABLE housekeeping.lost_found_items ADD COLUMN IF NOT EXISTS disposed_by_name VARCHAR(150);
ALTER TABLE housekeeping.lost_found_items ADD COLUMN IF NOT EXISTS dispose_reason VARCHAR(300);
ALTER TABLE housekeeping.lost_found_items ADD COLUMN IF NOT EXISTS front_office_told_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_lost_found_hotel_found ON housekeeping.lost_found_items (hotel_id, found_at);
