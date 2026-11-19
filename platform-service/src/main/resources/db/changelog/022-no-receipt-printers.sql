--liquibase formatted sql

--changeset hospio:platform-022-no-receipt-printers
-- Hospio360 does not supply receipt printers any more (the owner, 19 Nov 2026; payment receipts go on WhatsApp only, see
-- docs/whatsapp-receipts.md). The catalogue's receipt printer is switched off so it can't be added to a new package; the row stays,
-- and packages that already list a printer keep their lines, totals and history untouched.
SELECT set_config('app.scope', 'platform', true);
UPDATE platform.device_catalogue SET active = FALSE, updated_at = now()
WHERE id = 'dc000000-0000-4000-8000-000000000003' OR lower(name) LIKE '%receipt printer%';
