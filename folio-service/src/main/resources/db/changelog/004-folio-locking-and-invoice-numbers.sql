--liquibase formatted sql

--changeset hospio:folio-004-folio-version
-- Optimistic-lock backstop for folio totals (every writer also takes the row lock first).
ALTER TABLE folio.folios ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

--changeset hospio:folio-004-invoice-number-per-hotel
-- Invoice numbers are INV-<year>-NNNNN per hotel, so the same number at two hotels is expected: unique per hotel, not platform-wide.
-- Existing numbers are unique platform-wide today, so they are unique per hotel too and the index can't fail on existing rows.
ALTER TABLE folio.invoices DROP CONSTRAINT IF EXISTS invoices_number_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_invoices_hotel_number ON folio.invoices (hotel_id, number);
