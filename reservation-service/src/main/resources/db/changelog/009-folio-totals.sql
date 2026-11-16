--liquibase formatted sql

--changeset hospio:009-folio-totals
-- The stay's bill as its folios have it (folio-service's FolioTotals event): charged with extras and tax, less discounts; paid,
-- less refunds; still owed. Null until the guest checks in (no folio yet): then the booking's own total and amount paid count.
-- folio_totals_at is when folio-service sent the numbers, so an older event never overwrites a newer one.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE reservation.bookings ADD COLUMN IF NOT EXISTS folio_total numeric(14, 2);
ALTER TABLE reservation.bookings ADD COLUMN IF NOT EXISTS folio_paid numeric(14, 2);
ALTER TABLE reservation.bookings ADD COLUMN IF NOT EXISTS folio_balance numeric(14, 2);
ALTER TABLE reservation.bookings ADD COLUMN IF NOT EXISTS folio_totals_at timestamptz;
