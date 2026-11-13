--liquibase formatted sql

--changeset hospio:reservation-006-scale-indexes
-- Overlap queries (check_in < :to AND check_out > :from) start at check_out, which skips the hotel's past bookings; new
-- bookings by creation time; a guest found by the last ten digits of the phone (must match GuestRepository.findByPhoneKey).
CREATE INDEX IF NOT EXISTS idx_bookings_hotel_checkout ON reservation.bookings (hotel_id, check_out_date, check_in_date);
CREATE INDEX IF NOT EXISTS idx_bookings_business_checkout ON reservation.bookings (business_id, check_out_date, check_in_date);
CREATE INDEX IF NOT EXISTS idx_bookings_hotel_created ON reservation.bookings (hotel_id, created_at);
CREATE INDEX IF NOT EXISTS idx_guests_business_phone_key ON reservation.guests (business_id, right(regexp_replace(coalesce(phone, ''), '[^0-9]', '', 'g'), 10));
