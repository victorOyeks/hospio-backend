--liquibase formatted sql

--changeset hospio:003-list-indexes
-- The paged bookings and guests lists (docs/pagination.md).
CREATE INDEX IF NOT EXISTS idx_bookings_business_checkin ON reservation.bookings (business_id, check_in_date DESC);
CREATE INDEX IF NOT EXISTS idx_bookings_hotel_status ON reservation.bookings (hotel_id, status);
CREATE INDEX IF NOT EXISTS idx_guests_business_created ON reservation.guests (business_id, created_at DESC);
