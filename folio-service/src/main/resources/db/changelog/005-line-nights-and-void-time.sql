--liquibase formatted sql

--changeset hospio:folio-005-line-nights-and-void-time
-- service_from/service_to: the nights a room line covers ([from, to), check-out night excluded), so the daily sales spread a stay's
-- room charge one night per day. voided_at: when a charge was voided, so a void counts on the day it happened. Old lines keep nulls:
-- a room line without nights counts on the day it was posted, a void without a time on the day it was posted.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE folio.folio_lines ADD COLUMN IF NOT EXISTS service_from date;
ALTER TABLE folio.folio_lines ADD COLUMN IF NOT EXISTS service_to date;
ALTER TABLE folio.folio_lines ADD COLUMN IF NOT EXISTS voided_at timestamptz;
CREATE INDEX IF NOT EXISTS idx_folio_lines_hotel_posted ON folio.folio_lines (hotel_id, posted_at);
