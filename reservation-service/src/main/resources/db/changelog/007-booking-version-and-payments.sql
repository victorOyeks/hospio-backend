--liquibase formatted sql

--changeset hospio:reservation-007-booking-version
-- Optimistic lock on bookings (JPA @Version): a stale copy can no longer be saved over a newer row.
ALTER TABLE reservation.bookings ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

--changeset hospio:reservation-007-booking-payments
-- One row per payment or refund already added to a booking's amount_paid, so a redelivered Kafka event is applied once.
-- payment_ref is "payment:<paymentId>" or "refund:<refundId>".
CREATE TABLE IF NOT EXISTS reservation.booking_payments
(
    payment_ref VARCHAR(80)    PRIMARY KEY,
    booking_id  VARCHAR(36)    NOT NULL,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36),
    amount      NUMERIC(14, 2) NOT NULL,
    created_at  TIMESTAMP      NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_booking_payments_booking ON reservation.booking_payments (booking_id);
CALL app.protect('reservation', 'booking_payments');
