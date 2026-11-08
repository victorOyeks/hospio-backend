--liquibase formatted sql

--changeset hospio:002-amount-paid
-- What has actually been paid against the stay (payments approved by payment-service, minus approved
-- refunds), whether or not the folio exists yet. A deposit taken at booking time shows here before check-in.
ALTER TABLE reservation.bookings ADD COLUMN IF NOT EXISTS amount_paid NUMERIC(14, 2) NOT NULL DEFAULT 0;
