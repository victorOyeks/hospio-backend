--liquibase formatted sql

--changeset hospio:009-receipt-number-per-hotel
-- Receipt numbers run per hotel (RCP-2026-000001 at every hotel), so they are unique within a hotel, not
-- across the platform; the global constraint failed the first cash payment at every new hotel.
ALTER TABLE payment.receipts DROP CONSTRAINT IF EXISTS receipts_number_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_receipts_hotel_number ON payment.receipts (hotel_id, number);
