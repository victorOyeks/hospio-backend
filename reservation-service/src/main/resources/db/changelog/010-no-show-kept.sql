--liquibase formatted sql

--changeset hospio:010-no-show-kept
-- What a no-show left behind: when the night audit marked it, and what the guest had paid then (kept by the hotel). The daily
-- sales count the kept amount on that day; a refund of it later counts back on the refund's day.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE reservation.bookings ADD COLUMN IF NOT EXISTS no_show_at timestamptz;
ALTER TABLE reservation.bookings ADD COLUMN IF NOT EXISTS kept_on_no_show numeric(14, 2);
CREATE INDEX IF NOT EXISTS idx_bookings_no_show_at ON reservation.bookings (hotel_id, no_show_at) WHERE no_show_at IS NOT NULL;
