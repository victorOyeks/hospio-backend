--liquibase formatted sql

--changeset hospio:008-arrival-reminder
-- When the day-before-arrival message went to the guest (Hotel setup → Guest messaging, "Send arrival-day message"); set once,
-- before sending, so a booking is never reminded twice. Partial index: the daily job looks only at bookings not yet reminded.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE reservation.bookings ADD COLUMN IF NOT EXISTS arrival_reminded_at timestamptz;
CREATE INDEX IF NOT EXISTS idx_bookings_arrival_due ON reservation.bookings (check_in_date) WHERE status = 'BOOKED' AND arrival_reminded_at IS NULL;
