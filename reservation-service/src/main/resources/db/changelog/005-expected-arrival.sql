--liquibase formatted sql

--changeset hospio:005-expected-arrival
-- When the guest expects to arrive ("15:30"), asked on New booking; empty for a walk-in checked in on the spot and for older bookings.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE reservation.bookings ADD COLUMN IF NOT EXISTS expected_arrival varchar(5);
